package ru.mobile.mys.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileTransferTest {
    @Test fun metaMatchesDesktop() {
        val v = Vectors.root.getJSONObject("file_meta")
        val meta = FileMeta(v.bytes("transfer_id"), v.getLong("total_size"), v.getInt("chunk_size"),
            v.getInt("chunk_count"), v.bytes("sha256"), v.getString("mime_type"), v.getString("filename"))
        assertEquals(v.getString("hex"), encodeFileMeta(meta).hex())
        val back = decodeFileMeta(v.bytes("hex"))
        assertEquals(listOf(meta.filename, meta.mimeType), listOf(back.filename, back.mimeType))
        assertEquals(listOf(meta.totalSize, meta.chunkSize.toLong(), meta.chunkCount.toLong()),
            listOf(back.totalSize, back.chunkSize.toLong(), back.chunkCount.toLong()))
    }

    @Test fun chunkMatchesDesktop() {
        val v = Vectors.root.getJSONObject("file_chunk")
        val c = FileChunk(v.bytes("transfer_id"), v.getInt("index"), v.bytes("data"))
        assertEquals(v.getString("hex"), encodeFileChunk(c).hex())
        val back = decodeFileChunk(v.bytes("hex"))
        assertEquals(listOf(c.index.toLong(), c.data.hex()), listOf(back.index.toLong(), back.data.hex()))
    }

    @Test fun validateAndSplit() {
        val ok = FileMeta(ByteArray(16), 200_000, CHUNK_SIZE, 4, ByteArray(32), "a/b", "x")
        validateMeta(ok)
        assertFailsWith<IllegalArgumentException> { validateMeta(FileMeta(ByteArray(16), 200_000, CHUNK_SIZE, 3, ByteArray(32), "a/b", "x")) }
        assertFailsWith<IllegalArgumentException> { validateMeta(FileMeta(ByteArray(16), MAX_FILE_SIZE + 1L, CHUNK_SIZE, 801, ByteArray(32), "a/b", "x")) }
        assertEquals(listOf(65536, 65536, 65536, 3392), splitChunks(ByteArray(200_000)).map { it.size })
        assertEquals("image", kindForFilename("Фото.JPG"))
        assertEquals("file", kindForFilename("notes"))
        assertFailsWith<TransportException> { decodeFileMeta(ByteArray(10)) }
    }
}
