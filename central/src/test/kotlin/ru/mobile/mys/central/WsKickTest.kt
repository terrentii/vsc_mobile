package ru.mobile.mys.central

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test

/** kick(): после обрыва переподключаемся сразу, а не после паузы (вернулась сеть, будильник). */
class WsKickTest {
    @Test fun kickSkipsBackoff() = runBlocking {
        val srv = ServerSocket(0)
        val hits = AtomicInteger()
        thread(isDaemon = true) { while (!srv.isClosed) runCatching { srv.accept().close(); hits.incrementAndGet() } }
        val ws = WsClient("ws://127.0.0.1:${srv.localPort}/ws", "t", OkHttpClient(), initialBackoffMs = 60_000)
        val job = launch(Dispatchers.IO) { runCatching { ws.events().collect {} } }
        try {
            Thread.sleep(1_000)
            assertEquals(1, hits.get()) // первая попытка, дальше пауза минуту
            ws.kick()
            Thread.sleep(1_000)
            assertEquals(2, hits.get())
        } finally {
            job.cancel()
            srv.close()
        }
    }
}
