package com.example.imageoverlay

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.imageoverlay.adapter.ConfigAdapter
import com.example.imageoverlay.model.Config
import com.example.imageoverlay.model.Group
import com.example.imageoverlay.model.ConfigRepository
import com.example.imageoverlay.util.PermissionUtil
import com.example.imageoverlay.util.ConfigPathUtil
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class GroupDetailFragment : Fragment() {
    private var groupName: String? = null
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ConfigAdapter
    private lateinit var tvGroupTitle: TextView
    private lateinit var btnAddConfig: ImageButton
    private var configList: MutableList<Config> = mutableListOf()
    private var selectedImageUri: Uri? = null
    private var addConfigDialog: AlertDialog? = null
    private var ivPreview: ImageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val group = arguments?.getSerializable("group") as? Group
        groupName = group?.groupName
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_group_detail, container, false)
        tvGroupTitle = view.findViewById(R.id.tvGroupTitle)
        btnAddConfig = view.findViewById(R.id.btnAddConfig)
        recyclerView = view.findViewById(R.id.recyclerViewConfigs)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        refreshConfigList()
        adapter = ConfigAdapter(requireContext(), configList, { idx ->
            onConfigStatusClick(idx)
        }, { idx ->
            onConfigItemClick(idx)
        }, { idx ->
            showConfigContextMenu(idx)
        }, getCurrentGroup()?.defaultConfigName)
        recyclerView.adapter = adapter
        tvGroupTitle.text = groupName ?: "组"
        btnAddConfig.setOnClickListener { showAddConfigDialog() }
        val btnRefreshConfig = view.findViewById<ImageButton>(R.id.btnRefreshConfig)
        btnRefreshConfig.setOnClickListener {
            com.example.imageoverlay.model.ConfigRepository.load(requireContext())
            refreshConfigList()
            adapter = ConfigAdapter(requireContext(), configList, { idx ->
                onConfigStatusClick(idx)
            }, { idx ->
                onConfigItemClick(idx)
            }, { idx ->
                showConfigContextMenu(idx)
            }, getCurrentGroup()?.defaultConfigName)
            recyclerView.adapter = adapter
            android.widget.Toast.makeText(requireContext(), "已刷新", android.widget.Toast.LENGTH_SHORT).show()
        }
        return view
    }

    private fun getCurrentGroup(): Group? {
        return groupName?.let { name ->
            ConfigRepository.getGroups().find { it.groupName == name }
        }
    }

    private fun refreshConfigList() {
        configList = getCurrentGroup()?.configs ?: mutableListOf()
    }

    private fun showAddConfigDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_config, null)
        ivPreview = dialogView.findViewById(R.id.ivPreview)
        val btnSelectImage = dialogView.findViewById<Button>(R.id.btnSelectImage)
        val etConfigName = dialogView.findViewById<EditText>(R.id.etConfigName)
        val btnSaveConfig = dialogView.findViewById<Button>(R.id.btnSaveConfig)
        selectedImageUri = null
        btnSelectImage.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "image/png"
            startActivityForResult(intent, 200)
        }
        addConfigDialog = AlertDialog.Builder(requireContext())
            .setTitle("新建配置")
            .setView(dialogView)
            .create()
        btnSaveConfig.setOnClickListener {
            val name = etConfigName.text.toString().trim()
            if (name.isEmpty()) {
                etConfigName.error = "配置名不能为空"
                return@setOnClickListener
            }
            if (selectedImageUri == null) {
                Toast.makeText(requireContext(), "请选择图片", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 复制图片到保存路径/组名/配置名.png，支持 SAF
            val uriRoot = ConfigPathUtil.getConfigRoot(requireContext())
            val targetPath: String
            if (uriRoot.startsWith("content://")) {
                try {
                    val rootUri = android.net.Uri.parse(uriRoot)
                    val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(requireContext(), rootUri)
                    val overlayDoc = rootDoc?.findFile("ImageOverlay") ?: rootDoc?.createDirectory("ImageOverlay")
                    val groupDoc = overlayDoc?.findFile(groupName ?: "default") ?: overlayDoc?.createDirectory(groupName ?: "default")
                    val configDoc = groupDoc?.createFile("image/png", name + ".png")
                    val inputStream: InputStream? = requireContext().contentResolver.openInputStream(selectedImageUri!!)
                    val outputStream = configDoc?.uri?.let { requireContext().contentResolver.openOutputStream(it, "wt") }
                    if (inputStream == null || outputStream == null) throw java.lang.Exception("io null")
                    inputStream.copyTo(outputStream)
                    inputStream.close()
                    outputStream.close()
                    targetPath = configDoc.uri.toString()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "图片保存失败", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            } else {
                val groupDir = ConfigPathUtil.getGroupDir(requireContext(), groupName ?: "default")
                val fileName = name + ".png"
                val destFile = File(groupDir, fileName)
                try {
                    val inputStream: InputStream? = requireContext().contentResolver.openInputStream(selectedImageUri!!)
                    val outputStream = FileOutputStream(destFile)
                    inputStream?.copyTo(outputStream)
                    inputStream?.close()
                    outputStream.close()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "图片保存失败", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                targetPath = destFile.absolutePath
            }
            val config = Config(name, targetPath, false)
            getCurrentGroup()?.configs?.add(config)
            ConfigRepository.save(requireContext())
            refreshConfigList()
            adapter = ConfigAdapter(requireContext(), configList, { idx ->
                onConfigStatusClick(idx)
            }, { idx ->
                onConfigItemClick(idx)
            }, { idx ->
                showConfigContextMenu(idx)
            }, getCurrentGroup()?.defaultConfigName)
            recyclerView.adapter = adapter
            addConfigDialog?.dismiss()
        }
        etConfigName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                btnSaveConfig.performClick()
                true
            } else false
        }
        addConfigDialog?.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200 && resultCode == Activity.RESULT_OK) {
            selectedImageUri = data?.data
            ivPreview?.setImageURI(selectedImageUri)
        }
        if (requestCode == 201 && resultCode == Activity.RESULT_OK) {
            val img = data?.data ?: return
            // 覆盖默认配置图片：这里改成“设置为默认配置”时才会用到；正常条目点击不再覆盖
            val name = getCurrentGroup()?.configs?.firstOrNull()?.configName ?: "默认"
            val uriRoot = com.example.imageoverlay.util.ConfigPathUtil.getConfigRoot(requireContext())
            val targetPath: String
            if (uriRoot.startsWith("content://")) {
                try {
                    val rootUri = android.net.Uri.parse(uriRoot)
                    val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(requireContext(), rootUri)
                    val overlayDoc = rootDoc?.findFile("ImageOverlay") ?: rootDoc?.createDirectory("ImageOverlay")
                    val groupDoc = overlayDoc?.findFile(groupName ?: "default") ?: overlayDoc?.createDirectory(groupName ?: "default")
                    // 删除旧文件并写入新文件
                    groupDoc?.listFiles()?.forEach { it.delete() }
                    val configDoc = groupDoc?.createFile("image/png", name + ".png")
                    val inputStream: java.io.InputStream? = requireContext().contentResolver.openInputStream(img)
                    val outputStream = configDoc?.uri?.let { requireContext().contentResolver.openOutputStream(it, "wt") }
                    if (inputStream == null || outputStream == null) throw java.lang.Exception("io null")
                    inputStream.copyTo(outputStream)
                    inputStream.close()
                    outputStream.close()
                    targetPath = configDoc.uri.toString()
                } catch (_: Exception) {
                    android.widget.Toast.makeText(requireContext(), "图片保存失败", android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
            } else {
                val groupDir = com.example.imageoverlay.util.ConfigPathUtil.getGroupDir(requireContext(), groupName ?: "default")
                // 清空旧文件
                groupDir.listFiles()?.forEach { it.delete() }
                val destFile = java.io.File(groupDir, name + ".png")
                try {
                    val inputStream: java.io.InputStream? = requireContext().contentResolver.openInputStream(img)
                    val outputStream = java.io.FileOutputStream(destFile)
                    inputStream?.copyTo(outputStream)
                    inputStream?.close()
                    outputStream.close()
                } catch (_: Exception) {
                    android.widget.Toast.makeText(requireContext(), "图片保存失败", android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
                targetPath = destFile.absolutePath
            }
            val g = getCurrentGroup()
            if (g != null) {
                if (g.configs.isEmpty()) {
                    g.configs.add(com.example.imageoverlay.model.Config(name, targetPath, false))
                } else {
                    g.configs[0].imageUri = targetPath
                }
                com.example.imageoverlay.model.ConfigRepository.save(requireContext())
                refreshConfigList()
                adapter = ConfigAdapter(requireContext(), configList, { idx ->
                    onConfigStatusClick(idx)
                }, { idx ->
                    onConfigItemClick(idx)
                }, { idx ->
                    showConfigContextMenu(idx)
                }, getCurrentGroup()?.defaultConfigName)
                recyclerView.adapter = adapter
                android.widget.Toast.makeText(requireContext(), "已更新默认配置图片", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onConfigStatusClick(idx: Int) {
        try {
            refreshConfigList()
            if (idx < 0 || idx >= configList.size) return
            
            val config = configList[idx]
            if (config.imageUri.isBlank()) {
                android.widget.Toast.makeText(requireContext(), "请先选择图片", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            
            if (!config.active) {
                // 全局只允许一个绿点
                ConfigRepository.getGroups().forEach { group ->
                    group.configs.forEach { it.active = false }
                }
                config.active = true
                // 先关闭所有遮罩
                val stopIntent = Intent(requireContext(), OverlayService::class.java)
                requireContext().stopService(stopIntent)
                // 再启动遮罩
                if (PermissionUtil.checkOverlayPermission(requireContext())) {
                    val intent = Intent(requireContext(), OverlayService::class.java)
                    intent.putExtra("imageUri", config.imageUri)
                    // 不传递透明度参数，让OverlayService使用全局透明度设置
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        requireContext().startForegroundService(intent)
                    } else {
                        requireContext().startService(intent)
                    }
                    android.widget.Toast.makeText(requireContext(), "遮罩已启动", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    PermissionUtil.openOverlayPermissionSettings(requireContext())
                    android.widget.Toast.makeText(requireContext(), "需要悬浮窗权限，请授权后重试", android.widget.Toast.LENGTH_LONG).show()
                }
            } else {
                // 变红并关闭遮罩
                config.active = false
                val intent = Intent(requireContext(), OverlayService::class.java)
                requireContext().stopService(intent)
            }
            
            ConfigRepository.save(requireContext())
            refreshConfigList()
            adapter = ConfigAdapter(requireContext(), configList, { idx ->
                onConfigStatusClick(idx)
            }, { idx ->
                onConfigItemClick(idx)
            }, { idx ->
                showConfigContextMenu(idx)
            }, getCurrentGroup()?.defaultConfigName)
            recyclerView.adapter = adapter
        } catch (e: Exception) {
            android.util.Log.e("GroupDetailFragment", "配置状态切换异常", e)
            android.widget.Toast.makeText(requireContext(), "操作失败，请重试", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun onConfigItemClick(idx: Int) {
        // 普通点击不做上传；长按见上下文菜单
    }

    private fun showConfigContextMenu(idx: Int) {
        val options = arrayOf("删除该配置", "设置为默认配置")
        AlertDialog.Builder(requireContext())
            .setTitle("操作")
            .setItems(options) { d, which ->
                when (which) {
                    0 -> showDeleteConfigDialog(idx)
                    1 -> setAsDefaultConfig(idx)
                }
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setAsDefaultConfig(idx: Int) {
        val cfg = getCurrentGroup()?.configs?.getOrNull(idx) ?: return
        if (cfg.imageUri.isBlank()) {
            Toast.makeText(requireContext(), "该配置未设置图片", Toast.LENGTH_SHORT).show()
            return
        }
        // 设置为组的默认遮罩
        com.example.imageoverlay.model.ConfigRepository.setGroupDefaultConfig(groupName ?: "", cfg.configName)
        com.example.imageoverlay.model.ConfigRepository.save(requireContext())
        
        // 刷新界面
        refreshConfigList()
        adapter = ConfigAdapter(requireContext(), configList, { idx ->
            onConfigStatusClick(idx)
        }, { idx ->
            onConfigItemClick(idx)
        }, { idx ->
            showConfigContextMenu(idx)
        }, getCurrentGroup()?.defaultConfigName)
        recyclerView.adapter = adapter
        
        Toast.makeText(requireContext(), "已设为默认遮罩", Toast.LENGTH_SHORT).show()
    }

    private fun showDeleteConfigDialog(idx: Int) {
        val currentGroup = getCurrentGroup() ?: return
        val configs = currentGroup.configs
        if (idx < 0 || idx >= configs.size) return
        
        val config = configs[idx]
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("删除配置")
            .setMessage("确定要删除该配置吗？")
            .setPositiveButton("确定") { d, _ ->
                try {
                    // 如果该配置正在运行，先停止遮罩服务
                    if (config.active) {
                        val stopIntent = android.content.Intent(requireContext(), OverlayService::class.java)
                        requireContext().stopService(stopIntent)
                        config.active = false
                    }
                    
                    // 删除图片文件
                    val path = config.imageUri
                    if (path.startsWith("content://")) {
                        try {
                            val uri = android.net.Uri.parse(path)
                            requireContext().contentResolver.delete(uri, null, null)
                        } catch (e: Exception) {
                            android.util.Log.e("GroupDetailFragment", "SAF删除文件失败", e)
                        }
                    } else {
                        try {
                            val file = java.io.File(path)
                            if (file.exists()) {
                                file.delete()
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("GroupDetailFragment", "文件删除失败", e)
                        }
                    }
                    
                    // 从内存中移除配置
                    val currentGroup = getCurrentGroup()
                    currentGroup?.configs?.removeAt(idx)
                    // 保存配置
                    ConfigRepository.save(requireContext())
                    // 更新界面
                    refreshConfigList()
                    adapter = ConfigAdapter(requireContext(), configList, { idx ->
                        onConfigStatusClick(idx)
                    }, { idx ->
                        onConfigItemClick(idx)
                    }, { idx ->
                        showConfigContextMenu(idx)
                    }, getCurrentGroup()?.defaultConfigName)
                    recyclerView.adapter = adapter
                    android.widget.Toast.makeText(requireContext(), "配置删除成功", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.util.Log.e("GroupDetailFragment", "删除配置异常", e)
                    android.widget.Toast.makeText(requireContext(), "删除失败，请重试", android.widget.Toast.LENGTH_SHORT).show()
                }
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    companion object {
        fun newInstance(group: Group): GroupDetailFragment {
            val fragment = GroupDetailFragment()
            val args = Bundle()
            args.putSerializable("group", group)
            fragment.arguments = args
            return fragment
        }
    }
} 