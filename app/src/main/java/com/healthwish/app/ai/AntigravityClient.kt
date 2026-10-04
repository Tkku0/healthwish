package com.healthwish.app.ai

import android.content.Context
import com.healthwish.app.ai.AntigravityAuth.Adv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Antigravity (Cloud Code Assist internal API) client.
// Envelope: {"project": "...", "model": "...", "request": {gemini-style body}}.
// Sab URLs/headers/values Settings > Advanced se editable.
object AntigravityClient {

    private const val TIMEOUT_MS = 30000
    private const val GEN_TIMEOUT_MS = 90000

    private fun postJson(
        url: String,
        accessToken: String,
        ua: String,
        googApiClient: String,
        body: String,
        timeoutMs: Int = TIMEOUT_MS
    ): Pair<Int, String> = readConn(url, accessToken, ua, googApiClient, "POST", body, timeoutMs)

    private fun readConn(
        url: String,
        accessToken: String,
        ua: String,
        googApiClient: String,
        method: String,
        body: String?,
        timeoutMs: Int
    ): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = timeoutMs
            if (body != null) doOutput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", ua.ifBlank { "HealthWish-Antigravity/1.0" })
            if (googApiClient.isNotBlank()) setRequestProperty("X-Goog-Api-Client", googApiClient)
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = try {
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (e: Exception) {
                ""
            }
            return Pair(code, raw)
        } finally {
            conn.disconnect()
        }
    }

    private fun extractProjectId(data: JSONObject): String {
        val direct = listOf("cloudaicompanionProject", "projectId", "project")
        for (k in direct) {
            if (!data.has(k) || data.isNull(k)) continue
            val v = data.get(k)
            if (v is String && v.isNotBlank()) return v
            if (v is JSONObject) {
                val id = v.optString("id", "")
                if (id.isNotBlank()) return id
            }
        }
        return ""
    }

    private fun defaultTierId(loadResp: JSONObject): String {
        try {
            val tiers = loadResp.optJSONArray("allowedTiers")
            if (tiers != null) {
                for (i in 0 until tiers.length()) {
                    val t = tiers.optJSONObject(i) ?: continue
                    if (t.optBoolean("isDefault", false)) {
                        val id = t.optString("id", "")
                        if (id.isNotBlank()) return id
                    }
                }
            }
            val cur = loadResp.optJSONObject("currentTier")?.optString("id", "")
            if (!cur.isNullOrBlank()) return cur
        } catch (e: Exception) {
        }
        return "free-tier"
    }

    suspend fun ensureProjectId(ctx: Context, accessToken: String, adv: Adv): String =
        withContext(Dispatchers.IO) {
            AntigravityAuth.getProject(ctx)?.takeIf { it.isNotBlank() }?.let { return@withContext it }
            val loadUrl = "${adv.apiBase.trimEnd('/')}/${adv.apiVer}:loadCodeAssist"
            val loadBody = JSONObject()
                .put("metadata", JSONObject().put("ideType", "ANTIGRAVITY"))
                .toString()
            val (code, raw) = postJson(loadUrl, accessToken, adv.userAgent, "", loadBody)
            if (code !in 200..299) {
                throw RuntimeException("loadCodeAssist $code: ${raw.take(200)}")
            }
            val loadResp = JSONObject(raw)
            extractProjectId(loadResp).takeIf { it.isNotBlank() }?.let {
                AntigravityAuth.saveProject(ctx, it)
                return@withContext it
            }
            // Project nahi mila → onboard (max 5 tries, 2s gap)
            val tier = defaultTierId(loadResp)
            val onboardBody = JSONObject()
                .put("tier_id", tier)
                .put(
                    "metadata", JSONObject()
                        .put("ide_type", "ANTIGRAVITY")
                        .put("ide_version", "1.0")
                        .put("ide_name", "antigravity")
                )
                .toString()
            // onboard daily endpoint par bhi try karo (official flow), phir normal
            val bases = listOf(
                adv.apiBase.trimEnd('/'),
                "https://daily-cloudcode-pa.googleapis.com"
            )
            var lastErr = ""
            repeat(5) {
                for (base in bases) {
                    val url = "$base/${adv.apiVer}:onboardUser"
                    val (c2, r2) = postJson(url, accessToken, adv.userAgent, adv.googApiClient, onboardBody)
                    if (c2 == 200) {
                        val data = JSONObject(r2)
                        if (data.optBoolean("done", false)) {
                            val pid = extractProjectId(data.optJSONObject("response") ?: JSONObject())
                            if (pid.isNotBlank()) {
                                AntigravityAuth.saveProject(ctx, pid)
                                return@withContext pid
                            }
                            throw RuntimeException("onboardUser done par project_id nahi")
                        }
                    } else {
                        lastErr = "onboardUser $c2: ${r2.take(150)}"
                    }
                }
                delay(2000)
            }
            throw RuntimeException("onboardUser 5 tries me complete nahi. $lastErr")
        }

    // SSE stream se saara model text jodo (thought parts skip)
    private fun parseSseText(sse: String): String {
        val sb = StringBuilder()
        for (line in sse.lines()) {
            val t = line.trim()
            if (!t.startsWith("data:")) continue
            val payload = t.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            try {
                val j = JSONObject(payload)
                val cands = j.optJSONArray("candidates") ?: continue
                for (i in 0 until cands.length()) {
                    val parts = cands.optJSONObject(i)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts") ?: continue
                    for (p in 0 until parts.length()) {
                        val part = parts.optJSONObject(p) ?: continue
                        if (part.optBoolean("thought", false)) continue
                        val text = part.optString("text", "")
                        if (text.isNotEmpty()) sb.append(text)
                    }
                }
            } catch (e: Exception) {
                // ek kharab chunk poori reply kharab na kare
            }
        }
        // Kuch server seedha JSON (non-SSE) bhejte hain — fallback
        if (sb.isEmpty() && sse.trim().startsWith("{")) {
            try {
                val j = JSONObject(sse)
                val cands = j.optJSONArray("candidates")
                if (cands != null) {
                    for (i in 0 until cands.length()) {
                        val parts = cands.optJSONObject(i)?.optJSONObject("content")?.optJSONArray("parts")
                            ?: continue
                        for (p in 0 until parts.length()) {
                            val part = parts.optJSONObject(p) ?: continue
                            if (!part.optBoolean("thought", false)) {
                                sb.append(part.optString("text", ""))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }
        return sb.toString().trim()
    }

    suspend fun generate(
        ctx: Context,
        accessToken: String,
        adv: Adv,
        projectId: String,
        model: String,
        system: String,
        user: String
    ): String = withContext(Dispatchers.IO) {
        val request = JSONObject()
            .put(
                "contents", org.json.JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts", org.json.JSONArray().put(
                                JSONObject().put("text", "System: $system\n\nUser: $user")
                            )
                        )
                )
            )
            .put(
                "systemInstruction", JSONObject()
                    .put("role", "system")
                    .put(
                        "parts", org.json.JSONArray().put(
                            JSONObject().put("text", system)
                        )
                    )
            )
            .put(
                "generationConfig", JSONObject()
                    .put("temperature", 0.2)
                    .put("maxOutputTokens", 300)
            )
        val envelope = JSONObject()
            .put("project", projectId)
            .put("model", model)
            .put("request", request)
            .toString()
        val url = "${adv.apiBase.trimEnd('/')}/${adv.apiVer}:streamGenerateContent?alt=sse"
        val (code, raw) = postJson(url, accessToken, adv.userAgent, adv.googApiClient, envelope, GEN_TIMEOUT_MS)
        if (code !in 200..299) {
            throw RuntimeException("generate $code: ${raw.take(200)}")
        }
        val text = parseSseText(raw)
        if (text.isBlank()) throw RuntimeException("AI ne khaali reply diya — retry karo ya model badlo")
        text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    // Poori chain ek sath test: login → project → ek calorie call. Step-wise report.
    suspend fun testFull(ctx: Context, accessToken: String, adv: Adv, model: String, rules: String): String =
        withContext(Dispatchers.IO) {
            val log = StringBuilder()
            try {
                val email = AntigravityAuth.fetchEmail(accessToken)
                log.append("1. Google login ✓ ($email)\n")
            } catch (e: Exception) {
                return@withContext "1. Google login ✗: ${(e.message ?: "").take(150)}"
            }
            val project: String
            try {
                project = ensureProjectId(ctx, accessToken, adv)
                log.append("2. Project ✓ ($project)\n")
            } catch (e: Exception) {
                return@withContext log.toString() + "2. Project ✗: ${(e.message ?: "").take(180)}"
            }
            try {
                val out = generate(
                    ctx, accessToken, adv, project, model,
                    "Reply with ONLY JSON like {\"calories\": 450}. " + rules,
                    "Meal: 2 roti dal"
                )
                val kcal = try {
                    JSONObject(out).optInt("calories", -1).takeIf { it >= 0 }
                } catch (e: Exception) {
                    null
                }
                if (kcal != null) log.append("3. Generate ✓ (test: 2 roti dal = $kcal kcal)\nALL OK 🎉")
                else log.append("3. Generate ✗ (reply parse nahi hui): ${out.take(150)}")
            } catch (e: Exception) {
                log.append("3. Generate ✗: ${(e.message ?: "").take(200)}")
            }
            log.toString()
        }
}
