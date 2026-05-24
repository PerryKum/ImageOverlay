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
    
    /**
     * 切换到下一张图片
     */
    fun switchToNextImage(context: Context) {
        try {
            val groups = ConfigRepository.getGroups()
            if (groups.isEmpty()) return
            
            val currentGroup = groups.find { it.configs.any { config -> config.active } }
            val currentConfig = currentGroup?.configs?.find { it.active }
            
            if (currentGroup != null && currentConfig != null) {
                val configs = currentGroup.configs
                val currentIndex = configs.indexOf(currentConfig)
                val nextIndex = (currentIndex + 1) % configs.size
                val nextConfig = configs[nextIndex]
                
                // 停止当前遮罩
                val stopIntent = Intent(context, OverlayService::class.java)
                context.stopService(stopIntent)
                
                // 启动新的遮罩
                val intent = Intent(context, OverlayService::class.java)
                intent.putExtra("imageUri", nextConfig.imageUri)
                intent.putExtra("opacity", ConfigRepository.getDefaultOpacity(context))
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                
                // 更新激活状态
                configs.forEach { it.active = false }
                nextConfig.active = true
                ConfigRepository.save(context)
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayToggler", "切换到下一张图片失败", e)
        }
    }
    
    /**
     * 切换到上一张图片
     */
    fun switchToPreviousImage(context: Context) {
        try {
            val groups = ConfigRepository.getGroups()
            if (groups.isEmpty()) return
            
            val currentGroup = groups.find { it.configs.any { config -> config.active } }
            val currentConfig = currentGroup?.configs?.find { it.active }
            
            if (currentGroup != null && currentConfig != null) {
                val configs = currentGroup.configs
                val currentIndex = configs.indexOf(currentConfig)
                val previousIndex = if (currentIndex - 1 < 0) configs.size - 1 else currentIndex - 1
                val previousConfig = configs[previousIndex]
                
                // 停止当前遮罩
                val stopIntent = Intent(context, OverlayService::class.java)
                context.stopService(stopIntent)
                
                // 启动新的遮罩
                val intent = Intent(context, OverlayService::class.java)
                intent.putExtra("imageUri", previousConfig.imageUri)
                intent.putExtra("opacity", ConfigRepository.getDefaultOpacity(context))
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                
                // 更新激活状态
                configs.forEach { it.active = false }
                previousConfig.active = true
                ConfigRepository.save(context)
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayToggler", "切换到上一张图片失败", e)
        }
    }
    
    /**
     * 调整透明度
     * @param delta 透明度变化值，正数增加，负数减少
     */
    fun adjustOpacity(context: Context, delta: Float) {
        try {
            val currentOpacity = ConfigRepository.getDefaultOpacity(context)
            val newOpacity = (currentOpacity + delta).coerceIn(0.1f, 1.0f)
            ConfigRepository.setDefaultOpacity(context, (newOpacity * 100).toInt())
            
            // 如果遮罩正在运行，更新其透明度
            if (ConfigRepository.isDefaultActive(context)) {
                val intent = Intent(context, OverlayService::class.java)
                intent.putExtra("action", "update_opacity")
                intent.putExtra("opacity", newOpacity)
                context.startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayToggler", "调整透明度失败", e)
        }
    }
}
