package com.example.imageoverlay.model

import android.content.Context
import android.hardware.display.DisplayManager
import android.net.Uri
import android.view.Display
import com.example.imageoverlay.OverlayService
import com.example.imageoverlay.util.ConfigPathUtil
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

object ConfigRepository {
    private val gson = Gson()
    private var groupList: MutableList<Group> = mutableListOf()
    private val configLock = Any()
    private const val PREF_DEFAULT = "default_config"
    private const val KEY_DEFAULT_NAME = "name"
    private const val KEY_DEFAULT_URI = "uri"
    private const val KEY_DEFAULT_GROUP = "group"
    private const val KEY_DEFAULT_ACTIVE = "active"
    private const val KEY_DEFAULT_OPACITY = "opacity"
    private const val KEY_DEFAULT_NAME_SECONDARY = "name_secondary"
    private const val KEY_DEFAULT_URI_SECONDARY = "uri_secondary"
    private const val KEY_DEFAULT_GROUP_SECONDARY = "group_secondary"
    private const val KEY_DEFAULT_ACTIVE_SECONDARY = "active_secondary"
    private const val PREF_APP_BINDINGS = "app_bindings"
    private const val PREF_SETTINGS = "settings"
    private const val KEY_AUTO_START_OVERLAY = "auto_start_overlay"
    private const val KEY_COVER_CUTOUT = "cover_cutout"
    private const val KEY_FLOATING_BALL = "floating_ball"
    private const val KEY_BOUND_HARDWARE_KEYS = "bound_hardware_keys" // 逗号分隔的keyCode列表
    private var isServiceStarting = false // 防止服务重复启动
    private var isServiceStopping = false // 防止服务重复停止
    private var lastOperationTime = 0L // 记录最后一次操作的时间
    private val OPERATION_COOLDOWN = 3000L // 操作冷却时间3秒
    private var lastManualOperationTime = 0L // 记录最后一次手动操作的时间
    private val MANUAL_OPERATION_COOLDOWN = 5000L // 手动操作冷却时间5秒

    fun load(context: Context) {
        synchronized(configLock) {
            loadInternal(context)
        }
    }

    private fun loadInternal(context: Context) {
        val previous = groupList.toList()
        try {
            val configFile = ConfigPathUtil.getConfigFile(context)
            val uriStr = ConfigPathUtil.getConfigRoot(context)

            if (uriStr.isBlank()) {
                android.util.Log.w("ConfigRepository", "配置路径为空")
                if (!restoreOnLoadFailure(previous, "配置路径为空")) {
                    groupList = mutableListOf()
                }
                return
            }

            if (uriStr.startsWith("content://")) {
                loadFromSaf(context, uriStr, previous)
            } else {
                loadFromFile(configFile, previous)
            }

            var needMigration = false
            for (group in groupList) {
                if (group.id.isBlank()) {
                    group.id = java.util.UUID.randomUUID().toString()
                    needMigration = true
                }
            }
            if (needMigration) {
                saveInternal(context)
            }
            android.util.Log.d("ConfigRepository", "配置加载完成，共${groupList.size}个组")
        } catch (e: Exception) {
            android.util.Log.e("ConfigRepository", "配置加载出现未知异常", e)
            if (!restoreOnLoadFailure(previous, "配置加载异常")) {
                groupList = mutableListOf()
            }
        }
    }

    private fun loadFromSaf(context: Context, uriStr: String, previous: List<Group>) {
        try {
            val rootUri = Uri.parse(uriStr)
            val rootDoc =
                androidx.documentfile.provider.DocumentFile.fromTreeUri(context, rootUri)

            if (rootDoc == null || !rootDoc.exists()) {
                if (!restoreOnLoadFailure(previous, "SAF根目录不可访问")) {
                    groupList = mutableListOf()
                }
                return
            }

            if (!hasValidSafPermission(context, rootUri)) {
                if (!restoreOnLoadFailure(previous, "SAF权限暂时无效")) {
                    groupList = mutableListOf()
                }
                return
            }

            val overlayDoc = rootDoc.findFile("ImageOverlay")
                ?: rootDoc.createDirectory("ImageOverlay")

            if (overlayDoc == null || !overlayDoc.exists()) {
                if (!restoreOnLoadFailure(previous, "无法访问 ImageOverlay 目录")) {
                    groupList = mutableListOf()
                }
                return
            }

            var configDoc = findConfigJsonDoc(overlayDoc)
            if (configDoc == null) {
                if (previous.isNotEmpty()) {
                    restoreOnLoadFailure(previous, "找不到 config.json")
                    return
                }
                configDoc = overlayDoc.createFile("application/json", "config.json")
                configDoc?.uri?.let { uri ->
                    context.contentResolver.openOutputStream(uri, "wt")
                        ?.use { it.write("[]".toByteArray()) }
                }
            }

            if (configDoc == null) {
                if (!restoreOnLoadFailure(previous, "无法打开 config.json")) {
                    groupList = mutableListOf()
                }
                return
            }

            val json = readJsonFromSaf(context, configDoc)
            if (json == null) {
                if (!restoreOnLoadFailure(previous, "SAF 读取 config.json 失败")) {
                    groupList = mutableListOf()
                }
                return
            }

            applyJsonToGroupList(json)
        } catch (e: Exception) {
            android.util.Log.e("ConfigRepository", "SAF方式读取配置失败", e)
            if (!restoreOnLoadFailure(previous, "SAF读取异常")) {
                groupList = mutableListOf()
            }
        }
    }

