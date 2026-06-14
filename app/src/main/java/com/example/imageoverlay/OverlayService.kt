package com.example.imageoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.Display
import android.view.WindowManager
import android.widget.ImageView
import com.example.imageoverlay.util.DisplayUtil
import androidx.core.app.NotificationCompat
import pl.droidsonroids.gif.GifImageView
import pl.droidsonroids.gif.GifDrawable
import java.io.InputStream
import com.bumptech.glide.Glide

class OverlayService : Service() {

    private data class DisplayOverlay(
        var windowManager: WindowManager,
        var imageView: ImageView?,
        var layoutParams: WindowManager.LayoutParams?,
        var imageUri: String?,
        var opacity: Int,
        var usesAccessibilityOverlay: Boolean
    )

    private val overlays = mutableMapOf<Int, DisplayOverlay>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ensureForeground()

            when (intent?.action) {
                ACTION_STOP_DISPLAY -> {
                    val key = displayKeyFromIntentExtra(intent.getIntExtra(EXTRA_DISPLAY_ID, -1))
                    removeOverlayForDisplay(key)
                    if (overlays.isEmpty()) stopSelf()
                    return if (overlays.isEmpty()) START_NOT_STICKY else START_STICKY
                }
                ACTION_STOP_ALL -> {
                    removeAllOverlays()
                    stopSelf()
                    return START_NOT_STICKY
                }
            }

            val opacityValue = intent?.getIntExtra("updateOpacity", -1) ?: -1
            if (opacityValue != -1) {
                updateOpacity(opacityValue)
                return START_STICKY
            }

            val newImageUri = intent?.getStringExtra("imageUri")
            val displayId = intent?.getIntExtra(EXTRA_DISPLAY_ID, -1) ?: -1
            val displayKey = displayKeyFromIntentExtra(displayId)

            if (newImageUri.isNullOrBlank()) {
                android.util.Log.w("OverlayService", "图片URI为空，停止服务")
                removeAllOverlays()
                stopSelf()
                return START_NOT_STICKY
            }

            val newOpacity = when (val opacity = intent?.getIntExtra("opacity", -1) ?: -1) {
                -1 -> com.example.imageoverlay.model.ConfigRepository.getDefaultOpacity(this)
                else -> opacity
            }

            val existing = overlays[displayKey]
            if (newImageUri == existing?.imageUri && newOpacity == existing.opacity && existing.imageView != null) {
                android.util.Log.d("OverlayService", "display=$displayKey 相同图片与透明度，跳过")
                return START_STICKY
            }

            android.util.Log.d("OverlayService", "display=$displayKey 显示遮罩: $newImageUri")

            removeOverlayForDisplay(displayKey)

            val imageUri = try {
                Uri.parse(newImageUri)
            } catch (e: Exception) {
                android.util.Log.e("OverlayService", "解析图片URI失败: $newImageUri", e)
                if (overlays.isEmpty()) stopSelf()
                return START_NOT_STICKY
            }

            val success = showOverlay(displayKey, displayId, imageUri, newOpacity)
            if (success) {
                overlays[displayKey]?.imageUri = newImageUri
                overlays[displayKey]?.opacity = newOpacity
                activeDisplayKeys.add(displayKey)
                broadcastOverlayState(true)
            } else {
                if (overlays.isEmpty()) stopSelf()
                return START_NOT_STICKY
            }

