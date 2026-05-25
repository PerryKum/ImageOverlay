package com.example.imageoverlay.util

import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.imageoverlay.OverlayService
import com.example.imageoverlay.model.Config
import com.example.imageoverlay.model.ConfigRepository
import com.example.imageoverlay.model.Group

/**
 * 遮罩开关工具类，统一处理默认遮罩的开启/关闭逻辑。
 *
 * 磁贴 / 快捷键（toggle_overlay*）：前台包名未绑定任何组时用「全局默认」配置；
 * 已绑定主屏或副屏任一组时全局默认不生效，仅按该包名在该屏的绑定组默认配置开启。
 */
object OverlayToggler {

    /** 双屏磁贴四种模式（点击在可用项间循环：双屏开 → 上屏开 → 下屏开 → 全部关闭） */
    enum class DualScreenTileMode {
        BOTH_ON,
        MAIN_ON,
        SECONDARY_ON,
        ALL_OFF
    }

    /**
     * 该屏是否可被磁贴开启：有全局默认，或前台包名在本屏有绑定组且组内有默认图。
     * 前台已绑定任意组时，未绑定的屏不会走全局默认。
     */
    fun canTurnOnScreenViaTile(context: Context, screenType: String): Boolean {
        val pkg = foregroundPackageForTile(context)
        if (pkg != null && ConfigRepository.hasBoundGroupForPackage(pkg)) {
            val group = ConfigRepository.getBoundGroupForScreenType(pkg, screenType) ?: return false
            val config = ConfigRepository.getGroupDefaultConfig(group.id) ?: return false
            return config.imageUri.isNotBlank()
        }
        val global = ConfigRepository.getDefaultConfig(context, screenType) ?: return false
        return global.imageUri.isNotBlank()
    }

    /** 磁贴是否可操作（至少一屏可开；双屏都不可用时为 false） */
    fun isTileOperable(context: Context): Boolean {
        if (!DisplayUtil.hasSecondaryDisplay(context)) {
            return canTurnOnScreenViaTile(context, "main")
        }
        return canTurnOnScreenViaTile(context, "main") ||
            canTurnOnScreenViaTile(context, "secondary")
    }

    /** 双屏都不可用时：关掉遮罩并保持磁贴为「全部关闭」不可用态 */
    fun ensureTileAllOffForDisabled(context: Context) {
        turnOffOverlayForScreenType(context, "main")
        turnOffOverlayForScreenType(context, "secondary")
    }

    /**
     * 磁贴点击：单屏开关；双屏在可用状态间循环。
     * @return 双屏时的下一档位；不可操作或单屏时 null
     */
    fun handleTileClick(context: Context): DualScreenTileMode? {
        if (!isTileOperable(context)) {
            ensureTileAllOffForDisabled(context)
            return DualScreenTileMode.ALL_OFF
        }
        if (!DisplayUtil.hasSecondaryDisplay(context)) {
            toggleOverlayBoth(context)
            return null
        }
        val current = detectDualScreenTileMode(context)
        val next = nextAvailableDualTileMode(context, current)
        applyDualScreenTileMode(context, next)
        return next
    }

    fun detectDualScreenTileMode(context: Context): DualScreenTileMode {
        val mainOn = ConfigRepository.isOverlayRunningForScreenType(context, "main")
        val secondaryOn =
            ConfigRepository.isOverlayRunningForScreenType(context, "secondary")
        return when {
            mainOn && secondaryOn -> DualScreenTileMode.BOTH_ON
            mainOn -> DualScreenTileMode.MAIN_ON
            secondaryOn -> DualScreenTileMode.SECONDARY_ON
            else -> DualScreenTileMode.ALL_OFF
        }
    }

    fun dualScreenTileModeLabel(context: Context, mode: DualScreenTileMode): String {
        val res = context.resources
        return when (mode) {
            DualScreenTileMode.BOTH_ON -> res.getString(
                com.example.imageoverlay.R.string.tile_mode_both_on
            )
            DualScreenTileMode.MAIN_ON -> res.getString(
                com.example.imageoverlay.R.string.tile_mode_main_on
            )
            DualScreenTileMode.SECONDARY_ON -> res.getString(
                com.example.imageoverlay.R.string.tile_mode_secondary_on
            )
            DualScreenTileMode.ALL_OFF -> res.getString(
                com.example.imageoverlay.R.string.tile_mode_all_off
            )
        }
    }

    private fun availableDualTileModes(context: Context): List<DualScreenTileMode> {
        val modes = mutableListOf<DualScreenTileMode>()
        val canMain = canTurnOnScreenViaTile(context, "main")
        val canSecondary = canTurnOnScreenViaTile(context, "secondary")
        if (canMain || canSecondary) {
            modes.add(DualScreenTileMode.BOTH_ON)
        }
        if (canMain) {
            modes.add(DualScreenTileMode.MAIN_ON)
        }
        if (canSecondary) {
            modes.add(DualScreenTileMode.SECONDARY_ON)
        }
        modes.add(DualScreenTileMode.ALL_OFF)
        return modes
    }

