package com.example.imageoverlay

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.example.imageoverlay.model.ConfigRepository
import com.example.imageoverlay.util.AppStateUtil
import com.example.imageoverlay.util.ConfigPathUtil

class ImageOverlayApplication : Application() {

    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        if (ConfigPathUtil.getConfigRoot(this).isNotBlank()) {
            try {
                ConfigRepository.load(this)
            } catch (e: Exception) {
                android.util.Log.e("ImageOverlayApplication", "启动时预加载配置失败", e)
            }
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                AppStateUtil.setInAppActive(this@ImageOverlayApplication, true)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                AppStateUtil.setInAppActive(
                    this@ImageOverlayApplication,
                    startedActivityCount > 0
                )
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
