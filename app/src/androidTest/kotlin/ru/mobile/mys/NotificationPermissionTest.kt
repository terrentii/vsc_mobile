package ru.mobile.mys

import android.Manifest
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Регрессия: на первом запуске (разрешение на уведомления не выдано) приложение
 * падало сразу после разблокировки — biometric 1.1.0 тянул androidx.fragment 1.2.5,
 * чья проверка «только младшие 16 бит requestCode» отвергала код от нового
 * ActivityResultRegistry. Исключение летело синхронно из launch(), поэтому
 * проверяем ровно этот вызов, без Compose и системного диалога.
 */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun requestingNotificationPermissionDoesNotThrow() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val pkg = instrumentation.targetContext.packageName
        // Как свежая установка: разрешение не выдано и Android не «запомнил» отказ.
        instrumentation.uiAutomation.executeShellCommand(
            "pm clear-permission-flags $pkg ${Manifest.permission.POST_NOTIFICATIONS} user-set user-fixed",
        ).close()
        instrumentation.uiAutomation.revokeRuntimePermission(pkg, Manifest.permission.POST_NOTIFICATIONS)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val launcher = activity.activityResultRegistry.register("probe", ActivityResultContracts.RequestPermission()) {}
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS) // старый fragment бросал здесь
                launcher.unregister()
            }
            instrumentation.uiAutomation.executeShellCommand("input keyevent 4").close() // закрыть системный диалог
        }
    }
}
