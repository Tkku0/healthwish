package com.healthwish.app.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

// Couple ka shared sync-password: isi se Firestore field-encryption key banti hai.
// Password device ke Android Keystore-backed EncryptedSharedPreferences me rehta hai,
// Firebase par kabhi nahi jata. DONO phone me SAME password dalna zaroori hai.
object PrivacyManager {
    private const val PREF = "healthwish_privacy"
    private const val KEY_PASS = "sync_password"

    private fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx, PREF,
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun hasPassword(ctx: Context) = !getPassword(ctx).isNullOrBlank()

    fun getPassword(ctx: Context): String? =
        prefs(ctx).getString(KEY_PASS, null)?.ifBlank { null }

    fun setPassword(ctx: Context, password: String) {
        prefs(ctx).edit().putString(KEY_PASS, password).apply()
    }

    fun clearPassword(ctx: Context) {
        prefs(ctx).edit().remove(KEY_PASS).apply()
    }
}
