package com.example.imageoverlay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment

class SecondaryDisplayFragment : Fragment() {

    private lateinit var tvDisplayInfo: TextView
    private lateinit var tvDisplayList: TextView
    private lateinit var btnRefresh: Button
    private var displayManager: DisplayManager? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_secondary_display, container, false)
        tvDisplayInfo = view.findViewById(R.id.tvDisplayInfo)
        tvDisplayList = view.findViewById(R.id.tvDisplayList)
        btnRefresh = view.findViewById(R.id.btnRefreshDisplay)

        displayManager = requireContext().getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

        btnRefresh.setOnClickListener {
            refreshDisplayInfo()
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        refreshDisplayInfo()
    }

    private fun refreshDisplayInfo() {
        val dm = displayManager ?: run {
            tvDisplayInfo.text = "无法获取 DisplayManager 服务"
            return
        }

        val displays = dm.displays
        val presentationDisplays = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        } else {
            null
        }

        Log.d("SecondaryDisplay", "总屏幕数: ${displays.size}")

        val sb = StringBuilder()
        displays.forEachIndexed { index, display ->
            val type = getDisplayTypeName(display)
            val name = display.name ?: "未知"
            val id = display.displayId
            sb.append("屏幕 $index: ID=$id, 名称=$name, 类型=$type")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val mode = display.mode
                sb.append(", 分辨率=${mode.physicalWidth}x${mode.physicalHeight}")
            }
            sb.append("\n")
        }

        tvDisplayInfo.text = "检测到 ${displays.size} 个显示设备"
        tvDisplayList.text = sb.toString()

        // 判断是否有副屏（displayId != 0 或 displays > 1）
        val hasSecondaryDisplay = displays.any { it.displayId != Display.DEFAULT_DISPLAY || displays.size > 1 }

        if (hasSecondaryDisplay) {
            tvDisplayInfo.text = "${tvDisplayInfo.text}\n✓ 检测到副屏，可以进行配置"
        } else {
            tvDisplayInfo.text = "${tvDisplayInfo.text}\n✗ 未检测到副屏"
        }

        Log.d("SecondaryDisplay", "hasSecondaryDisplay=$hasSecondaryDisplay, displayCount=${displays.size}")
    }

    private fun getDisplayTypeName(display: Display): String {
        return when (display.displayId) {
            Display.DEFAULT_DISPLAY -> "主屏幕"
            else -> {
                val sb = StringBuilder("副屏幕")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    val flags = display.flags
                    if (flags and Display.FLAG_PRESENTATION != 0) sb.append("(演示)")
                    if (flags and Display.FLAG_PRIVATE != 0) sb.append("(私有)")
                }
                sb.toString()
            }
        }
    }

    companion object {
        fun hasSecondaryDisplay(context: Context): Boolean {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                ?: return false
            val displays = dm.displays
            // 多于1个屏幕即认为有副屏
            return displays.size > 1
        }
    }
}
