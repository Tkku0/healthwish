package com.healthwish.app.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

// Custom AI setup: koi bhi OpenAI-compatible provider (Gemini / OpenAI / Groq / OpenRouter / Custom).
// Key sirf is phone me rehti hai (Keystore-backed), Firebase/git me kabhi nahi jati.
data class AiConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = ""
)

object AiConfigManager {
    private const val PREF = "healthwish_ai"
    private const val KEY_BASE = "base_url"
    private const val KEY_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_PROMPT = "custom_prompt"

    // Default nutrition rules — Settings > AI Prompt se edit ho sakta hai
    const val DEFAULT_RULES =
        "Use these standard home-style portions (average oil): " +
            "1 roti (30g atta)=100; 1 katori cooked dal (150g)=150; 1 katori cooked rice (150g)=200; " +
            "1 katori sabzi=130; 1 tsp oil/ghee=45; 1 glass milk (250ml)=150; 1 boiled egg=70; " +
            "100g chicken curry=180; 100g paneer curry=250; 1 samosa=250; 1 cup chai (milk+sugar)=70; " +
            "1 banana=105; 1 apple=95; 100g curd=65; 1 thali (2 roti+dal+rice+sabzi)=550. " +
            "If quantity unclear, assume 1 standard serving. Always reply an integer."

    // Gemini ka OpenAI-compatible endpoint — AI Studio key seedha chalti hai
    val PRESETS: Map<String, Pair<String, String>> = linkedMapOf(
        "Gemini (Google AI Studio)" to Pair(
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-2.0-flash"
        ),
        "OpenAI" to Pair("https://api.openai.com/v1", "gpt-4o-mini"),
        "Groq" to Pair("https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
        "OpenRouter" to Pair("https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct"),
        "DeepSeek" to Pair("https://api.deepseek.com/v1", "deepseek-chat"),
        "Custom" to Pair("", "")
    )

    private fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx, PREF,
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun get(ctx: Context): AiConfig {
        val p = prefs(ctx)
        return AiConfig(
            baseUrl = (p.getString(KEY_BASE, "") ?: "").trim().trimEnd('/'),
            apiKey = (p.getString(KEY_KEY, "") ?: "").trim(),
            model = (p.getString(KEY_MODEL, "") ?: "").trim()
        )
    }

    fun save(ctx: Context, cfg: AiConfig) {
        prefs(ctx).edit()
            .putString(KEY_BASE, cfg.baseUrl.trim().trimEnd('/'))
            .putString(KEY_KEY, cfg.apiKey.trim())
            .putString(KEY_MODEL, cfg.model.trim())
            .apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    // Custom nutrition prompt (khali = default rules). Dono flows (manual + AI box) me judta hai.
    fun getPrompt(ctx: Context): String {
        val saved = prefs(ctx).getString(KEY_PROMPT, null)?.trim()
        return if (saved.isNullOrBlank()) DEFAULT_RULES else saved
    }

    fun savePrompt(ctx: Context, text: String) {
        prefs(ctx).edit().putString(KEY_PROMPT, text.trim()).apply()
    }

    fun resetPrompt(ctx: Context) {
        prefs(ctx).edit().remove(KEY_PROMPT).apply()
    }

    fun isPromptCustomized(ctx: Context): Boolean {
        return !(prefs(ctx).getString(KEY_PROMPT, null)?.trim().isNullOrBlank())
    }

    fun isConfigured(ctx: Context): Boolean {
        val c = get(ctx)
        return c.baseUrl.startsWith("http") && c.apiKey.length >= 8 && c.model.isNotBlank()
    }
}
