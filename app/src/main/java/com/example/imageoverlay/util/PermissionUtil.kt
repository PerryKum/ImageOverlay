package com.example.imageoverlay.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

object PermissionUtil {
    
    /**
     * 检查悬浮窗权限
     */
    fun checkOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }
    
    /**
     * 检查通知权限（安卓13+需要）
     */
    fun checkNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
    
    /**
     * 打开悬浮窗权限设置页面
     */
    fun openOverlayPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, 
            Uri.parse("package:${context.packageName}"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
    

}
