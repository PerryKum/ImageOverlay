package com.example.imageoverlay.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.example.imageoverlay.FloatingBallService
import com.example.imageoverlay.model.ConfigRepository

object FloatingBallLauncher {

    /** 本应用任意界面在前台时不显示悬浮球 */
    fun shouldSuppressInApp(context: Context): Boolean {
        if (AppStateUtil.isInAppActive(context)) return true
        val foreground = ForegroundAppUtil.getRecentForegroundPackage(context)
        return foreground == context.packageName
    }

    fun start(context: Context, packageName: String? = null, forceShow: Boolean = false) {
        if (!ConfigRepository.isFloatingBallEnabled(context)) {
            return
        }
        if (shouldSuppressInApp(context)) {
            android.util.Log.d("FloatingBallLauncher", "本应用内不显示悬浮球")
            return
        }
        if (!PermissionUtil.checkOverlayPermission(context)) {
            Toast.makeText(context, "需要悬浮窗权限", Toast.LENGTH_SHORT).show()
            PermissionUtil.openOverlayPermissionSettings(context)
            return
        }

        val resolved = resolvePackageName(context, packageName) ?: return
        if (resolved == context.packageName) {
            return
        }

        val intent = Intent(context, FloatingBallService::class.java).apply {
            putExtra(FloatingBallService.EXTRA_PACKAGE_NAME, resolved)
            putExtra(FloatingBallService.EXTRA_FORCE_SHOW, forceShow)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, FloatingBallService::class.java))
    }

    /**
     * 仅当包名已绑定配置组时返回；未绑定应用不启动悬浮球。
     */
    private fun resolvePackageName(context: Context, hint: String?): String? {
        if (!hint.isNullOrBlank() && hint != context.packageName) {
            if (ConfigRepository.getGroupByPackageName(hint) != null) {
                return hint
            }
        }
        val foreground = ForegroundAppUtil.getTopForegroundPackage()
        if (!foreground.isNullOrBlank() &&
            foreground != context.packageName &&
            ConfigRepository.getGroupByPackageName(foreground) != null
        ) {
            return foreground
        }
        return null
    }
}
