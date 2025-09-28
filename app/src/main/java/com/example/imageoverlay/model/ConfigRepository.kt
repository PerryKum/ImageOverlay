package com.example.imageoverlay.model

import android.content.Context
import android.net.Uri
import com.example.imageoverlay.util.ConfigPathUtil
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

object ConfigRepository {
    private val gson = Gson()
    private var groupList: MutableList<Group> = mutableListOf()
    private const val PREF_DEFAULT = "default_config"
    private const val KEY_DEFAULT_NAME = "name"
    private const val KEY_DEFAULT_URI = "uri"
    private const val KEY_DEFAULT_GROUP = "group"
    private const val KEY_DEFAULT_ACTIVE = "active"
    private const val KEY_DEFAULT_OPACITY = "opacity"
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
        try {
            val configFile = ConfigPathUtil.getConfigFile(context)
            val uriStr = ConfigPathUtil.getConfigRoot(context)
            
            if (uriStr.isBlank()) {
                android.util.Log.w("ConfigRepository", "配置路径为空，使用空列表")
                groupList = mutableListOf()
                return
            }
            
            if (uriStr.startsWith("content://")) {
                // SAF方式读取
                try {
                    val rootUri = Uri.parse(uriStr)
                    val rootDoc =
                        androidx.documentfile.provider.DocumentFile.fromTreeUri(context, rootUri)
                    
                    if (rootDoc == null || !rootDoc.exists()) {
                        android.util.Log.w("ConfigRepository", "SAF根目录不存在，使用空列表")
                        groupList = mutableListOf()
                        return
                    }
                    
                    // 检查权限是否有效
                    if (!hasValidSafPermission(context, rootUri)) {
                        android.util.Log.w("ConfigRepository", "SAF权限无效，使用空列表")
                        groupList = mutableListOf()
                        return
                    }
                    
                    // 统一在 ImageOverlay 目录下读写 config.json
                    val overlayDoc = rootDoc.findFile("ImageOverlay")
                        ?: rootDoc.createDirectory("ImageOverlay")
                    
                    if (overlayDoc == null) {
                        android.util.Log.w("ConfigRepository", "无法创建ImageOverlay目录，使用空列表")
                        groupList = mutableListOf()
                        return
                    }
                    
                    var configDoc = overlayDoc.findFile("config.json")
                    if (configDoc == null) {
                        configDoc = overlayDoc.createFile("application/json", "config.json")
                        // 写入空数组
                        configDoc?.uri?.let { uri ->
                            context.contentResolver.openOutputStream(uri, "wt")
                                ?.use { it.write("[]".toByteArray()) }
                        }
                    }
                    
                    if (configDoc == null) {
                        android.util.Log.w("ConfigRepository", "无法创建config.json文件，使用空列表")
                        groupList = mutableListOf()
                        return
                    }
                    
                    val inputStream =
                        configDoc.uri?.let { context.contentResolver.openInputStream(it) }
                    if (inputStream != null) {
                        val reader = InputStreamReader(inputStream)
                        val json = reader.readText()
                        reader.close()
                        inputStream.close()
                        if (json.isNotBlank()) {
                            val type = object : TypeToken<MutableList<Group>>() {}.type
                            groupList = gson.fromJson(json, type) ?: mutableListOf()
                        } else {
                            groupList = mutableListOf()
                        }
                    } else {
                        groupList = mutableListOf()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ConfigRepository", "SAF方式读取配置失败", e)
                    groupList = mutableListOf()
                }
            } else {
                // 普通文件方式
                try {
                    if (!configFile.exists()) {
                        configFile.writeText("[]")
                    }
                    if (configFile.exists()) {
                        val json = configFile.readText()
                        if (json.isNotBlank()) {
                            val type = object : TypeToken<MutableList<Group>>() {}.type
                            groupList = gson.fromJson(json, type) ?: mutableListOf()
                        } else {
                            groupList = mutableListOf()
                        }
                    } else {
                        groupList = mutableListOf()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ConfigRepository", "普通文件方式读取配置失败", e)
                    groupList = mutableListOf()
                }
            }
            android.util.Log.d("ConfigRepository", "配置加载完成，共${groupList.size}个组")
        } catch (e: Exception) {
            android.util.Log.e("ConfigRepository", "配置加载出现未知异常", e)
            groupList = mutableListOf()
        }
        // no-op: default config is stored separately in SharedPreferences
    }
    
    private fun hasValidSafPermission(context: Context, uri: Uri): Boolean {
        return try {
            // 检查是否有持久化权限
            val flags = context.contentResolver.getPersistedUriPermissions()
            val hasPermission = flags.any { permission ->
                permission.uri == uri && 
                (permission.isReadPermission || permission.isWritePermission)
            }
            
            if (!hasPermission) {
                android.util.Log.w("ConfigRepository", "没有SAF持久化权限")
                return false
            }
            
            // 尝试访问目录来验证权限
            val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
            docFile?.exists() == true && docFile.isDirectory
        } catch (e: Exception) {
            android.util.Log.e("ConfigRepository", "SAF权限检查失败", e)
            false
        }
    }

    fun save(context: Context) {
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
                var configDoc = overlayDoc?.findFile("config.json")
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

    fun setDefaultConfig(context: Context, groupName: String, config: Config) {
        val sp = context.getSharedPreferences(PREF_DEFAULT, Context.MODE_PRIVATE)
        sp.edit()
            .putString(KEY_DEFAULT_NAME, config.configName)
            .putString(KEY_DEFAULT_URI, config.imageUri)
            .putString(KEY_DEFAULT_GROUP, groupName)
            .apply()
    }

    fun getDefaultConfig(context: Context): Config? {
        val sp = context.getSharedPreferences(PREF_DEFAULT, Context.MODE_PRIVATE)
        val name = sp.getString(KEY_DEFAULT_NAME, null)
        val uri = sp.getString(KEY_DEFAULT_URI, null)
        return if (!name.isNullOrBlank() && !uri.isNullOrBlank()) Config(name, uri, false) else null
    }

    fun getDefaultGroupName(context: Context): String? {
        val sp = context.getSharedPreferences(PREF_DEFAULT, Context.MODE_PRIVATE)
        return sp.getString(KEY_DEFAULT_GROUP, null)
    }

    fun setDefaultActive(context: Context, active: Boolean) {
        val sp = context.getSharedPreferences(PREF_DEFAULT, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_DEFAULT_ACTIVE, active).apply()
    }

    fun isDefaultActive(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREF_DEFAULT, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_DEFAULT_ACTIVE, false)
    }

    fun setDefaultOpacity(context: Context, opacity: Int) {
        val sp = context.getSharedPreferences(PREF_DEFAULT, Context.MODE_PRIVATE)
        sp.edit().putInt(KEY_DEFAULT_OPACITY, opacity).apply()
    }

    fun getDefaultOpacity(context: Context): Int {
        val sp = context.getSharedPreferences(PREF_DEFAULT, Context.MODE_PRIVATE)
        return sp.getInt(KEY_DEFAULT_OPACITY, 100)
    }

    fun clearDefaultConfig(context: Context) {
        val sp = context.getSharedPreferences(PREF_DEFAULT, Context.MODE_PRIVATE)
        sp.edit()
            .remove(KEY_DEFAULT_NAME)
            .remove(KEY_DEFAULT_URI)
            .remove(KEY_DEFAULT_GROUP)
            .putBoolean(KEY_DEFAULT_ACTIVE, false)
            .apply()
    }

    // 新增：设置组的默认遮罩
    fun setGroupDefaultConfig(groupName: String, configName: String) {
        val group = groupList.find { it.groupName == groupName }
        group?.let {
            // 清除其他配置的默认标记
            it.configs.forEach { config -> config.isDefault = false }
            // 设置指定配置为默认
            it.configs.find { config -> config.configName == configName }?.isDefault = true
            it.defaultConfigName = configName
        }
    }

    // 新增：获取组的默认遮罩
    fun getGroupDefaultConfig(groupName: String): Config? {
        val group = groupList.find { it.groupName == groupName }
        return group?.configs?.find { it.isDefault } ?: group?.configs?.firstOrNull()
    }

    // 新增：绑定应用到组
    fun bindAppToGroup(groupName: String, packageName: String) {
        val group = groupList.find { it.groupName == groupName }
        group?.boundPackageName = packageName
    }

    // 新增：解绑应用
    fun unbindAppFromGroup(groupName: String) {
        val group = groupList.find { it.groupName == groupName }
        group?.boundPackageName = null
    }

    // 新增：根据包名获取绑定的组
    fun getGroupByPackageName(packageName: String): Group? {
        return groupList.find { it.boundPackageName == packageName }
    }

    // 新增：处理应用启动事件
    fun handleAppLaunch(context: Context, packageName: String, isLauncher: Boolean = false) {
        try {
            val currentTime = System.currentTimeMillis()
            
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
            
            if (isLauncher) {
                // 检测到桌面/启动器
                android.util.Log.d("ConfigRepository", "检测到桌面/启动器")
                lastOperationTime = currentTime
                
                // 停止悬浮球服务
                try {
                    val floatingBallIntent = android.content.Intent(context, com.example.imageoverlay.FloatingBallService::class.java)
                    context.stopService(floatingBallIntent)
                    android.util.Log.d("ConfigRepository", "桌面检测：停止悬浮球服务")
                } catch (e: Exception) {
                    android.util.Log.e("ConfigRepository", "停止悬浮球服务失败", e)
                }
                
                // 根据自动开启开关决定是否关闭遮罩
                if (isAutoStartOverlayEnabled(context)) {
                    // 自动开启开关开启，关闭遮罩
                    turnOffOverlaySafely(context)
                } else {
                    // 自动开启开关关闭，只设置状态不关闭遮罩
                    setDefaultActive(context, false)
                    android.util.Log.d("ConfigRepository", "自动开启开关关闭，只设置状态不关闭遮罩")
                }
                return
            }
            
            val group = getGroupByPackageName(packageName)
            if (group != null) {
                val defaultConfig = getGroupDefaultConfig(group.groupName)
                if (defaultConfig != null) {
                    // 始终切换为组默认遮罩（不管自动开启开关是否开启）
                    android.util.Log.d("ConfigRepository", "切换到组默认遮罩: ${group.groupName}")
                    lastOperationTime = currentTime
                    switchToGroupDefault(context, group.groupName, defaultConfig)
                }
                
                // 启动悬浮球服务（如果启用）
                if (isFloatingBallEnabled(context)) {
                    try {
                        val floatingBallIntent = android.content.Intent(context, com.example.imageoverlay.FloatingBallService::class.java)
                        floatingBallIntent.putExtra("packageName", packageName)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            context.startForegroundService(floatingBallIntent)
                        } else {
                            context.startService(floatingBallIntent)
                        }
                        android.util.Log.d("ConfigRepository", "启动悬浮球服务: $packageName")
                    } catch (e: Exception) {
                        android.util.Log.e("ConfigRepository", "启动悬浮球服务失败", e)
                    }
                } else {
                    android.util.Log.d("ConfigRepository", "悬浮球功能已禁用，跳过启动")
                }
            } else {
                // 没有绑定组的应用，停止悬浮球服务
                try {
                    val floatingBallIntent = android.content.Intent(context, com.example.imageoverlay.FloatingBallService::class.java)
                    context.stopService(floatingBallIntent)
                    android.util.Log.d("ConfigRepository", "停止悬浮球服务: $packageName")
                } catch (e: Exception) {
                    android.util.Log.e("ConfigRepository", "停止悬浮球服务失败", e)
                }
                
                android.util.Log.d("ConfigRepository", "应用 $packageName 未绑定组")
                lastOperationTime = currentTime
                
                // 根据自动开启开关决定是否关闭遮罩
                if (isAutoStartOverlayEnabled(context)) {
                    // 自动开启开关开启，关闭遮罩
                    turnOffOverlaySafely(context)
                } else {
                    // 自动开启开关关闭，只设置状态不关闭遮罩
                    setDefaultActive(context, false)
                    android.util.Log.d("ConfigRepository", "自动开启开关关闭，只设置状态不关闭遮罩")
                }
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
        
        // 检查当前是否真的有遮罩在运行
        if (!isDefaultActive(context)) {
            android.util.Log.d("ConfigRepository", "当前没有遮罩运行，跳过关闭操作")
            return
        }
        
        try {
            isServiceStopping = true
            android.util.Log.d("ConfigRepository", "开始安全关闭遮罩")
            
            // 停止遮罩服务
            val stopIntent = android.content.Intent(context, com.example.imageoverlay.OverlayService::class.java)
            context.stopService(stopIntent)
            setDefaultActive(context, false)
            
            // 等待服务完全停止
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    android.util.Log.d("ConfigRepository", "遮罩已安全关闭")
                } catch (e: Exception) {
                    android.util.Log.e("ConfigRepository", "关闭遮罩后处理失败", e)
                } finally {
                    isServiceStopping = false
                }
            }, 500) // 增加到500毫秒延迟确保服务完全停止
        } catch (e: Exception) {
            android.util.Log.e("ConfigRepository", "安全关闭遮罩失败", e)
            isServiceStopping = false
        }
    }
    
     /**
      * 切换到组默认遮罩，根据自动开启开关决定是否显示
      */
     private fun switchToGroupDefault(context: Context, groupName: String, defaultConfig: com.example.imageoverlay.model.Config) {
         try {
             // 检查当前是否已经有相同的遮罩在运行
             val currentDefaultConfig = getDefaultConfig(context)
             val isSameConfig = currentDefaultConfig?.imageUri == defaultConfig.imageUri && isDefaultActive(context)
             
             if (isSameConfig) {
                 android.util.Log.d("ConfigRepository", "相同配置已运行，跳过切换: ${groupName}/${defaultConfig.configName}")
                 return
             }
             
             // 1. 先切换默认遮罩配置（独立逻辑）
             switchDefaultConfig(context, groupName, defaultConfig)
             
             // 2. 根据自动开启开关决定是否显示遮罩
             if (isAutoStartOverlayEnabled(context)) {
                 // 自动开启开关开启，启动遮罩
                 if (!isServiceStarting) {
                     turnOnOverlaySafely(context)
                 }
             } else {
                 // 自动开启开关关闭，只设置配置不启动遮罩
                 setDefaultActive(context, false)
                 android.util.Log.d("ConfigRepository", "自动开启开关关闭，只设置配置不启动遮罩")
             }
         } catch (e: Exception) {
             android.util.Log.e("ConfigRepository", "切换到组默认遮罩失败", e)
         }
     }
     
     /**
      * 切换默认遮罩配置（独立逻辑）
      */
     fun switchDefaultConfig(context: Context, groupName: String, defaultConfig: com.example.imageoverlay.model.Config) {
         try {
             // 1. 清除所有组的激活状态
             groupList.forEach { group ->
                 group.configs.forEach { config -> config.active = false }
             }
             
             // 2. 激活当前组的默认配置
             val currentGroup = groupList.find { it.groupName == groupName }
             currentGroup?.let { group ->
                 val targetConfig = group.configs.find { it.configName == defaultConfig.configName }
                 targetConfig?.active = true
                 android.util.Log.d("ConfigRepository", "激活组配置: ${groupName}/${defaultConfig.configName}")
             }
             
             // 3. 设置为全局默认遮罩
             setDefaultConfig(context, groupName, defaultConfig)
             android.util.Log.d("ConfigRepository", "已设置组默认遮罩: ${groupName}/${defaultConfig.configName}")
             
             // 4. 保存配置
             save(context)
         } catch (e: Exception) {
             android.util.Log.e("ConfigRepository", "切换默认遮罩配置失败", e)
         }
     }
     
     /**
      * 同步更新组配置状态，确保手动切换和自动切换状态一致
      */
     fun syncGroupConfigStates(context: Context, groupName: String, defaultConfig: com.example.imageoverlay.model.Config) {
         try {
             // 1. 清除所有组的激活状态
             groupList.forEach { group ->
                 group.configs.forEach { config -> config.active = false }
             }
             
             // 2. 激活当前组的默认配置
             val currentGroup = groupList.find { it.groupName == groupName }
             currentGroup?.let { group ->
                 val targetConfig = group.configs.find { it.configName == defaultConfig.configName }
                 targetConfig?.active = true
                 android.util.Log.d("ConfigRepository", "激活组配置: ${groupName}/${defaultConfig.configName}")
             }
             
             // 3. 设置为全局默认遮罩
             setDefaultConfig(context, groupName, defaultConfig)
             android.util.Log.d("ConfigRepository", "已设置组默认遮罩: ${groupName}/${defaultConfig.configName}")
             
             // 4. 保存配置
             save(context)
         } catch (e: Exception) {
             android.util.Log.e("ConfigRepository", "同步组配置状态失败", e)
         }
     }
     
    
     /**
      * 安全地开启遮罩，确保之前的遮罩完全关闭后再开启新的
      */
     private fun turnOnOverlaySafely(context: Context) {
         if (isServiceStarting || isServiceStopping) {
             android.util.Log.d("ConfigRepository", "服务正在启动或停止中，跳过开启操作")
             return
         }
         
         // 获取当前默认配置
         val defaultConfig = getDefaultConfig(context)
         if (defaultConfig == null || defaultConfig.imageUri.isBlank()) {
             android.util.Log.w("ConfigRepository", "默认配置为空，跳过开启操作")
             return
         }
         
         try {
             isServiceStarting = true
             android.util.Log.d("ConfigRepository", "开始安全开启遮罩: ${defaultConfig.configName}")
             
             // 第一步：先关闭当前遮罩服务
             try {
                 val stopIntent = android.content.Intent(context, com.example.imageoverlay.OverlayService::class.java)
                 context.stopService(stopIntent)
                 android.util.Log.d("ConfigRepository", "已停止当前遮罩服务")
             } catch (e: Exception) {
                 android.util.Log.e("ConfigRepository", "停止遮罩服务失败", e)
             }
             
             // 第二步：延迟启动新的遮罩服务
             android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                 try {
                     // 再次检查服务状态，防止重复启动
                     if (isServiceStarting && !isServiceStopping) {
                         // 激活遮罩
                         setDefaultActive(context, true)
                         
                         // 启动遮罩服务
                         val intent = android.content.Intent(context, com.example.imageoverlay.OverlayService::class.java)
                         intent.putExtra("imageUri", defaultConfig.imageUri)
                         intent.putExtra("opacity", getDefaultOpacity(context))
                         if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                             context.startForegroundService(intent)
                         } else {
                             context.startService(intent)
                         }
                         android.util.Log.d("ConfigRepository", "已启动新的遮罩服务")
                     } else {
                         android.util.Log.d("ConfigRepository", "服务状态已改变，取消启动")
                     }
                 } catch (e: Exception) {
                     android.util.Log.e("ConfigRepository", "启动遮罩服务失败", e)
                 } finally {
                     isServiceStarting = false
                 }
             }, 500) // 增加到500毫秒延迟启动新服务，确保之前的服务完全停止
         } catch (e: Exception) {
             android.util.Log.e("ConfigRepository", "安全开启遮罩失败", e)
             isServiceStarting = false
         }
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

    fun addGroup(context: Context, group: Group) {
        groupList.add(group)
        save(context)
        load(context)
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