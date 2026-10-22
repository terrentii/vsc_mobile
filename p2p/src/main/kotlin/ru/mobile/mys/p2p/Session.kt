package ru.mobile.mys.p2p

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.mobile.mys.crypto.RatchetState
import ru.mobile.mys.crypto.deriveTransformKey
import ru.mobile.mys.crypto.open
import ru.mobile.mys.crypto.seal

/**
 * E2E-сессия над транспортом — порт mys_decentralized/session.py.
 *
 * Отправка: `DATA{seal(kind ‖ body)}`. Приём: open → атомарная запись в хранилище.
 * Битый/повторный кадр отбрасывается, сессия жива. RESPONDER до prime-кадра
 * копит исходящие в очереди.
 */
class Session internal constructor(
    private val transport: Transport,
    private val state: RatchetState,
    private val tk: ByteArray,
    private val store: SessionStore,
    private val onMessage: (Long) -> Unit,
    private val onDisconnect: () -> Unit,
) {
    private class PendingFile {
        var meta: FileMeta? = null
        val chunks = HashMap<Int, ByteArray>()
    }

    private class Incoming(val kind: String, val filename: String?, val mimeType: String?, val body: ByteArray)

    private val mutex = Mutex()
    private var recvJob: Job? = null
    private val outbox = ArrayDeque<kotlin.Pair<Long, ByteArray>>()
    private val pendingFiles = HashMap<ByteBuffer, PendingFile>()

    /** Есть ли отправляющая цепочка (у RESPONDER — после первого входящего). */
    val canSend: Boolean get() = state.cks != null

    fun start(scope: CoroutineScope) {
        if (recvJob == null) recvJob = scope.launch { recvLoop() }
    }

    suspend fun send(text: String) {
        val data = text.toByteArray()
        if (data.isEmpty()) return // пустой payload зарезервирован под prime
        val payload = byteArrayOf(KIND_TEXT) + data
        mutex.withLock {
            if (!canSend) {
                outbox.addLast(store.addOutgoing(data, "pending") to payload)
                return
            }
            sealSaveSend(payload)
            store.addOutgoing(data, "sent")
        }
    }

    /** META + чанки; требует [canSend] (файл в очередь не ставится — как в десктопе). */
    suspend fun sendFile(filename: String, mimeType: String, data: ByteArray): Long {
        require(data.size <= MAX_FILE_SIZE) { "файл превышает лимит $MAX_FILE_SIZE байт" }
        return mutex.withLock {
            check(canSend) { "канал ещё не готов для отправки файла (примите первое сообщение)" }
            val id = ByteArray(TRANSFER_ID_LEN).also { SecureRandom().nextBytes(it) }
            val chunks = splitChunks(data)
            val sha = MessageDigest.getInstance("SHA-256").digest(data)
            sealSaveSend(byteArrayOf(KIND_FILE_META) + encodeFileMeta(FileMeta(id, data.size.toLong(), CHUNK_SIZE, chunks.size, sha, mimeType, filename)))
            chunks.forEachIndexed { i, c -> sealSaveSend(byteArrayOf(KIND_FILE_CHUNK) + encodeFileChunk(FileChunk(id, i, c))) }
            store.addOutgoing(data, "sent", kindForFilename(filename), filename, mimeType)
        }
    }

    /** INITIATOR после подключения: пустой кадр даёт RESPONDER'у отправляющую цепочку. */
    suspend fun sendPrime() = mutex.withLock { if (canSend) sealSaveSend(ByteArray(0)) }

    /** Состояние сохраняется ДО отправки: упади процесс после send — ключ цепочки не переиспользуется. */
    private suspend fun sealSaveSend(payload: ByteArray) {
        val sealed = seal(state, tk, payload)
        store.saveRatchet(state)
        transport.send(encodeMessage(Msg.Data(sealed)))
    }

    private suspend fun flushOutboxLocked() {
        while (outbox.isNotEmpty() && canSend) {
            val (id, payload) = outbox.removeFirst()
            sealSaveSend(payload)
            store.setStatus(id, "sent")
        }
    }

    private suspend fun recvLoop() {
        try {
            while (true) handleFrame(transport.recv())
        } catch (e: TransportException) {
            // Обрыв сам по себе (close() отменяет задачу и сюда не попадает) — пир пропал.
            runCatching { transport.close() }
            runCatching { onDisconnect() }
        }
    }

    private suspend fun handleFrame(frame: ByteArray) {
        val msg = try { decodeMessage(frame) } catch (e: TransportException) { return }
        if (msg !is Msg.Data) return
        var receivedId: Long? = null
        mutex.withLock {
            val plaintext = try { open(state, tk, msg.sealed) } catch (e: Exception) { return } // ratchet не двинулся
            val incoming = if (plaintext.isEmpty()) null else runCatching { dispatch(plaintext) }.getOrNull()
            if (incoming == null) store.saveRatchet(state) // prime / промежуточный кадр файла
            else receivedId = store.receive(incoming.body, state, incoming.kind, incoming.filename, incoming.mimeType)
            flushOutboxLocked()
        }
        receivedId?.let(onMessage)
    }

    private fun dispatch(plaintext: ByteArray): Incoming? {
        val body = plaintext.copyOfRange(1, plaintext.size)
        return when (plaintext[0]) {
            KIND_TEXT -> Incoming("text", null, null, body)
            KIND_FILE_META -> decodeFileMeta(body).let { validateMeta(it); entry(it.transferId).meta = it; complete(it.transferId) }
            KIND_FILE_CHUNK -> decodeFileChunk(body).let { entry(it.transferId).chunks[it.index] = it.data; complete(it.transferId) }
            else -> null // неизвестный kind (напр. сигналинг WebRTC новых версий) — тихо отбросить
        }
    }

    private fun entry(id: ByteArray) = pendingFiles.getOrPut(ByteBuffer.wrap(id)) { PendingFile() }

    private fun complete(id: ByteArray): Incoming? {
        val key = ByteBuffer.wrap(id)
        val e = pendingFiles[key] ?: return null
        val meta = e.meta ?: return null
        if (e.chunks.size < meta.chunkCount) return null
        val out = ByteArrayOutputStream(meta.totalSize.toInt())
        for (i in 0 until meta.chunkCount) out.write(e.chunks[i] ?: return null)
        pendingFiles.remove(key)
        val full = out.toByteArray()
        if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(full), meta.sha256)) return null
        return Incoming(kindForFilename(meta.filename), meta.filename, meta.mimeType, full)
    }

    suspend fun close() {
        recvJob?.cancelAndJoin()
        recvJob = null
        pendingFiles.clear()
        transport.close()
    }
}

/** Сессия беседы: ratchet возобновляется из хранилища, иначе сеется из хендшейка. */
fun openSession(
    store: SessionStore,
    transport: Transport,
    sk: ByteArray,
    seedState: RatchetState,
    onMessage: (Long) -> Unit = {},
    onDisconnect: () -> Unit = {},
): Session {
    val state = store.loadRatchet() ?: seedState.also { store.saveRatchet(it) }
    return Session(transport, state, deriveTransformKey(sk), store, onMessage, onDisconnect)
}
