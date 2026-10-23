package ru.mobile.mys.mesh

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

const val MAX_FRAME = 72 * 1024

class WireClosed(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Упорядоченный надёжный поток кадров к соседу; шифрование — в [Link]. */
interface Wire {
    suspend fun send(frame: ByteArray)
    suspend fun recv(): ByteArray
    suspend fun close()
}

/** Пара каналов; close() любой стороны обрывает recv() обеих. */
class MemoryWire private constructor(private val inbox: Channel<ByteArray>, private val outbox: Channel<ByteArray>) : Wire {
    override suspend fun send(frame: ByteArray) {
        try { outbox.send(frame) } catch (e: ClosedSendChannelException) { throw WireClosed("провод закрыт") }
    }

    override suspend fun recv(): ByteArray {
        val f = try { inbox.receive() } catch (e: ClosedReceiveChannelException) { throw WireClosed("провод закрыт") }
        if (f.size > MAX_FRAME) throw WireClosed("кадр больше MAX_FRAME")
        return f
    }

    override suspend fun close() {
        outbox.close()
        inbox.close()
    }

    companion object {
        fun pair(): Pair<MemoryWire, MemoryWire> {
            val ab = Channel<ByteArray>(Channel.UNLIMITED)
            val ba = Channel<ByteArray>(Channel.UNLIMITED)
            return MemoryWire(ba, ab) to MemoryWire(ab, ba)
        }
    }
}

/** Кадры `u32be длина ‖ байты` поверх TCP. */
class TcpWire(private val socket: Socket) : Wire {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private val writeLock = Mutex()

    override suspend fun send(frame: ByteArray) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                output.writeInt(frame.size)
                output.write(frame)
                output.flush()
            } catch (e: IOException) { throw WireClosed("TCP: ${e.message}", e) }
        }
    }

    override suspend fun recv(): ByteArray = withContext(Dispatchers.IO) {
        try {
            val n = input.readInt()
            if (n < 0 || n > MAX_FRAME) throw WireClosed("кадр больше MAX_FRAME")
            ByteArray(n).also { input.readFully(it) }
        } catch (e: IOException) { throw WireClosed("TCP: ${e.message}", e) }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) { runCatching { socket.close() } }
    }

    companion object {
        suspend fun connect(host: String, port: Int): TcpWire =
            withContext(Dispatchers.IO) { TcpWire(Socket(host, port).apply { tcpNoDelay = true }) }
    }
}
