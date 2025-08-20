package com.example.imageoverlay

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.imageoverlay.adapter.GroupAdapter
import com.example.imageoverlay.model.ConfigRepository
import com.example.imageoverlay.model.Group
import com.example.imageoverlay.util.ConfigPathUtil

class ConfigFragment : Fragment() {
	private lateinit var recyclerView: RecyclerView
	private lateinit var adapter: GroupAdapter
	private var groupList: MutableList<Group> = mutableListOf()
	private var ivDefaultStatus: android.widget.ImageView? = null
	private var ivDefaultThumb: android.widget.ImageView? = null
	private var tvDefaultName: android.widget.TextView? = null

	override fun onCreateView(
		inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
	): View? {
		val view = inflater.inflate(R.layout.fragment_config, container, false)
		recyclerView = view.findViewById(R.id.recyclerViewGroups)
		recyclerView.layoutManager = LinearLayoutManager(requireContext())
		groupList = ConfigRepository.getGroups().filter { it.groupName != "默认配置" }.toMutableList()
		adapter = GroupAdapter(groupList, { group ->
			// 跳转到组详情
			parentFragmentManager.beginTransaction()
				.replace(R.id.fragment_container, GroupDetailFragment.newInstance(group))
				.addToBackStack(null)
				.commit()
		}, { idx ->
			showDeleteGroupDialog(idx)
		})
		recyclerView.adapter = adapter

		// Default config row
		val layoutDefault = view.findViewById<android.widget.LinearLayout>(R.id.layoutDefaultConfig)
		ivDefaultStatus = view.findViewById<android.widget.ImageView>(R.id.ivDefaultStatus)
		ivDefaultThumb = view.findViewById<android.widget.ImageView>(R.id.ivDefaultThumb)
		tvDefaultName = view.findViewById<android.widget.TextView>(R.id.tvDefaultName)

		refreshDefaultRow()

		ivDefaultStatus?.setOnClickListener {
			val def = ConfigRepository.getDefaultConfig(requireContext())
			val active = ConfigRepository.isDefaultActive(requireContext())
			if (active) {
				val stopIntent = android.content.Intent(requireContext(), OverlayService::class.java)
				requireContext().stopService(stopIntent)
				ConfigRepository.setDefaultActive(requireContext(), false)
			} else {
				if (def != null && !def.imageUri.isBlank() && android.provider.Settings.canDrawOverlays(requireContext())) {
					// 关闭其他预设
					ConfigRepository.getGroups().forEach { g -> g.configs.forEach { it.active = false } }
					ConfigRepository.save(requireContext())
					val intent = android.content.Intent(requireContext(), OverlayService::class.java)
					intent.putExtra("imageUri", def.imageUri)
					if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
						requireContext().startForegroundService(intent)
					} else {
						requireContext().startService(intent)
					}
					ConfigRepository.setDefaultActive(requireContext(), true)
				} else {
					android.widget.Toast.makeText(requireContext(), "请先在任意配置上设置默认配置", android.widget.Toast.LENGTH_SHORT).show()
				}
			}
			refreshDefaultRow()
		}

		// Also refresh when coming back
		view.viewTreeObserver.addOnWindowFocusChangeListener {
			refreshDefaultRow()
		}

		// 长按默认配置行 -> 清除默认配置
		layoutDefault.setOnLongClickListener {
			android.app.AlertDialog.Builder(requireContext())
				.setTitle("清除默认配置")
				.setMessage("是否清除默认配置？")
				.setPositiveButton("确定") { d, _ ->
					ConfigRepository.clearDefaultConfig(requireContext())
					refreshDefaultRow()
					android.widget.Toast.makeText(requireContext(), "已清除默认配置", android.widget.Toast.LENGTH_SHORT).show()
					d.dismiss()
				}
				.setNegativeButton("取消", null)
				.show()
			true
		}

