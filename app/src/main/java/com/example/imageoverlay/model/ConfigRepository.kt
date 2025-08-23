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