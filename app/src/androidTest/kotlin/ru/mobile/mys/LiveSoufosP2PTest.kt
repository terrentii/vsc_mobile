package ru.mobile.mys

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.mobile.mys.crypto.Ristretto
import ru.mobile.mys.p2p.P2PService
import ru.mobile.mys.storage.Vault
import ru.mobile.mys.storage.VaultP2PStore
import ru.mobile.mys.webrtc.WebRtcLink

/**
 * Телефон ↔ настоящий десктоп через боевой rendezvous. Запуск — только с
 * аргументом `soufosPhrase`; на хосте тем временем работает эхо-пир:
 * `python tools/desktop_peer.py ../vsc_desktop/src wss://soufos.ru/p2p "<фраза>" <tmp>`.
 * URL переопределяется аргументом `rendezvous`; `expectDirect=1` — с WebRTC и
 * проверкой, что канал стал прямым (эхо-пир с MYS_WEBRTC=1).
 */
@RunWith(AndroidJUnit4::class)
class LiveSoufosP2PTest {
    @Test fun echoThroughProductionRendezvous() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phrase = args.getString("soufosPhrase")
        assumeTrue("нужен аргумент soufosPhrase", phrase != null)
        val url = args.getString("rendezvous") ?: "wss://soufos.ru/p2p"
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "live-${System.nanoTime()}")
        val vault = Vault.create(dir, "pw".toByteArray(), opsLimit = 1, memLimit = 8L shl 20)
        val direct = args.getString("expectDirect") == "1"
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val service = P2PService(
            VaultP2PStore(vault), url, Ristretto(sodium), connectTimeoutMs = 300_000,
            directLinks = if (direct) ({ WebRtcLink(ctx, emptyList()) }) else null,
        )
        try {
            val conv = service.startSession(phrase!!)
            if (direct) withTimeout(60_000) { while (!service.isDirect(conv)) delay(100) }
            service.send(conv, "привет с телефона")
            val expected = (if (direct) "[direct] " else "") + "echo: привет с телефона"
            withTimeout(120_000) { // эхо — два кадра через relay
                while (vault.messages.list(conv).none { it.direction == "in" && it.body?.decodeToString() == expected }) delay(100)
            }
            assertEquals(true, service.hasSession(conv))
            service.send(conv, "bye")
            delay(1_000)
        } finally {
            service.stop()
            vault.close()
            Vault.wipe(dir)
            dir.delete()
        }
    }
}
