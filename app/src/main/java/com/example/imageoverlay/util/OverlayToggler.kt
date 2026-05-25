package com.example.imageoverlay.util

import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.imageoverlay.OverlayService
import com.example.imageoverlay.model.Config
import com.example.imageoverlay.model.ConfigRepository
import com.example.imageoverlay.model.Group

/**
 * 遮罩开关工具类，统一处理默认遮罩的开启/关闭逻辑
 */
object OverlayToggler {

    /** 磁贴 / 旧接口：双屏时同时切换主副屏 */
    fun toggleDefaultOverlay(context: Context): Boolean = toggleOverlayBoth(context)

    fun toggleOverlayBoth(context: Context): Boolean {
        val hasSecondary = DisplayUtil.hasSecondaryDisplay(context)
        val mainOn = ConfigRepository.isOverlayRunningForScreenType(context, "main")
        val secondaryOn = hasSecondary &&
            ConfigRepository.isOverlayRunningForScreenType(context, "secondary")
        val anyOn = mainOn || secondaryOn

        return if (anyOn) {
            turnOffOverlayForScreenType(context, "main")
            if (hasSecondary) {
                turnOffOverlayForScreenType(context, "secondary")
            }
            false
        } else {
            val mainOk = turnOnOverlayForScreenType(context, "main")
            val secondaryOk = if (hasSecondary) {
                turnOnOverlayForScreenType(context, "secondary")
            } else {
                true
            }
            mainOk || secondaryOk
        }
    }

    fun toggleOverlayForScreenType(context: Context, screenType: String): Boolean {
        return if (ConfigRepository.isOverlayRunningForScreenType(context, screenType)) {
            turnOffOverlayForScreenType(context, screenType)
            false
        } else {
            turnOnOverlayForScreenType(context, screenType)
        }
    }

    fun turnOffOverlayForScreenType(context: Context, screenType: String) {
        try {
            OverlayService.stopDisplay(
                context,
                ConfigRepository.displayKeyForScreenType(context, screenType)
            )
            ConfigRepository.setDefaultActive(context, false, screenType)
            ConfigRepository.clearActiveConfigsForScreenType(screenType)
            ConfigRepository.save(context)
        } catch (e: Exception) {
            android.util.Log.e("OverlayToggler", "关闭遮罩失败 screen=$screenType", e)
        }
    }

    /** 仅开启指定绑定组在对应屏上的遮罩，不按屏解析其它组 */
    fun turnOnOverlayForBoundGroup(context: Context, group: Group, config: Config): Boolean {
        return try {
            if (!PermissionUtil.checkOverlayPermission(context)) {
                android.util.Log.w("OverlayToggler", "悬浮窗权限未授予")
                return false
            }
            if (config.imageUri.isBlank()) {
                return false
            }

            val screenType = group.screenType
            ConfigRepository.clearActiveConfigsForScreenType(screenType)
            group.configs.find { it.configName == config.configName }?.active = true
            ConfigRepository.setDefaultConfig(context, group.id, config, screenType)
            ConfigRepository.save(context)

            val intent = Intent(context, OverlayService::class.java)
            intent.putExtra("imageUri", config.imageUri)
            intent.putExtra("opacity", ConfigRepository.getDefaultOpacity(context))
            if (screenType == "secondary") {
                intent.putExtra(
                    OverlayService.EXTRA_DISPLAY_ID,
                    ConfigRepository.displayKeyForScreenType(context, screenType)
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            ConfigRepository.setDefaultActive(context, true, screenType)
            true
        } catch (e: Exception) {
            android.util.Log.e("OverlayToggler", "开启绑定组遮罩失败", e)
            false
        }
    }

    fun turnOnOverlayForScreenType(context: Context, screenType: String): Boolean {
        return try {
            if (!PermissionUtil.checkOverlayPermission(context)) {
                android.util.Log.w("OverlayToggler", "悬浮窗权限未授予")
                return false
            }

            val config = ConfigRepository.resolveOverlayConfigForScreenType(context, screenType)
            if (config == null || config.imageUri.isBlank()) {
                android.util.Log.w("OverlayToggler", "无可用遮罩配置 screen=$screenType")
                return false
            }

            ConfigRepository.clearActiveConfigsForScreenType(screenType)
            val group = ConfigRepository.findGroupWithActiveOrDefaultConfig(screenType, config)
            group?.let { g ->
                g.configs.find { it.configName == config.configName }?.active = true
            }
            ConfigRepository.save(context)

            val intent = Intent(context, OverlayService::class.java)
            intent.putExtra("imageUri", config.imageUri)
            intent.putExtra("opacity", ConfigRepository.getDefaultOpacity(context))
            if (screenType == "secondary") {
                intent.putExtra(
                    OverlayService.EXTRA_DISPLAY_ID,
                    ConfigRepository.displayKeyForScreenType(context, screenType)
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            ConfigRepository.setDefaultActive(context, true, screenType)
            true
        } catch (e: Exception) {
            android.util.Log.e("OverlayToggler", "开启遮罩失败 screen=$screenType", e)
            false
        }
    }

    /** @deprecated 使用 turnOffOverlayForScreenType */
    fun turnOffOverlay(context: Context) = turnOffOverlayForScreenType(context, "main")

    /** @deprecated 使用 turnOnOverlayForScreenType */
    fun turnOnOverlay(context: Context): Boolean = turnOnOverlayForScreenType(context, "main")

    fun isOverlayActive(context: Context): Boolean {
        val main = ConfigRepository.isOverlayRunningForScreenType(context, "main")
        if (!DisplayUtil.hasSecondaryDisplay(context)) {
            return main
        }
        return main || ConfigRepository.isOverlayRunningForScreenType(context, "secondary")
    }
}
