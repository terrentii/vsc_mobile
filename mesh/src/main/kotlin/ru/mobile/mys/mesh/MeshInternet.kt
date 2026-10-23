package ru.mobile.mys.mesh

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Интернет-связь mesh (порт mys_mesh/internet.py): до [links] линков к публичным
 * узлам — стартовые адреса + выученные из PEERS; обрыв — повтор 2 с … 2 мин.
 */
class MeshInternet(
    private val node: MeshNode,
    private val bootstrap: () -> List<String>,
    private val connect: suspend (String) -> Wire,
    private val links: Int = 3,
    private val tickMs: Long = 1_000,
) {
    private val active = ConcurrentHashMap<String, Job>()
    private val up = ConcurrentHashMap.newKeySet<String>()
    private val fails = ConcurrentHashMap<String, Int>()
    private val retryAt = ConcurrentHashMap<String, Long>()
    private var loop: Job? = null

    fun start(scope: CoroutineScope) {
        if (loop != null) return
        loop = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                for (url in candidates()) {
                    if (active.size >= links) break
                    if (active.containsKey(url) || now < (retryAt[url] ?: 0L)) continue
                    active[url] = scope.launch { hold(url) }
                }
                delay(tickMs)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        active.values.forEach { it.cancel() }
        active.clear()
        up.clear()
    }

    /** Узлы, с которыми линк сейчас поднят. */
    fun connected(): List<String> = up.sorted()

    fun candidates(): List<String> = (bootstrap() + node.knownUrls).distinct()

    private suspend fun hold(url: String) {
        try {
            val wire = connect(url)
            fails.remove(url)
            up += url
            node.serve(wire)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // отказ, обрыв, неверный линк — повтор после паузы
        } finally {
            up -= url
            active.remove(url)
            val n = (fails[url] ?: 0) + 1
            fails[url] = n
            retryAt[url] = System.currentTimeMillis() + minOf(120_000L, 2_000L shl minOf(n - 1, 6))
        }
    }
}
