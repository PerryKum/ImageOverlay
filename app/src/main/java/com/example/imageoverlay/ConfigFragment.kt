package com.example.imageoverlay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.imageoverlay.adapter.GroupAdapter
import com.example.imageoverlay.model.ConfigRepository
import com.example.imageoverlay.model.Group
import com.example.imageoverlay.util.ConfigPathUtil
import com.google.android.material.tabs.TabLayout

class ConfigFragment : Fragment() {
	private lateinit var recyclerView: RecyclerView
	private lateinit var adapter: GroupAdapter
	private var groupList: MutableList<Group> = mutableListOf()
	private lateinit var tvTitle: TextView
	private var currentScreenType: String = "main"

	override fun onCreateView(
		inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
	): View? {
		val view = inflater.inflate(R.layout.fragment_config, container, false)

		// ========== TabLayout: 主屏 / 副屏 ==========
		val tabLayout = view.findViewById<TabLayout>(R.id.tabLayout)

		// 标题文字
		tvTitle = view.findViewById(R.id.tvTitle)

		// 检测副屏
		if (hasSecondaryDisplay(requireContext())) {
			tabLayout.visibility = View.VISIBLE
			tabLayout.addTab(tabLayout.newTab().setText("主屏"))
			tabLayout.addTab(tabLayout.newTab().setText("副屏"))

			tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
				override fun onTabSelected(tab: TabLayout.Tab?) {
					when (tab?.position) {
						0 -> {
							currentScreenType = "main"
							tvTitle.text = "组列表"
							loadGroupsForCurrentScreen()
						}
							1 -> {
								currentScreenType = "secondary"
								tvTitle.text = "组列表 - 副屏"
								loadGroupsForCurrentScreen()
							}
						}
					}
					override fun onTabUnselected(tab: TabLayout.Tab?) {}
					override fun onTabReselected(tab: TabLayout.Tab?) {}
				})
				