		val btnAddGroup = view.findViewById<ImageButton>(R.id.btnAddGroup)
		btnAddGroup.setOnClickListener {
			showAddGroupDialog()
		}
		val btnRefreshGroup = view.findViewById<ImageButton>(R.id.btnRefreshGroup)
		btnRefreshGroup.setOnClickListener {
			ConfigRepository.load(requireContext())
			groupList.clear()
			groupList.addAll(ConfigRepository.getGroups().filter { it.groupName != "默认配置" })
			adapter.notifyDataSetChanged()
			refreshDefaultRow()
			Toast.makeText(requireContext(), "已刷新", Toast.LENGTH_SHORT).show()
		}
		return view
	}

	fun refreshDefaultRow() {
		val def = ConfigRepository.getDefaultConfig(requireContext())
		val title = if (def == null) {
			"默认配置（长按任意配置可设置）"
		} else {
			val group = ConfigRepository.getDefaultGroupName(requireContext()) ?: ""
			"默认配置（" + group + "/" + def.configName + ")"
		}
		tvDefaultName?.text = title
		ivDefaultStatus?.setImageResource(if (ConfigRepository.isDefaultActive(requireContext())) R.drawable.ic_circle_green else R.drawable.ic_circle_red)
		if (!def?.imageUri.isNullOrBlank()) {
			try { ivDefaultThumb?.setImageURI(android.net.Uri.parse(def?.imageUri ?: "")) } catch (_: Exception) { ivDefaultThumb?.setImageResource(android.R.drawable.ic_menu_report_image) }
		} else {
			ivDefaultThumb?.setImageResource(android.R.drawable.ic_menu_report_image)
		}
	}

	override fun onResume() {
		super.onResume()
		ConfigRepository.load(requireContext())
		groupList.clear()
		groupList.addAll(ConfigRepository.getGroups().filter { it.groupName != "默认配置" })
		adapter.notifyDataSetChanged()
		refreshDefaultRow()
	}

	private fun showAddGroupDialog() {
		val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_group, null)
		val etGroupName = dialogView.findViewById<EditText>(R.id.etGroupName)
		val etRemark = dialogView.findViewById<EditText>(R.id.etRemark)
		val dialog = AlertDialog.Builder(requireContext())
			.setTitle("新建组")
			.setView(dialogView)
			.create()
		etGroupName.setOnEditorActionListener { _, actionId, _ ->
			if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
				etRemark.requestFocus()
				true
			} else false
		}
		etRemark.setOnEditorActionListener { _, actionId, _ ->
			if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
				dialogView.findViewById<Button>(R.id.btnConfirm).performClick()
				true
			} else false
		}
		dialogView.findViewById<Button>(R.id.btnConfirm).setOnClickListener {
			val name = etGroupName.text.toString().trim()
			val remark = etRemark.text.toString().trim()
			if (name.isNotEmpty()) {
				val group = Group(name, remark)
				ConfigRepository.addGroup(requireContext(), group)
				groupList.clear()
				groupList.addAll(ConfigRepository.getGroups().filter { it.groupName != "默认配置" })
				adapter.notifyDataSetChanged()
				dialog.dismiss()
			} else {
				etGroupName.error = "组名称不能为空"
			}
		}
		dialog.show()
	}

	private fun showDeleteGroupDialog(idx: Int) {
		val dialog = android.app.AlertDialog.Builder(requireContext())
			.setTitle("删除组")
			.setMessage("确定要删除该组吗？")
			.setPositiveButton("确定") { d, _ ->
				showDeleteGroupConfigsDialog(idx)
				d.dismiss()
			}
			.setNegativeButton("取消", null)
			.create()
		dialog.show()
	}

	private fun showDeleteGroupConfigsDialog(idx: Int) {
		val group = groupList.getOrNull(idx)
		val dialog = android.app.AlertDialog.Builder(requireContext())
			.setTitle("警告")
			.setMessage("将删除组内所有配置，是否继续？")
			.setPositiveButton("确定") { d, _ ->
				// 删除组文件夹及图片
				group?.let {
					val groupName = it.groupName
					val uriStr = ConfigPathUtil.getConfigRoot(requireContext())
					if (uriStr.startsWith("content://")) {
						try {
							val rootUri = android.net.Uri.parse(uriStr)
							val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(requireContext(), rootUri)
							val groupDoc = rootDoc?.findFile(groupName)
							groupDoc?.delete()
						} catch (_: Exception) {}
					} else {
						try {
							val groupDir = java.io.File(uriStr, groupName)
							groupDir.deleteRecursively()
						} catch (_: Exception) {}
					}
				}
				groupList.removeAt(idx)
				ConfigRepository.save(requireContext())
				adapter.notifyDataSetChanged()
				d.dismiss()
			}
			.setNegativeButton("取消", null)
			.create()
		dialog.show()
	}
} 