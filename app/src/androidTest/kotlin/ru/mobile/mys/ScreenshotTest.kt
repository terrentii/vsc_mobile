package ru.mobile.mys

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import ru.mobile.mys.storage.Vault

/**
 * Кадры экранов для визуальной проверки (adb screencap чёрный из-за FLAG_SECURE —
 * снимаем отрисовкой Compose). Только по аргументу `screenshots=1`;
 * файлы — в /sdcard/Android/media/ru.mobile.mys/.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(object : ExternalResource() {
            override fun before() {
                runBlocking { AppGraph.lock()?.join() }
                Vault.wipe(AppGraph.vaultDir)
            }
        }).around(compose)

    private fun shot(name: String) {
        compose.waitForIdle()
        val dir = File(compose.activity.externalMediaDirs.first(), "").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // по таймауту — кадр того, что было на экране
    private fun waitText(t: String) = try {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) { shot("timeout"); throw e }

    @Test fun screens() {
        val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("screenshots") == "1")
        shot("1-unlock")
        compose.onNodeWithTag("password").performTextInput("пароль-теста")
        compose.onNodeWithTag("password2").performTextInput("пароль-теста")
        compose.onNodeWithText("СОЗДАТЬ ХРАНИЛИЩЕ").performScrollTo().performClick()
        waitText("КАНАЛЫ")
        val v = AppGraph.vault!!
        val conv = v.conversations.add(DECENTRALIZED, roomId = byteArrayOf(1), title = "Алиса")
        v.conversations.add(DECENTRALIZED, roomId = byteArrayOf(2), title = "Работа")
        v.messages.add(conv, "in", "Привет! Это канал МЫС.".toByteArray(), "received")
        v.messages.add(conv, "out", "Привет, вижу тебя".toByteArray(), "sent")
        v.messages.add(conv, "out", "А это ещё в пути".toByteArray(), "pending")
        AppGraph.changed()
        waitText("Алиса")
        shot("2-channels")
        compose.onNodeWithText("Алиса").performClick()
        waitText("Это канал")
        shot("3-chat")
        compose.onNodeWithTag("message").performTextInput("Длинное сообщение,\nчтобы проверить,\nкак кнопки держат строку")
        shot("3b-chat-typing")
        compose.activity.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitText("КАНАЛЫ")
        compose.onNodeWithText("ЦЕНТР").performClick()
        waitText("ВХОД В «ЦЕНТР»")
        shot("4-central-login")
        compose.onNodeWithText("⚙").performClick()
        waitText("НАСТРОЙКИ")
        shot("5-settings")
    }
}
