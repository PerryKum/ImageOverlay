package com.example.imageoverlay.keybinding

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
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

        // 应用在前台时，屏蔽用实体键切换遮罩
        if (com.example.imageoverlay.util.AppStateUtil.isInAppActive(this)) {
            return super.onKeyEvent(event)
        }

        val boundKeys = ConfigRepository.getBoundHardwareKeys(this)
        if (boundKeys.isEmpty()) return super.onKeyEvent(event)

        when (event.action) {
            KeyEvent.ACTION_DOWN -> pressedKeys.add(event.keyCode)
            KeyEvent.ACTION_UP -> pressedKeys.remove(event.keyCode)
        }

        // 简化逻辑：当按下事件发生时，若当前按下集合包含所有绑定键，则触发切换
        if (event.action == KeyEvent.ACTION_DOWN) {
            val matches = boundKeys.all { pressedKeys.contains(it) }
            if (matches) {
                toggleOverlay()
                // 防止重复触发：清空一次状态
                pressedKeys.clear()
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    private fun toggleOverlay() {
        try {
            OverlayToggler.toggleDefaultOverlay(this)
        } catch (e: Exception) {
            android.util.Log.e("KeyBindingService", "切换遮罩失败", e)
        }
    }
}


