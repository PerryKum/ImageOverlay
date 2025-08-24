package com.example.imageoverlay

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
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
        
        // 注册广播接收器监听应用安装/更新
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(appLaunchReceiver, filter)
        
        // 启动使用情况监听
        UsageStatsListener.getInstance(this).start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
