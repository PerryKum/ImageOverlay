package com.example.imageoverlay

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView
import android.widget.Toast
import android.widget.SeekBar
import android.app.AlertDialog
import android.widget.EditText
import com.example.imageoverlay.util.ConfigPathUtil
import android.content.Intent
import android.net.Uri
import android.app.Activity
import androidx.appcompat.widget.SwitchCompat

class SettingsFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SettingsAdapter
    private var settingsList: MutableList<SettingItem> = mutableListOf()
    private val REQUEST_CODE_PICK_DIR = 1001
    private var lastConfigRoot: String? = null

    sealed class SettingItem {
        data class TextItem(val title: String, val value: String, val action: () -> Unit) : SettingItem()
        data class SwitchItem(val title: String, val description: String, val isChecked: Boolean, val onCheckedChange: (Boolean) -> Unit) : SettingItem()
        data class SliderItem(val title: String, val description: String, val value: Int, val maxValue: Int, val onValueChange: (Int) -> Unit) : SettingItem()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)
        recyclerView = view.findViewById(R.id.recyclerViewSettings)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        
        val configRoot = com.example.imageoverlay.util.ConfigPathUtil.getConfigRoot(requireContext())
        lastConfigRoot = configRoot
        
        // 创建设置项列表
        val versionName = try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            pInfo.versionName ?: ""
        } catch (_: Exception) { "" }

        settingsList = mutableListOf(
            SettingItem.TextItem("配置保存路径", configRoot) { pickDirectory() },
            SettingItem.SwitchItem(
                "自动开启遮罩", 
                "启动绑定组的软件时自动开启对应遮罩", 
                com.example.imageoverlay.model.ConfigRepository.isAutoStartOverlayEnabled(requireContext())
            ) { isChecked ->
                com.example.imageoverlay.model.ConfigRepository.setAutoStartOverlayEnabled(requireContext(), isChecked)
                Toast.makeText(requireContext(), if (isChecked) "已开启自动遮罩" else "已关闭自动遮罩", Toast.LENGTH_SHORT).show()
            },
            SettingItem.SwitchItem(
                "覆盖刘海/挖孔区域",
                "是否将遮罩延伸覆盖到刘海/挖孔区域（Android 9+）",
                com.example.imageoverlay.model.ConfigRepository.isCoverCutoutEnabled(requireContext())
            ) { isChecked ->
                com.example.imageoverlay.model.ConfigRepository.setCoverCutoutEnabled(requireContext(), isChecked)
                Toast.makeText(requireContext(), if (isChecked) "将覆盖刘海/挖孔区域" else "不覆盖刘海/挖孔区域", Toast.LENGTH_SHORT).show()
            },
            SettingItem.SliderItem(
                "全局遮罩透明度",
                "设置所有遮罩的透明度值",
                com.example.imageoverlay.model.ConfigRepository.getDefaultOpacity(requireContext()),
                100
            ) { value ->
                // 防抖处理：延迟保存，避免频繁调整时的重复保存
                com.example.imageoverlay.model.ConfigRepository.setDefaultOpacity(requireContext(), value)
                
                // 实时更新所有正在运行的遮罩的透明度
                try {
                    // 发送广播通知所有遮罩服务更新透明度
                    val intent = android.content.Intent(requireContext(), com.example.imageoverlay.OverlayService::class.java)
                    intent.putExtra("updateOpacity", value)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        requireContext().startForegroundService(intent)
                    } else {
                        requireContext().startService(intent)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SettingsFragment", "更新透明度失败", e)
                }
            },
            SettingItem.TextItem("清除缓存", "") { showClearCacheDialog() },
            // 版权信息（置于底部）
            SettingItem.TextItem(
                "关于与版权",
                "v${versionName} · 作者 Perry Kum · GPL-3.0"
            ) {
                try {
                    val uri = Uri.parse("https://perrykum.github.io/ImageOverlay/index.html")
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), "无法打开官网", Toast.LENGTH_SHORT).show()
                }
            }
        )
        
        adapter = SettingsAdapter(settingsList)
        recyclerView.adapter = adapter
        return view
    }

    private fun pickDirectory() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(intent, REQUEST_CODE_PICK_DIR)
    }

    private fun showClearCacheDialog() {
        val options = arrayOf("清理无效图片", "清除全部缓存")
        AlertDialog.Builder(requireContext())
            .setTitle("清除缓存")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showClearUnusedImagesDialog()
                    1 -> showClearAllCacheDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showClearUnusedImagesDialog() {
        if (!isAdded || context == null) return
        
        AlertDialog.Builder(requireContext())
            .setTitle("清理无效图片")
            .setMessage("将清理所有无效图片，是否继续？")
            .setPositiveButton("确定") { d, _ ->
                try {
                    if (isAdded && context != null) {
                        clearUnusedImages()
                        Toast.makeText(requireContext(), "已清理无效图片", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), "清理无效图片失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showClearAllCacheDialog() {
        if (!isAdded || context == null) return
        
        AlertDialog.Builder(requireContext())
            .setTitle("清除全部缓存")
            .setMessage("将清除所有配置和图片，是否继续？")
            .setPositiveButton("确定") { d, _ ->
                try {
                    if (isAdded && context != null) {
                        com.example.imageoverlay.model.ConfigRepository.clear(requireContext())
                        Toast.makeText(requireContext(), "已清除缓存", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), "清除缓存失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearUnusedImages() {
        if (!isAdded || context == null) return
        
        try {
            val overlayRoot = com.example.imageoverlay.util.ConfigPathUtil.getOverlayRoot(requireContext())
            val usedImages = mutableSetOf<String>()
            com.example.imageoverlay.model.ConfigRepository.getGroups().forEach { group ->
                group.configs.forEach { config ->
                    usedImages.add(config.imageUri)
                }
            }
            if (overlayRoot.startsWith("content://")) {
                // SAF 模式，使用 DocumentFile API
                try {
                    val rootUri = android.net.Uri.parse(overlayRoot)
                    val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(requireContext(), rootUri)
                    val overlayDoc = rootDoc?.findFile("ImageOverlay")
                    if (overlayDoc != null && overlayDoc.exists()) {
                        overlayDoc.listFiles().forEach { groupDoc ->
                            if (groupDoc.isDirectory) {
                                groupDoc.listFiles().forEach { file ->
                                    if (file.name?.endsWith(".png") == true && file.uri.toString() !in usedImages) {
                                        file.delete()
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // SAF清理无效图片失败
                }
            } else {
                // 传统文件模式
                val root = java.io.File(overlayRoot)
                if (root.exists()) {
                    root.listFiles()?.forEach { groupDir ->
                        if (groupDir.isDirectory) {
                            groupDir.listFiles()?.forEach { file ->
                                if (file.name.endsWith(".png") && file.absolutePath !in usedImages) {
                                    file.delete()
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // clearUnusedImages异常
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_DIR && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            
            // 设置新的配置路径（会自动迁移配置）
            com.example.imageoverlay.util.ConfigPathUtil.setConfigRootUri(requireContext(), uri)
            
            // 更新显示
            val newConfigRoot = com.example.imageoverlay.util.ConfigPathUtil.getConfigRoot(requireContext())
            settingsList[0] = SettingItem.TextItem("配置保存路径", newConfigRoot) { pickDirectory() }
            adapter.notifyItemChanged(0)
            
            // 重新加载配置数据
            com.example.imageoverlay.model.ConfigRepository.load(requireContext())
            
            Toast.makeText(requireContext(), "已设置保存目录并迁移配置", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // 刷新开关状态
        val autoStartOverlay = com.example.imageoverlay.model.ConfigRepository.isAutoStartOverlayEnabled(requireContext())
        if (settingsList.size > 1 && settingsList[1] is SettingItem.SwitchItem) {
            settingsList[1] = SettingItem.SwitchItem(
                "自动开启遮罩", 
                "启动绑定组的软件时自动开启对应遮罩", 
                autoStartOverlay
            ) { isChecked ->
                com.example.imageoverlay.model.ConfigRepository.setAutoStartOverlayEnabled(requireContext(), isChecked)
                Toast.makeText(requireContext(), if (isChecked) "已开启自动遮罩" else "已关闭自动遮罩", Toast.LENGTH_SHORT).show()
            }
            adapter.notifyItemChanged(1)
        }

        // 同步 覆盖刘海/挖孔区域 开关
        val coverCutout = com.example.imageoverlay.model.ConfigRepository.isCoverCutoutEnabled(requireContext())
        if (settingsList.size > 2 && settingsList[2] is SettingItem.SwitchItem) {
            settingsList[2] = SettingItem.SwitchItem(
                "覆盖刘海/挖孔区域",
                "是否将遮罩延伸覆盖到刘海/挖孔区域（Android 9+）",
                coverCutout
            ) { isChecked ->
                com.example.imageoverlay.model.ConfigRepository.setCoverCutoutEnabled(requireContext(), isChecked)
                Toast.makeText(requireContext(), if (isChecked) "将覆盖刘海/挖孔区域" else "不覆盖刘海/挖孔区域", Toast.LENGTH_SHORT).show()
            }
            adapter.notifyItemChanged(2)
        }
    }
}

class SettingsAdapter(
    private val items: List<SettingsFragment.SettingItem>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    
    companion object {
        private const val VIEW_TYPE_TEXT = 0
        private const val VIEW_TYPE_SWITCH = 1
        private const val VIEW_TYPE_SLIDER = 2
    }

    class TextViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        val tvValue: TextView = itemView.findViewById(R.id.tvValue)
    }

    class SwitchViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        val tvDescription: TextView = itemView.findViewById(R.id.tvDescription)
        val switchSetting: SwitchCompat = itemView.findViewById(R.id.switchSetting)
    }

    class SliderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        val tvDescription: TextView = itemView.findViewById(R.id.tvDescription)
        val seekBarSetting: android.widget.SeekBar = itemView.findViewById(R.id.seekBarSetting)
        val tvValue: TextView = itemView.findViewById(R.id.tvValue)
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is SettingsFragment.SettingItem.TextItem -> VIEW_TYPE_TEXT
            is SettingsFragment.SettingItem.SwitchItem -> VIEW_TYPE_SWITCH
            is SettingsFragment.SettingItem.SliderItem -> VIEW_TYPE_SLIDER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_TEXT -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_setting, parent, false)
                TextViewHolder(view)
            }
            VIEW_TYPE_SWITCH -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_setting_switch, parent, false)
                SwitchViewHolder(view)
            }
            VIEW_TYPE_SLIDER -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_setting_slider, parent, false)
                SliderViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is SettingsFragment.SettingItem.TextItem -> {
                val textHolder = holder as TextViewHolder
                textHolder.tvTitle.text = item.title
                textHolder.tvValue.text = item.value
                textHolder.itemView.setOnClickListener { item.action() }
            }
            is SettingsFragment.SettingItem.SwitchItem -> {
                val switchHolder = holder as SwitchViewHolder
                switchHolder.tvTitle.text = item.title
                switchHolder.tvDescription.text = item.description
                switchHolder.switchSetting.isChecked = item.isChecked
                switchHolder.switchSetting.setOnCheckedChangeListener { _, isChecked ->
                    item.onCheckedChange(isChecked)
                }
            }
            is SettingsFragment.SettingItem.SliderItem -> {
                val sliderHolder = holder as SliderViewHolder
                sliderHolder.tvTitle.text = item.title
                sliderHolder.tvDescription.text = item.description
                sliderHolder.seekBarSetting.max = item.maxValue
                sliderHolder.seekBarSetting.progress = item.value
                sliderHolder.tvValue.text = "${item.value}%"
                
                // 防抖处理：使用Handler延迟执行保存操作
                var saveHandler: android.os.Handler? = null
                var saveRunnable: Runnable? = null
                
                sliderHolder.seekBarSetting.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            sliderHolder.tvValue.text = "${progress}%"
                            
                            // 取消之前的延迟保存
                            saveHandler?.removeCallbacks(saveRunnable ?: return)
                            
                            // 创建新的延迟保存任务
                            saveRunnable = Runnable {
                                item.onValueChange(progress)
                            }
                            
                            // 延迟500毫秒执行保存，实现防抖
                            if (saveHandler == null) {
                                saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
                            }
                            saveHandler?.postDelayed(saveRunnable!!, 500)
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
                })
            }
        }
    }

    override fun getItemCount(): Int = items.size
} 