package com.healthwish.app.ai

import com.healthwish.app.security.AiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// OpenAI-compatible chat client: Gemini / OpenAI / Groq / OpenRouter / koi bhi custom server.
// Koi extra dependency nahi — HttpURLConnection + org.json (Android built-in).
data class AiMealResult(
    val meal: String, // breakfast | lunch | dinner | snacks
    val text: String,
    val calories: Int
)

object AiClient {

    private const val TIMEOUT_MS = 30000

    private suspend fun postJson(url: String, apiKey: String, body: String): String =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("HTTP-Referer", "https://github.com/Shubhamkothar/healthwish")
                setRequestProperty("X-Title", "HealthWish")
                // Default Dalvik UA ko kabhi-kabhi Cloudflare block kar deta hai — browser UA safe
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) HealthWish/1.0")
            }
            try {
                try {
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                } catch (e: IOException) {
                    throw RuntimeException("Net fail (${e.message}) — internet check karo")
                }
                val code = try {
                    conn.responseCode
                } catch (e: IOException) {
                    throw RuntimeException("Net fail (${e.message}) — internet check karo")
                }
                val stream = try {
                    if (code in 200..299) conn.inputStream else conn.errorStream
                } catch (e: IOException) {
                    throw RuntimeException("Net fail (${e.message})")
                } ?: throw RuntimeException("AI error $code (empty reply)")
                val raw = stream.bufferedReader().use { it.readText() }
                if (code !in 200..299) {
                    throw RuntimeException("AI error $code: ${shortError(raw)}")
                }
                raw
            } finally {
                conn.disconnect()
            }
        }

    private fun shortError(raw: String): String {
        return try {
            val err = JSONObject(raw).optJSONObject("error")
            (err?.optString("message", "")?.ifBlank { null } ?: raw).take(160)
        } catch (e: Exception) {
            raw.take(160).ifBlank { "unknown error" }
        }
    }

    private fun chatBody(cfg: AiConfig, system: String, user: String, jsonMode: Boolean): String {
        val root = JSONObject()
            .put("model", cfg.model)
            .put("temperature", 0.2)
            .put("max_tokens", 300)
            .put("messages", org.json.JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user))
            )
        if (jsonMode) root.put("response_format", JSONObject().put("type", "json_object"))
        return root.toString()
    }

    private suspend fun chat(cfg: AiConfig, system: String, user: String): String {
        val url = cfg.baseUrl.trimEnd('/') + "/chat/completions"
        // 1st try: JSON mode
        val first = runCatching {
            choiceText(postJson(url, cfg.apiKey, chatBody(cfg, system, user, jsonMode = true)))
        }
        if (first.getOrNull()?.isNotBlank() == true) return first.getOrThrow()
        val firstErr = first.exceptionOrNull()
        val firstMsg = firstErr?.message ?: ""
        // JSON-mode reject (400) ho ya khaali reply aaya ho — bina JSON mode ke retry
        if (firstMsg.contains("response_format", ignoreCase = true) ||
            firstMsg.contains("json mode", ignoreCase = true) ||
            firstMsg.contains(" 400") ||
            firstErr == null || firstMsg.isBlank()
        ) {
            val second = runCatching {
                choiceText(postJson(url, cfg.apiKey, chatBody(cfg, system, user, jsonMode = false)))
            }
            if (second.getOrNull()?.isNotBlank() == true) return second.getOrThrow()
            val sErr = second.exceptionOrNull()?.message
            throw RuntimeException(
                if (!sErr.isNullOrBlank()) sErr
                else "AI ne khaali reply diya (2 tries) — thodi der me retry karo ya model badlo"
            )
        }
        throw firstErr ?: RuntimeException("AI fail")
    }

    private fun choiceText(raw: String): String {
        val choices = JSONObject(raw).optJSONArray("choices")
            ?: throw RuntimeException("AI reply me choices nahi — retry karo")
        if (choices.length() == 0) throw RuntimeException("AI reply khaali — retry karo")
        val content = choices.getJSONObject(0)
            .optJSONObject("message")?.optString("content", "") ?: ""
        return content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    // {..} block nikalo (model ne aas-paas text likh diya ho tab bhi)
    private fun extractJsonObject(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("{") && t.endsWith("}")) return t
        val s = t.indexOf('{')
        val e = t.lastIndexOf('}')
        if (s >= 0 && e > s) return t.substring(s, e + 1)
        return t
    }

    private fun extractKcal(raw: String): Int? {
        try {
            JSONObject(extractJsonObject(raw)).optInt("calories", -1)
                .takeIf { it >= 0 }?.let { return it }
        } catch (e: Exception) {
            // neeche fallback
        }
        // Fallback: "450 kcal / 450 calories" jaisa pehla number
        val m = Regex("(\\d{2,4})\\s*(kcal|calories|\\bcal\\b)", RegexOption.IGNORE_CASE).find(raw)
        return m?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..5000 }
    }

    // Manual entry par auto-calorie. Returns (kcal, error?) — error null matlab success.
    suspend fun estimateCalories(cfg: AiConfig, foodText: String, rules: String): Pair<Int?, String?> {
        if (foodText.isBlank()) return Pair(null, null)
        return try {
            val out = chat(
                cfg,
                "You estimate calories for Indian food. Reply with ONLY JSON like {\"calories\": 450}. " +
                    "Estimate total kcal for the whole described meal. " + rules,
                "Meal: $foodText"
            )
            val kcal = extractKcal(out)
            if (kcal != null) Pair(kcal, null)
            else Pair(null, "AI reply samajh nahi aaya")
        } catch (e: Exception) {
            Pair(null, e.message ?: "AI fail")
        }
    }

    // Free-text entry: kaunsa meal + saaf text + calories — teeno ek call me.
    suspend fun parseFoodInput(cfg: AiConfig, input: String, rules: String): Pair<AiMealResult?, String?> {
        if (input.isBlank()) return Pair(null, null)
        return try {
            val out = chat(
                cfg,
                "You log Indian meals. The user describes what they ate and optionally when " +
                    "(like \"subah\", \"dopahar ko\", \"raat me\", \"abhi\", or a meal name). " +
                    "Reply with ONLY JSON like {\"meal\": \"breakfast\", \"text\": \"2 roti, dal\", \"calories\": 350}. " +
                    "Rules: meal must be exactly one of breakfast, lunch, dinner, snacks. " +
                    "Map morning/subah/breakfast/nashta to breakfast; afternoon/dopahar/lunch to lunch; " +
                    "night/raat/dinner to dinner; evening snacks/chai-time/munching to snacks. " +
                    "If no time given, default to snacks. Rewrite text cleanly with quantities. " +
                    "Estimate total kcal as an integer. " + rules,
                input
            )
            val j = JSONObject(extractJsonObject(out))
            val meal = j.optString("meal", "snacks").lowercase()
            val kcal = j.optInt("calories", -1).takeIf { it >= 0 }
                ?: extractKcal(out) ?: 0
            Pair(
                AiMealResult(
                    meal = if (meal in setOf("breakfast", "lunch", "dinner", "snacks")) meal else "snacks",
                    text = j.optString("text", input).trim().ifBlank { input.trim() },
                    calories = kcal
                ),
                null
            )
        } catch (e: Exception) {
            Pair(null, e.message ?: "AI fail")
        }
    }

    // Settings me Test button ke liye
    suspend fun test(cfg: AiConfig): String {
        val out = chat(
            cfg,
            "Reply with ONLY JSON like {\"ok\": \"HealthWish AI ready\"}.",
            "ping"
        )
        return try {
            JSONObject(extractJsonObject(out)).optString("ok", out.take(120)).ifBlank { "Connected ✓" }
        } catch (e: Exception) {
            out.take(120)
        }
    }
}