            ensureForeground("遮罩已启动")
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "onStartCommand异常", e)
            removeAllOverlays()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun ensureForeground(contentText: String = "正在运行遮罩...") {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("遮罩服务")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun showOverlay(displayKey: Int, displayId: Int, imageUri: Uri, opacity: Int): Boolean {
        return try {
            val usesAccessibilityOverlay = DisplayUtil.canUseAccessibilityOverlay(this, displayId)
            val windowManager = windowManagerForDisplay(displayId, usesAccessibilityOverlay) ?: return false

            val mimeType = contentResolver.getType(imageUri)
            val isGif = mimeType == "image/gif" || imageUri.toString().lowercase().endsWith(".gif")

            val imageView: ImageView = if (isGif) {
                try {
                    val inputStream: InputStream? = contentResolver.openInputStream(imageUri)
                    if (inputStream != null) {
                        val gifDrawable = GifDrawable(inputStream)
                        val gifView = GifImageView(this)
                        gifView.setImageDrawable(gifDrawable)
                        gifDrawable.start()
                        inputStream.close()
                        gifView
                    } else return false
                } catch (e: Exception) {
                    android.util.Log.e("OverlayService", "加载GIF失败: $imageUri", e)
                    val fallback = ImageView(this)
                    Glide.with(this).asGif().load(imageUri).into(fallback)
                    fallback
                }
            } else {
                val view = ImageView(this)
                view.setImageURI(imageUri)
                if (view.drawable == null) return false
                view
            }

            imageView.scaleType = ImageView.ScaleType.FIT_XY
            imageView.isClickable = false
            imageView.isFocusable = false

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                DisplayUtil.overlayWindowType(this, displayId),
                DisplayUtil.overlayWindowFlags(),
                PixelFormat.TRANSLUCENT
            )
            DisplayUtil.applyOverlayLayoutParams(params, windowManager)
            DisplayUtil.applyOverlayVisualOpacity(params, imageView, opacity, usesAccessibilityOverlay)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val coverCutout = com.example.imageoverlay.model.ConfigRepository.isCoverCutoutEnabled(this)
                params.layoutInDisplayCutoutMode = if (coverCutout)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN
            }

            windowManager.addView(imageView, params)
            overlays[displayKey] = DisplayOverlay(
                windowManager,
                imageView,
                params,
                imageUri.toString(),
                opacity,
                usesAccessibilityOverlay
            )
            true
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "showOverlay异常 display=$displayKey", e)
            false
        }
    }

    private fun windowManagerForDisplay(displayId: Int, usesAccessibilityOverlay: Boolean): WindowManager? {
        if (usesAccessibilityOverlay) {
            return com.example.imageoverlay.keybinding.KeyBindingService.instance
                ?.getSystemService(WINDOW_SERVICE) as? WindowManager
        }
        return if (displayId > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(displayId) ?: return getSystemService(WINDOW_SERVICE) as WindowManager
            val displayContext = createWindowContext(
                display,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                null
            )
            displayContext.getSystemService(WindowManager::class.java)
        } else {
            getSystemService(WINDOW_SERVICE) as WindowManager
        }
    }

    private fun removeOverlayForDisplay(displayKey: Int) {
        val entry = overlays.remove(displayKey) ?: return
        activeDisplayKeys.remove(displayKey)
        if (entry.imageView != null) {
            try {
                entry.windowManager.removeView(entry.imageView)
            } catch (_: Exception) {
            }
        }
        if (overlays.isEmpty()) {
            broadcastOverlayState(false)
        }
    }

    private fun removeAllOverlays() {
        overlays.keys.toList().forEach { removeOverlayForDisplay(it) }
        activeDisplayKeys.clear()
    }

    fun updateOpacity(opacity: Int) {
        overlays.values.forEach { entry ->
            entry.opacity = opacity
            val imageView = entry.imageView
            val params = entry.layoutParams
            if (imageView != null && params != null) {
                DisplayUtil.applyOverlayVisualOpacity(
                    params,
                    imageView,
                    opacity,
                    entry.usesAccessibilityOverlay
                )
                try {
                    entry.windowManager.updateViewLayout(imageView, params)
                } catch (e: Exception) {
                    android.util.Log.e("OverlayService", "更新透明度失败", e)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("OverlayService", "服务销毁，清理资源")
        removeAllOverlays()
        try {
            getSharedPreferences("quick_use_prefs", 0).edit()
                .putBoolean("is_overlay_active", false)
                .putBoolean("is_overlay_active_main", false)
                .putBoolean("is_overlay_active_secondary", false)
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "清理快速使用状态失败", e)
        }
        broadcastOverlayState(false)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        removeAllOverlays()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun broadcastOverlayState(active: Boolean) {
        try {
            val stateIntent = Intent(ACTION_OVERLAY_STATE_CHANGED)
            stateIntent.putExtra("active", active)
            sendBroadcast(stateIntent)
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "遮罩服务", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_OVERLAY_STATE_CHANGED =
            "com.example.imageoverlay.OVERLAY_STATE_CHANGED"
        const val ACTION_STOP_DISPLAY = "com.example.imageoverlay.STOP_DISPLAY"
        const val ACTION_STOP_ALL = "com.example.imageoverlay.STOP_ALL"
        const val EXTRA_DISPLAY_ID = "displayId"
        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIFICATION_ID = 1

        private val activeDisplayKeys = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

        fun displayKeyFromIntentExtra(displayId: Int): Int =
            if (displayId <= 0) Display.DEFAULT_DISPLAY else displayId

        fun isRunningOnDisplay(displayKey: Int): Boolean =
            activeDisplayKeys.contains(displayKey)

        fun hasAnyOverlayRunning(): Boolean = activeDisplayKeys.isNotEmpty()

        fun stopDisplay(context: Context, displayKey: Int) {
            if (!isRunningOnDisplay(displayKey) && !hasAnyOverlayRunning()) return
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_STOP_DISPLAY
                putExtra(EXTRA_DISPLAY_ID, displayKey)
            }
            context.startService(intent)
        }

        fun stopAll(context: Context) {
            if (!hasAnyOverlayRunning()) {
                context.stopService(Intent(context, OverlayService::class.java))
                return
            }
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_STOP_ALL
            }
            context.startService(intent)
        }
    }
}