    private fun loadFromFile(configFile: File, previous: List<Group>) {
        try {
            if (!configFile.exists()) {
                if (previous.isNotEmpty()) {
                    restoreOnLoadFailure(previous, "本地 config.json 不存在")
                    return
                }
                configFile.parentFile?.mkdirs()
                configFile.writeText("[]")
            }
            if (!configFile.exists()) {
                if (!restoreOnLoadFailure(previous, "无法创建 config.json")) {
                    groupList = mutableListOf()
                }
                return
            }
            val json = configFile.readText()
            applyJsonToGroupList(json)
        } catch (e: Exception) {
            android.util.Log.e("ConfigRepository", "普通文件方式读取配置失败", e)
            if (!restoreOnLoadFailure(previous, "文件读取异常")) {
                groupList = mutableListOf()
            }
        }
    }

    private fun applyJsonToGroupList(json: String) {
        if (json.isNotBlank()) {
            val type = object : TypeToken<MutableList<Group>>() {}.type
            groupList = gson.fromJson(json, type) ?: mutableListOf()
        } else {
            groupList = mutableListOf()
        }
    }

    private fun findConfigJsonDoc(
        overlayDoc: androidx.documentfile.provider.DocumentFile
    ): androidx.documentfile.provider.DocumentFile? {
        overlayDoc.findFile("config.json")?.takeIf { it.exists() }?.let { return it }
        return overlayDoc.listFiles().firstOrNull { file ->
            file.isFile && file.name?.equals("config.json", ignoreCase = true) == true
        }
    }

    private fun readJsonFromSaf(
        context: Context,
        configDoc: androidx.documentfile.provider.DocumentFile
    ): String? {
        repeat(3) { attempt ->
            try {
                val uri = configDoc.uri ?: return@repeat
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    return InputStreamReader(stream).readText()
                }
            } catch (e: Exception) {
                android.util.Log.w("ConfigRepository", "SAF读取重试 ${attempt + 1}/3", e)
                if (attempt < 2) {
                    try {
                        Thread.sleep(150)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }
        return null
    }

    private fun restoreOnLoadFailure(previous: List<Group>, reason: String): Boolean {
        if (previous.isNotEmpty()) {
            groupList = previous.toMutableList()
            android.util.Log.w(
                "ConfigRepository",
                "$reason，保留内存缓存 ${groupList.size} 组"
            )
            return true
        }
        return false
    }

    private fun hasValidSafPermission(context: Context, uri: Uri): Boolean {
        return try {
            val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
            if (docFile != null && docFile.exists() && docFile.isDirectory) {
                try {
                    docFile.listFiles()
                } catch (e: Exception) {
                    android.util.Log.w("ConfigRepository", "SAF listFiles 失败，目录仍可访问", e)
                }
                return true
            }
            android.util.Log.w("ConfigRepository", "SAF目录暂时不可访问，尝试持久化权限校验")
            val flags = context.contentResolver.getPersistedUriPermissions()
            flags.any { permission ->
                urisMatch(permission.uri, uri) &&
                    (permission.isReadPermission || permission.isWritePermission)
            }
        } catch (e: Exception) {
            android.util.Log.e("ConfigRepository", "SAF权限检查失败", e)
            false
        }
    }

    private fun urisMatch(a: Uri, b: Uri): Boolean {
        if (a == b) return true
        return a.toString().trimEnd('/') == b.toString().trimEnd('/')
    }

    fun save(context: Context) {
        synchronized(configLock) {
            saveInternal(context)
        }
    }

    private fun saveInternal(context: Context) {
        val configFile = ConfigPathUtil.getConfigFile(context)
        val uriStr = ConfigPathUtil.getConfigRoot(context)
        val json = gson.toJson(groupList)
        if (uriStr.startsWith("content://")) {
            // SAF方式写入
            try {
                val rootUri = Uri.parse(uriStr)
                val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, rootUri)
                val overlayDoc = rootDoc?.findFile("ImageOverlay")
                    ?: rootDoc?.createDirectory("ImageOverlay")
                var configDoc = overlayDoc?.let { findConfigJsonDoc(it) }
                if (configDoc == null) {
                    configDoc = overlayDoc?.createFile("application/json", "config.json")
                }
                configDoc?.uri?.let { uri ->
                    context.contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
                        val writer = OutputStreamWriter(outputStream)
                        writer.write(json)
                        writer.flush()
                        writer.close()
                    }
                }
            } catch (_: Exception) {
            }
        } else {
            // 普通文件方式
            configFile.writeText(json)
        }
    }

