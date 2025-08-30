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

class OverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var imageView: ImageView? = null
    private var currentImageUri: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val newImageUri = intent?.getStringExtra("imageUri")
            
            // 检查是否是相同的图片URI，如果是则不重复处理
            if (newImageUri == currentImageUri && imageView != null) {
                android.util.Log.d("OverlayService", "相同图片URI，跳过处理")
                return START_STICKY
            }
            
            android.util.Log.d("OverlayService", "开始处理新遮罩: $newImageUri")
            
            // 先清理之前的遮罩
            removeOverlay()
            
            val imageUri = newImageUri?.let { Uri.parse(it) }
            if (imageUri != null) {
                currentImageUri = newImageUri
                showOverlay(imageUri)
                android.util.Log.d("OverlayService", "遮罩显示成功")
            }
            
            // 确保前台服务通知
            createNotificationChannel()
            val notification: Notification = NotificationCompat.Builder(this, "overlay_channel")
                .setContentTitle("遮罩已启动")
                .setContentText("点击返回应用")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setAutoCancel(false)
                .build()
            startForeground(1, notification)
            
            android.util.Log.d("OverlayService", "前台服务已启动")
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "onStartCommand异常", e)
        }
        return START_STICKY
    }

    private fun showOverlay(imageUri: Uri) {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            imageView = ImageView(this)
            imageView?.setImageURI(imageUri)
            imageView?.scaleType = ImageView.ScaleType.FIT_XY

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
            
            // 为安卓15+添加额外的兼容性设置
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN
            }
            
            windowManager?.addView(imageView, params)
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "showOverlay异常", e)
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

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("OverlayService", "服务销毁，清理资源")
        removeOverlay()
        currentImageUri = null
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