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

        when (event.action) {
            KeyEvent.ACTION_DOWN -> pressedKeys.add(event.keyCode)
            KeyEvent.ACTION_UP -> pressedKeys.remove(event.keyCode)
        }

        // 检查各个功能的按键绑定
        if (event.action == KeyEvent.ACTION_DOWN) {
            // 检查遮罩开关
            val overlayBoundKeys = ConfigRepository.getBoundHardwareKeysForFunction(this, "toggle_overlay")
            if (overlayBoundKeys.isNotEmpty() && overlayBoundKeys.all { pressedKeys.contains(it) }) {
                toggleOverlay()
                pressedKeys.clear()
                return true
            }
            
            // 检查悬浮球开关
            val floatingBallBoundKeys = ConfigRepository.getBoundHardwareKeysForFunction(this, "toggle_floating_ball")
            if (floatingBallBoundKeys.isNotEmpty() && floatingBallBoundKeys.all { pressedKeys.contains(it) }) {
                toggleFloatingBall()
                pressedKeys.clear()
                return true
            }
            
            // 检查其他功能
            val functions = listOf("next_image", "previous_image", "increase_opacity", "decrease_opacity")
            for (function in functions) {
                val boundKeys = ConfigRepository.getBoundHardwareKeysForFunction(this, function)
                if (boundKeys.isNotEmpty() && boundKeys.all { pressedKeys.contains(it) }) {
                    handleFunction(function)
                    pressedKeys.clear()
                    return true
                }
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
    
    private fun toggleFloatingBall() {
        try {
            val context = this
            val isEnabled = com.example.imageoverlay.model.ConfigRepository.isFloatingBallEnabled(context)
            com.example.imageoverlay.model.ConfigRepository.setFloatingBallEnabled(context, !isEnabled)
            
            if (!isEnabled) {
                // 开启悬浮球
                val intent = android.content.Intent(context, com.example.imageoverlay.FloatingBallService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                // 关闭悬浮球
                val intent = android.content.Intent(context, com.example.imageoverlay.FloatingBallService::class.java)
                context.stopService(intent)
            }
            
            android.util.Log.d("KeyBindingService", "悬浮球开关已切换：${if (!isEnabled) "开启" else "关闭"}")
        } catch (e: Exception) {
            android.util.Log.e("KeyBindingService", "切换悬浮球失败", e)
        }
    }
    
    private fun handleFunction(function: String) {
        try {
            when (function) {
                "next_image" -> {
                    // 切换到下一张图片
                    com.example.imageoverlay.util.OverlayToggler.switchToNextImage(this)
                }
                "previous_image" -> {
                    // 切换到上一张图片
                    com.example.imageoverlay.util.OverlayToggler.switchToPreviousImage(this)
                }
                "increase_opacity" -> {
                    // 增加透明度
                    com.example.imageoverlay.util.OverlayToggler.adjustOpacity(this, 0.1f)
                }
                "decrease_opacity" -> {
                    // 减少透明度
                    com.example.imageoverlay.util.OverlayToggler.adjustOpacity(this, -0.1f)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("KeyBindingService", "执行功能 $function 失败", e)
        }
    }
}


