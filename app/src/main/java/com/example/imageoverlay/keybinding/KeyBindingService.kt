package com.example.imageoverlay.keybinding

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.widget.Toast
import com.example.imageoverlay.R
import android.view.accessibility.AccessibilityEvent
import com.example.imageoverlay.model.ConfigRepository
import com.example.imageoverlay.util.OverlayToggler

/**
 * 通过无障碍服务监听实体按键事件。若检测到与用户绑定的按键组合，切换遮罩开关。
 * 注意：部分机型/系统对音量键等按键事件在无障碍层的分发存在限制，行为可能因ROM而异。
 */
class KeyBindingService : AccessibilityService() {

    // 用于组合键检测的简单状态：记录最近按下的keyCode集合
    private val pressedKeys: MutableSet<Int> = mutableSetOf()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不处理可访问性事件，键由onKeyEvent接收
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            // 请求过滤按键事件（Android会参考无障碍xml中的 canRequestFilterKeyEvents）
            serviceInfo = serviceInfo?.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            }
        } catch (_: Exception) {}
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyEvent(event)

        // 绑定页录制：优先交给协调器，并拦截音量键避免系统音量条
        if (KeyCaptureCoordinator.isCapturing) {
            val consume = KeyCaptureCoordinator.handleKeyEvent(event.keyCode, event.action)
            if (consume || KeyCaptureCoordinator.shouldConsumeWhileCapturing(event.keyCode)) {
                return true
            }
        }

        // 主界面在前台时不触发快捷键（避免设置页误触）
        if (com.example.imageoverlay.util.AppStateUtil.isInAppActive(this)) {
            return super.onKeyEvent(event)
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> pressedKeys.add(event.keyCode)
            KeyEvent.ACTION_UP -> pressedKeys.remove(event.keyCode)
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (tryHandleFunction("toggle_overlay") { OverlayToggler.toggleOverlayBoth(this) }) return true
            if (tryHandleFunction("toggle_overlay_main") {
                OverlayToggler.toggleOverlayForScreenType(this, "main")
            }) return true
            if (tryHandleFunction("toggle_overlay_secondary") {
                OverlayToggler.toggleOverlayForScreenType(this, "secondary")
            }) return true
            if (tryHandleFunction("toggle_floating_ball") { toggleFloatingBall() }) return true
        }
        return super.onKeyEvent(event)
    }

    private fun tryHandleFunction(functionKey: String, action: () -> Unit): Boolean {
        val boundKeys = ConfigRepository.getBoundHardwareKeysForFunction(this, functionKey)
        if (boundKeys.isNotEmpty() && boundKeys.all { pressedKeys.contains(it) }) {
            try {
                action()
            } catch (e: Exception) {
                android.util.Log.e("KeyBindingService", "执行 $functionKey 失败", e)
            }
            pressedKeys.clear()
            return true
        }
        return false
    }
    
    private fun toggleFloatingBall() {
        try {
            val context = this
            val isEnabled = com.example.imageoverlay.model.ConfigRepository.isFloatingBallEnabled(context)
            val turningOn = !isEnabled
            com.example.imageoverlay.model.ConfigRepository.setFloatingBallEnabled(context, turningOn)

            if (turningOn) {
                com.example.imageoverlay.util.FloatingBallLauncher.start(context)
            } else {
                com.example.imageoverlay.util.FloatingBallLauncher.stop(context)
            }

            val msg = if (turningOn) getString(R.string.floating_ball_enabled)
            else getString(R.string.floating_ball_disabled)
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            android.util.Log.d("KeyBindingService", "悬浮球开关已切换：$msg")
        } catch (e: Exception) {
            android.util.Log.e("KeyBindingService", "切换悬浮球失败", e)
        }
    }
}


