package com.healthwish.app.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

// Google-login Antigravity auth — manual code-paste flow (mobile friendly, no localhost server).
// Saare values Settings > Advanced se editable hain taaki Google kuch badle to app update ke bina theek ho.
object AntigravityAuth {

    const val DEF_CLIENT_ID = "***OAUTH_CLIENT_ID***"
    const val DEF_CLIENT_SECRET = "***OAUTH_SECRET***"
    const val DEF_REDIRECT_URI = "http://localhost:51121/oauth-callback"
    const val DEF_SCOPES =
        "https://www.googleapis.com/auth/cloud-platform " +
            "https://www.googleapis.com/auth/userinfo.email " +
            "https://www.googleapis.com/auth/userinfo.profile " +
            "https://www.googleapis.com/auth/cclog " +
            "https://www.googleapis.com/auth/experimentsandconfigs"
    const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    const val USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v2/userinfo?alt=json"

    private const val PREF = "healthwish_antigravity"
    private const val K_ACCESS = "access_token"
    private const val K_REFRESH = "refresh_token"
    private const val K_EXPIRY = "expiry_ms"
    private const val K_EMAIL = "email"
    private const val K_PROJECT = "project_id"
    private const val K_MODEL = "model"
    private const val K_CLIENT_ID = "client_id"
    private const val K_CLIENT_SECRET = "client_secret"
    private const val K_REDIRECT = "redirect_uri"
    private const val K_SCOPES = "scopes"
    private const val K_API_BASE = "api_base"
    private const val K_API_VER = "api_ver"
    private const val K_UA = "user_agent"
    private const val K_GAPI_CLIENT = "goog_api_client"

    const val DEF_API_BASE = "https://cloudcode-pa.googleapis.com"
    const val DEF_API_VER = "v1internal"
    const val DEF_MODEL = "gemini-2.5-pro"
    const val DEF_UA = "HealthWish-Antigravity/1.0"

    data class Adv(
        val clientId: String,
        val clientSecret: String,
        val redirectUri: String,
        val scopes: String,
        val apiBase: String,
        val apiVer: String,
        val userAgent: String,
        val googApiClient: String,
        val model: String
    )

    private fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx, PREF,
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getAdv(ctx: Context): Adv {
        val p = prefs(ctx)
        fun s(k: String, d: String) = (p.getString(k, null) ?: "").ifBlank { d }
        return Adv(
            clientId = s(K_CLIENT_ID, DEF_CLIENT_ID),
            clientSecret = s(K_CLIENT_SECRET, DEF_CLIENT_SECRET),
            redirectUri = s(K_REDIRECT, DEF_REDIRECT_URI),
            scopes = s(K_SCOPES, DEF_SCOPES),
            apiBase = s(K_API_BASE, DEF_API_BASE).trimEnd('/'),
            apiVer = s(K_API_VER, DEF_API_VER),
            userAgent = s(K_UA, DEF_UA),
            googApiClient = p.getString(K_GAPI_CLIENT, "") ?: "",
            model = s(K_MODEL, DEF_MODEL)
        )
    }

    fun saveAdv(ctx: Context, a: Adv) {
        prefs(ctx).edit()
            .putString(K_CLIENT_ID, a.clientId.trim())
            .putString(K_CLIENT_SECRET, a.clientSecret.trim())
            .putString(K_REDIRECT, a.redirectUri.trim())
            .putString(K_SCOPES, a.scopes.trim())
            .putString(K_API_BASE, a.apiBase.trim().trimEnd('/'))
            .putString(K_API_VER, a.apiVer.trim())
            .putString(K_UA, a.userAgent.trim())
            .putString(K_GAPI_CLIENT, a.googApiClient.trim())
            .putString(K_MODEL, a.model.trim())
            .apply()
    }

    fun buildAuthUrl(adv: Adv, state: String): String {
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        return AUTH_ENDPOINT + "?" +
            "access_type=offline&" +
            "client_id=" + enc(adv.clientId) + "&" +
            "prompt=consent&" +
            "redirect_uri=" + enc(adv.redirectUri) + "&" +
            "response_type=code&" +
            "scope=" + enc(adv.scopes) + "&" +
            "state=" + enc(state)
    }

    fun newState(): String = UUID.randomUUID().toString().take(8)

    // User browser wale failed-localhost URL ya sirf code paste kare — dono chalega
    fun extractCode(pasted: String): String? {
        val t = pasted.trim()
        if (t.isEmpty()) return null
        if (!t.contains("://") && !t.contains("?") && !t.contains("&") && !t.contains(" ")) return t
        val m = Regex("[?&]code=([^&\\s]+)").find(t)
        if (m != null) return m.groupValues[1]
        val m2 = Regex("code%3D([^%&\\s]+)").find(t)
        return m2?.groupValues?.get(1)
    }

