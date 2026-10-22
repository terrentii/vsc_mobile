package ru.mobile.mys.p2p

/*
 * Формат содержимого внутри envelope.seal — порт mys_decentralized/filetransfer.py.
 * Первый байт plaintext — kind; wire-кадры не меняются.
 */

const val KIND_TEXT: Byte = 0x00
const val KIND_FILE_META: Byte = 0x01
const val KIND_FILE_CHUNK: Byte = 0x02
/** Сигналинг WebRTC (JSON offer/answer/cand) — служебный, не сообщение. */
const val KIND_SIGNAL: Byte = 0x03

/** 64 КиБ: влезает и в лимит сообщения WebRTC (спека §2). Получатель берёт размер из META. */
const val CHUNK_SIZE = 64 * 1024
const val MAX_FILE_SIZE = 50 * 1024 * 1024
const val TRANSFER_ID_LEN = 16

private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "tif")

/** "image" — для инлайн-превью, иначе "file". */
fun kindForFilename(filename: String): String =
    if (filename.substringAfterLast('.', "").lowercase() in IMAGE_EXTS) "image" else "file"

class FileMeta(
    val transferId: ByteArray, val totalSize: Long, val chunkSize: Int, val chunkCount: Int,
    val sha256: ByteArray, val mimeType: String, val filename: String,
)

class FileChunk(val transferId: ByteArray, val index: Int, val data: ByteArray)

private const val META_FIXED = TRANSFER_ID_LEN + 8 + 4 + 4 + 32

fun encodeFileMeta(m: FileMeta): ByteArray {
    require(m.transferId.size == TRANSFER_ID_LEN && m.sha256.size == 32)
    return Writer().bytes(m.transferId).u64(m.totalSize).u32(m.chunkSize).u32(m.chunkCount)
        .bytes(m.sha256).var8(m.mimeType.toByteArray()).var16(m.filename.toByteArray()).done()
}

fun decodeFileMeta(buf: ByteArray): FileMeta {
    if (buf.size < META_FIXED) throw TransportException("усечённый заголовок FILE_META")
    val r = Reader(buf)
    return FileMeta(r.take(TRANSFER_ID_LEN), r.u64(), r.u32().toInt(), r.u32().toInt(), r.take(32),
        r.var8().decodeToString(), r.var16().decodeToString())
}

fun encodeFileChunk(c: FileChunk): ByteArray {
    require(c.transferId.size == TRANSFER_ID_LEN)
    return Writer().bytes(c.transferId).u32(c.index).bytes(c.data).done()
}

fun decodeFileChunk(buf: ByteArray): FileChunk {
    if (buf.size < TRANSFER_ID_LEN + 4) throw TransportException("усечённый заголовок FILE_CHUNK")
    val r = Reader(buf)
    return FileChunk(r.take(TRANSFER_ID_LEN), r.u32().toInt(), r.rest())
}

fun splitChunks(data: ByteArray, size: Int = CHUNK_SIZE): List<ByteArray> =
    (data.indices step size).map { data.copyOfRange(it, minOf(it + size, data.size)) }

fun validateMeta(m: FileMeta) {
    require(m.totalSize <= MAX_FILE_SIZE) { "файл превышает лимит $MAX_FILE_SIZE байт" }
    require(m.chunkSize > 0) { "chunk_size должен быть положительным" }
    val expected = (m.totalSize + m.chunkSize - 1) / m.chunkSize
    require(m.chunkCount.toLong() == expected) { "chunk_count=${m.chunkCount} не соответствует ожидаемому $expected" }
}
