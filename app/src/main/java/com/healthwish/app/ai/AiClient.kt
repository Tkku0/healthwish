package com.healthwish.app.ai

import com.healthwish.app.security.AiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

    private suspend fun chat(cfg: AiConfig, system: String, user: String): String =
        withContext(Dispatchers.IO) {
            val url = URL(cfg.baseUrl.trimEnd('/') + "/chat/completions")
            val body = JSONObject()
                .put("model", cfg.model)
                .put("temperature", 0.2)
                .put("max_tokens", 300)
                .put("response_format", JSONObject().put("type", "json_object"))
                .put("messages", org.json.JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
                )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val raw = stream.bufferedReader().use { it.readText() }
                if (code !in 200..299) {
                    val msg = try {
                        JSONObject(raw).optJSONObject("error")?.optString("message", raw)
                            ?: raw.take(200)
                    } catch (e: Exception) {
                        raw.take(200)
                    }
                    throw RuntimeException("AI error $code: $msg")
                }
                val content = JSONObject(raw)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            } finally {
                conn.disconnect()
            }
        }

    // Manual entry par auto-calorie: sirf number nikalta hai
    suspend fun estimateCalories(cfg: AiConfig, foodText: String): Int? {
        if (foodText.isBlank()) return null
        return try {
            val out = chat(
                cfg,
                "You estimate calories for Indian food. Reply with ONLY JSON like {\"calories\": 450}. " +
                    "Estimate total kcal for the whole described meal. If unsure, give your best guess as an integer.",
                "Meal: $foodText"
            )
            JSONObject(out).optInt("calories", -1).takeIf { it >= 0 }
        } catch (e: Exception) {
            null
        }
    }

    // Free-text entry: kaunsa meal + saaf text + calories — teeno ek call me
    suspend fun parseFoodInput(cfg: AiConfig, input: String): AiMealResult? {
        if (input.isBlank()) return null
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
                    "Estimate total kcal as an integer (best guess for Indian portions).",
                input
            )
            val j = JSONObject(out)
            val meal = j.optString("meal", "snacks").lowercase()
            AiMealResult(
                meal = if (meal in setOf("breakfast", "lunch", "dinner", "snacks")) meal else "snacks",
                text = j.optString("text", input).trim().ifBlank { input.trim() },
                calories = j.optInt("calories", 0).coerceAtLeast(0)
            )
        } catch (e: Exception) {
            null
        }
    }

    // Settings me Test button ke liye — kaam kiya to model ka short reply, nahi to error
    suspend fun test(cfg: AiConfig): String {
        val out = chat(
            cfg,
            "Reply with ONLY JSON like {\"ok\": \"HealthWish AI ready\"}.",
            "ping"
        )
        return try {
            JSONObject(out).optString("ok", out.take(120)).ifBlank { "Connected ✓" }
        } catch (e: Exception) {
            out.take(120)
        }
    }
}
