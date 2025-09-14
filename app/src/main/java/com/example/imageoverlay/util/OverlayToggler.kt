package com.example.imageoverlay.util

import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.imageoverlay.OverlayService
import com.example.imageoverlay.model.ConfigRepository

/**
 * 遮罩开关工具类，统一处理默认遮罩的开启/关闭逻辑
 * 供磁贴服务、按键绑定服务等复用
 */
object OverlayToggler {
    
    /**
     * 切换默认遮罩状态
     * @param context 上下文
     * @return 切换后的状态，true表示已开启，false表示已关闭
     */
    fun toggleDefaultOverlay(context: Context): Boolean {
        val currentlyActive = ConfigRepository.isDefaultActive(context)
        
        if (currentlyActive) {
            // 关闭遮罩
            turnOffOverlay(context)
            return false
        } else {
            // 开启遮罩
            return turnOnOverlay(context)
        }
    }
    
    /**
     * 关闭遮罩
     */
    fun turnOffOverlay(context: Context) {
        try {
            val stopIntent = Intent(context, OverlayService::class.java)
            context.stopService(stopIntent)
            ConfigRepository.setDefaultActive(context, false)
        } catch (e: Exception) {
            android.util.Log.e("OverlayToggler", "关闭遮罩失败", e)
        }
    }
    
    /**
     * 开启默认遮罩
     * @param context 上下文
     * @return 是否成功开启
     */
    fun turnOnOverlay(context: Context): Boolean {
        return try {
            // 检查权限
            if (!PermissionUtil.checkOverlayPermission(context)) {
                android.util.Log.w("OverlayToggler", "悬浮窗权限未授予")
                return false
            }
            
            val defaultConfig = ConfigRepository.getDefaultConfig(context)
            if (defaultConfig == null || defaultConfig.imageUri.isBlank()) {
                android.util.Log.w("OverlayToggler", "默认配置为空")
                return false
            }
            
            // 先停止所有遮罩
            val stopIntent = Intent(context, OverlayService::class.java)
            context.stopService(stopIntent)
            
            // 关闭其他预设的激活状态
            ConfigRepository.getGroups().forEach { group ->
                group.configs.forEach { config -> config.active = false }
            }
            ConfigRepository.save(context)
            
            // 启动默认遮罩
            val intent = Intent(context, OverlayService::class.java)
            intent.putExtra("imageUri", defaultConfig.imageUri)
            intent.putExtra("opacity", ConfigRepository.getDefaultOpacity(context))
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            
            ConfigRepository.setDefaultActive(context, true)
            true
        } catch (e: Exception) {
            android.util.Log.e("OverlayToggler", "开启遮罩失败", e)
            false
        }
    }
    
    /**
     * 检查遮罩是否处于活跃状态
     */
    fun isOverlayActive(context: Context): Boolean {
        return ConfigRepository.isDefaultActive(context)
    }
}
