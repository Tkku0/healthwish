package com.healthwish.app.ai

import android.content.Context
import com.healthwish.app.security.AiConfigManager
import org.json.JSONObject

// AI backend router: "key" (API key wala) ya "google" (Google login Antigravity).
// Dono flows (manual estimate + AI box) yahi se guzarte hain.
object AiRouter {

    fun activeBackend(ctx: Context): String = AiConfigManager.getBackend(ctx)

    fun isReady(ctx: Context): Boolean {
        return if (activeBackend(ctx) == AiConfigManager.BACKEND_GOOGLE) {
            AntigravityAuth.isConnected(ctx)
        } else {
            AiConfigManager.isConfigured(ctx)
        }
    }

    private suspend fun runGoogle(
        ctx: Context,
        system: String,
        user: String
    ): String {
        val appCtx = ctx.applicationContext
        val adv = AntigravityAuth.getAdv(appCtx)
        val token = AntigravityAuth.validAccessToken(appCtx)
            ?: throw RuntimeException("Google login expire — dobara Connect karo 🅖")
        val project = AntigravityClient.ensureProjectId(appCtx, token, adv)
        return AntigravityClient.generate(appCtx, token, adv, project, adv.model, system, user)
    }

    private fun keyCfg(ctx: Context): com.healthwish.app.security.AiConfig {
        val appCtx = ctx.applicationContext
        if (!AiConfigManager.isConfigured(appCtx)) {
            throw RuntimeException("Settings > AI Setup me key dalo 🔑")
        }
        return AiConfigManager.get(appCtx)
    }

    suspend fun estimateCalories(
        ctx: Context,
        foodText: String,
        rules: String
    ): Pair<Int?, String?> {
        if (foodText.isBlank()) return Pair(null, null)
        val appCtx = ctx.applicationContext
        return try {
            if (activeBackend(ctx) == AiConfigManager.BACKEND_GOOGLE) {
                val adv = AntigravityAuth.getAdv(appCtx)
                val token = AntigravityAuth.validAccessToken(appCtx)
                    ?: return Pair(null, "Google login expire — dobara Connect karo 🅖")
                val project = AntigravityClient.ensureProjectId(appCtx, token, adv)
                val out = AntigravityClient.generate(
                    appCtx, token, adv, project, adv.model,
                    "You estimate calories for Indian food. Reply with ONLY JSON like {\"calories\": 450}. " +
                        "Estimate total kcal for the whole described meal. " + rules,
                    "Meal: $foodText"
                )
                val kcal = AiClient.extractKcal(out)
                if (kcal != null) Pair(kcal, null)
                else Pair(null, "AI reply samajh nahi aaya")
            } else {
                AiClient.estimateCalories(keyCfg(ctx), foodText, rules)
            }
        } catch (e: Exception) {
            Pair(null, e.message ?: "AI fail")
        }
    }

    suspend fun parseFoodInput(
        ctx: Context,
        input: String,
        rules: String
    ): Pair<AiMealResult?, String?> {
        if (input.isBlank()) return Pair(null, null)
        val appCtx = ctx.applicationContext
        return try {
            if (activeBackend(ctx) == AiConfigManager.BACKEND_GOOGLE) {
                val adv = AntigravityAuth.getAdv(appCtx)
                val token = AntigravityAuth.validAccessToken(appCtx)
                    ?: return Pair(null, "Google login expire — dobara Connect karo 🅖")
                val project = AntigravityClient.ensureProjectId(appCtx, token, adv)
                val out = AntigravityClient.generate(
                    appCtx, token, adv, project, adv.model,
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
                Pair(AiClient.parseJsonResult(out, input), null)
            } else {
                AiClient.parseFoodInput(keyCfg(ctx), input, rules)
            }
        } catch (e: Exception) {
            Pair(null, e.message ?: "AI fail")
        }
    }

    // Google-login backend ka full test (Settings me)
    suspend fun testGoogle(ctx: Context): String {
        val appCtx = ctx.applicationContext
        val adv = AntigravityAuth.getAdv(appCtx)
        val token = AntigravityAuth.validAccessToken(appCtx)
            ?: return "1. Google login ✗: connected nahi — pehle Connect karo"
        return AntigravityClient.testFull(
            appCtx, token, adv, adv.model, AiConfigManager.getPrompt(appCtx)
        )
    }
}
