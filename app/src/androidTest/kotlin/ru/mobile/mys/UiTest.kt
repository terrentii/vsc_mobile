package ru.mobile.mys

import android.Manifest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import ru.mobile.mys.storage.Vault

/** Сценарии UI на эмуляторе; каждый тест — с чистого хранилища. */
@RunWith(AndroidJUnit4::class)
class UiTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    private val fresh = object : ExternalResource() {
        override fun before() {
            runBlocking { AppGraph.lock()?.join() }
            Vault.wipe(AppGraph.vaultDir)
        }
    }

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(fresh).around(compose)

    private fun waitText(text: String, timeoutMs: Long = 30_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun create(pw: String = "2468") { // ПИН из 4 цифр — минимум
        compose.onNodeWithTag("password").performTextInput(pw)
        compose.onNodeWithTag("password2").performTextInput(pw)
        compose.onNodeWithText("СОЗДАТЬ ХРАНИЛИЩЕ").performClick()
        waitText("КАНАЛЫ")
    }

    @Test fun createVaultOpensChannels() = create()

    @Test fun lockThenWrongAndRightPassword() {
        create("верный-пароль")
        compose.onNodeWithText("ЗАМОК").performClick()
        waitText("Введите ПИН-код")
        compose.onNodeWithTag("password").performTextInput("не-тот-пароль")
        compose.onNodeWithText("ОТКРЫТЬ").performClick()
        waitText("Неверный ПИН-код")
        Thread.sleep(2_500) // прогрессивная задержка после первой ошибки — 2 с
        compose.onNodeWithTag("password").performTextInput("верный-пароль")
        compose.onNodeWithText("ОТКРЫТЬ").performClick()
        waitText("КАНАЛЫ")
    }

    @Test fun centralWithoutSessionShowsLogin() {
        create()
        compose.onNodeWithText("ЦЕНТР").performClick()
        waitText("ВХОД В «ЦЕНТР»")
        compose.onNodeWithTag("username").performTextInput("кто-то")
    }

    @Test fun p2pAuthorIsPeerUntilRenamed() {
        create()
        val v = AppGraph.vault!!
        val conv = v.conversations.add(DECENTRALIZED, roomId = byteArrayOf(9))
        v.messages.add(conv, "in", "привет".toByteArray(), "received")
        AppGraph.changed()
        waitText("Канал $conv")
        compose.onNodeWithText("Канал $conv").performClick()
        waitText("СОБЕСЕДНИК")
        v.conversations.rename(conv, "Алиса")
        AppGraph.changed()
        waitText("АЛИСА")
    }

    @Test fun chatShowsStoredMessagesAndConnectButton() {
        create()
        val v = AppGraph.vault!!
        val conv = v.conversations.add(DECENTRALIZED, roomId = byteArrayOf(1, 2, 3), title = "Тестовый канал")
        v.messages.add(conv, "in", "привет из теста".toByteArray(), "received")
        AppGraph.changed()
        waitText("Тестовый канал")
        compose.onNodeWithText("Тестовый канал").performClick()
        waitText("привет из теста")
        waitText("ВЫЙТИ НА СВЯЗЬ")
    }
}
