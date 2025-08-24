package com.example.imageoverlay

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.imageoverlay.model.ConfigRepository
import java.util.concurrent.Executors

class UsageStatsListener(private val context: Context) {
    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var lastEventTime = System.currentTimeMillis()
    private var lastPackageName = ""

    fun start() {
        if (isRunning) return
        isRunning = true
        executor.execute {
            while (isRunning) {
                try {
                    checkAppUsage()
                    Thread.sleep(1000) // 每秒检查一次
                } catch (e: Exception) {
                    Log.e("UsageStatsListener", "检查应用使用情况失败", e)
                }
            }
        }
    }

    fun stop() {
        isRunning = false
    }

    private fun checkAppUsage() {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 5000 // 检查最近5秒的事件

        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                val packageName = event.packageName
                if (packageName != lastPackageName && packageName != context.packageName) {
                    lastPackageName = packageName
                    Log.d("UsageStatsListener", "检测到应用启动: $packageName")
                    
                    // 在主线程中处理应用启动事件
                    handler.post {
                        ConfigRepository.handleAppLaunch(context, packageName)
                    }
                }
            }
        }
    }

    companion object {
        private var instance: UsageStatsListener? = null

        fun getInstance(context: Context): UsageStatsListener {
            if (instance == null) {
                instance = UsageStatsListener(context.applicationContext)
            }
            return instance!!
        }
    }
}
