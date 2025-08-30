package com.example.imageoverlay.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import java.io.File

object ConfigPathUtil {
    private const val PREF_KEY_PATH = "config_save_path"
    private const val PREF_KEY_URI = "config_save_uri"
    private const val SUB_DIR = "ImageOverlay"

    fun getConfigRoot(context: Context): String {
        val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val uriStr = sp.getString(PREF_KEY_URI, null)
        if (!uriStr.isNullOrBlank()) {
            return uriStr
        }
        // 强制使用SAF，如果没有设置URI，返回空字符串
        return ""
    }

    fun getOverlayRoot(context: Context): String {
        val root = getConfigRoot(context)
        // 如果是 SAF URI，返回原始路径，调用方需要自己处理
        if (root.startsWith("content://")) {
            return root
        }
        return if (File(root).name == SUB_DIR) root else File(root, SUB_DIR).absolutePath
    }

    fun setConfigRoot(context: Context, path: String): Boolean {
        val file = File(path)
        val parentPath = if (file.name == SUB_DIR) file.parent ?: path else path
        val overlayDir = File(parentPath, SUB_DIR)
        if (!overlayDir.exists()) {
            if (!overlayDir.mkdirs()) return false
        }
        try {
            File(overlayDir, ".nomedia").createNewFile()
        } catch (_: Exception) {}
        val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        sp.edit().putString(PREF_KEY_PATH, parentPath).remove(PREF_KEY_URI).apply()
        return true
    }

    fun setConfigRootUri(context: Context, uri: Uri) {
        val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val oldUriStr = sp.getString(PREF_KEY_URI, null)
        
        // 保存新的URI
        sp.edit().putString(PREF_KEY_URI, uri.toString()).remove(PREF_KEY_PATH).apply()
        val rootDoc = DocumentFile.fromTreeUri(context, uri)
        val overlayDir = rootDoc?.findFile(SUB_DIR) ?: rootDoc?.createDirectory(SUB_DIR)
        
        // 在SAF目录中创建.nomedia文件
        if (overlayDir != null && overlayDir.exists()) {
            try {
                val nomediaFile = overlayDir.findFile(".nomedia")
                if (nomediaFile == null) {
                    overlayDir.createFile("text/plain", ".nomedia")
                    android.util.Log.d("ConfigPathUtil", "已在SAF目录创建.nomedia文件")
                }
            } catch (e: Exception) {
                android.util.Log.e("ConfigPathUtil", "创建.nomedia文件失败", e)
            }
        }
        
        // 如果有旧的配置路径，进行迁移
        if (!oldUriStr.isNullOrBlank() && oldUriStr != uri.toString()) {
            migrateConfigs(context, oldUriStr, uri.toString())
        }
    }
    
