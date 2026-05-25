package com.example.imageoverlay

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.imageoverlay.model.ConfigRepository
import com.example.imageoverlay.util.FloatingBallLauncher
import com.example.imageoverlay.util.ForegroundAppUtil
import com.example.imageoverlay.util.LauncherUtil
import java.util.concurrent.Executors

class UsageStatsListener(private val context: Context) {
    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var lastEventQueryTime = System.currentTimeMillis()
    private var lastHandledForeground = ""
    private var lastProcessTime = 0L
    private val PROCESS_COOLDOWN = 3000L
    private var isProcessing = false
    private var lastLauncherTime = 0L
    private val LAUNCHER_COOLDOWN = 5000L

    fun start() {
        if (isRunning) return
        isRunning = true
        lastEventQueryTime = System.currentTimeMillis() - 10_000L
        executor.execute {
            while (isRunning) {
                try {
                    drainUsageEvents()
                    Thread.sleep(500)
                } catch (e: Exception) {
                    Log.e("UsageStatsListener", "检查应用使用情况失败", e)
                }
            }
        }
    }

    fun stop() {
        isRunning = false
    }

    private fun drainUsageEvents() {
        val endTime = System.currentTimeMillis()
        val startTime = lastEventQueryTime
        lastEventQueryTime = endTime

        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val packageName = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    ForegroundAppUtil.onMoveToForeground(context, packageName)
                    dispatchForegroundEntered(packageName)
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    ForegroundAppUtil.onMoveToBackground(context, packageName)
                    dispatchBackgroundLeft(packageName)
                }
            }
        }
    }

    private fun dispatchForegroundEntered(packageName: String) {
        if (packageName == context.packageName) return
        val now = System.currentTimeMillis()
        if (packageName == lastHandledForeground &&
            now - lastProcessTime < PROCESS_COOLDOWN
        ) {
            return
        }
        if (isProcessing) return

        lastHandledForeground = packageName
        lastProcessTime = now
        isProcessing = true
        Log.d("UsageStatsListener", "MOVE_TO_FOREGROUND: $packageName")

        handler.post {
            try {
                when {
                    LauncherUtil.isLauncherPackage(packageName) -> {
                        if (now - lastLauncherTime > LAUNCHER_COOLDOWN) {
                            lastLauncherTime = now
                            ConfigRepository.handleAppLaunch(
                                context,
                                packageName,
                                isLauncher = true
                            )
                        }
                    }
                    ConfigRepository.hasBoundGroupForPackage(packageName) -> {
                        ConfigRepository.handleAppLaunch(
                            context,
                            packageName,
                            isLauncher = false
                        )
                    }
                    else -> {
                        Log.d("UsageStatsListener", "忽略非绑定前台: $packageName")
                    }
                }
            } catch (e: Exception) {
                Log.e("UsageStatsListener", "处理前台事件失败", e)
            } finally {
                isProcessing = false
            }
        }
    }

    private fun dispatchBackgroundLeft(packageName: String) {
        if (packageName == context.packageName) return
        Log.d("UsageStatsListener", "MOVE_TO_BACKGROUND: $packageName")

        handler.post {
            try {
                if (ConfigRepository.hasBoundGroupForPackage(packageName)) {
                    if (ConfigRepository.isFloatingBallEnabled(context)) {
                        FloatingBallLauncher.stop(context)
                    }
                    ConfigRepository.handleBoundAppLeftForeground(context, packageName)
                }
                if (LauncherUtil.isLauncherPackage(packageName)) {
                    val now = System.currentTimeMillis()
                    if (now - lastLauncherTime > LAUNCHER_COOLDOWN) {
                        lastLauncherTime = now
                        ConfigRepository.handleAppLaunch(
                            context,
                            packageName,
                            isLauncher = true
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("UsageStatsListener", "处理后台事件失败", e)
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
