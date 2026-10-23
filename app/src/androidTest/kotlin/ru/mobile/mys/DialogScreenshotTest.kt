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
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * Кадры диалога «Центра» против живого vsc_web на хосте. Аргументы: `screenshots=1`,
 * `centralUrl=http://10.0.2.2:<порт>`, `centralUser`, `centralPassword` (данные засевает хост).
 */
@RunWith(AndroidJUnit4::class)
class DialogScreenshotTest {
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
        val dir = compose.activity.externalMediaDirs.first().apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitText(t: String) = compose.waitUntil(30_000) { compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun dialogScreens() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("centralUrl")
        assumeTrue(args.getString("screenshots") == "1" && url != null)
        compose.onNodeWithTag("password").performTextInput("2468")
        compose.onNodeWithTag("password2").performTextInput("2468")
        compose.onNodeWithText("СОЗДАТЬ ХРАНИЛИЩЕ").performClick()
        waitText("КАНАЛЫ")
        runBlocking { AppGraph.central!!.login(url!!, args.getString("centralUser")!!, args.getString("centralPassword")!!) }
        compose.onNodeWithText("ЦЕНТР").performClick()
        waitText("bobby")
        Thread.sleep(1500)
        shot("d1-list")
        compose.onAllNodesWithText("bobby")[0].performClick()
        waitText("галочки")
        Thread.sleep(1500)
        shot("d2-dialog")
        compose.onNodeWithText("⚙").performClick()
        waitText("МОЁ НАЗВАНИЕ")
        Thread.sleep(1000)
        shot("d3-settings")
    }
}
