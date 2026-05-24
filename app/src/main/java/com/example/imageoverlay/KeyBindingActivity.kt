package com.example.imageoverlay

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.imageoverlay.model.ConfigRepository
import com.example.imageoverlay.util.AccessibilityUtil

/**
 * 按键绑定管理页面
 * 提供列表形式的按键绑定管理，支持多个功能分别绑定不同的按键组合
 */
class KeyBindingActivity : AppCompatActivity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: KeyBindingAdapter
    private var currentBindingPosition = -1
    private var isWaitingForKey = false
    
    // 按键绑定功能列表
    private val bindingFunctions = listOf(
        KeyBindingFunction("开关遮罩", "toggle_overlay"),
        KeyBindingFunction("显示/隐藏悬浮球", "toggle_floating_ball"),
        KeyBindingFunction("切换到下一张图片", "next_image"),
        KeyBindingFunction("切换到上一张图片", "previous_image"),
        KeyBindingFunction("增加透明度", "increase_opacity"),
        KeyBindingFunction("减少透明度", "decrease_opacity")
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_key_binding)
        
        // 设置工具栏
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        toolbar.setNavigationOnClickListener {
            finish()
        }
        
        // 初始化RecyclerView
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = KeyBindingAdapter(bindingFunctions) { position ->
            showKeyBindingDialog(position)
        }
        recyclerView.adapter = adapter
        
        // 检查无障碍服务权限
        checkAccessibilityService()
    }
    
    private fun checkAccessibilityService() {
        val a11yEnabled = AccessibilityUtil.isServiceEnabled(this, "com.example.imageoverlay.keybinding.KeyBindingService")
        if (!a11yEnabled) {
            AlertDialog.Builder(this)
                .setTitle("需要无障碍服务权限")
                .setMessage("按键绑定功能需要开启“按键绑定服务”才能正常工作。是否现在去开启？")
                .setPositiveButton("去开启") { _, _ ->
                    AccessibilityUtil.openAccessibilitySettings(this)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
    
    private fun showKeyBindingDialog(position: Int) {
        currentBindingPosition = position
        val function = bindingFunctions[position]
        val currentKeys = getCurrentBoundKeys(function.key)
        
        AlertDialog.Builder(this)
            .setTitle("绑定按键 - ${function.name}")
            .setMessage("请按下最多3个实体按键作为组合键；再次按确定保存。\n当前绑定：${formatKeyNames(currentKeys)}\n\n提示：不同机型可能限制音量键捕获，需要开启无障碍服务后生效。")
            .setPositiveButton("确定") { _, _ ->
                if (isWaitingForKey) {
                    isWaitingForKey = false
                    saveKeyBinding(function.key, currentKeys)
                }
            }
            .setNegativeButton("取消") { _, _ ->
                isWaitingForKey = false
                currentBindingPosition = -1
            }
            .setNeutralButton("清除绑定") { _, _ ->
                isWaitingForKey = false
                currentBindingPosition = -1
                saveKeyBinding(function.key, emptyList())
                Toast.makeText(this, "已清除${function.name}的按键绑定", Toast.LENGTH_SHORT).show()
            }
            .setOnDismissListener {
                isWaitingForKey = false
                currentBindingPosition = -1
            }
            .show()
        
        isWaitingForKey = true
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isWaitingForKey && currentBindingPosition != -1) {
            event?.let {
                val function = bindingFunctions[currentBindingPosition]
                val currentKeys = getCurrentBoundKeys(function.key).toMutableList()
                
                // 检查按键是否已存在
                if (!currentKeys.contains(keyCode)) {
                    // 检查是否与现有绑定冲突
                    if (checkKeyConflict(keyCode, function.key)) {
                        Toast.makeText(this, "该按键与其他功能冲突，请选择其他按键", Toast.LENGTH_SHORT).show()
                        return true
                    }
                    
                    currentKeys.add(keyCode)
                    if (currentKeys.size <= 3) {
                        // 临时保存到SharedPreferences
                        ConfigRepository.setBoundHardwareKeysForFunction(this, function.key, currentKeys)
                        adapter.notifyItemChanged(currentBindingPosition)
                        Toast.makeText(this, "已添加按键：${getKeyName(keyCode)}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "最多只能绑定3个按键", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    
    private fun checkKeyConflict(keyCode: Int, currentFunction: String): Boolean {
        // 检查该按键是否已被其他功能绑定
        for (func in bindingFunctions) {
            if (func.key != currentFunction) {
                val boundKeys = getCurrentBoundKeys(func.key)
                if (boundKeys.contains(keyCode)) {
                    return true
                }
            }
        }
        return false
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
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "音量上"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "音量下"
            KeyEvent.KEYCODE_POWER -> "电源键"
            KeyEvent.KEYCODE_BACK -> "返回键"
            KeyEvent.KEYCODE_HOME -> "Home键"
            KeyEvent.KEYCODE_MENU -> "菜单键"
            KeyEvent.KEYCODE_SEARCH -> "搜索键"
            KeyEvent.KEYCODE_CAMERA -> "相机键"
            KeyEvent.KEYCODE_HEADSETHOOK -> "耳机键"
            else -> "按键$keyCode"
        }
    }
    
    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()
    }
}

/**
 * 按键绑定功能数据类
 */
data class KeyBindingFunction(
    val name: String,
    val key: String
)

/**
 * 按键绑定适配器
 */
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
            boundKeys.joinToString(" + ") { getKeyName(it, context) }
        }
        
        // 设置状态指示器
        holder.ivStatus.setImageResource(
            if (boundKeys.isNotEmpty()) R.drawable.dot_green else R.drawable.dot_red
        )
        
        holder.btnBind.setOnClickListener {
            onBindClick(position)
        }
    }
    
    override fun getItemCount() = functions.size
    
    private fun getKeyName(keyCode: Int, context: Context): String {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "音量上"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "音量下"
            KeyEvent.KEYCODE_POWER -> "电源键"
            KeyEvent.KEYCODE_BACK -> "返回键"
            KeyEvent.KEYCODE_HOME -> "Home键"
            KeyEvent.KEYCODE_MENU -> "菜单键"
            KeyEvent.KEYCODE_SEARCH -> "搜索键"
            KeyEvent.KEYCODE_CAMERA -> "相机键"
            KeyEvent.KEYCODE_HEADSETHOOK -> "耳机键"
            else -> "按键$keyCode"
        }
    }
}