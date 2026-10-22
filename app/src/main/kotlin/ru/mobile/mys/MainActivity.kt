package ru.mobile.mys

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import ru.mobile.mys.crypto.Ristretto

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ponytail: временный смоук libsodium на устройстве, уйдёт с UI под-проекта 5.
        val ok = runCatching { Ristretto(sodium).scalarRandom() }.isSuccess
        setContent { Text("МЫС · ristretto255: ${if (ok) "ok" else "нет"}") }
    }
}
