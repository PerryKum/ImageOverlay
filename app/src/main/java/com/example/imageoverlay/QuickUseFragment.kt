package com.example.imageoverlay

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.imageoverlay.model.ConfigRepository
import com.example.imageoverlay.util.DisplayUtil
import com.example.imageoverlay.util.PermissionUtil

class QuickUseFragment : Fragment() {

    private data class ScreenPanel(
        val screenType: String,
        val root: View,
        val labelView: TextView,
        val imageView: ImageView,
        val btnSelect: Button,
        val btnStart: Button,
        val btnStop: Button,
        val selectRequestCode: Int,
        var imageUri: Uri? = null,
        var isOverlayActive: Boolean = false
    )

    private val panels = mutableListOf<ScreenPanel>()
    private var overlayStateReceiver: BroadcastReceiver? = null
    private var pendingSelectPanel: ScreenPanel? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_quick_use, container, false)
        val containerLayout = view.findViewById<LinearLayout>(R.id.quickUseContainer)
        val panelDivider = view.findViewById<View>(R.id.panelDivider)
        val panelMainRoot = view.findViewById<View>(R.id.panelMain)
        val panelSecondaryRoot = view.findViewById<View>(R.id.panelSecondary)

        val dual = DisplayUtil.hasSecondaryDisplay(requireContext())

        if (dual) {
            containerLayout.orientation = LinearLayout.HORIZONTAL
            panelDivider.visibility = View.VISIBLE
            (panelDivider.layoutParams as LinearLayout.LayoutParams).apply {
                width = 1
                height = LinearLayout.LayoutParams.MATCH_PARENT
                marginStart = 4
                marginEnd = 4
            }
            panelSecondaryRoot.visibility = View.VISIBLE
            listOf(panelMainRoot, panelSecondaryRoot).forEach { panelRoot ->
                (panelRoot.layoutParams as LinearLayout.LayoutParams).apply {
                    width = 0
                    height = LinearLayout.LayoutParams.WRAP_CONTENT
                    weight = 1f
                }
            }
        } else {
            containerLayout.orientation = LinearLayout.VERTICAL
            panelDivider.visibility = View.GONE
            panelSecondaryRoot.visibility = View.GONE
        }

        panels.clear()
        panels.add(
            bindPanel(
                panelMainRoot,
                screenType = "main",
                labelRes = R.string.quick_use_screen_main,
                showLabel = dual,
                selectRequestCode = REQUEST_SELECT_MAIN,
                dualLayout = dual
            )
        )
        if (dual) {
            panels.add(
                bindPanel(
                    panelSecondaryRoot,
                    screenType = "secondary",
                    labelRes = R.string.quick_use_screen_secondary,
                    showLabel = true,
                    selectRequestCode = REQUEST_SELECT_SECONDARY,
                    dualLayout = true
                )
            )
        }

        panels.forEach { panel ->
            restorePanelState(panel)
            updateButtonState(panel)
            wirePanelListeners(panel)
        }

        return view
    }

    private fun bindPanel(
        root: View,
        screenType: String,
        labelRes: Int,
        showLabel: Boolean,
        selectRequestCode: Int,
        dualLayout: Boolean
    ): ScreenPanel {
        val labelView = root.findViewById<TextView>(R.id.tvScreenLabel)
        val imageView = root.findViewById<ImageView>(R.id.imageView)
        if (showLabel) {
            labelView.visibility = View.VISIBLE
            labelView.setText(labelRes)
        } else {
            labelView.visibility = View.GONE
        }
        val previewHeight = resources.getDimensionPixelSize(
            if (dualLayout) R.dimen.quick_use_preview_height_dual
            else R.dimen.quick_use_preview_height
        )
        imageView.layoutParams = imageView.layoutParams.apply {
            height = previewHeight
        }
        return ScreenPanel(
            screenType = screenType,
            root = root,
            labelView = labelView,
            imageView = imageView,
            btnSelect = root.findViewById(R.id.btnSelect),
            btnStart = root.findViewById(R.id.btnStart),
            btnStop = root.findViewById(R.id.btnStop),
            selectRequestCode = selectRequestCode
        )
    }

    private fun wirePanelListeners(panel: ScreenPanel) {
        panel.btnSelect.setOnClickListener {
            if (panel.isOverlayActive) {
                Toast.makeText(requireContext(), "请先关闭遮罩", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pendingSelectPanel = panel
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/png"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/gif"))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, panel.selectRequestCode)
        }

        panel.btnStart.setOnClickListener {
            if (panel.isOverlayActive) {
                Toast.makeText(requireContext(), "遮罩已在运行", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (ConfigRepository.syncPresetStateForScreenType(requireContext(), panel.screenType)) {
                val msg = if (panel.screenType == "secondary") {
                    R.string.quick_use_preset_active_secondary
                } else {
                    R.string.quick_use_preset_active_main
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (panel.imageUri == null) {
                Toast.makeText(requireContext(), "请先选择图片", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!PermissionUtil.checkOverlayPermission(requireContext())) {
                PermissionUtil.openOverlayPermissionSettings(requireContext())
                Toast.makeText(requireContext(), "需要悬浮窗权限，请授权后重试", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startOverlayForPanel(panel)
        }

        panel.btnStop.setOnClickListener {
            if (!panel.isOverlayActive) {
                Toast.makeText(requireContext(), "遮罩未在运行", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            stopOverlayForPanel(panel)
        }
    }

    private fun startOverlayForPanel(panel: ScreenPanel) {
        val intent = Intent(requireContext(), OverlayService::class.java).apply {
            putExtra("imageUri", panel.imageUri.toString())
            putExtra("opacity", ConfigRepository.getDefaultOpacity(requireContext()))
            if (panel.screenType == "secondary") {
                putExtra(
                    OverlayService.EXTRA_DISPLAY_ID,
                    ConfigRepository.displayKeyForScreenType(requireContext(), "secondary")
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
        panel.isOverlayActive = true
        savePanelState(panel)
        updateButtonState(panel)
        val label = if (panel.screenType == "secondary") "副屏" else "主屏"
        Toast.makeText(requireContext(), "${label}遮罩已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopOverlayForPanel(panel: ScreenPanel) {
        val displayKey = ConfigRepository.displayKeyForScreenType(requireContext(), panel.screenType)
        OverlayService.stopDisplay(requireContext(), displayKey)
        panel.isOverlayActive = false
        savePanelState(panel)
        updateButtonState(panel)
        val label = if (panel.screenType == "secondary") "副屏" else "主屏"
        Toast.makeText(requireContext(), "${label}遮罩已停止", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        ConfigRepository.syncAllPresetStates(requireContext())
        panels.forEach { panel ->
            restorePanelState(panel)
            updateButtonState(panel)
        }
        registerOverlayStateReceiver()
    }

    override fun onPause() {
        super.onPause()
        try {
            overlayStateReceiver?.let { requireContext().unregisterReceiver(it) }
        } catch (_: Exception) {
        }
        overlayStateReceiver = null
    }

    private fun restorePanelState(panel: ScreenPanel) {
        try {
            val sp = requireContext().getSharedPreferences(PREFS, 0)
            val imageUriStr = sp.getString(imageUriKey(panel.screenType), null)
                ?: if (panel.screenType == "main") sp.getString(KEY_IMAGE_URI_LEGACY, null) else null

            panel.imageUri = imageUriStr?.let {
                try {
                    Uri.parse(it)
                } catch (e: Exception) {
                    android.util.Log.e("QuickUseFragment", "解析图片URI失败 screen=${panel.screenType}", e)
                    null
                }
            }

            if (panel.imageUri != null) {
                try {
                    panel.imageView.setImageURI(panel.imageUri)
                } catch (e: Exception) {
                    android.util.Log.e("QuickUseFragment", "设置图片失败 screen=${panel.screenType}", e)
                    panel.imageUri = null
                }
            } else {
                panel.imageView.setImageDrawable(null)
            }

            val serviceRunning = isOverlayRunningForPanel(panel)
            panel.isOverlayActive = serviceRunning

            val savedActive = sp.getBoolean(overlayActiveKey(panel.screenType), false)
                || (panel.screenType == "main" && sp.getBoolean(KEY_OVERLAY_ACTIVE_LEGACY, false))
            if (savedActive != serviceRunning) {
                savePanelState(panel)
            }
        } catch (e: Exception) {
            android.util.Log.e("QuickUseFragment", "状态恢复失败 screen=${panel.screenType}", e)
            panel.isOverlayActive = false
            panel.imageUri = null
            savePanelState(panel)
        }
    }

    private fun isOverlayRunningForPanel(panel: ScreenPanel): Boolean {
        val displayKey = ConfigRepository.displayKeyForScreenType(requireContext(), panel.screenType)
        return OverlayService.isRunningOnDisplay(displayKey)
    }

    private fun registerOverlayStateReceiver() {
        try {
            if (overlayStateReceiver != null) return
            overlayStateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != ACTION_OVERLAY_STATE_CHANGED) return
                    panels.forEach { panel ->
                        panel.isOverlayActive = isOverlayRunningForPanel(panel)
                        savePanelState(panel)
                        updateButtonState(panel)
                    }
                }
            }
            requireContext().registerReceiver(
                overlayStateReceiver,
                IntentFilter(ACTION_OVERLAY_STATE_CHANGED)
            )
        } catch (e: Exception) {
            android.util.Log.e("QuickUseFragment", "注册遮罩状态接收器失败", e)
        }
    }

    private fun savePanelState(panel: ScreenPanel) {
        try {
            requireContext().getSharedPreferences(PREFS, 0).edit()
                .putBoolean(overlayActiveKey(panel.screenType), panel.isOverlayActive)
                .putString(imageUriKey(panel.screenType), panel.imageUri?.toString())
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("QuickUseFragment", "保存状态失败 screen=${panel.screenType}", e)
        }
    }

    private fun updateButtonState(panel: ScreenPanel) {
        panel.btnStart.isEnabled = !panel.isOverlayActive
        panel.btnStop.isEnabled = panel.isOverlayActive
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val panel = panels.find { it.selectRequestCode == requestCode } ?: pendingSelectPanel
        pendingSelectPanel = null
        if (panel == null || resultCode != Activity.RESULT_OK) return

        try {
            panel.imageUri = data?.data
            if (panel.imageUri == null) return

            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    panel.imageUri!!,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                android.util.Log.e("QuickUseFragment", "无法获取持久化权限", e)
                Toast.makeText(requireContext(), "无法获取图片访问权限，请重新选择", Toast.LENGTH_SHORT).show()
                panel.imageUri = null
                return
            }

            try {
                panel.imageView.setImageURI(panel.imageUri)
                if (panel.imageView.drawable == null) {
                    throw IllegalStateException("图片加载失败")
                }
            } catch (e: Exception) {
                android.util.Log.e("QuickUseFragment", "图片加载验证失败", e)
                Toast.makeText(requireContext(), "图片加载失败，请选择其他图片", Toast.LENGTH_SHORT).show()
                panel.imageUri = null
                return
            }

            savePanelState(panel)
        } catch (e: Exception) {
            android.util.Log.e("QuickUseFragment", "处理图片选择结果失败", e)
            Toast.makeText(requireContext(), "图片选择失败，请重试", Toast.LENGTH_SHORT).show()
            panel.imageUri = null
        }
    }

    private fun imageUriKey(screenType: String) =
        "image_uri_$screenType"

    private fun overlayActiveKey(screenType: String) =
        "is_overlay_active_$screenType"

    companion object {
        private const val PREFS = "quick_use_prefs"
        private const val KEY_IMAGE_URI_LEGACY = "image_uri"
        private const val KEY_OVERLAY_ACTIVE_LEGACY = "is_overlay_active"
        private const val ACTION_OVERLAY_STATE_CHANGED =
            "com.example.imageoverlay.OVERLAY_STATE_CHANGED"
        private const val REQUEST_SELECT_MAIN = 100
        private const val REQUEST_SELECT_SECONDARY = 101
    }
}
