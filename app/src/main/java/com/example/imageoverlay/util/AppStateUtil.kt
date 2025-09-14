package com.example.imageoverlay.util

import android.content.Context

object AppStateUtil {
    private const val PREF = "app_state"
    private const val KEY_IN_APP_ACTIVE = "in_app_active"

    fun setInAppActive(context: Context, active: Boolean) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_IN_APP_ACTIVE, active).apply()
    }

    fun isInAppActive(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_IN_APP_ACTIVE, false)
    }
}


