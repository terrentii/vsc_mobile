package ru.mobile.mys.central

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Сервер недоступен при разблокировке — /ws и опрос всё равно запущены и стучатся дальше. */
class ResumeOfflineTest {
    @Test fun resumeWithoutNetworkKeepsRetrying() = runBlocking {
        val srv = ServerSocket(0)
        val hits = AtomicInteger()
        thread(isDaemon = true) { while (true) runCatching { srv.accept().close(); hits.incrementAndGet() }.onFailure { if (srv.isClosed) return@thread } }
        val store = MemoryCentralStore()
        Account.save(store, Session("http://127.0.0.1:${srv.localPort}", "me", 1, "tok"))
        val c = CentralService(store, roomPollMs = 200)
        try {
            c.resume() // раньше бросало сетевую ошибку и оставляло «Центр» без live
            val before = hits.get()
            Thread.sleep(2_000)
            assertTrue(hits.get() - before >= 3, "после неудачного синка нет повторных обращений к серверу")
        } finally {
            c.stop()
            srv.close()
        }
    }
}
