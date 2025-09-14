package com.example.imageoverlay.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils

object AccessibilityUtil {

    fun isServiceEnabled(context: Context, serviceClassName: String): Boolean {
        return try {
            val enabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            if (!enabled) return false
            val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (enabledServices.isNullOrBlank()) return false
            val myService = ComponentName(context.packageName, serviceClassName).flattenToString()
            TextUtils.SimpleStringSplitter(':').let { splitter ->
                splitter.setString(enabledServices)
                while (splitter.hasNext()) {
                    val s = splitter.next()
                    if (s.equals(myService, ignoreCase = true)) return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}


