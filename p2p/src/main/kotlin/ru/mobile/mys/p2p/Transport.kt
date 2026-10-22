package ru.mobile.mys.p2p

/** Двунаправленный канал непрозрачных кадров к одному пиру; о шифровании не знает. */
interface Transport {
    suspend fun send(data: ByteArray)
    /** Следующий кадр; TransportException — канал оборвался. */
    suspend fun recv(): ByteArray
    suspend fun close()
}