    fun getGroups(): MutableList<Group> = groupList

    fun getGroupsByScreenType(screenType: String): MutableList<Group> {
        return groupList.filter { it.screenType == screenType }.toMutableList()
    }

    private fun defaultNameKey(screenType: String) =
        if (screenType == "secondary") KEY_DEFAULT_NAME_SECONDARY else KEY_DEFAULT_NAME

    private fun defaultUriKey(screenType: String) =
        if (screenType == "secondary") KEY_DEFAULT_URI_SECONDARY else KEY_DEFAULT_URI

    private fun defaultGroupKey(screenType: String) =
        if (screenType == "secondary") KEY_DEFAULT_GROUP_SECONDARY else KEY_DEFAULT_GROUP

    private fun defaultActiveKey(screenType: String) =
        if (screenType == "secondary") KEY_DEFAULT_ACTIVE_SECONDARY else KEY_DEFAULT_ACTIVE

    fun displayKeyForScreenType(context: Context, screenType: String): Int {
        if (screenType == "secondary") {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val displays = dm?.displays
            if (displays != null && displays.size > 1) {
                return displays[1].displayId
            }
        }
        return Display.DEFAULT_DISPLAY
    }

    fun setDefaultConfig(context: Context, groupId: String, config: Config, screenType: String = "main") {
        val sp = context.getSharedPreferences(PREF_DEFAULT, Context.MODE_PRIVATE)
        sp.edit()
            .putString(defaultNameKey(screenType), config.configName)
            .putString(defaultUriKey(screenType), config.imageUri)
            .putString(defaultGroupKey(screenType), groupId)
            .apply()
    }

    fun getDefaultConfig(context: Context, screenType: String = "main"): Config? {
        val sp = context.getSharedPreferences(PREF_DEFAULT, Context.MODE_PRIVATE)
        val name = sp.getString(defaultNameKey(screenType), null)
        val uri = sp.getString(defaultUriKey(screenType), null)
        return if (!name.isNullOrBlank() && !uri.isNullOrBlank()) Config(name, uri, false) else null
    }

    fun getDefaultGroupId(context: Context, screenType: String = "main"): String? {
        val sp = context.getSharedPreferences(PREF_DEFAULT, Context.MODE_PRIVATE)
        return sp.getString(defaultGroupKey(screenType), null)
    }

    fun setDefaultActive(context: Context, active: Boolean, screenType: String = "main") {
        val sp = context.getSharedPreferences(PREF_DEFAULT, Context.MODE_PRIVATE)
        sp.edit().putBoolean(defaultActiveKey(screenType), active).apply()
    }

    fun isDefaultActive(context: Context, screenType: String = "main"): Boolean {
        val sp = context.getSharedPreferences(PREF_DEFAULT, Context.MODE_PRIVATE)
        return sp.getBoolean(defaultActiveKey(screenType), false)
    }

    fun isOverlayRunningForScreenType(context: Context, screenType: String): Boolean {
        return OverlayService.isRunningOnDisplay(displayKeyForScreenType(context, screenType))
    }

