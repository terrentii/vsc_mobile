package ru.mobile.mys.central

import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject

/** /ws: auth → ready → кадры сервера. Реконнект с backoff+джиттером; AuthException терминальна. */
class WsClient(
    private val url: String,
    private val token: String,
    private val http: OkHttpClient,
    private val initialBackoffMs: Long = 500,
    private val maxBackoffMs: Long = 30_000,
) {
    /** `{"type":"ready"}` при каждом (пере)подключении, затем кадры message/message_edited/message_deleted. */
    fun events(): Flow<JSONObject> = flow {
        var backoff = initialBackoffMs
        while (true) {
            try {
                session().collect { backoff = initialBackoffMs; emit(it) }
            } catch (e: AuthException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // обрыв — переподключимся после паузы
            }
            delay(backoff + Random.nextLong(0, 300))
            backoff = minOf(backoff * 2, maxBackoffMs)
        }
    }

    private fun session(): Flow<JSONObject> = callbackFlow {
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            var ready = false

            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "auth").put("token", token).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = try { JSONObject(text) } catch (e: JSONException) { close(ProtocolException("invalid ws frame")); return }
                if (!ready) {
                    when (o.optString("type")) {
                        "ready" -> ready = true
                        "error" -> { close(AuthException(o.optString("code", "unauthorized"))); return }
                        else -> { close(ProtocolException("unexpected first frame")); return }
                    }
                }
                trySend(o)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(NetworkException("ws: ${t.message}", t))
            }
        })
        awaitClose { ws.cancel() }
    }
}
