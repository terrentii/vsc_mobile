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

/**
 * Телефон ↔ настоящий десктоп через боевой rendezvous. Запуск — только с
 * аргументом `soufosPhrase`; на хосте тем временем работает эхо-пир:
 * `python tools/desktop_peer.py ../vsc_desktop/src wss://soufos.ru/p2p "<фраза>" <tmp>`.
 * URL переопределяется аргументом `rendezvous`.
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
        val service = P2PService(VaultP2PStore(vault), url, Ristretto(sodium), connectTimeoutMs = 300_000)
        try {
            val conv = service.startSession(phrase!!)
            service.send(conv, "привет с телефона")
            withTimeout(120_000) { // эхо — два кадра через relay
                while (vault.messages.list(conv).none { it.direction == "in" && it.body?.decodeToString() == "echo: привет с телефона" }) delay(100)
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
