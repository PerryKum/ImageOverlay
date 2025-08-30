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
    private const val PREF_APP_BINDINGS = "app_bindings"
    private const val PREF_SETTINGS = "settings"
    private const val KEY_AUTO_START_OVERLAY = "auto_start_overlay"
    private var isServiceStarting = false // 防止服务重复启动

    fun load(context: Context) {
        val configFile = ConfigPathUtil.getConfigFile(context)
        val uriStr = ConfigPathUtil.getConfigRoot(context)
        if (uriStr.startsWith("content://")) {
            // SAF方式读取
            try {
                val rootUri = Uri.parse(uriStr)
                val rootDoc =
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(context, rootUri)
                // 统一在 ImageOverlay 目录下读写 config.json
                val overlayDoc = rootDoc?.findFile("ImageOverlay")
                    ?: rootDoc?.createDirectory("ImageOverlay")
                var configDoc = overlayDoc?.findFile("config.json")
                if (configDoc == null) {
                    configDoc = overlayDoc?.createFile("application/json", "config.json")
                    // 写入空数组
                    configDoc?.uri?.let { uri ->
                        context.contentResolver.openOutputStream(uri, "wt")
                            ?.use { it.write("[]".toByteArray()) }
                    }
                }
                val inputStream =
                    configDoc?.uri?.let { context.contentResolver.openInputStream(it) }
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
                groupList = mutableListOf()
            }
        } else {
            // 普通文件方式
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
        }
        // no-op: default config is stored separately in SharedPreferences
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
    fun handleAppLaunch(context: Context, packageName: String) {
        try {
            val group = getGroupByPackageName(packageName)
            if (group != null) {
                val defaultConfig = getGroupDefaultConfig(group.groupName)
                if (defaultConfig != null) {
                    // 检查是否自动开启遮罩
                    if (isAutoStartOverlayEnabled(context)) {
                        // 检查当前是否已经有相同的遮罩在运行
                        val currentDefaultConfig = getDefaultConfig(context)
                        val isSameConfig = currentDefaultConfig?.imageUri == defaultConfig.imageUri && isDefaultActive(context)
                        
                        if (!isSameConfig && !isServiceStarting) {
                            isServiceStarting = true
                            
                            // 第一步：先关闭当前遮罩服务
                            try {
                                val stopIntent = android.content.Intent(context, com.example.imageoverlay.OverlayService::class.java)
                                context.stopService(stopIntent)
                                setDefaultActive(context, false)
                                android.util.Log.d("ConfigRepository", "已停止当前遮罩服务")
                                
                                // 等待服务完全停止
                                Thread.sleep(50)
                            } catch (e: Exception) {
                                android.util.Log.e("ConfigRepository", "停止遮罩服务失败", e)
                            }
                            
                            // 第二步：延迟设置新的默认配置
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                try {
                                    // 设置为全局默认遮罩
                                    setDefaultConfig(context, group.groupName, defaultConfig)
                                    android.util.Log.d("ConfigRepository", "已设置新的默认配置")
                                    
                                    // 第三步：延迟启动新的遮罩服务
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        try {
                                            // 激活遮罩
                                            setDefaultActive(context, true)
                                            
                                            // 启动遮罩服务
                                            val intent = android.content.Intent(context, com.example.imageoverlay.OverlayService::class.java)
                                            intent.putExtra("imageUri", defaultConfig.imageUri)
                                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                                context.startForegroundService(intent)
                                            } else {
                                                context.startService(intent)
                                            }
                                            android.util.Log.d("ConfigRepository", "已启动新的遮罩服务")
                                        } catch (e: Exception) {
                                            android.util.Log.e("ConfigRepository", "启动遮罩服务失败", e)
                                        } finally {
                                            isServiceStarting = false
                                        }
                                    }, 100) // 100毫秒延迟启动新服务
                                } catch (e: Exception) {
                                    android.util.Log.e("ConfigRepository", "设置默认配置失败", e)
                                    isServiceStarting = false
                                }
                            }, 50) // 50毫秒延迟设置配置
                        }
                    } else {
                        // 仅设置全局默认遮罩，不启动服务
                        setDefaultConfig(context, group.groupName, defaultConfig)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ConfigRepository", "处理应用启动事件失败: $packageName", e)
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