    /** 解析某块屏要显示的遮罩：优先当前激活项，其次该屏默认配置，再其次任意有图的配置 */
    fun resolveOverlayConfigForScreenType(context: Context, screenType: String): Config? {
        groupList.filter { it.screenType == screenType }.forEach { group ->
            group.configs.forEach { config ->
                if (config.active && config.imageUri.isNotBlank()) {
                    return config
                }
            }
        }
        getDefaultConfig(context, screenType)?.let { config ->
            if (config.imageUri.isNotBlank()) return config
        }
        groupList.filter { it.screenType == screenType }.forEach { group ->
            group.configs.firstOrNull { it.imageUri.isNotBlank() }?.let { return it }
        }
        return null
    }

    fun findGroupWithActiveOrDefaultConfig(screenType: String, config: Config): Group? {
        return groupList.find { group ->
            group.screenType == screenType &&
                group.configs.any { it.configName == config.configName }
        }
    }

    fun getKeyBindingFunctionKeys(context: Context): List<String> {
        val keys = mutableListOf(
            "toggle_overlay",
            "toggle_overlay_main",
            "toggle_overlay_secondary",
            "toggle_floating_ball"
        )
        if (!com.example.imageoverlay.util.DisplayUtil.hasSecondaryDisplay(context)) {
            keys.remove("toggle_overlay_main")
            keys.remove("toggle_overlay_secondary")
        }
        return keys
    }

    fun setDefaultOpacity(context: Context, opacity: Int) {
        val sp = context.getSharedPreferences(PREF_DEFAULT, Context.MODE_PRIVATE)
        sp.edit().putInt(KEY_DEFAULT_OPACITY, opacity).apply()
    }

    fun getDefaultOpacity(context: Context): Int {
        val sp = context.getSharedPreferences(PREF_DEFAULT, Context.MODE_PRIVATE)
        return sp.getInt(KEY_DEFAULT_OPACITY, 100)
    }

    fun clearDefaultConfig(context: Context, screenType: String = "main") {
        val sp = context.getSharedPreferences(PREF_DEFAULT, Context.MODE_PRIVATE)
        sp.edit()
            .remove(defaultNameKey(screenType))
            .remove(defaultUriKey(screenType))
            .remove(defaultGroupKey(screenType))
            .putBoolean(defaultActiveKey(screenType), false)
            .apply()
    }

    // 通过 UUID 查找组
    fun findGroupById(id: String): Group? {
        return groupList.find { it.id == id }
    }

    // 只清除同屏类型的 active 状态
    fun clearActiveConfigsForScreenType(screenType: String) {
        groupList.filter { it.screenType == screenType }.forEach { group ->
            group.configs.forEach { config -> config.active = false }
        }
    }

    private const val QUICK_USE_PREFS = "quick_use_prefs"
    private const val QUICK_USE_ACTIVE_PREFIX = "is_overlay_active_"
    private const val QUICK_USE_ACTIVE_LEGACY = "is_overlay_active"

    /** 该屏遮罩是否由「快速使用」启动（与预设互斥判断用） */
    fun isQuickUseOverlayActive(context: Context, screenType: String): Boolean {
        val sp = context.getSharedPreferences(QUICK_USE_PREFS, Context.MODE_PRIVATE)
        val saved = sp.getBoolean(QUICK_USE_ACTIVE_PREFIX + screenType, false) ||
            (screenType == "main" && sp.getBoolean(QUICK_USE_ACTIVE_LEGACY, false))
        return saved && isOverlayRunningForScreenType(context, screenType)
    }

    /**
     * 将某屏预设状态与真实遮罩服务对齐；服务未运行则清理残留的 active / defaultActive。
     * @return 该屏是否仍有预设遮罩在运行（与快速使用互斥）
     */
    fun syncPresetStateForScreenType(context: Context, screenType: String): Boolean {
        val overlayRunning = isOverlayRunningForScreenType(context, screenType)
        val hasActiveConfigFlags = getGroupsByScreenType(screenType).any { group ->
            group.configs.any { it.active }
        }
        val savedDefaultActive = isDefaultActive(context, screenType)

        if (!overlayRunning) {
            var changed = false
            if (hasActiveConfigFlags) {
                clearActiveConfigsForScreenType(screenType)
                changed = true
            }
            if (savedDefaultActive) {
                setDefaultActive(context, false, screenType)
                changed = true
            }
            if (changed) {
                save(context)
                android.util.Log.d(
                    "ConfigRepository",
                    "已同步 $screenType 预设：遮罩未运行，已清理残留标记"
                )
            }
            return false
        }

        if (isQuickUseOverlayActive(context, screenType)) {
            return false
        }

        return true
    }

