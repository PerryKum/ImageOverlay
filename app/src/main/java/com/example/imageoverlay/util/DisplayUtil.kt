package com.example.imageoverlay.util

import android.content.Context
import android.hardware.display.DisplayManager

object DisplayUtil {
    fun hasSecondaryDisplay(context: Context): Boolean {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            (dm?.displays?.size ?: 0) > 1
        } catch (_: Exception) {
            false
        }
    }
}