    data class Tokens(val access: String, val refresh: String, val expiresInSec: Long)

    private suspend fun postForm(url: String, params: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val body = params.entries.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
            }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) HealthWish/1.0")
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    ?: throw RuntimeException("Google auth error $code (empty)")
                val raw = stream.bufferedReader().use { it.readText() }
                if (code !in 200..299) throw RuntimeException("Google auth error $code: ${raw.take(200)}")
                JSONObject(raw)
            } finally {
                conn.disconnect()
            }
        }

    suspend fun exchangeCode(adv: Adv, code: String): Tokens {
        val j = postForm(
            TOKEN_ENDPOINT, mapOf(
                "code" to code,
                "client_id" to adv.clientId,
                "client_secret" to adv.clientSecret,
                "redirect_uri" to adv.redirectUri,
                "grant_type" to "authorization_code"
            )
        )
        val access = j.optString("access_token", "")
        if (access.isBlank()) throw RuntimeException("Token exchange me access_token nahi: ${j.toString().take(200)}")
        return Tokens(access, j.optString("refresh_token", ""), j.optLong("expires_in", 3600))
    }

    suspend fun refresh(adv: Adv, refreshToken: String): Tokens {
        val j = postForm(
            TOKEN_ENDPOINT, mapOf(
                "client_id" to adv.clientId,
                "client_secret" to adv.clientSecret,
                "refresh_token" to refreshToken,
                "grant_type" to "refresh_token"
            )
        )
        val access = j.optString("access_token", "")
        if (access.isBlank()) throw RuntimeException("Refresh fail: ${j.toString().take(200)}")
        val newRefresh = j.optString("refresh_token", "").ifBlank { refreshToken }
        return Tokens(access, newRefresh, j.optLong("expires_in", 3600))
    }

    suspend fun fetchEmail(accessToken: String): String = withContext(Dispatchers.IO) {
        val conn = (URL(USERINFO_ENDPOINT).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000
            readTimeout = 30000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) HealthWish/1.0")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                ?: throw RuntimeException("Userinfo error $code")
            val raw = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw RuntimeException("Userinfo error $code: ${raw.take(150)}")
            val email = JSONObject(raw).optString("email", "").trim()
            if (email.isBlank()) throw RuntimeException("Userinfo me email nahi")
            email
        } finally {
            conn.disconnect()
        }
    }

    fun saveSession(ctx: Context, t: Tokens, email: String) {
        prefs(ctx).edit()
            .putString(K_ACCESS, t.access)
            .putString(K_REFRESH, t.refresh)
            .putLong(K_EXPIRY, System.currentTimeMillis() + t.expiresInSec * 1000)
            .putString(K_EMAIL, email)
            .apply()
    }

    fun getEmail(ctx: Context): String? =
        prefs(ctx).getString(K_EMAIL, null)?.ifBlank { null }

    fun isConnected(ctx: Context): Boolean =
        getEmail(ctx) != null &&
            !(prefs(ctx).getString(K_REFRESH, null)?.isBlank() ?: true)

    fun saveProject(ctx: Context, projectId: String) {
        prefs(ctx).edit().putString(K_PROJECT, projectId).apply()
    }

    fun getProject(ctx: Context): String? =
        prefs(ctx).getString(K_PROJECT, null)?.ifBlank { null }

    // Valid access token do (zaroorat ho to refresh karke). Null = login chahiye.
    suspend fun validAccessToken(ctx: Context): String? {
        val p = prefs(ctx)
        val adv = getAdv(ctx)
        val access = p.getString(K_ACCESS, null) ?: ""
        val expiry = p.getLong(K_EXPIRY, 0)
        if (access.isNotBlank() && System.currentTimeMillis() < expiry - 60_000) return access
        val refresh = p.getString(K_REFRESH, null)?.ifBlank { null } ?: return null
        return try {
            val t = refresh(adv, refresh)
            saveSession(ctx, t, getEmail(ctx) ?: "")
            t.access
        } catch (e: Exception) {
            null
        }
    }

    fun logout(ctx: Context) {
        val adv = getAdv(ctx) // advanced settings rakho, sirf session udaao
        prefs(ctx).edit().clear().apply()
        // advanced wapas likho taaki client/model setup na khoye
        saveAdv(
            ctx, Adv(
                adv.clientId, adv.clientSecret, adv.redirectUri, adv.scopes,
                adv.apiBase, adv.apiVer, adv.userAgent, adv.googApiClient, adv.model
            )
        )
    }
}
