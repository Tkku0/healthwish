package com.healthwish.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.healthwish.app.auth.AuthViewModel

@Composable
fun LoginScreen(vm: AuthViewModel = viewModel()) {
    val st by vm.s.collectAsState()
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ElevatedCard(Modifier.padding(24.dp).fillMaxWidth()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("💜 HealthWish", style = MaterialTheme.typography.headlineMedium)
                Text("Tum + GF — Email se login karo", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                if (st.error.isNotEmpty()) Text(st.error, color = MaterialTheme.colorScheme.error)
                Button(onClick = { vm.login(email, pass) }, enabled = !st.loading, modifier = Modifier.fillMaxWidth()) {
                    if (st.loading) CircularProgressIndicator(Modifier.size(20.dp)) else Text("Login & Sync")
                }
                Text("User Firebase Console > Authentication > Users me pehle banao.", style = MaterialTheme.typography.bodySmall)
                if (st.uid != null) Text("UID: ${st.uid}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
