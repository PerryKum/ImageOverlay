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
import android.app.AlertDialog
import android.widget.EditText
import com.example.imageoverlay.util.ConfigPathUtil
import android.content.Intent
import android.net.Uri
import android.app.Activity

class SettingsFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SettingsAdapter
    private var settingsList: MutableList<Pair<String, String>> = mutableListOf()
    private val REQUEST_CODE_PICK_DIR = 1001
    private var lastConfigRoot: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)
        recyclerView = view.findViewById(R.id.recyclerViewSettings)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        lastConfigRoot = com.example.imageoverlay.util.ConfigPathUtil.getConfigRoot(requireContext())
        settingsList = mutableListOf(
            Pair("配置保存路径", com.example.imageoverlay.util.ConfigPathUtil.getConfigRoot(requireContext())),
            Pair("清除缓存", "")
        )
        adapter = SettingsAdapter(settingsList, { idx ->
            when (idx) {
                0 -> pickDirectory()
                1 -> showClearCacheDialog()
            }
        }, { idx ->
            if (idx == 0) pickDirectory()
        })
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
        AlertDialog.Builder(requireContext())
            .setTitle("清理无效图片")
            .setMessage("将清理所有无效图片，是否继续？")
            .setPositiveButton("确定") { d, _ ->
                clearUnusedImages()
                Toast.makeText(requireContext(), "已清理无效图片", Toast.LENGTH_SHORT).show()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showClearAllCacheDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("清除全部缓存")
            .setMessage("将清除所有配置和图片，是否继续？")
            .setPositiveButton("确定") { d, _ ->
                com.example.imageoverlay.model.ConfigRepository.clear(requireContext())
                Toast.makeText(requireContext(), "已清除缓存", Toast.LENGTH_SHORT).show()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearUnusedImages() {
        val uriStr = com.example.imageoverlay.util.ConfigPathUtil.getConfigRoot(requireContext())
        val usedImages = mutableSetOf<String>()
        com.example.imageoverlay.model.ConfigRepository.getGroups().forEach { group ->
            group.configs.forEach { config ->
                usedImages.add(config.imageUri)
            }
        }
        if (uriStr.startsWith("content://")) {
            try {
                val rootUri = android.net.Uri.parse(uriStr)
                val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(requireContext(), rootUri)
                rootDoc?.listFiles()?.forEach { groupDoc ->
                    if (groupDoc.isDirectory) {
                        groupDoc.listFiles().forEach { file ->
                            if (file.name?.endsWith(".png") == true && file.uri.toString() !in usedImages) {
                                file.delete()
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        } else {
            val root = java.io.File(uriStr)
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
            com.example.imageoverlay.util.ConfigPathUtil.setConfigRootUri(requireContext(), uri)
            // 更新显示与数据
            settingsList[0] = Pair("配置保存路径", com.example.imageoverlay.util.ConfigPathUtil.getConfigRoot(requireContext()))
            adapter.notifyItemChanged(0)
            com.example.imageoverlay.model.ConfigRepository.load(requireContext())
            Toast.makeText(requireContext(), "已设置保存目录", Toast.LENGTH_SHORT).show()
        }
    }
}

class SettingsAdapter(
    private val items: List<Pair<String, String>>,
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit
) : RecyclerView.Adapter<SettingsAdapter.ViewHolder>() {
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        val tvValue: TextView = itemView.findViewById(R.id.tvValue)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_setting, parent, false)
        return ViewHolder(view)
    }
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (title, value) = items[position]
        holder.tvTitle.text = title
        holder.tvValue.text = value
        holder.itemView.setOnClickListener { onClick(position) }
        holder.itemView.setOnLongClickListener {
            onLongClick(position)
            true
        }
    }
    override fun getItemCount(): Int = items.size
} 