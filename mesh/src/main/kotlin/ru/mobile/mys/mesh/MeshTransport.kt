package ru.mobile.mys.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Конечная точка маршрута: кадры сессии в DATA, keepalive, RERR при закрытии. */
class MeshTransport internal constructor(private val node: MeshNode, val routeId: ByteArray, private val neighbor: Int) {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile private var closed = false
    private var keepalive: Job? = null

    internal fun start(scope: CoroutineScope) {
        keepalive = scope.launch {
            try {
                while (true) {
                    delay(node.config.keepaliveMs)
                    send(ByteArray(0))
                }
            } catch (e: MeshClosed) {
            }
        }
    }

    suspend fun send(frame: ByteArray) {
        if (closed) throw MeshClosed("маршрут закрыт")
        require(frame.size <= MAX_DATA) { "кадр больше MAX_DATA" }
        val ok = node.onNode {
            node.touch(routeId)
            node.sendTo(neighbor, Packet.data(routeId, frame).encode())
        }
        if (!ok) throw MeshClosed("сосед отключился")
    }

    suspend fun recv(): ByteArray =
        try { inbox.receive() } catch (e: ClosedReceiveChannelException) { throw MeshClosed("маршрут оборвался") }

    suspend fun close() {
        if (closed) return
        fail()
        node.onNode { if (node.routes.remove(routeId.k) != null) node.sendTo(neighbor, Packet.rerr(routeId).encode()) }
    }

    internal fun deliver(payload: ByteArray) {
        if (!closed) inbox.trySend(payload)
    }

    internal fun fail() {
        closed = true
        keepalive?.cancel()
        inbox.close()
    }
}
