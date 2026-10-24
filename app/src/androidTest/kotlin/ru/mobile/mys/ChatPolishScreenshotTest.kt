package ru.mobile.mys

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import ru.mobile.mys.storage.Vault

/** Кадры и проверки «фишек» чата против живого vsc_web (засев — seed_polish.py). */
class ChatPolishScreenshotTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(object : ExternalResource() {
            override fun before() { runBlocking { AppGraph.lock()?.join() }; Vault.wipe(AppGraph.vaultDir) }
        }).around(compose)

    private fun shot(name: String) {
        compose.waitForIdle()
        val dir = compose.activity.externalMediaDirs.first().apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitText(t: String) = compose.waitUntil(30_000) { compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun scrollTo(text: String, desc: Boolean = false) {
        compose.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(if (desc) androidx.compose.ui.test.hasContentDescription(text) else androidx.compose.ui.test.hasText(text))
        compose.waitForIdle()
    }

    @Test fun chatPolish() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("centralUrl")
        assumeTrue(url != null)
        compose.onNodeWithTag("password").performTextInput("2468")
        compose.onNodeWithTag("password2").performTextInput("2468")
        compose.onNodeWithText("СОЗДАТЬ ХРАНИЛИЩЕ").performClick()
        waitText("КАНАЛЫ")
        runBlocking { AppGraph.central!!.login(url!!, "alice", "pw123456") }
        compose.onNodeWithText("ЦЕНТР").performClick()
        waitText("bobby")
        compose.onAllNodesWithText("bobby")[0].performClick()
        waitText("нашёл сам")
        // фото — своим сообщением
        val conv = AppGraph.vault!!.conversations.list().first { AppGraph.central?.meta(it.id)?.isDialog == true }.id
        val png = java.io.ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF4D7BFF.toInt()) }
                .compress(Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()
        AppGraph.vault!!.messages.add(conv, "out", png, "sent", kind = "image", filename = "схема.png", mimeType = "image/png")
        AppGraph.changed()
        Thread.sleep(1500)
        shot("p1-chat")
        // цитата → переход к исходному с подсветкой
        compose.onNodeWithText("Уже не нужно", substring = true).assertExists()
        compose.onAllNodesWithText("Скинь, пожалуйста", substring = true)[0].performClick()
        Thread.sleep(700)
        shot("p2-quote-jump")
        // кнопка «вниз»
        assertTrue(compose.onAllNodesWithText("↓").fetchSemanticsNodes().isNotEmpty(), "нет кнопки «вниз»")
        compose.onNodeWithText("↓").performClick()
        Thread.sleep(800)
        // долгое нажатие → меню
        scrollTo("Вот, держи")
        compose.onNodeWithText("Вот, держи").performTouchInput { longClick() }
        Thread.sleep(500)
        shot("p3-menu")
        compose.onNodeWithText("Ответить").performClick()
        // свайп влево → ответ
        compose.onNodeWithText("✕").performClick()
        scrollTo("Сообщение 11")
        compose.onNodeWithText("Сообщение 11").performTouchInput { swipeLeft(startX = centerX, endX = centerX - 400f) }
        Thread.sleep(500)
        assertTrue(compose.onAllNodesWithText("↩ bobby", substring = true).fetchSemanticsNodes().isNotEmpty(), "свайп не включил ответ")
        shot("p4-swipe-reply")
        // фото на весь экран
        scrollTo("схема.png", desc = true)
        val img = compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("схема.png"))
        img[0].performClick()
        Thread.sleep(600)
        shot("p5-viewer")
        assertTrue(compose.onAllNodesWithText("СОХРАНИТЬ").fetchSemanticsNodes().isNotEmpty(), "просмотр не открылся")
    }
}
