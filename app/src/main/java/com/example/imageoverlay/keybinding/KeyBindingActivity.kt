package com.example.imageoverlay.keybinding

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.imageoverlay.R
import com.example.imageoverlay.model.ConfigRepository
import com.example.imageoverlay.util.AccessibilityUtil
import com.example.imageoverlay.util.DisplayUtil

/**
 * 按键绑定管理页面
 */
class KeyBindingActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: KeyBindingAdapter
    private var bindingDialog: AlertDialog? = null
    private lateinit var bindingFunctions: List<KeyBindingFunction>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_key_binding)

        bindingFunctions = buildBindingFunctions()

        findViewById<Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = KeyBindingAdapter(bindingFunctions) { position ->
            showKeyBindingDialog(position)
        }
        recyclerView.adapter = adapter

        checkAccessibilityService()
    }

    override fun onDestroy() {
        bindingDialog?.dismiss()
        KeyCaptureCoordinator.stopCapture()
        super.onDestroy()
    }

    private fun checkAccessibilityService() {
        if (!isAccessibilityReady()) {
            AlertDialog.Builder(this)
                .setTitle("需要无障碍服务")
                .setMessage(
                    "实体按键（尤其音量键）需开启「按键绑定服务」后才能录制与在游戏外使用。\n\n" +
                        "请在无障碍设置中启用本应用的无障碍服务。"
                )
                .setPositiveButton("去开启") { _, _ ->
                    AccessibilityUtil.openAccessibilitySettings(this)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun isAccessibilityReady(): Boolean {
        return AccessibilityUtil.isServiceEnabled(
            this,
            "com.example.imageoverlay.keybinding.KeyBindingService"
        )
    }

    private fun showKeyBindingDialog(position: Int) {
        if (!isAccessibilityReady()) {
            Toast.makeText(this, "请先开启无障碍「按键绑定服务」", Toast.LENGTH_LONG).show()
            AccessibilityUtil.openAccessibilitySettings(this)
            return
        }

        val function = bindingFunctions[position]
        val recorded = getCurrentBoundKeys(function.key).toMutableList()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val tip = TextView(this).apply {
            text =
                "请直接按实体键（含音量键）。已开启无障碍时会拦截系统音量条。\n" +
                    "最多 3 个组合键，按「确定」保存。"
            textSize = 14f
        }
        container.addView(tip)

        val captureView = TextView(this).apply {
            text = "已录制：${formatKeyNames(recorded)}"
            textSize = 16f
            setPadding(0, 24, 0, 0)
        }
        container.addView(captureView)

        fun refreshCaptureLabel() {
            captureView.text = "已录制：" + if (recorded.isEmpty()) {
                "无（请按键）"
            } else {
                formatKeyNames(recorded)
            }
        }

        fun addKey(keyCode: Int) {
            if (keyCode == KeyEvent.KEYCODE_BACK) return
            if (recorded.contains(keyCode)) return
            if (checkKeyConflict(keyCode, function.key)) {
                Toast.makeText(this, "该按键已绑定到其他功能", Toast.LENGTH_SHORT).show()
                return
            }
            if (recorded.size >= 3) {
                Toast.makeText(this, "最多只能绑定 3 个按键", Toast.LENGTH_SHORT).show()
                return
            }
            recorded.add(keyCode)
            ConfigRepository.setBoundHardwareKeysForFunction(this, function.key, recorded)
            refreshCaptureLabel()
            adapter.notifyItemChanged(position)
            Toast.makeText(this, "已添加：${getKeyName(keyCode)}", Toast.LENGTH_SHORT).show()
        }

        KeyCaptureCoordinator.startCapture { keyCode -> addKey(keyCode) }

        bindingDialog = AlertDialog.Builder(this)
            .setTitle("绑定按键 - ${function.name}")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                saveKeyBinding(function.key, recorded)
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("清除绑定") { _, _ ->
                saveKeyBinding(function.key, emptyList())
                Toast.makeText(this, "已清除${function.name}的按键绑定", Toast.LENGTH_SHORT).show()
            }
            .setOnDismissListener {
                KeyCaptureCoordinator.stopCapture()
                bindingDialog = null
            }
            .create()

        bindingDialog?.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                addKey(keyCode)
                true
            } else {
                false
            }
        }
        bindingDialog?.show()
        refreshCaptureLabel()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (KeyCaptureCoordinator.isCapturing && event.action == KeyEvent.ACTION_DOWN) {
            if (KeyCaptureCoordinator.shouldConsumeWhileCapturing(event.keyCode)) {
                KeyCaptureCoordinator.handleKeyEvent(event.keyCode, event.action)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun buildBindingFunctions(): List<KeyBindingFunction> {
        val list = mutableListOf(
            KeyBindingFunction(getString(R.string.toggle_overlay), "toggle_overlay"),
        )
        if (DisplayUtil.hasSecondaryDisplay(this)) {
            list.add(KeyBindingFunction(getString(R.string.key_bind_overlay_main), "toggle_overlay_main"))
            list.add(KeyBindingFunction(getString(R.string.key_bind_overlay_secondary), "toggle_overlay_secondary"))
        }
        list.add(KeyBindingFunction(getString(R.string.key_bind_floating_ball), "toggle_floating_ball"))
        return list
    }

    private fun checkKeyConflict(keyCode: Int, currentFunction: String): Boolean {
        return ConfigRepository.checkKeyConflictForFunction(this, keyCode, currentFunction)
    }

    private fun saveKeyBinding(functionKey: String, keys: List<Int>) {
        ConfigRepository.setBoundHardwareKeysForFunction(this, functionKey, keys)
        adapter.notifyItemChanged(bindingFunctions.indexOfFirst { it.key == functionKey })
        Toast.makeText(this, "按键绑定已保存", Toast.LENGTH_SHORT).show()
    }

    private fun getCurrentBoundKeys(functionKey: String): List<Int> {
        return ConfigRepository.getBoundHardwareKeysForFunction(this, functionKey)
    }

    private fun formatKeyNames(keyCodes: List<Int>): String {
        return if (keyCodes.isEmpty()) {
            "未绑定"
        } else {
            keyCodes.joinToString(" + ") { getKeyName(it) }
        }
    }

    private fun getKeyName(keyCode: Int): String {
        return friendlyKeyName(keyCode)
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()
    }
}

data class KeyBindingFunction(
    val name: String,
    val key: String
)

class KeyBindingAdapter(
    private val functions: List<KeyBindingFunction>,
    private val onBindClick: (Int) -> Unit
) : RecyclerView.Adapter<KeyBindingAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvFunctionName: TextView = itemView.findViewById(R.id.tvFunctionName)
        val tvKeyBinding: TextView = itemView.findViewById(R.id.tvKeyBinding)
        val ivStatus: ImageView = itemView.findViewById(R.id.ivStatus)
        val btnBind: Button = itemView.findViewById(R.id.btnBind)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_key_binding, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val function = functions[position]
        val context = holder.itemView.context

        holder.tvFunctionName.text = function.name

        val boundKeys = ConfigRepository.getBoundHardwareKeysForFunction(context, function.key)
        holder.tvKeyBinding.text = if (boundKeys.isEmpty()) {
            "未绑定"
        } else {
            boundKeys.joinToString(" + ") { keyLabel(it) }
        }

        holder.ivStatus.setImageResource(
            if (boundKeys.isNotEmpty()) R.drawable.dot_green else R.drawable.dot_red
        )

        holder.btnBind.setOnClickListener {
            onBindClick(position)
        }
    }

    override fun getItemCount() = functions.size

    private fun keyLabel(keyCode: Int): String = friendlyKeyName(keyCode)
}

private fun friendlyKeyName(keyCode: Int): String {
    return when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> "音量上"
        KeyEvent.KEYCODE_VOLUME_DOWN -> "音量下"
        KeyEvent.KEYCODE_VOLUME_MUTE -> "静音"
        KeyEvent.KEYCODE_POWER -> "电源"
        KeyEvent.KEYCODE_BACK -> "返回"
        KeyEvent.KEYCODE_HOME -> "Home"
        KeyEvent.KEYCODE_MENU -> "菜单"
        KeyEvent.KEYCODE_CAMERA -> "相机"
        KeyEvent.KEYCODE_HEADSETHOOK -> "耳机"
        else -> KeyEvent.keyCodeToString(keyCode)
            .removePrefix("KEYCODE_")
            .replace('_', ' ')
            .ifBlank { "按键$keyCode" }
    }
}