    private fun nextAvailableDualTileMode(
        context: Context,
        current: DualScreenTileMode
    ): DualScreenTileMode {
        val modes = availableDualTileModes(context)
        if (modes.isEmpty()) {
            return DualScreenTileMode.ALL_OFF
        }
        val idx = modes.indexOf(current).takeIf { it >= 0 }
            ?: modes.indexOf(DualScreenTileMode.ALL_OFF).coerceAtLeast(0)
        return modes[(idx + 1) % modes.size]
    }

    private fun applyDualScreenTileMode(context: Context, mode: DualScreenTileMode) {
        val canMain = canTurnOnScreenViaTile(context, "main")
        val canSecondary = canTurnOnScreenViaTile(context, "secondary")
        when (mode) {
            DualScreenTileMode.ALL_OFF -> {
                turnOffOverlayForScreenType(context, "main")
                turnOffOverlayForScreenType(context, "secondary")
            }
            DualScreenTileMode.BOTH_ON -> {
                if (canMain) {
                    turnOnViaTileOrShortcut(context, "main")
                }
                if (canSecondary) {
                    turnOnViaTileOrShortcut(context, "secondary")
                }
            }
            DualScreenTileMode.MAIN_ON -> {
                if (canSecondary) {
                    turnOffOverlayForScreenType(context, "secondary")
                }
                if (canMain) {
                    turnOnViaTileOrShortcut(context, "main")
                }
            }
            DualScreenTileMode.SECONDARY_ON -> {
                if (canMain) {
                    turnOffOverlayForScreenType(context, "main")
                }
                if (canSecondary) {
                    turnOnViaTileOrShortcut(context, "secondary")
                }
            }
        }
    }

    /** 磁贴：单屏开关（快捷键 toggle_overlay 仍用 toggleOverlayBoth） */
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
            val mainOk = turnOnViaTileOrShortcut(context, "main")
            val secondaryOk = if (hasSecondary) {
                turnOnViaTileOrShortcut(context, "secondary")
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
            turnOnViaTileOrShortcut(context, screenType)
        }
    }

    private fun foregroundPackageForTile(context: Context): String? =
        ForegroundAppUtil.findTopMatching {
            ConfigRepository.hasBoundGroupForPackage(it)
        }

    /** 磁贴 / 快捷键开启：未绑定 → 全局默认；已绑定任意组 → 仅该屏绑定组默认，且不写全局默认 SP */
    private fun turnOnViaTileOrShortcut(context: Context, screenType: String): Boolean {
        val pkg = foregroundPackageForTile(context)
        if (pkg != null && ConfigRepository.hasBoundGroupForPackage(pkg)) {
            val group = ConfigRepository.getBoundGroupForScreenType(pkg, screenType) ?: run {
                android.util.Log.d(
                    "OverlayToggler",
                    "前台已绑定应用但 $screenType 无绑定组，跳过: $pkg"
                )
                return false
            }
            val config = ConfigRepository.getGroupDefaultConfig(group.id) ?: return false
            if (config.imageUri.isBlank()) return false
            return turnOnOverlayForBoundGroup(
                context,
                group,
                config,
                persistGlobalDefault = false
            )
        }
        return turnOnGlobalDefaultForScreenType(context, screenType)
    }

    /** 仅使用预设页「全局默认」存储的配置（不走绿点 / 任意有图回退） */
    private fun turnOnGlobalDefaultForScreenType(context: Context, screenType: String): Boolean {
        val config = ConfigRepository.getDefaultConfig(context, screenType) ?: run {
            android.util.Log.w("OverlayToggler", "未设置全局默认 screen=$screenType")
            return false
        }
        if (config.imageUri.isBlank()) return false
        val groupId = ConfigRepository.getDefaultGroupId(context, screenType) ?: return false
        val group = ConfigRepository.findGroupById(groupId) ?: return false
        return turnOnOverlayForBoundGroup(
            context,
            group,
            config,
            persistGlobalDefault = true
        )
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
            ConfigRepository.notifyConfigActiveChanged(context)
        } catch (e: Exception) {
            android.util.Log.e("OverlayToggler", "关闭遮罩失败 screen=$screenType", e)
        }
    }

    /**
     * 开启指定绑定组在对应屏上的遮罩。
     * @param persistGlobalDefault 为 true 时同步写入全局默认 SP（仅磁贴/快捷键在未绑定前台时使用）
     */
    fun turnOnOverlayForBoundGroup(
        context: Context,
        group: Group,
        config: Config,
        persistGlobalDefault: Boolean = false
    ): Boolean {
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
            if (persistGlobalDefault) {
                ConfigRepository.setDefaultConfig(context, group.id, config, screenType)
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

            if (persistGlobalDefault) {
                ConfigRepository.setDefaultActive(context, true, screenType)
            }
            ConfigRepository.notifyConfigActiveChanged(context)
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