    private fun migrateConfigs(context: Context, oldUriStr: String, newUriStr: String) {
        try {
            val oldUri = Uri.parse(oldUriStr)
            val newUri = Uri.parse(newUriStr)
            val oldRootDoc = DocumentFile.fromTreeUri(context, oldUri)
            val newRootDoc = DocumentFile.fromTreeUri(context, newUri)
            
            if (oldRootDoc != null && newRootDoc != null) {
                val oldOverlayDoc = oldRootDoc.findFile(SUB_DIR)
                val newOverlayDoc = newRootDoc.findFile(SUB_DIR) ?: newRootDoc.createDirectory(SUB_DIR)
                
                if (oldOverlayDoc != null && oldOverlayDoc.exists() && newOverlayDoc != null) {
                    // 在新目录中创建.nomedia文件
                    try {
                        val nomediaFile = newOverlayDoc.findFile(".nomedia")
                        if (nomediaFile == null) {
                            newOverlayDoc.createFile("text/plain", ".nomedia")
                            android.util.Log.d("ConfigPathUtil", "迁移时已在新目录创建.nomedia文件")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ConfigPathUtil", "迁移时创建.nomedia文件失败", e)
                    }
                    
                    // 迁移config.json
                    val oldConfigFile = oldOverlayDoc.findFile("config.json")
                    if (oldConfigFile != null && oldConfigFile.exists()) {
                        val newConfigFile = newOverlayDoc.createFile("application/json", "config.json")
                        if (newConfigFile != null) {
                            val inputStream = context.contentResolver.openInputStream(oldConfigFile.uri)
                            val outputStream = context.contentResolver.openOutputStream(newConfigFile.uri, "wt")
                            inputStream?.use { input ->
                                outputStream?.use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                    
                    // 迁移所有组文件夹和图片
                    oldOverlayDoc.listFiles().forEach { oldGroupDoc ->
                        val groupName = oldGroupDoc.name
                        if (oldGroupDoc.isDirectory && groupName != null) {
                            val newGroupDoc = newOverlayDoc.createDirectory(groupName)
                            if (newGroupDoc != null) {
                                // 在组目录中创建.nomedia文件
                                try {
                                    val nomediaFile = newGroupDoc.findFile(".nomedia")
                                    if (nomediaFile == null) {
                                        newGroupDoc.createFile("text/plain", ".nomedia")
                                        android.util.Log.d("ConfigPathUtil", "已在组目录 $groupName 创建.nomedia文件")
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("ConfigPathUtil", "在组目录创建.nomedia文件失败", e)
                                }
                                
                                oldGroupDoc.listFiles().forEach { oldFile ->
                                    val fileName = oldFile.name
                                    if (fileName != null && fileName != ".nomedia") { // 跳过.nomedia文件
                                        val newFile = newGroupDoc.createFile("image/png", fileName)
                                        if (newFile != null) {
                                            val inputStream = context.contentResolver.openInputStream(oldFile.uri)
                                            val outputStream = context.contentResolver.openOutputStream(newFile.uri, "wt")
                                            inputStream?.use { input ->
                                                outputStream?.use { output ->
                                                    input.copyTo(output)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // 迁移完成后删除旧目录
                    try {
                        oldOverlayDoc.delete()
                    } catch (e: Exception) {
                        android.util.Log.e("ConfigPathUtil", "删除旧目录失败", e)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ConfigPathUtil", "配置迁移失败", e)
        }
    }

    fun getDefaultRoot(): String {
        return Environment.getExternalStorageDirectory().absolutePath
    }

    fun getGroupDir(context: Context, groupName: String): File {
        val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val uriStr = sp.getString(PREF_KEY_URI, null)
        if (!uriStr.isNullOrBlank()) {
            // 当使用 SAF 时，调用方应使用 DocumentFile API，不返回无效的 File 路径
            // 这里回退到 app 外部文件目录下的镜像目录，确保非 SAF 流程可用
            val fallbackRoot = getOverlayRoot(context)
            val groupDir = File(fallbackRoot, groupName)
            if (!groupDir.exists()) groupDir.mkdirs()
            try {
                File(groupDir, ".nomedia").createNewFile()
            } catch (_: Exception) {}
            return groupDir
        }
        val root = File(getOverlayRoot(context))
        val groupDir = File(root, groupName)
        if (!groupDir.exists()) groupDir.mkdirs()
        try {
            File(groupDir, ".nomedia").createNewFile()
        } catch (_: Exception) {}
        return groupDir
    }

    fun getConfigFile(context: Context): File {
        val overlayRoot = getOverlayRoot(context)
        val dir = File(overlayRoot)
        if (!dir.exists()) {
            dir.mkdirs()
            try {
                File(dir, ".nomedia").createNewFile()
            } catch (_: Exception) {}
        }
        return File(overlayRoot, "config.json")
    }

    fun checkAndFixRoot(context: Context) {
        val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val uriStr = sp.getString(PREF_KEY_URI, null)
        if (!uriStr.isNullOrBlank()) {
            val uri = Uri.parse(uriStr)
            val docFile = DocumentFile.fromTreeUri(context, uri)
            if (docFile == null || !docFile.exists() || !docFile.isDirectory) {
                // 如果SAF路径无效，清除设置，强制用户重新选择
                sp.edit().remove(PREF_KEY_URI).remove(PREF_KEY_PATH).apply()
            } else {
                // 确保现有目录都有.nomedia文件
                ensureNomediaFiles(context, docFile)
            }
        } else {
            // 如果没有设置SAF路径，不创建默认路径，强制用户选择
            // 这里不做任何操作，让用户必须选择SAF路径
        }
    }
    
    private fun ensureNomediaFiles(context: Context, rootDoc: DocumentFile) {
        try {
            val overlayDir = rootDoc.findFile(SUB_DIR)
            if (overlayDir != null && overlayDir.exists()) {
                // 在主目录创建.nomedia
                val nomediaFile = overlayDir.findFile(".nomedia")
                if (nomediaFile == null) {
                    overlayDir.createFile("text/plain", ".nomedia")
                    android.util.Log.d("ConfigPathUtil", "已为主目录创建.nomedia文件")
                }
                
                // 为每个组目录创建.nomedia
                overlayDir.listFiles().forEach { groupDoc ->
                    if (groupDoc.isDirectory) {
                        val groupNomedia = groupDoc.findFile(".nomedia")
                        if (groupNomedia == null) {
                            groupDoc.createFile("text/plain", ".nomedia")
                            android.util.Log.d("ConfigPathUtil", "已为组目录 ${groupDoc.name} 创建.nomedia文件")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ConfigPathUtil", "确保.nomedia文件失败", e)
        }
    }
} 