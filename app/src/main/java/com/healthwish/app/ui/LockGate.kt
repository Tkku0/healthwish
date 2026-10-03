package com.healthwish.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.healthwish.app.security.AppLockManager
import androidx.fragment.app.FragmentActivity

// App open hote hi ye screen: Fingerprint -> fail to PIN
@Composable
fun LockGate(activity: FragmentActivity, unlocked: Boolean, onUnlocked: () -> Unit, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    if (!AppLockManager.isEnabled(ctx) || unlocked) { content(); return }
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        if (AppLockManager.canUseBiometric(ctx)) {
            AppLockManager.promptBiometric(activity, onOk = onUnlocked, onFail = { err = it })
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ElevatedCard(Modifier.padding(24.dp)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("🔒 HealthWish", style = MaterialTheme.typography.headlineSmall)
                Text("4-digit PIN dalo")
                OutlinedTextField(pin, { if (it.length <= 4) pin = it.filter(Char::isDigit) }, label = { Text("PIN") })
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
                Button(onClick = {
                    if (AppLockManager.checkPin(ctx, pin)) onUnlocked() else err = "Galat PIN"
                }, modifier = Modifier.fillMaxWidth()) { Text("Unlock 💜") }
            }
        }
    }
}