    fun syncAllPresetStates(context: Context) {
        syncPresetStateForScreenType(context, "main")
        syncPresetStateForScreenType(context, "secondary")
    }

    // 新增：设置组的默认遮罩
    fun setGroupDefaultConfig(groupId: String, configName: String) {
        val group = findGroupById(groupId) ?: return

        // 同包名其它组：先去掉其「组内默认」标记（避免多组绑同一应用时默认配置冲突）
        val packageName = group.boundPackageName
        if (!packageName.isNullOrBlank()) {
            groupList.forEach { other ->
                if (other.id != groupId && other.boundPackageName == packageName) {
                    other.configs.forEach { config -> config.isDefault = false }
                    other.defaultConfigName = null
                }
            }
        }

        group.configs.forEach { config -> config.isDefault = false }
        group.configs.find { config -> config.configName == configName }?.isDefault = true
        group.defaultConfigName = configName
    }

    // 新增：获取组的默认遮罩
    fun getGroupDefaultConfig(groupId: String): Config? {
        val group = findGroupById(groupId)
        return group?.configs?.find { it.isDefault } ?: group?.configs?.firstOrNull()
    }

    // 新增：绑定应用到组
    fun bindAppToGroup(groupId: String, packageName: String) {
        val group = findGroupById(groupId)
        group?.boundPackageName = packageName
    }

    // 新增：解绑应用
    fun unbindAppFromGroup(groupId: String) {
        val group = findGroupById(groupId)
        group?.boundPackageName = null
    }

    /** 该包名绑定的全部组（主屏/副屏可各有一个） */
    fun getGroupsByPackageName(packageName: String): List<Group> {
        return groupList.filter { it.boundPackageName == packageName }
    }

    fun hasBoundGroupForPackage(packageName: String): Boolean {
        return getGroupsByPackageName(packageName).isNotEmpty()
    }

    /** 该包名在指定屏类型上绑定的全部组（与自动开启、悬浮球数据源一致） */
    fun getBoundGroupsForScreenType(packageName: String, screenType: String): List<Group> {
        return groupList.filter {
            it.boundPackageName == packageName &&
                it.screenType == screenType &&
                it.groupName != "默认配置"
        }
    }

    /** 该包名在指定屏类型上绑定的组（同屏多个时取列表中第一个） */
    fun getBoundGroupForScreenType(packageName: String, screenType: String): Group? {
        return getBoundGroupsForScreenType(packageName, screenType).firstOrNull()
    }

    // 兼容：返回该包名绑定的第一个组
    fun getGroupByPackageName(packageName: String): Group? {
        return getGroupsByPackageName(packageName).firstOrNull()
    }

    // 新增：处理应用启动事件
    fun handleAppLaunch(context: Context, packageName: String, isLauncher: Boolean = false) {
        try {
            val currentTime = System.currentTimeMillis()
            
            // 特殊：桌面事件不受任何冷却或手动操作限制，必须优先处理
            if (isLauncher) {
                // 检测到桌面/启动器
                android.util.Log.d("ConfigRepository", "检测到桌面/启动器")
                lastOperationTime = currentTime
                
                // 停止悬浮球服务
                try {
                    com.example.imageoverlay.util.FloatingBallLauncher.stop(context)
                    android.util.Log.d("ConfigRepository", "桌面检测：停止悬浮球服务")
                } catch (e: Exception) {
                    android.util.Log.e("ConfigRepository", "停止悬浮球服务失败", e)
                }
                
                // 根据自动开启开关决定是否关闭遮罩
                if (isAutoStartOverlayEnabled(context)) {
                    // 自动开启开关开启，关闭遮罩
                    turnOffOverlaySafely(context)
                } else {
                    setDefaultActive(context, false, "main")
                    setDefaultActive(context, false, "secondary")
                    android.util.Log.d("ConfigRepository", "自动开启开关关闭，只设置状态不关闭遮罩")
                }
                return
            }
            
            // 非桌面事件才应用冷却与手动操作屏蔽
            // 检查操作冷却时间，防止频繁操作
            if (currentTime - lastOperationTime < OPERATION_COOLDOWN) {
                android.util.Log.d("ConfigRepository", "操作冷却中，跳过处理: $packageName")
                return
            }
            
            // 检查是否有最近的手动操作，如果有则跳过自动处理
            if (currentTime - lastManualOperationTime < MANUAL_OPERATION_COOLDOWN) {
                android.util.Log.d("ConfigRepository", "检测到最近手动操作，跳过自动处理: $packageName")
                return
            }
            
            if (!hasBoundGroupForPackage(packageName)) {
                android.util.Log.d(
                    "ConfigRepository",
                    "忽略非绑定应用前台事件: $packageName"
                )
                return
            }

            lastOperationTime = currentTime
            applyBoundDefaultConfigsForPackage(context, packageName)

            if (isAutoStartOverlayEnabled(context) && !isServiceStarting) {
                autoStartOverlaysForBoundPackage(context, packageName)
            }

            // 启动悬浮球服务（如果启用）
            if (isFloatingBallEnabled(context)) {
                    try {
                        com.example.imageoverlay.util.FloatingBallLauncher.start(context, packageName)
                        android.util.Log.d("ConfigRepository", "启动悬浮球服务: $packageName")
                    } catch (e: Exception) {
                        android.util.Log.e("ConfigRepository", "启动悬浮球服务失败", e)
                    }
                } else {
                    android.util.Log.d("ConfigRepository", "悬浮球功能已禁用，跳过启动")
                }
        } catch (e: Exception) {
            android.util.Log.e("ConfigRepository", "处理应用启动事件失败: $packageName", e)
        }
    }
    
