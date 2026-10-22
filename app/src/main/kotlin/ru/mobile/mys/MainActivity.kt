package ru.mobile.mys

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import ru.mobile.mys.ui.MysTheme
import ru.mobile.mys.ui.Root

/** FragmentActivity — для BiometricPrompt. FLAG_SECURE: нет скриншотов и превью в «Недавних». */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent { MysTheme(AppGraph.dark) { Root() } }
    }

    override fun onStart() {
        super.onStart()
        AppGraph.checkAutoLock()
        AppGraph.foreground = true
    }

    override fun onStop() {
        AppGraph.foreground = false
        AppGraph.touch() // отсчёт автоблокировки — с момента ухода с экрана
        super.onStop()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        AppGraph.touch()
    }
}
