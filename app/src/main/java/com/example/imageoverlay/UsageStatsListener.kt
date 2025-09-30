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
    private var lastProcessTime = 0L
    private val PROCESS_COOLDOWN = 3000L // 增加到3秒冷却时间，防止频繁切换
    private var isProcessing = false // 防止并发处理
    private var lastLauncherTime = 0L // 记录最后一次检测到桌面的时间
    private val LAUNCHER_COOLDOWN = 5000L // 桌面检测冷却时间5秒
    
    // 桌面/启动器包名列表，用于检测用户是否在桌面
    private val launcherPackages = setOf(
        "com.android.launcher", "com.android.launcher2", "com.android.launcher3",
        "com.google.android.launcher", "com.google.android.apps.nexuslauncher",
        "com.samsung.android.launcher", "com.huawei.android.launcher",
        "com.miui.home", "com.oneplus.launcher", "com.oppo.launcher",
        "com.vivo.launcher", "com.meizu.flyme.launcher", "com.bbk.launcher2",
        "com.sec.android.app.launcher", "com.lge.launcher2", "com.lge.launcher3",
        "com.htc.launcher", "com.sonyericsson.home", "com.cyanogenmod.trebuchet",
        "com.teslacoilsw.launcher", "com.nova.launcher"
    )

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
                val currentTime = System.currentTimeMillis()
                
                if (packageName != lastPackageName && 
                    packageName != context.packageName && 
                    currentTime - lastProcessTime > PROCESS_COOLDOWN &&
                    !isProcessing) {
                    
                    lastPackageName = packageName
                    lastProcessTime = currentTime
                    isProcessing = true
                    Log.d("UsageStatsListener", "检测到应用启动: $packageName")
                    
                    // 在主线程中处理应用启动事件
                    handler.post {
                        try {
                            val currentTime = System.currentTimeMillis()
                            
                            // 检查是否是桌面/启动器应用
                            if (isLauncherPackage(packageName)) {
                                // 桌面检测需要额外的冷却时间，防止频繁触发
                                if (currentTime - lastLauncherTime > LAUNCHER_COOLDOWN) {
                                    lastLauncherTime = currentTime
                                    Log.d("UsageStatsListener", "检测到桌面/启动器: $packageName")
                                    ConfigRepository.handleAppLaunch(context, packageName, isLauncher = true)
                                } else {
                                    Log.d("UsageStatsListener", "桌面检测冷却中，跳过: $packageName")
                                }
                            } else {
                                Log.d("UsageStatsListener", "检测到普通应用: $packageName")
                                ConfigRepository.handleAppLaunch(context, packageName, isLauncher = false)
                            }
                        } catch (e: Exception) {
                            Log.e("UsageStatsListener", "处理应用启动事件失败", e)
                        } finally {
                            isProcessing = false
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 检查包名是否为桌面/启动器应用
     */
    private fun isLauncherPackage(packageName: String): Boolean {
        return launcherPackages.contains(packageName) || 
               packageName.contains("launcher") || 
               packageName.contains("home")
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
