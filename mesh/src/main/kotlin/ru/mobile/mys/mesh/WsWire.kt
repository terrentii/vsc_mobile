package ru.mobile.mys.mesh

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Провод через WebSocket (wss://…/mesh): одно бинарное сообщение = один кадр (порт WsWire десктопа). */
class WsWire private constructor(private val inbox: Channel<ByteArray>) : Wire {
    @Volatile private var ws: WebSocket? = null

    override suspend fun send(frame: ByteArray) {
        if (ws?.send(frame.toByteString()) != true) throw WireClosed("ws закрыт")
    }

    override suspend fun recv(): ByteArray =
        try { inbox.receive() } catch (e: ClosedReceiveChannelException) { throw WireClosed("ws закрыт") }

    override suspend fun close() {
        ws?.close(1000, null)
        inbox.close()
    }

    companion object {
        suspend fun connect(http: OkHttpClient, url: String): WsWire = suspendCancellableCoroutine { cont ->
            val inbox = Channel<ByteArray>(Channel.UNLIMITED)
            val wire = WsWire(inbox)
            val socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (cont.isActive) cont.resume(wire)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes.size > MAX_FRAME) { webSocket.close(1009, null); inbox.close(); return }
                    inbox.trySend(bytes.toByteArray())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.close(1003, null) // текстовых кадров в протоколе нет
                    inbox.close()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    inbox.close()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { inbox.close() }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    inbox.close()
                    if (cont.isActive) cont.resumeWithException(WireClosed("$url: ${t.message}", t))
                }
            })
            wire.ws = socket
            cont.invokeOnCancellation { socket.cancel() }
        }
    }
}
