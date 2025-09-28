package com.example.imageoverlay

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.imageoverlay.model.ConfigRepository
import com.example.imageoverlay.util.PermissionUtil

class QuickUseFragment : Fragment() {
    private var imageUri: Uri? = null
    private lateinit var imageView: ImageView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private var isOverlayActive = false
    private val PREFS = "quick_use_prefs"
    private val KEY_OVERLAY_ACTIVE = "is_overlay_active"
    private val KEY_IMAGE_URI = "image_uri"



    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_quick_use, container, false)
        imageView = view.findViewById(R.id.imageView)
        val btnSelect = view.findViewById<Button>(R.id.btnSelect)
        btnStart = view.findViewById(R.id.btnStart)
        btnStop = view.findViewById(R.id.btnStop)

        // 恢复状态 - 改进的状态恢复逻辑
        restoreState()
        updateButtonState()

        btnSelect.setOnClickListener {
            if (isOverlayActive) {
                Toast.makeText(requireContext(), "请先关闭遮罩", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/png"
                // 添加GIF支持
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/gif"))
                // 请求持久化权限
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(intent, 100)
        }

        btnStart.setOnClickListener {
            // 再次检查状态，确保UI状态是最新的
            if (isOverlayActive) {
                Toast.makeText(requireContext(), "遮罩已在运行", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // 检查是否有预设配置激活
            val hasActivePreset = ConfigRepository.getGroups().any { group ->
                group.configs.any { it.active }
            }
            if (hasActivePreset) {
                Toast.makeText(requireContext(), "请先关闭预设配置", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (imageUri != null) {
                if (PermissionUtil.checkOverlayPermission(requireContext())) {
                    val intent = Intent(requireContext(), OverlayService::class.java)
                    intent.putExtra("imageUri", imageUri.toString())
                    // 不传递透明度参数，让OverlayService使用全局透明度设置
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        requireContext().startForegroundService(intent)
                    } else {
                        requireContext().startService(intent)
                    }
                    isOverlayActive = true
                    saveState()
                    updateButtonState()
                    Toast.makeText(requireContext(), "遮罩已启动", Toast.LENGTH_SHORT).show()
                } else {
                    PermissionUtil.openOverlayPermissionSettings(requireContext())
                    Toast.makeText(requireContext(), "需要悬浮窗权限，请授权后重试", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(requireContext(), "请先选择图片", Toast.LENGTH_SHORT).show()
            }
        }

        btnStop.setOnClickListener {
            // 再次检查状态，确保UI状态是最新的
            if (!isOverlayActive) {
                Toast.makeText(requireContext(), "遮罩未在运行", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // 停止服务
            val intent = Intent(requireContext(), OverlayService::class.java)
            requireContext().stopService(intent)
            isOverlayActive = false
            saveState()
            updateButtonState()
            Toast.makeText(requireContext(), "遮罩已停止", Toast.LENGTH_SHORT).show()
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        // 简单恢复状态
        restoreState()
        updateButtonState()
    }
    


    private fun restoreState() {
        try {
            val sp = requireContext().getSharedPreferences(PREFS, 0)
            val imageUriStr = sp.getString(KEY_IMAGE_URI, null)
            
            // 恢复图片URI
            imageUri = if (imageUriStr != null) {
                try {
                    Uri.parse(imageUriStr)
                } catch (e: Exception) {
                    android.util.Log.e("QuickUseFragment", "解析图片URI失败", e)
                    null
                }
            } else null
            
            // 设置图片显示
            if (imageUri != null) {
                try {
                    imageView.setImageURI(imageUri)
                } catch (e: Exception) {
                    android.util.Log.e("QuickUseFragment", "设置图片显示失败", e)
                    imageUri = null
                }
            }
            
            // 关键：检查服务是否真的在运行，而不是盲目相信保存的状态
            val serviceRunning = isOverlayServiceRunning()
            isOverlayActive = serviceRunning
            
            // 如果保存的状态与实际不符，立即同步
            val savedIsOverlayActive = sp.getBoolean(KEY_OVERLAY_ACTIVE, false)
            if (savedIsOverlayActive != serviceRunning) {
                android.util.Log.w("QuickUseFragment", "状态不一致，同步: 保存=$savedIsOverlayActive, 实际=$serviceRunning")
                saveState()
            }
            
            android.util.Log.d("QuickUseFragment", "状态恢复完成: isOverlayActive=$isOverlayActive, imageUri=${imageUri?.toString()}")
        } catch (e: Exception) {
            android.util.Log.e("QuickUseFragment", "状态恢复失败", e)
            // 恢复失败时重置状态
            isOverlayActive = false
            imageUri = null
            saveState()
        }
    }

    private fun saveState() {
        try {
            val sp = requireContext().getSharedPreferences(PREFS, 0)
            sp.edit()
                .putBoolean(KEY_OVERLAY_ACTIVE, isOverlayActive)
                .putString(KEY_IMAGE_URI, imageUri?.toString())
                .apply()
            android.util.Log.d("QuickUseFragment", "状态已保存: isOverlayActive=$isOverlayActive")
        } catch (e: Exception) {
            android.util.Log.e("QuickUseFragment", "保存状态失败", e)
        }
    }

    private fun isOverlayServiceRunning(): Boolean {
        return try {
            // 方法1：检查前台服务通知（最可靠）
            val notificationManager = requireContext().getSystemService(android.app.NotificationManager::class.java)
            val activeNotifications = notificationManager.activeNotifications
            val hasNotification = activeNotifications.any { 
                it.packageName == requireContext().packageName && it.id == 1
            }
            
            if (hasNotification) {
                android.util.Log.d("QuickUseFragment", "通过通知检测到服务运行")
                return true
            }
            
            // 方法2：检查运行的服务（备用方案）
            try {
                val manager = requireContext().getSystemService(android.app.ActivityManager::class.java)
                val runningServices = manager.getRunningServices(Integer.MAX_VALUE)
                val isServiceInList = runningServices.any { 
                    it.service.className == "com.example.imageoverlay.OverlayService" 
                }
                
                if (isServiceInList) {
                    android.util.Log.d("QuickUseFragment", "通过服务列表检测到服务运行")
                    return true
                }
            } catch (e: Exception) {
                android.util.Log.w("QuickUseFragment", "服务列表检查失败，使用通知检查结果", e)
            }
            
            android.util.Log.d("QuickUseFragment", "服务未运行")
            false
        } catch (e: Exception) {
            android.util.Log.e("QuickUseFragment", "检查服务状态失败", e)
            false
        }
    }

    private fun hasUriPermission(uri: Uri): Boolean {
        return try {
            // 检查是否有持久化权限
            val flags = requireContext().contentResolver.getPersistedUriPermissions()
            val hasPermission = flags.any { permission ->
                permission.uri == uri && permission.isReadPermission
            }
            
            if (!hasPermission) {
                android.util.Log.w("QuickUseFragment", "没有持久化权限: $uri")
                return false
            }
            
            // 尝试访问URI来验证权限是否真的有效
            requireContext().contentResolver.openInputStream(uri)?.use {
                // 如果能成功打开，说明权限有效
                true
            } ?: false
        } catch (e: Exception) {
            android.util.Log.e("QuickUseFragment", "检查URI权限失败: $uri", e)
            false
        }
    }

    private fun updateButtonState() {
        btnStart.isEnabled = !isOverlayActive
        btnStop.isEnabled = isOverlayActive
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == Activity.RESULT_OK) {
            try {
            imageUri = data?.data
                if (imageUri != null) {
                    // 获取持久化权限，这是关键！
                    try {
                        requireContext().contentResolver.takePersistableUriPermission(
                            imageUri!!,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                        android.util.Log.d("QuickUseFragment", "已获取图片URI持久化权限")
                    } catch (e: SecurityException) {
                        android.util.Log.e("QuickUseFragment", "无法获取持久化权限", e)
                        Toast.makeText(requireContext(), "无法获取图片访问权限，请重新选择", Toast.LENGTH_SHORT).show()
                        imageUri = null
                        return
                    }
                    
                    // 验证图片是否可以正常加载
                    try {
            imageView.setImageURI(imageUri)
                        // 检查是否成功设置
                        if (imageView.drawable == null) {
                            throw Exception("图片加载失败")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("QuickUseFragment", "图片加载验证失败", e)
                        Toast.makeText(requireContext(), "图片加载失败，请选择其他图片", Toast.LENGTH_SHORT).show()
                        imageUri = null
                        return
                    }
                    
                    saveState()
                    android.util.Log.d("QuickUseFragment", "图片选择成功: ${imageUri?.toString()}")
                }
            } catch (e: Exception) {
                android.util.Log.e("QuickUseFragment", "处理图片选择结果失败", e)
                Toast.makeText(requireContext(), "图片选择失败，请重试", Toast.LENGTH_SHORT).show()
                imageUri = null
            }
        }
    }
} 