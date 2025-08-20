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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val imageUri = intent?.getStringExtra("imageUri")?.let { Uri.parse(it) }
        if (imageUri != null) {
            showOverlay(imageUri)
        }
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, "overlay_channel")
            .setContentTitle("遮罩已启动")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        startForeground(1, notification)
        return START_STICKY
    }

    private fun showOverlay(imageUri: Uri) {
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
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager?.addView(imageView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (imageView != null) windowManager?.removeView(imageView)
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