				// View 重建时 TabLayout 默认选中 tab 0，需恢复之前的选中状态
				if (currentScreenType == "secondary") {
					tabLayout.selectTab(tabLayout.getTabAt(1))
				}
			}
		// ========== End TabLayout ==========
		recyclerView = view.findViewById(R.id.recyclerViewGroups)
		recyclerView.layoutManager = LinearLayoutManager(requireContext())
		groupList.addAll(ConfigRepository.getGroupsByScreenType(currentScreenType).filter { it.groupName != "默认配置" })
		adapter = GroupAdapter(groupList, { group ->
			// 跳转到组详情
			parentFragmentManager.beginTransaction()
				.replace(R.id.fragment_container, GroupDetailFragment.newInstance(group))
				.addToBackStack(null)
				.commit()
		}, { idx ->
			showGroupContextMenu(idx)
		})
		recyclerView.adapter = adapter

		val btnAddGroup = view.findViewById<ImageButton>(R.id.btnAddGroup)
		btnAddGroup.setOnClickListener {
			showAddGroupDialog()
		}
		val btnRefreshGroup = view.findViewById<ImageButton>(R.id.btnRefreshGroup)
		btnRefreshGroup.setOnClickListener {
			ConfigRepository.load(requireContext())
			loadGroupsForCurrentScreen()
			Toast.makeText(requireContext(), "已刷新", Toast.LENGTH_SHORT).show()
		}
		return view
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		super.onActivityResult(requestCode, resultCode, data)
		if (requestCode == 1001 && resultCode == Activity.RESULT_OK) {
			val packageName = data?.getStringExtra("package_name")
			val appName = data?.getStringExtra("app_name")
			if (!packageName.isNullOrBlank()) {
				loadGroupsForCurrentScreen()
				Toast.makeText(requireContext(), "已绑定应用: $appName", Toast.LENGTH_SHORT).show()
			}
		}
	}

	override fun onResume() {
		super.onResume()
		// 强制同步预设模式状态
		forceSyncPresetState()
		ConfigRepository.load(requireContext())
		loadGroupsForCurrentScreen()
	}
	
	/**
	 * 强制同步预设模式状态
	 */
	private fun forceSyncPresetState() {
		try {
			val hadStaleMain = ConfigRepository.getGroupsByScreenType("main")
				.any { g -> g.configs.any { it.active } } ||
				ConfigRepository.isDefaultActive(requireContext(), "main")
			val hadStaleSecondary = ConfigRepository.getGroupsByScreenType("secondary")
				.any { g -> g.configs.any { it.active } } ||
				ConfigRepository.isDefaultActive(requireContext(), "secondary")

			ConfigRepository.syncPresetStateForScreenType(requireContext(), "main")
			ConfigRepository.syncPresetStateForScreenType(requireContext(), "secondary")

			val mainRunning = ConfigRepository.isOverlayRunningForScreenType(requireContext(), "main")
			val mainStaleCleared = hadStaleMain && !mainRunning
			val secStaleCleared = hadStaleSecondary &&
				!ConfigRepository.isOverlayRunningForScreenType(requireContext(), "secondary")

			if (mainStaleCleared || secStaleCleared) {
				android.widget.Toast.makeText(
					requireContext(),
					"检测到预设遮罩已停止，状态已同步",
					android.widget.Toast.LENGTH_SHORT
				).show()
			}
		} catch (e: Exception) {
			android.util.Log.e("ConfigFragment", "强制同步预设模式状态失败", e)
		}
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
				group.screenType = currentScreenType
				ConfigRepository.addGroup(requireContext(), group)
				loadGroupsForCurrentScreen()
				dialog.dismiss()
			} else {
				etGroupName.error = "组名称不能为空"
			}
		}
		dialog.show()
	}

	private fun showGroupContextMenu(idx: Int) {
		val group = groupList.getOrNull(idx) ?: return
		val options = mutableListOf<String>()
		
		if (group.boundPackageName != null) {
			options.add("解绑应用")
		} else {
			options.add("绑定应用")
		}
		options.add("设为全局默认")
		options.add("删除组")
		
		AlertDialog.Builder(requireContext())
			.setTitle("操作")
			.setItems(options.toTypedArray()) { d, which ->
				when (which) {
					0 -> {
						if (group.boundPackageName != null) {
							unbindAppFromGroup(group)
						} else {
							bindAppToGroup(group)
						}
					}
					1 -> setGroupAsGlobalDefault(group)
					2 -> showDeleteGroupDialog(idx)
				}
				d.dismiss()
			}
			.setNegativeButton("取消", null)
			.show()
	}

	/** 将该组当前默认配置写入全局默认（磁贴 / 快捷键，前台未绑定时使用） */
	private fun setGroupAsGlobalDefault(group: Group) {
		val config = ConfigRepository.getGroupDefaultConfig(group.id)
		if (config == null || config.imageUri.isBlank()) {
			Toast.makeText(
				requireContext(),
				"请先在组内添加带图片的配置，并长按配置设为组默认",
				Toast.LENGTH_SHORT
			).show()
			return
		}
		ConfigRepository.setDefaultConfig(
			requireContext(),
			group.id,
			config,
			group.screenType
		)
		ConfigRepository.save(requireContext())
		val screenLabel = if (group.screenType == "secondary") "副屏" else "主屏"
		Toast.makeText(
			requireContext(),
			"已设为${screenLabel}全局默认：${group.groupName}/${config.configName}",
			Toast.LENGTH_SHORT
		).show()
	}

	private fun bindAppToGroup(group: Group) {
		val intent = Intent(requireContext(), AppSelectorActivity::class.java)
		intent.putExtra("group_id", group.id)
		startActivityForResult(intent, 1001)
	}

	private fun unbindAppFromGroup(group: Group) {
		ConfigRepository.unbindAppFromGroup(group.id)
		ConfigRepository.save(requireContext())
		loadGroupsForCurrentScreen()
		Toast.makeText(requireContext(), "已解绑应用", Toast.LENGTH_SHORT).show()
	}

	private fun showDeleteGroupDialog(idx: Int) {
		val group = groupList.getOrNull(idx) ?: return
		val dialog = android.app.AlertDialog.Builder(requireContext())
			.setTitle("删除组")
			.setMessage("确定要删除该组吗？")
			.setPositiveButton("确定") { d, _ ->
				showDeleteGroupConfigsDialog(group)
				d.dismiss()
			}
			.setNegativeButton("取消", null)
			.create()
		dialog.show()
	}

	private fun showDeleteGroupConfigsDialog(group: Group) {
		val dialog = android.app.AlertDialog.Builder(requireContext())
			.setTitle("警告")
			.setMessage("将删除组内所有配置，是否继续？")
			.setPositiveButton("确定") { d, _ ->
				try {
					// 如果组内有正在运行的配置，先停止遮罩服务
					if (group.configs.any { it.active }) {
						OverlayService.stopDisplay(
							requireContext(),
							ConfigRepository.displayKeyForScreenType(requireContext(), group.screenType)
						)
						ConfigRepository.clearActiveConfigsForScreenType(group.screenType)
						ConfigRepository.setDefaultActive(requireContext(), false, group.screenType)
					}
					
					// 删除组文件夹及图片
					val groupName = group.groupName
					val overlayRoot = ConfigPathUtil.getOverlayRoot(requireContext())
					if (overlayRoot.startsWith("content://")) {
						// SAF 模式，使用 DocumentFile API
						try {
							val rootUri = android.net.Uri.parse(overlayRoot)
							val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(requireContext(), rootUri)
							val overlayDoc = rootDoc?.findFile("ImageOverlay")
							val groupDoc = overlayDoc?.findFile(groupName)
							if (groupDoc != null && groupDoc.exists()) {
								// 先删除组内的所有文件
								groupDoc.listFiles().forEach { it.delete() }
								// 再删除组文件夹
								groupDoc.delete()
							}
						} catch (e: Exception) {
							android.util.Log.e("ConfigFragment", "SAF删除组失败", e)
						}
					} else {
						// 传统文件模式
						try {
							val groupDir = java.io.File(overlayRoot, groupName)
							if (groupDir.exists()) {
								groupDir.deleteRecursively()
							}
						} catch (e: Exception) {
							android.util.Log.e("ConfigFragment", "文件删除组失败", e)
						}
					}
					
					// 从内存中移除组
					val groups = ConfigRepository.getGroups()
					groups.removeAll { it.id == group.id }
					// 保存配置
					ConfigRepository.save(requireContext())
					// 更新界面
					loadGroupsForCurrentScreen()
					android.widget.Toast.makeText(requireContext(), "组删除成功", android.widget.Toast.LENGTH_SHORT).show()
				} catch (e: Exception) {
					android.util.Log.e("ConfigFragment", "删除组异常", e)
					android.widget.Toast.makeText(requireContext(), "删除失败，请重试", android.widget.Toast.LENGTH_SHORT).show()
				}
				d.dismiss()
			}
			.setNegativeButton("取消", null)
			.create()
		dialog.show()
	}

	// ========== 屏幕类型过滤 ==========
	private fun loadGroupsForCurrentScreen() {
		groupList.clear()
		groupList.addAll(ConfigRepository.getGroupsByScreenType(currentScreenType).filter { it.groupName != "默认配置" })
		adapter.notifyDataSetChanged()
	}

	companion object {
		fun hasSecondaryDisplay(context: Context): Boolean {
			return try {
				val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
					?: return false
				dm.displays.size > 1
			} catch (e: Exception) {
				android.util.Log.e("ConfigFragment", "检测副屏失败", e)
				false
			}
		}
	}
} 