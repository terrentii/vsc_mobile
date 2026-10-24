package ru.mobile.mys

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import ru.mobile.mys.storage.Vault

/**
 * Сообщение в диалоге «Центра», приложение свёрнуто → уведомление. Против живого vsc_web:
 * `centralUrl=http://10.0.2.2:<порт>`, `centralUser`, `centralPassword`, `peerUser`, `dialogRoom`.
 */
@RunWith(AndroidJUnit4::class)
class DialogNotifyTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(object : ExternalResource() {
            override fun before() {
                runBlocking { AppGraph.lock()?.join() }
                Vault.wipe(AppGraph.vaultDir)
            }
        }).around(compose)

    private fun post(url: String, body: JSONObject, token: String? = null): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        return JSONObject(c.inputStream.bufferedReader().readText())
    }

    @Test fun backgroundDialogMessageNotifies() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("centralUrl")
        assumeTrue(url != null)
        compose.onNodeWithTag("password").performTextInput("2468")
        compose.onNodeWithTag("password2").performTextInput("2468")
        compose.onNodeWithText("СОЗДАТЬ ХРАНИЛИЩЕ").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("КАНАЛЫ").fetchSemanticsNodes().isNotEmpty() }
        runBlocking { AppGraph.central!!.login(url!!, args.getString("centralUser")!!, args.getString("centralPassword")!!) }
        Thread.sleep(3_000) // WS «Центра» подключился
        val withText = args.getString("notifyText") == "1"
        if (withText) AppGraph.setSetting(AppGraph.K_NOTIFY_TEXT, "1")
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        val until = System.currentTimeMillis() + 10_000
        while (AppGraph.foreground && System.currentTimeMillis() < until) Thread.sleep(100)
        val nm = compose.activity.getSystemService(NotificationManager::class.java)
        nm.cancelAll()
        val ua = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (args.getString("doze") == "1") { // экран погас, телефон уснул
            ua.executeShellCommand("dumpsys deviceidle force-idle").close()
            Thread.sleep(45_000)
        }
        val token = post("$url/api/auth/login", JSONObject().put("username", args.getString("peerUser")).put("password", args.getString("centralPassword"))).getString("token")
        post("$url/api/messages", JSONObject().put("room_id", args.getString("dialogRoom")!!.toLong()).put("body", "ты тут?"), token)
        val deadline = System.currentTimeMillis() + 40_000
        while (nm.activeNotifications.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(200)
        val ok = if (withText) {
            nm.activeNotifications.any { it.notification.extras.getCharSequence("android.text")?.toString() == "ты тут?" }
        } else nm.activeNotifications.any { it.id == 2 }
        ua.executeShellCommand("dumpsys deviceidle unforce").close()
        assertTrue(ok, "нет уведомления о сообщении в диалоге")
    }

    @Test fun screenshotsToggleClearsSecureUntilLock() {
        compose.onNodeWithTag("password").performTextInput("2468")
        compose.onNodeWithTag("password2").performTextInput("2468")
        compose.onNodeWithText("СОЗДАТЬ ХРАНИЛИЩЕ").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("КАНАЛЫ").fetchSemanticsNodes().isNotEmpty() }
        val secure = android.view.WindowManager.LayoutParams.FLAG_SECURE
        fun isSecure() = compose.activity.window.attributes.flags and secure != 0
        assertTrue(isSecure())
        compose.runOnUiThread { AppGraph.allowScreenshots(true) }
        compose.waitForIdle()
        assertTrue(!isSecure(), "скриншоты не разрешились")
        compose.runOnUiThread { AppGraph.lock() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("password").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(isSecure(), "замок не вернул FLAG_SECURE")
    }
}
