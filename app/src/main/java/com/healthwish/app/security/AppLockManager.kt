package com.healthwish.app.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object AppLockManager {
    private const val PREF = "healthwish_lock"
    private const val KEY_PIN = "pin"
    private const val KEY_ENABLED = "enabled"

    private fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx, PREF,
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
    fun savePin(ctx: Context, pin: String) {
        prefs(ctx).edit().putString(KEY_PIN, pin).putBoolean(KEY_ENABLED, true).apply()
    }
    fun checkPin(ctx: Context, pin: String): Boolean =
        prefs(ctx).getString(KEY_PIN, "") == pin

    fun canUseBiometric(ctx: Context): Boolean {
        val m = BiometricManager.from(ctx)
        return m.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun promptBiometric(activity: FragmentActivity, onOk: () -> Unit, onFail: (String) -> Unit) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { onOk() }
                override fun onAuthenticationFailed() { onFail("Try again") }
                override fun onAuthenticationError(c: Int, s: CharSequence) { onFail(s.toString()) }
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("HealthWish 💜")
                .setSubtitle("Apna fingerprint se unlock karo")
                .setNegativeButtonText("PIN use karo")
                .build()
        )
    }
}
