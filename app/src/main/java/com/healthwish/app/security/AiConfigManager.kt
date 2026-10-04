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

    // Built-in shared defaults — GF ko kuch setup nahi karna padega.
    // NOTE: ye key APK/repo me rahegi; decompile se nikal sakti hai. Abuse lage to Groq se revoke karo.
    private const val BUILTIN_BASE = "https://api.groq.com/openai/v1"
    private const val BUILTIN_MODEL = "openai/gpt-oss-120b"
    private const val BUILTIN_KEY = "***REVOKED***"

    // Default nutrition rules (user-tuned prompt) — Settings > AI Prompt se edit ho sakta hai.
    // IMPORTANT: output hamesha sirf JSON hona chahiye taaki app auto-fill kar sake.
    const val DEFAULT_RULES =
        "You are an Indian Food & Nutrition Logging AI. Interpret every food the Indian way " +
            "(roti/paratha/rice/dal/rajma/chole/sabzi/aloo/paneer/curd/poha/upma/idli/dosa/sambar/chutney/samosa/pakora/biryani/pulao/chicken curry/egg curry/chai/milk/lassi/fruits/dry fruits/mithai). " +
            "Understand quantities: roti count, katori (a small Indian bowl, ~150-180ml — never a Western cup), bowl, plate, glass, cup, spoon, handful, piece, thali. " +
            "Account for Indian cooking: oil/ghee/butter/cream/sugar/tadka/gravy/frying — e.g. aloo sabzi is NOT plain boiled potato. " +
            "Assume a medium homemade wheat roti unless stated (never restaurant naan). " +
            "For biryani/thali/restaurant food estimate the complete serving incl. rice, oil/ghee and gravy; scale up if unusually large. " +
            "Split dry fruits by type (almond/cashew/walnut/raisin/pista/dates) — missing qty means one Indian handful. " +
            "Split mixed meals into components and total them (e.g. 3 roti + 1 katori aloo sabzi; biryani rice part + chicken pieces separately). " +
            "Calories are estimates, never exact. Don't ask questions unless missing info changes the result a lot. " +
            "Understand Hindi/Hinglish/English ('2 roti aur dal', 'ek katori aloo ki sabji', 'subah chai biscuit'). " +
            "Keep a balanced, non-restrictive tone. " +
            "CRITICAL OUTPUT RULE: your entire reply must be ONLY the single JSON object asked for in the system message — " +
            "no headings, no emojis, no ranges, no extra text. The app parses it automatically."

    // Gemini ka OpenAI-compatible endpoint — AI Studio key seedha chalti hai
    val PRESETS: Map<String, Pair<String, String>> = linkedMapOf(
        "Gemini (Google AI Studio)" to Pair(
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-2.5-flash"
        ),
        "OpenAI" to Pair("https://api.openai.com/v1", "gpt-4o-mini"),
        "Groq" to Pair("https://api.groq.com/openai/v1", "openai/gpt-oss-120b"),
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

    // Saved values win; jo khaali ho wahan built-in default lagta hai (zero-setup for family).
    fun get(ctx: Context): AiConfig {
        val p = prefs(ctx)
        val base = (p.getString(KEY_BASE, "") ?: "").trim().trimEnd('/')
        val key = (p.getString(KEY_KEY, "") ?: "").trim()
        val model = (p.getString(KEY_MODEL, "") ?: "").trim()
        return AiConfig(
            baseUrl = if (base.isBlank()) BUILTIN_BASE else base,
            apiKey = if (key.isBlank()) BUILTIN_KEY else key,
            model = if (model.isBlank()) BUILTIN_MODEL else model
        )
    }

    // true = apni key dali hui hai; false = built-in shared key chal rahi hai
    fun isUsingCustomKey(ctx: Context): Boolean {
        return !((prefs(ctx).getString(KEY_KEY, "") ?: "").trim().isBlank())
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
