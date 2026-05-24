package com.example.imageoverlay.keybinding

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/**
 * 绑定按键时由无障碍服务把实体键事件转到这里（可拦截音量键等系统优先处理的按键）。
 */
object KeyCaptureCoordinator {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isCapturing: Boolean = false
        private set

    private var onKeyCaptured: ((keyCode: Int) -> Unit)? = null

    fun startCapture(onKeyCaptured: (keyCode: Int) -> Unit) {
        isCapturing = true
        this.onKeyCaptured = onKeyCaptured
    }

    fun stopCapture() {
        isCapturing = false
        onKeyCaptured = null
    }

    /** @return 是否应消费该按键（阻止系统音量条等） */
    fun handleKeyEvent(keyCode: Int, action: Int): Boolean {
        if (!isCapturing || action != KeyEvent.ACTION_DOWN) {
            return false
        }
        mainHandler.post {
            onKeyCaptured?.invoke(keyCode)
        }
        return shouldConsumeWhileCapturing(keyCode)
    }

    /** 录制期间拦截这些键，避免被系统/焦点抢走 */
    fun shouldConsumeWhileCapturing(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return false
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_CAMERA,
            KeyEvent.KEYCODE_FOCUS,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> true
            else -> isCapturing
        }
    }
}
