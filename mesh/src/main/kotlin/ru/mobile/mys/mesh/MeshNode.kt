package ru.mobile.mys.mesh

import java.security.SecureRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class MeshRole { INITIATOR, RESPONDER }

class MeshClosed(message: String) : Exception(message)

data class MeshConfig(
    val relay: Boolean = true,
    val ttlMax: Int = TTL_MAX,
    val dedupTtlMs: Long = 120_000,
    val dedupMax: Int = 4096,
    val routeIdleMs: Long = 120_000,
    val routeMax: Int = 1024,
    val keepaliveMs: Long = 30_000,
    val rreqRetryMs: Long = 5_000,
    val rreqRate: Double = 10.0,
    val relayRate: Double = 256.0 * 1024,
    val sweepMs: Long = 5_000,
    val clock: () -> Long = System::currentTimeMillis,
    /** Публичный узел (сервер): свой адрес wss://…/mesh — сообщается соседям в PEERS. */
    val publicUrl: String? = null,
)

/** Токен-бакет: rate в секунду, запас — на одну секунду. */
private class Bucket(private val rate: Double) {
    private var tokens = rate
    private var t = System.nanoTime()

    fun take(n: Double = 1.0): Boolean {
        val now = System.nanoTime()
        tokens = minOf(rate, tokens + (now - t) / 1e9 * rate)
        t = now
        return if (tokens >= n) { tokens -= n; true } else false
    }
}

/** ByteArray как ключ хэш-таблицы. */
internal class Key(val b: ByteArray) {
    override fun equals(other: Any?) = other is Key && other.b.contentEquals(b)
    override fun hashCode() = b.contentHashCode()
}

internal val ByteArray.k get() = Key(this)

private class Search(val tags: List<ByteArray>, val nonce: ByteArray) {
    val result = CompletableDeferred<Pair<MeshRole, MeshTransport>>()
}

internal class Route(val a: Int?, val b: Int, val endpoint: MeshTransport? = null) {
    var last = System.nanoTime()
}

/**
 * Узел mesh (порт mys_mesh/node.py): соседи, RREQ/RREP/DATA/RERR, точка встречи.
 * Состояние — только на однопоточном контексте узла (как в asyncio), в памяти, минуты.
 */
