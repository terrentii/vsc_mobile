package ru.mobile.mys.p2p

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Двунаправленный канал непрозрачных кадров к одному пиру; о шифровании не знает. */
interface Transport {
    suspend fun send(data: ByteArray)
    /** Следующий кадр; TransportException — канал оборвался. */
    suspend fun recv(): ByteArray
    suspend fun close()
}

/** WebSocket (OkHttp) как очередь бинарных сообщений: одно сообщение = один кадр. */
class WsConnection private constructor() {
    private lateinit var ws: WebSocket
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)

    fun send(data: ByteArray) {
        if (!ws.send(data.toByteString())) throw TransportException("WebSocket закрыт")
    }

    suspend fun recv(): ByteArray =
        try { inbox.receive() } catch (e: ClosedReceiveChannelException) { throw TransportException("WebSocket закрыт") }

    fun close() {
        ws.close(1000, null)
        inbox.close()
    }

    companion object {
        suspend fun connect(client: OkHttpClient, url: String): WsConnection = suspendCancellableCoroutine { cont ->
            val conn = WsConnection()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = cont.resume(conn)
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) { conn.inbox.trySend(bytes.toByteArray()) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    conn.inbox.close()
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { conn.inbox.close() }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (cont.isActive) cont.resumeWithException(RendezvousException("не удалось подключиться к rendezvous-серверу", t))
                    conn.inbox.close()
                }
            }
            conn.ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
            cont.invokeOnCancellation { conn.ws.cancel() }
        }
    }
}

/** Кадры к пиру — в `RELAY{кадр}` через rendezvous-сервер, который их не читает. */
class RelayTransport(private val ws: WsConnection) : Transport {
    override suspend fun send(data: ByteArray) = ws.send(encodeMessage(Msg.Relay(data)))

    override suspend fun recv(): ByteArray = when (val m = decodeMessage(ws.recv())) {
        is Msg.Relay -> m.inner
        // Сервер сигналит уход пира отдельным кадром — для сессии это обрыв канала.
        is Msg.PeerLeft -> throw TransportException("пир покинул комнату")
        else -> throw TransportException("ожидался RELAY-кадр")
    }

    override suspend fun close() = ws.close()
}

class Rendezvous(val role: Role, val peerCandidates: List<Candidate>, val ws: WsConnection)

/** Войти в комнату и дождаться пары. Кандидатов не анонсируем: прямой путь — WebRTC (под-проект 6). */
suspend fun joinRendezvous(client: OkHttpClient, url: String, roomId: ByteArray, timeoutMs: Long): Rendezvous {
    val ws = WsConnection.connect(client, url)
    try {
        ws.send(encodeMessage(Msg.Hello(roomId)))
        val first = withTimeoutOrNull(timeoutMs) { ws.recv() }
            ?: throw PeerUnavailable("собеседник не вышел на связь за отведённое время")
        val pair = decodeMessage(first) as? Msg.Pair ?: throw RendezvousException("ожидался PAIR от сервера")
        return Rendezvous(pair.role, pair.peerCandidates, ws)
    } catch (e: TransportException) {
        ws.close()
        throw RendezvousException("соединение с rendezvous-сервером оборвалось", e)
    } catch (e: Throwable) {
        ws.close()
        throw e
    }
}