    /**
     * 安全地关闭遮罩，确保完全关闭后再进行其他操作
     */
    private fun turnOffOverlaySafely(context: Context) {
        if (isServiceStarting || isServiceStopping) {
            android.util.Log.d("ConfigRepository", "服务正在启动或停止中，跳过关闭操作")
            return
        }

        if (!OverlayService.hasAnyOverlayRunning() &&
            !isDefaultActive(context, "main") &&
            !isDefaultActive(context, "secondary")
        ) {
            android.util.Log.d("ConfigRepository", "当前没有遮罩运行，跳过关闭操作")
            return
        }

        try {
            isServiceStopping = true
            android.util.Log.d("ConfigRepository", "开始安全关闭全部遮罩")

            OverlayService.stopAll(context)
            setDefaultActive(context, false, "main")
            setDefaultActive(context, false, "secondary")

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                isServiceStopping = false
            }, 500)
        } catch (e: Exception) {
            android.util.Log.e("ConfigRepository", "安全关闭遮罩失败", e)
            isServiceStopping = false
        }
    }
    
     /** 前台包名：按主/副屏分别切换为该包绑定组的默认配置（不启动遮罩、不碰其它屏） */
     private fun applyBoundDefaultConfigsForPackage(context: Context, packageName: String) {
         val screenTypes = mutableListOf("main")
         if (com.example.imageoverlay.util.DisplayUtil.hasSecondaryDisplay(context)) {
             screenTypes.add("secondary")
         }
         for (screenType in screenTypes) {
             val group = getBoundGroupForScreenType(packageName, screenType) ?: continue
             val defaultConfig = getGroupDefaultConfig(group.id) ?: continue
             switchDefaultConfig(context, group.id, defaultConfig)
             android.util.Log.d(
                 "ConfigRepository",
                 "已切换绑定组默认配置: $packageName / ${group.groupName} / $screenType"
             )
         }
     }

     
     /**
      * 切换默认遮罩配置（独立逻辑）
      */
     fun switchDefaultConfig(context: Context, groupId: String, defaultConfig: com.example.imageoverlay.model.Config) {
         try {
             // 1. 只清除同屏类型的激活状态（主屏副屏独立）
             val targetGroup = findGroupById(groupId)
             clearActiveConfigsForScreenType(targetGroup?.screenType ?: "main")
             
             // 2. 激活当前组的默认配置
             targetGroup?.let { group ->
                 val targetConfig = group.configs.find { it.configName == defaultConfig.configName }
                 targetConfig?.active = true
                 android.util.Log.d("ConfigRepository", "激活组配置: ${groupId}/${defaultConfig.configName}")
             }
             
             val screenType = targetGroup?.screenType ?: "main"
             setDefaultConfig(context, groupId, defaultConfig, screenType)
             android.util.Log.d("ConfigRepository", "已设置组默认遮罩: ${groupId}/${defaultConfig.configName}")
             
             save(context)
         } catch (e: Exception) {
             android.util.Log.e("ConfigRepository", "切换默认遮罩配置失败", e)
         }
     }
     
     /**
      * 同步更新组配置状态，确保手动切换和自动切换状态一致
      */
     fun syncGroupConfigStates(context: Context, groupId: String, defaultConfig: com.example.imageoverlay.model.Config) {
         try {
             val targetGroup = findGroupById(groupId)
             clearActiveConfigsForScreenType(targetGroup?.screenType ?: "main")
             
             targetGroup?.let { group ->
                 val targetConfig = group.configs.find { it.configName == defaultConfig.configName }
                 targetConfig?.active = true
                 android.util.Log.d("ConfigRepository", "激活组配置: ${groupId}/${defaultConfig.configName}")
             }
             
             val screenType = targetGroup?.screenType ?: "main"
             setDefaultConfig(context, groupId, defaultConfig, screenType)
             android.util.Log.d("ConfigRepository", "已设置组默认遮罩: ${groupId}/${defaultConfig.configName}")
             
             // 4. 保存配置
             save(context)
         } catch (e: Exception) {
             android.util.Log.e("ConfigRepository", "同步组配置状态失败", e)
         }
     }
     
    
     /**
      * 自动开启：按前台包名分别检查主/副屏绑定组，有默认配置则只开对应屏，主副互不影响。
      */
     private fun autoStartOverlaysForBoundPackage(context: Context, packageName: String) {
         if (isServiceStarting || isServiceStopping) {
             android.util.Log.d("ConfigRepository", "服务正在启动或停止中，跳过自动开启")
             return
         }
         try {
             isServiceStarting = true
             autoStartBoundOverlayOnScreenType(context, packageName, "main")
             if (com.example.imageoverlay.util.DisplayUtil.hasSecondaryDisplay(context)) {
                 autoStartBoundOverlayOnScreenType(context, packageName, "secondary")
             }
         } catch (e: Exception) {
             android.util.Log.e("ConfigRepository", "自动开启遮罩失败", e)
         } finally {
             isServiceStarting = false
         }
     }

     private fun autoStartBoundOverlayOnScreenType(
         context: Context,
         packageName: String,
         screenType: String
     ) {
         val group = getBoundGroupForScreenType(packageName, screenType) ?: return
         val defaultConfig = getGroupDefaultConfig(group.id) ?: return
         if (defaultConfig.imageUri.isBlank()) return

         val currentDefault = getDefaultConfig(context, screenType)
         val alreadyRunning = currentDefault?.imageUri == defaultConfig.imageUri &&
             isDefaultActive(context, screenType) &&
             isOverlayRunningForScreenType(context, screenType)
         if (alreadyRunning) {
             android.util.Log.d(
                 "ConfigRepository",
                 "自动开启跳过，$screenType 已是相同遮罩: ${group.groupName}"
             )
             return
         }

         com.example.imageoverlay.util.OverlayToggler.turnOnOverlayForBoundGroup(
             context,
             group,
             defaultConfig
         )
         android.util.Log.d(
             "ConfigRepository",
             "自动开启 $screenType 遮罩: $packageName / ${group.groupName}"
         )
     }

    // 新增：设置自动开启遮罩
    fun setAutoStartOverlayEnabled(context: Context, enabled: Boolean) {
        val sp = context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_AUTO_START_OVERLAY, enabled).apply()
    }

    // 新增：获取自动开启遮罩设置
    fun isAutoStartOverlayEnabled(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_AUTO_START_OVERLAY, false)
    }
    

    // 覆盖刘海/挖孔区域 设置
    fun setCoverCutoutEnabled(context: Context, enabled: Boolean) {
        val sp = context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_COVER_CUTOUT, enabled).apply()
    }

    fun isCoverCutoutEnabled(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_COVER_CUTOUT, true)
    }

    // 悬浮球功能设置
    fun setFloatingBallEnabled(context: Context, enabled: Boolean) {
        val sp = context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_FLOATING_BALL, enabled).apply()
    }

    fun isFloatingBallEnabled(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_FLOATING_BALL, true)
    }
    
    // 标记手动操作时间
    fun markManualOperation() {
        lastManualOperationTime = System.currentTimeMillis()
        android.util.Log.d("ConfigRepository", "标记手动操作时间: $lastManualOperationTime")
    }

    // ============ 实体按键绑定 ============
    /**
     * 保存绑定的实体按键，最多3个。使用逗号分隔的字符串持久化。
     */
    fun setBoundHardwareKeys(context: Context, keyCodes: List<Int>) {
        val sanitized = keyCodes.distinct().take(3)
        val sp = context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_BOUND_HARDWARE_KEYS, sanitized.joinToString(",")).apply()
    }

    /**
     * 读取绑定的实体按键列表。
     */
    fun getBoundHardwareKeys(context: Context): List<Int> {
        val sp = context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_BOUND_HARDWARE_KEYS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(',').mapNotNull {
            try { it.trim().toInt() } catch (_: Exception) { null }
        }.distinct().take(3)
    }

    /**
     * 判断某个按键是否被绑定。
     */
    fun isHardwareKeyBound(context: Context, keyCode: Int): Boolean {
        return getBoundHardwareKeys(context).contains(keyCode)
    }

    // ============ 功能特定的按键绑定 ============
    /**
     * 为特定功能保存绑定的实体按键，最多3个。
     */
    fun setBoundHardwareKeysForFunction(context: Context, functionKey: String, keyCodes: List<Int>) {
        val sanitized = keyCodes.distinct().take(3)
        val sp = context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
        sp.edit().putString("bound_keys_$functionKey", sanitized.joinToString(",")).apply()
    }

    /**
     * 读取特定功能绑定的实体按键列表。
     */
    fun getBoundHardwareKeysForFunction(context: Context, functionKey: String): List<Int> {
        val sp = context.getSharedPreferences(PREF_SETTINGS, Context.MODE_PRIVATE)
        val raw = sp.getString("bound_keys_$functionKey", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(',').mapNotNull {
            try { it.trim().toInt() } catch (_: Exception) { null }
        }.distinct().take(3)
    }

    /**
     * 检查按键是否与已绑定的其他功能冲突。
     */
    fun checkKeyConflictForFunction(context: Context, keyCode: Int, currentFunction: String): Boolean {
        for (funcKey in getKeyBindingFunctionKeys(context)) {
            if (funcKey != currentFunction) {
                val boundKeys = getBoundHardwareKeysForFunction(context, funcKey)
                if (boundKeys.contains(keyCode)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * 获取所有功能绑定的按键（用于冲突检测）。
     */
    fun getAllBoundKeys(context: Context): Map<String, List<Int>> {
        val functionKeys = getKeyBindingFunctionKeys(context)
        val result = mutableMapOf<String, List<Int>>()
        
        for (funcKey in functionKeys) {
            result[funcKey] = getBoundHardwareKeysForFunction(context, funcKey)
        }
        return result
    }

    fun addGroup(context: Context, group: Group) {
        if (group.id.isBlank()) {
            group.id = java.util.UUID.randomUUID().toString()
        }
        groupList.add(group)
        save(context)
    }

    fun clear(context: Context) {
        // 先停止遮罩服务
        try {
            val stopIntent = android.content.Intent(context, com.example.imageoverlay.OverlayService::class.java)
            context.stopService(stopIntent)
        } catch (e: Exception) {
            android.util.Log.e("ConfigRepository", "停止遮罩服务失败", e)
        }
        
        // 先清空内存数据，避免文件删除失败导致的问题
        groupList.clear()
        save(context)
        
        try {
            // 删除所有组文件夹和图片
            val overlayRoot = com.example.imageoverlay.util.ConfigPathUtil.getOverlayRoot(context)
            if (overlayRoot.startsWith("content://")) {
                // SAF 模式，使用 DocumentFile API
                try {
                    val rootUri = android.net.Uri.parse(overlayRoot)
                    val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, rootUri)
                    val overlayDoc = rootDoc?.findFile("ImageOverlay")
                    if (overlayDoc != null && overlayDoc.exists()) {
                        overlayDoc.listFiles().forEach { child ->
                            try {
                                if (child.isDirectory) {
                                    // 如果是目录，先删除目录内的所有文件
                                    child.listFiles().forEach { file ->
                                        try {
                                            file.delete()
                                        } catch (e: Exception) {
                                            android.util.Log.e("ConfigRepository", "SAF删除文件失败", e)
                                        }
                                    }
                                    // 再删除目录本身
                                    child.delete()
                                } else {
                                    // 如果是文件，直接删除
                                    child.delete()
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("ConfigRepository", "SAF删除子项失败", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ConfigRepository", "SAF清除缓存失败", e)
                }
            } else {
                // 传统文件模式
                val overlayRootFile = java.io.File(overlayRoot)
                if (overlayRootFile.exists()) {
                    overlayRootFile.listFiles()?.forEach { file ->
                        try {
                            if (file.isDirectory) {
                                file.deleteRecursively()
                            } else {
                                file.delete()
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ConfigRepository", "文件清除缓存失败", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ConfigRepository", "清除缓存总异常", e)
        }
    }
}