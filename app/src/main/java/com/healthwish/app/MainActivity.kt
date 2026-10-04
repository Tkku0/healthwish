package com.healthwish.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.healthwish.app.ui.HealthWishApp
import com.healthwish.app.ui.LoginScreen
import com.healthwish.app.ui.LockGate
import com.healthwish.app.auth.AuthViewModel
import androidx.lifecycle.viewmodel.compose.viewModel as vmCompose

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.healthwish.app.data.DayRepository.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            HealthWishTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val authVm: AuthViewModel = vmCompose()
                    val authState by authVm.s.collectAsState()
                    var unlocked by remember { mutableStateOf(false) }
                    if (authState.uid == null) {
                        LoginScreen(authVm)
                    } else {
                        LockGate(activity = this, unlocked = unlocked, onUnlocked = { unlocked = true }) {
                            HealthWishApp(onLogout = { authVm.logout(); unlocked = false })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HealthWishTheme(
    darkTheme: Boolean = true, // default dark, cozy for couple
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFFFF8FA3), // soft rose
        secondary = androidx.compose.ui.graphics.Color(0xFF7ED6A5),
        tertiary = androidx.compose.ui.graphics.Color(0xFFFFD6A5)
    ) else lightColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFFE35D7C),
        secondary = androidx.compose.ui.graphics.Color(0xFF2E9E6B)
    )
    MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = content)
}
