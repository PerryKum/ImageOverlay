package com.example.imageoverlay

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.fragment.app.Fragment
import com.example.imageoverlay.util.ConfigPathUtil
import com.example.imageoverlay.model.ConfigRepository
import com.example.imageoverlay.util.PermissionUtil

class MainActivity : AppCompatActivity() {
    private val REQUEST_CODE_PICK_DIR = 1001
    private var isDialogShowing = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.imageoverlay.util.ConfigPathUtil.checkAndFixRoot(this)
        setContentView(R.layout.activity_main)
        
        // 检查使用情况访问权限
        if (!com.example.imageoverlay.util.UsagePermissionUtil.hasUsageStatsPermission(this)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("需要权限")
                .setMessage("应用需要访问使用情况权限来监听应用启动。请在设置中授权。")
                .setPositiveButton("去设置") { _, _ ->
                    com.example.imageoverlay.util.UsagePermissionUtil.openUsageStatsSettings(this)
                }
                .setNegativeButton("取消", null)
                .show()
        }
        
        // 启动应用监听服务（确保在后台持续运行）
        AppLaunchListenerService.startService(this)
        android.util.Log.d("MainActivity", "应用已启动，监听服务已启动")
        
        // 如果之前有活跃的遮罩，恢复它
        if (com.example.imageoverlay.model.ConfigRepository.isDefaultActive(this)) {
            val defaultConfig = com.example.imageoverlay.model.ConfigRepository.getDefaultConfig(this)
            if (defaultConfig != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val intent = android.content.Intent(this, com.example.imageoverlay.OverlayService::class.java)
                    intent.putExtra("imageUri", defaultConfig.imageUri)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    android.util.Log.d("MainActivity", "已恢复之前的遮罩")
                }, 1000) // 延迟1秒恢复
            }
        }
        
        // 检查是否已设置SAF路径
        val configRoot = com.example.imageoverlay.util.ConfigPathUtil.getConfigRoot(this)
        if (configRoot.isNotBlank()) {
            com.example.imageoverlay.model.ConfigRepository.load(this)
            setupNavigation()
        } else if (!isDialogShowing) {
            // 如果没有设置SAF路径且对话框未显示，强制用户选择
            showForcePickDirectoryDialog()
        }
    }
    
    private fun setupNavigation() {
        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_quick_use -> {
                    switchFragment(QuickUseFragment())
                    true
                }
                R.id.nav_config -> {
                    switchFragment(ConfigFragment())
                    true
                }
                R.id.nav_settings -> {
                    switchFragment(SettingsFragment())
                    true
                }
                else -> false
            }
        }
        // 默认显示快速使用
        bottomNav.selectedItemId = R.id.nav_quick_use
    }
    
    private fun showForcePickDirectoryDialog() {
        if (isDialogShowing) return
        isDialogShowing = true
        
        android.app.AlertDialog.Builder(this)
            .setTitle("选择保存路径")
            .setMessage("应用需要选择一个目录来保存配置和图片文件。请选择一个合适的目录。")
            .setCancelable(false)
            .setPositiveButton("选择目录") { _, _ ->
                isDialogShowing = false
                pickDirectory()
            }
            .setOnDismissListener {
                isDialogShowing = false
            }
            .show()
    }
    
    private fun pickDirectory() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(intent, REQUEST_CODE_PICK_DIR)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_DIR && resultCode == android.app.Activity.RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            
            // 设置新的配置路径（会自动迁移配置）
            com.example.imageoverlay.util.ConfigPathUtil.setConfigRootUri(this, uri)
            
            // 重新加载配置数据
            com.example.imageoverlay.model.ConfigRepository.load(this)
            
            // 设置导航并显示主界面
            setupNavigation()
            
            android.widget.Toast.makeText(this, "已设置保存目录并迁移配置", android.widget.Toast.LENGTH_SHORT).show()
        } else if (requestCode == REQUEST_CODE_PICK_DIR && resultCode == android.app.Activity.RESULT_CANCELED) {
            // 如果用户取消了选择，检查是否已经有路径设置
            val configRoot = com.example.imageoverlay.util.ConfigPathUtil.getConfigRoot(this)
            if (configRoot.isBlank() && !isDialogShowing) {
                // 只有在没有设置路径且对话框未显示时才再次显示对话框
                showForcePickDirectoryDialog()
            }
        }
    }

    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
} 