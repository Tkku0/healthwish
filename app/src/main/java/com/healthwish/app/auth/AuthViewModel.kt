package com.healthwish.app.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class AuthState(val loading: Boolean = false, val error: String = "", val uid: String? = FirebaseAuth.getInstance().currentUser?.uid)

class AuthViewModel : ViewModel() {
    private val auth = FirebaseAuth.getInstance()
    private val _s = MutableStateFlow(AuthState())
    val s: StateFlow<AuthState> = _s

    init {
        auth.addAuthStateListener { _s.value = AuthState(uid = it.currentUser?.uid) }
    }

    fun login(email: String, pass: String) {
        if (email.isBlank() || pass.length < 6) { _s.value = _s.value.copy(error = "Email + 6-char password dalo"); return }
        viewModelScope.launch {
            _s.value = _s.value.copy(loading = true, error = "")
            try {
                auth.signInWithEmailAndPassword(email.trim(), pass).await()
                _s.value = AuthState(uid = auth.currentUser?.uid)
            } catch (e: Exception) {
                _s.value = _s.value.copy(loading = false, error = e.message ?: "Login fail — Firebase me user bana hai?")
            }
        }
    }
    fun logout() { auth.signOut(); _s.value = AuthState(uid = null) }
}
