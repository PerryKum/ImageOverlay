package com.example.imageoverlay.util

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import com.example.imageoverlay.keybinding.KeyBindingService

object DisplayUtil {
    private const val KEY_BINDING_SERVICE =
        "com.example.imageoverlay.keybinding.KeyBindingService"

    fun hasSecondaryDisplay(context: Context): Boolean {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            (dm?.displays?.size ?: 0) > 1
        } catch (_: Exception) {
            false
        }
    }

    /** 遮罩应覆盖的物理显示区域（含手势导航条、状态栏等系统 UI 区域）。 */
    fun overlayLayoutBounds(windowManager: WindowManager): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.maximumWindowMetrics.bounds
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    fun overlayWindowFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    fun applyOverlayLayoutParams(
        params: WindowManager.LayoutParams,
        windowManager: WindowManager
    ) {
        val bounds = overlayLayoutBounds(windowManager)
        params.width = bounds.width()
        params.height = bounds.height()
        params.gravity = Gravity.TOP or Gravity.START
        params.x = bounds.left
        params.y = bounds.top
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.setFitInsetsTypes(0)
            params.setFitInsetsSides(0)
        }
    }

    /**
     * Android 12+ 对 TYPE_APPLICATION_OVERLAY 的触摸穿透要求窗口级 alpha ≤ 系统阈值（默认 0.8），
     * 仅设置 View.alpha 无效。可信窗口（如无障碍 overlay）不受此限。
     */
    fun applyOverlayVisualOpacity(
        params: WindowManager.LayoutParams,
        imageView: ImageView,
        opacityPercent: Int,
        trustedOverlay: Boolean
    ) {
        val userAlpha = opacityPercent.coerceIn(0, 100) / 100f
        if (trustedOverlay || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            params.alpha = 1.0f
            imageView.alpha = userAlpha
            return
        }
        val maxObscuring = maxObscuringOpacityForTouch(imageView.context)
        if (userAlpha <= maxObscuring) {
            params.alpha = userAlpha
            imageView.alpha = 1.0f
        } else {
            params.alpha = maxObscuring
            imageView.alpha = (userAlpha / maxObscuring).coerceAtMost(1.0f)
        }
    }

    fun maxObscuringOpacityForTouch(context: Context): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
            return inputManager.maximumObscuringOpacityForTouch
        }
        return 1.0f
    }

    fun canUseAccessibilityOverlay(context: Context, displayId: Int): Boolean {
        return displayId <= 0 &&
            KeyBindingService.instance != null &&
            AccessibilityUtil.isServiceEnabled(context, KEY_BINDING_SERVICE)
    }

    fun overlayWindowType(context: Context, displayId: Int): Int {
        if (canUseAccessibilityOverlay(context, displayId)) {
            return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }
}