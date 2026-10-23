package ru.mobile.mys.p2p

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import okhttp3.OkHttpClient
import ru.mobile.mys.mesh.MeshClosed
import ru.mobile.mys.mesh.MeshNode
import ru.mobile.mys.mesh.MeshTransport
import ru.mobile.mys.mesh.tagKey

const val MESH_GRACE_MS = 3_000L

/** Канал через mesh: транспорт сессии поверх маршрута. */
class MeshPathTransport(private val t: MeshTransport) : Transport {
    override suspend fun send(data: ByteArray) =
        try { t.send(data) } catch (e: MeshClosed) { throw TransportException(e.message ?: "mesh") }

    override suspend fun recv(): ByteArray =
        try { t.recv() } catch (e: MeshClosed) { throw TransportException(e.message ?: "mesh") }

    override suspend fun close() = t.close()
}

class Paired(val role: Role, val transport: Transport, val mesh: Boolean, val rendezvous: Rendezvous?)

/**
 * Рандеву через сервер и поиск через mesh параллельно (порт mesh_path.pair).
 * Пару от сервера оба получают почти одновременно — сервер в приоритете: mesh нашёл
 * первым — ждём сервер ещё [graceMs]; сервер недоступен — сразу mesh.
 */
suspend fun pair(
    http: OkHttpClient, url: String, mesh: MeshNode?, roomId: ByteArray, prs: ByteArray,
    timeoutMs: Long, graceMs: Long = MESH_GRACE_MS,
): Paired = supervisorScope {
    val server = async { joinRendezvous(http, url, roomId, timeoutMs) }
    if (mesh == null) return@supervisorScope server.await().let { Paired(it.role, RelayTransport(it.ws), false, it) }
    val found = async { mesh.find(tagKey(prs), timeoutMs) }
    val errors = mutableListOf<Throwable>()
    var serverFailed = false
    var meshFailed = false
    var meshResult: Pair<ru.mobile.mys.mesh.MeshRole, MeshTransport>? = null
    var deadline = Long.MAX_VALUE
    try {
        while (true) {
            if (!serverFailed && server.isCompleted) {
                val r = runCatching { server.await() }
                r.getOrNull()?.let { rv ->
                    found.cancel()
                    meshResult?.second?.close() // проигравший маршрут — закрыть
                    return@supervisorScope Paired(rv.role, RelayTransport(rv.ws), false, rv)
                }
                r.exceptionOrNull()?.let { if (it is CancellationException) throw it; errors += it; serverFailed = true }
            }
            if (meshResult == null && !meshFailed && found.isCompleted) {
                val r = runCatching { found.await() }
                r.getOrNull()?.let { meshResult = it; deadline = System.currentTimeMillis() + graceMs }
                r.exceptionOrNull()?.let { if (it is CancellationException) throw it; errors += it; meshFailed = true }
            }
            val m = meshResult
            if (m != null && (serverFailed || System.currentTimeMillis() >= deadline)) {
                server.cancel()
                return@supervisorScope Paired(Role.valueOf(m.first.name), MeshPathTransport(m.second), true, null)
            }
            if (serverFailed && meshFailed) throw errors.first()
            delay(50)
        }
        @Suppress("UNREACHABLE_CODE") throw IllegalStateException()
    } finally {
        server.cancel()
        found.cancel()
    }
}
