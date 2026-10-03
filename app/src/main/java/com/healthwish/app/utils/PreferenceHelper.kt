package com.healthwish.app.utils

import android.content.Context

object PreferenceHelper {
    private const val PREFS = "healthwish_user_prefs"
    private const val KEY_MY_PROFILE = "my_profile" // "Tkku" or "Shumu"

    fun getMyProfile(ctx: Context): String? {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getString(KEY_MY_PROFILE, null)
    }

    fun setMyProfile(ctx: Context, profile: String) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_MY_PROFILE, profile).apply()
    }
}
