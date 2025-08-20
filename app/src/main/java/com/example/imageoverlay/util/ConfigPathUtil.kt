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
        val custom = sp.getString(PREF_KEY_PATH, null)
        return if (!custom.isNullOrBlank()) custom else getDefaultRoot()
    }

    fun getOverlayRoot(context: Context): String {
        val root = getConfigRoot(context)
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
        sp.edit().putString(PREF_KEY_URI, uri.toString()).remove(PREF_KEY_PATH).apply()
        val rootDoc = DocumentFile.fromTreeUri(context, uri)
        rootDoc?.findFile(SUB_DIR) ?: rootDoc?.createDirectory(SUB_DIR)
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
                setConfigRoot(context, getDefaultRoot())
            }
        } else {
            val path = getOverlayRoot(context)
            val file = File(path)
            if (!file.exists()) {
                file.mkdirs()
                try {
                    File(file, ".nomedia").createNewFile()
                } catch (_: Exception) {}
            }
        }
    }
} 