class MeshNode(private val crypto: LinkCrypto, val config: MeshConfig = MeshConfig()) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val ctx = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + ctx)
    private val random = SecureRandom()
    private val links = HashMap<Int, Link>()
    private val rreqBuckets = HashMap<Int, Bucket>()
    private var nextId = 1
    private val seen = LinkedHashMap<Key, Pair<Int?, Long>>()
    internal val routes = HashMap<Key, Route>()
    private val byTag = HashMap<Key, Search>()
    private val byReq = HashMap<Key, Search>()
    private val relayBucket = Bucket(config.relayRate)
    private var relayed = 0L
    private var rreqForwarded = 0L
    private var sweeper: Job? = null
    private val closed = HashMap<Int, CompletableDeferred<Unit>>()

    /** Адреса публичных узлов: свой (если есть) + выученные из PEERS. */
    @Volatile var knownUrls: List<String> = listOfNotNull(config.publicUrl)
        private set

    /** Выучены новые адреса (зовётся в контексте узла). */
    var onPeers: ((List<String>) -> Unit)? = null

    internal suspend fun <T> onNode(block: suspend () -> T): T = withContext(ctx) { block() }

    // -- соседи

    suspend fun attach(wire: Wire): Int {
        val link = Link.open(wire, crypto)
        return withContext(ctx) {
            val id = nextId++
            links[id] = link
            rreqBuckets[id] = Bucket(config.rreqRate)
            closed[id] = CompletableDeferred()
            scope.launch { readLoop(id, link) }
            ensureSweeper()
            if (knownUrls.isNotEmpty()) { // поделиться адресами публичных узлов
                sendTo(id, Packet(PEERS, bytes(ID_LEN), payload = encodePeers(knownUrls)).encode())
            }
            id
        }
    }

    /** Подключить провод и держать, пока линк жив (менеджеры связи, серверы). */
    suspend fun serve(wire: Wire) {
        val id = attach(wire)
        val done = withContext(ctx) { closed[id] } ?: return
        done.await()
    }

    /** Добавить адреса публичных узлов (не больше 32); → новые. */
    fun learnUrls(urls: List<String>): List<String> {
        val fresh = urls.filter { it !in knownUrls }
        knownUrls = (knownUrls + fresh).take(32)
        return fresh.filter { it in knownUrls }
    }

    suspend fun detach(id: Int) {
        withContext(ctx) { links[id] }?.close()
    }

    suspend fun stats(): Map<String, Long> = withContext(ctx) {
        mapOf("neighbors" to links.size.toLong(), "routes" to routes.size.toLong(),
            "relayed_bytes" to relayed, "rreq_forwarded" to rreqForwarded)
    }

    suspend fun close() {
        withContext(ctx) { links.values.toList() }.forEach { it.close() }
        scope.cancel()
    }

    // -- поиск собеседника

    suspend fun find(key: ByteArray, timeoutMs: Long): Pair<MeshRole, MeshTransport> = withContext(ctx) {
        val hour = hourOf(config.clock())
        val cur = tagForHour(key, hour)
        val s = Search(listOf(cur, tagForHour(key, hour - 1)), bytes(NONCE_LEN))
        s.tags.forEach { byTag[it.k] = s }
        ensureSweeper()
        val asker = scope.launch { ask(s, cur) }
        try {
            withTimeout(timeoutMs) { s.result.await() }
        } finally {
            asker.cancel()
            s.result.cancel() // поздний RREP получит RERR
            s.tags.forEach { if (byTag[it.k] === s) byTag.remove(it.k) }
        }
    }

    private suspend fun ask(s: Search, tag: ByteArray) {
        while (!s.result.isCompleted) {
            val rid = bytes(ID_LEN)
            remember(rid, null)
            byReq[rid.k] = s
            broadcast(Packet.rreq(rid, tag, s.nonce, config.ttlMax).encode(), exclude = null)
            delay(config.rreqRetryMs)
        }
    }

    // -- приём

    private suspend fun readLoop(id: Int, link: Link) {
        try {
            while (true) {
                val raw = link.recv()
                handle(id, Packet.decode(raw, config.ttlMax), raw)
            }
        } catch (e: WireClosed) {
        } catch (e: MeshProtocolException) {
        } finally {
            withContext(NonCancellable) { dropNeighbor(id) }
        }
    }

    private suspend fun handle(id: Int, p: Packet, raw: ByteArray) = when (p.type) {
        RREQ -> onRreq(id, p)
        RREP -> onRrep(id, p)
        DATA -> onData(id, p, raw)
        PEERS -> { // только между соседями, дальше не идёт
            val fresh = learnUrls(decodePeers(p.payload))
            if (fresh.isNotEmpty()) onPeers?.invoke(fresh)
            Unit
        }
        else -> onRerr(id, p, raw)
    }

    private suspend fun onRreq(id: Int, p: Packet) {
        if (p.id.k in seen || rreqBuckets[id]?.take() != true) return
        remember(p.id, id)
        val s = byTag[p.tag.k]
        if (s != null) { // ищут нас: отвечает тот, у кого nonce меньше
            if (!s.result.isCompleted && compareUnsigned(s.nonce, p.nonce) < 0 && routes.size < config.routeMax) {
                val rid = bytes(ID_LEN)
                val t = MeshTransport(this, rid, id)
                routes[rid.k] = Route(null, id, t)
                sendTo(id, Packet.rrep(p.id, rid, s.nonce).encode())
                s.result.complete(MeshRole.RESPONDER to t)
                t.start(scope)
            }
            return
        }
        if (config.relay && p.ttl > 1) {
            rreqForwarded++
            broadcast(p.with(ttl = p.ttl - 1, hops = minOf(p.hops + 1, 255)).encode(), exclude = id)
        }
    }

    private suspend fun onRrep(mid: Int, p: Packet) {
        val s = byReq[p.id.k]
        if (s != null) {
            if (p.routeId.k in routes) return // повтор уже принятого ответа
            if (s.result.isCompleted) {
                sendTo(mid, Packet.rerr(p.routeId).encode())
                return
            }
            val t = MeshTransport(this, p.routeId, mid)
            routes[p.routeId.k] = Route(null, mid, t)
            s.result.complete(MeshRole.INITIATOR to t)
            t.start(scope)
            return
        }
        val entry = seen[p.id.k]
        if (!config.relay || entry == null) return
        val nid = entry.first
        if (nid == null || nid == mid || nid !in links || p.routeId.k in routes || routes.size >= config.routeMax) return
        routes[p.routeId.k] = Route(nid, mid)
        sendTo(nid, p.with(hops = minOf(p.hops + 1, 255)).encode())
    }

    private suspend fun onData(id: Int, p: Packet, raw: ByteArray) {
        val r = routes[p.id.k] ?: return
        val ep = r.endpoint
        if (ep != null) {
            if (id == r.b) {
                r.last = System.nanoTime()
                if (p.payload.isNotEmpty()) ep.deliver(p.payload)
            }
            return
        }
        val other = when (id) { r.a -> r.b; r.b -> r.a; else -> null } ?: return
        if (!relayBucket.take(p.payload.size.toDouble())) return
        r.last = System.nanoTime()
        relayed += p.payload.size
        sendTo(other, raw)
    }

    private suspend fun onRerr(id: Int, p: Packet, raw: ByteArray) {
        val r = routes[p.id.k] ?: return
        if (id != r.a && id != r.b) return
        routes.remove(p.id.k)
        val ep = r.endpoint
        if (ep != null) ep.fail() else sendTo(if (id == r.a) r.b else r.a!!, raw)
    }

    // -- служебное

    private suspend fun dropNeighbor(id: Int) {
        val link = links.remove(id)
        rreqBuckets.remove(id)
        closed.remove(id)?.complete(Unit)
        link?.close()
        for ((rid, r) in routes.entries.toList()) {
            if (id != r.a && id != r.b) continue
            routes.remove(rid)
            val ep = r.endpoint
            if (ep != null) ep.fail() else sendTo(if (id == r.a) r.b else r.a!!, Packet.rerr(rid.b).encode())
        }
    }

    internal suspend fun sendTo(id: Int, raw: ByteArray): Boolean {
        val link = links[id] ?: return false
        return try {
            link.send(raw)
            true
        } catch (e: WireClosed) {
            link.close()
            false
        }
    }

    private suspend fun broadcast(raw: ByteArray, exclude: Int?) {
        for (id in links.keys.toList()) if (id != exclude) sendTo(id, raw)
    }

    private fun remember(rid: ByteArray, id: Int?) {
        seen[rid.k] = id to System.nanoTime()
        while (seen.size > config.dedupMax) seen.remove(seen.keys.first())
    }

    internal fun touch(rid: ByteArray) {
        routes[rid.k]?.last = System.nanoTime()
    }

    private fun bytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    private fun ensureSweeper() {
        if (sweeper?.isActive == true) return
        sweeper = scope.launch {
            while (isActive) {
                delay(config.sweepMs)
                val now = System.nanoTime()
                val it = seen.entries.iterator()
                while (it.hasNext()) {
                    if ((now - it.next().value.second) / 1_000_000 < config.dedupTtlMs) break
                    it.remove()
                }
                byReq.keys.retainAll(seen.keys)
                for ((rid, r) in routes.entries.toList()) {
                    if ((now - r.last) / 1_000_000 > config.routeIdleMs) {
                        routes.remove(rid)
                        r.endpoint?.fail()
                    }
                }
            }
        }
    }
}
