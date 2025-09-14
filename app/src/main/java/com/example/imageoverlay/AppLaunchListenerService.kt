package com.example.imageoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.imageoverlay.model.ConfigRepository

class AppLaunchListenerService : Service() {
    private val appLaunchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_PACKAGE_ADDED ||
                intent?.action == Intent.ACTION_PACKAGE_REPLACED) {
                val packageName = intent.data?.schemeSpecificPart
                if (!packageName.isNullOrBlank()) {
                    Log.d("AppLaunchListener", "应用安装/更新: $packageName")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("AppLaunchListener", "应用启动监听服务已创建")
        
        try {
            // 注册广播接收器监听应用安装/更新
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            registerReceiver(appLaunchReceiver, filter)
            
            // 启动使用情况监听
            UsageStatsListener.getInstance(this).start()
        } catch (e: Exception) {
            Log.e("AppLaunchListener", "服务创建时出现异常", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 创建前台服务通知，确保服务不被系统杀死
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, "listener_channel")
            .setContentTitle("ImageOverlay")
            .setContentText("后台监听中")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setSilent(true)
            .build()
        startForeground(2, notification)
        
        Log.d("AppLaunchListener", "应用启动监听服务已启动")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(appLaunchReceiver)
        } catch (e: Exception) {
            Log.e("AppLaunchListener", "注销广播接收器失败", e)
        }
        
        // 停止使用情况监听
        UsageStatsListener.getInstance(this).stop()
        
        Log.d("AppLaunchListener", "应用启动监听服务已销毁")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "listener_channel",
                "后台监听",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "后台监听应用切换"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun startService(context: Context) {
            val intent = Intent(context, AppLaunchListenerService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AppLaunchListenerService::class.java)
            context.stopService(intent)
        }
    }
}
