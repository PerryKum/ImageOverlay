package com.example.imageoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import pl.droidsonroids.gif.GifImageView
import pl.droidsonroids.gif.GifDrawable
import java.io.InputStream
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.gif.GifDrawable as GlideGifDrawable

class OverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var imageView: ImageView? = null
    private var currentImageUri: String? = null
    private var currentOpacity: Int = 100

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // 立即创建前台服务通知，避免超时
            createNotificationChannel()
            val notification: Notification = NotificationCompat.Builder(this, "overlay_channel")
                .setContentTitle("遮罩服务")
                .setContentText("正在启动遮罩...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false)
                .build()
            startForeground(1, notification)
            
            // 检查是否是透明度更新请求
            val opacityValue = intent?.getIntExtra("updateOpacity", -1) ?: -1
            if (opacityValue != -1) {
                // 更新透明度
                updateOpacity(opacityValue)
                return START_STICKY
            }
            
            val newImageUri = intent?.getStringExtra("imageUri")
            if (newImageUri.isNullOrBlank()) {
                android.util.Log.w("OverlayService", "图片URI为空，停止服务")
                stopSelf()
                return START_NOT_STICKY
            }
            
            // 如果没有传递透明度参数，使用全局透明度设置
            val newOpacity = when (val opacity = intent?.getIntExtra("opacity", -1) ?: -1) {
                -1 -> com.example.imageoverlay.model.ConfigRepository.getDefaultOpacity(this)
                else -> opacity
            }
            
            // 检查是否是相同的图片URI和透明度，如果是则不重复处理
            if (newImageUri == currentImageUri && newOpacity == currentOpacity && imageView != null) {
                android.util.Log.d("OverlayService", "相同图片URI和透明度，跳过处理")
                return START_STICKY
            }
            
            android.util.Log.d("OverlayService", "开始处理新遮罩: $newImageUri, 透明度: $newOpacity")
            
            // 先清理之前的遮罩
            removeOverlay()
            
            val imageUri = try {
                Uri.parse(newImageUri)
            } catch (e: Exception) {
                android.util.Log.e("OverlayService", "解析图片URI失败: $newImageUri", e)
                stopSelf()
                return START_NOT_STICKY
            }
            
            if (imageUri != null) {
                currentImageUri = newImageUri
                currentOpacity = newOpacity
                val success = showOverlay(imageUri, newOpacity)
                if (success) {
                    android.util.Log.d("OverlayService", "遮罩显示成功")
                } else {
                    android.util.Log.e("OverlayService", "遮罩显示失败，停止服务")
                    stopSelf()
                    return START_NOT_STICKY
                }
            } else {
                android.util.Log.e("OverlayService", "imageUri为null，停止服务")
                stopSelf()
                return START_NOT_STICKY
            }
            
            // 更新前台服务通知内容
            val successNotification: Notification = NotificationCompat.Builder(this, "overlay_channel")
                .setContentTitle("遮罩已启动")
                .setContentText("点击返回应用")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setAutoCancel(false)
                .build()
            startForeground(1, successNotification)
            
            android.util.Log.d("OverlayService", "前台服务已启动")
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "onStartCommand异常", e)
            // 出现异常时停止服务，避免无限重启
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun showOverlay(imageUri: Uri, opacity: Int): Boolean {
        return try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            
            // 检查文件类型，决定使用哪种ImageView
            val mimeType = contentResolver.getType(imageUri)
            val isGif = mimeType == "image/gif" || imageUri.toString().lowercase().endsWith(".gif")
            android.util.Log.d("OverlayService", "文件类型检测: mimeType=$mimeType, isGif=$isGif, uri=$imageUri")
            
            if (isGif) {
                // 使用GifImageView支持GIF动画
                try {
                    val inputStream: InputStream? = contentResolver.openInputStream(imageUri)
                    if (inputStream != null) {
                        val gifDrawable = GifDrawable(inputStream)
                        imageView = GifImageView(this)
                        (imageView as GifImageView).setImageDrawable(gifDrawable)
                        // 确保GIF动画开始播放
                        gifDrawable.start()
                        inputStream.close()
                        android.util.Log.d("OverlayService", "GIF加载成功并开始播放: $imageUri")
                    } else {
                        android.util.Log.e("OverlayService", "无法打开GIF文件: $imageUri")
                        return false
                    }
                } catch (e: Exception) {
                    android.util.Log.e("OverlayService", "加载GIF失败，尝试备用方案: $imageUri", e)
                    // 备用方案：使用标准ImageView + Glide
                    try {
                        imageView = ImageView(this)
                        Glide.with(this)
                            .asGif()
                            .load(imageUri)
                            .into(imageView as ImageView)
                        android.util.Log.d("OverlayService", "使用Glide加载GIF成功: $imageUri")
                    } catch (e2: Exception) {
                        android.util.Log.e("OverlayService", "Glide加载GIF也失败: $imageUri", e2)
                        return false
                    }
                }
            } else {
                // 使用标准ImageView支持PNG等静态图片
                imageView = ImageView(this)
                try {
                    imageView?.setImageURI(imageUri)
                    // 检查图片是否成功加载
                    if (imageView?.drawable == null) {
                        android.util.Log.e("OverlayService", "图片加载失败: $imageUri")
                        return false
                    }
                } catch (e: Exception) {
                    android.util.Log.e("OverlayService", "设置图片失败: $imageUri", e)
                    return false
                }
            }
            
            imageView?.scaleType = ImageView.ScaleType.FIT_XY
            
            // 设置透明度
            val alpha = opacity / 100f
            imageView?.alpha = alpha

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            
            // 允许内容延伸至刘海/挖孔区域（Android 9+），按设置开关
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val coverCutout = com.example.imageoverlay.model.ConfigRepository.isCoverCutoutEnabled(this)
                params.layoutInDisplayCutoutMode = if (coverCutout)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }

            // 为安卓15+添加额外的兼容性设置
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN
            }
            
            windowManager?.addView(imageView, params)
            true
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "showOverlay异常", e)
            false
        }
    }

    private fun removeOverlay() {
        if (imageView != null && windowManager != null) {
            try {
                windowManager?.removeView(imageView)
            } catch (e: Exception) {
                // 忽略移除视图时的异常
            }
            imageView = null
        }
    }

    fun updateOpacity(opacity: Int) {
        currentOpacity = opacity
        imageView?.let { view ->
            val alpha = opacity / 100f
            view.alpha = alpha
            android.util.Log.d("OverlayService", "透明度更新: $opacity%")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("OverlayService", "服务销毁，清理资源")
        removeOverlay()
        currentImageUri = null
        currentOpacity = 100
        
        // 简化：只清理快速使用状态，避免过度复杂化
        try {
            val quickUsePrefs = getSharedPreferences("quick_use_prefs", 0)
            if (quickUsePrefs.getBoolean("is_overlay_active", false)) {
                android.util.Log.d("OverlayService", "服务销毁时清理快速使用状态")
                quickUsePrefs.edit().putBoolean("is_overlay_active", false).apply()
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "清理快速使用状态失败", e)
        }
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        android.util.Log.d("OverlayService", "任务被移除，清理资源")
        
        // 简化：只清理快速使用状态
        try {
            val quickUsePrefs = getSharedPreferences("quick_use_prefs", 0)
            if (quickUsePrefs.getBoolean("is_overlay_active", false)) {
                android.util.Log.d("OverlayService", "任务移除时清理快速使用状态")
                quickUsePrefs.edit().putBoolean("is_overlay_active", false).apply()
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "清理快速使用状态失败", e)
        }
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "overlay_channel", "遮罩服务", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
} 