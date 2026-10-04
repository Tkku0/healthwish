package com.healthwish.app.utils

import android.content.Context

object PreferenceHelper {
    private const val PREFS = "healthwish_user_prefs"
    private const val KEY_MY_PROFILE = "my_profile" // "Tkku" or "Shumu"
    private const val KEY_CAL_TARGET = "calorie_target" // daily kcal goal

    fun getMyProfile(ctx: Context): String? {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getString(KEY_MY_PROFILE, null)
    }

    fun setMyProfile(ctx: Context, profile: String) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_MY_PROFILE, profile).apply()
    }

    fun getCalorieTarget(ctx: Context): Int {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getInt(KEY_CAL_TARGET, 2000).coerceIn(500, 10000)
    }

    fun setCalorieTarget(ctx: Context, kcal: Int) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit().putInt(KEY_CAL_TARGET, kcal.coerceIn(500, 10000)).apply()
    }
}
