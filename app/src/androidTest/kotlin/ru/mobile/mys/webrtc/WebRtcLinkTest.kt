package ru.mobile.mys.webrtc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import ru.mobile.mys.p2p.Transport

/** Два WebRtcLink в одном процессе: сигналы — напрямую друг другу, канал — настоящий DataChannel. */
@RunWith(AndroidJUnit4::class)
class WebRtcLinkTest {
    @Test fun twoLinksOpenDataChannelAndCarryFrames() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val a = WebRtcLink(ctx, emptyList())
        val b = WebRtcLink(ctx, emptyList())
        val ta = CompletableDeferred<Transport>()
        val tb = CompletableDeferred<Transport>()
        a.start(true, signal = { b.onSignal(it) }, opened = { ta.complete(it) }, closed = {})
        b.start(false, signal = { a.onSignal(it) }, opened = { tb.complete(it) }, closed = {})
        try {
            val (x, y) = withTimeout(30_000) { ta.await() to tb.await() }
            val big = Random(1).nextBytes(70_000) // кадр с чанком файла 64 КиБ + заголовки
            x.send("привет".toByteArray())
            x.send(big)
            y.send("ответ".toByteArray())
            withTimeout(10_000) {
                assertContentEquals("привет".toByteArray(), y.recv())
                assertContentEquals(big, y.recv())
                assertContentEquals("ответ".toByteArray(), x.recv())
            }
        } finally {
            a.close()
            b.close()
        }
    }
}
