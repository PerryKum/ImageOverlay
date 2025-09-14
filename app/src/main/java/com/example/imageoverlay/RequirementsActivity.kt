package com.example.imageoverlay

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.imageoverlay.util.AccessibilityUtil
import com.example.imageoverlay.util.PermissionUtil
import com.example.imageoverlay.util.UsagePermissionUtil

class RequirementsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_requirements)

        findViewById<Button>(R.id.btnOpenOverlay).setOnClickListener {
            PermissionUtil.openOverlayPermissionSettings(this)
        }
        findViewById<Button>(R.id.btnOpenA11y).setOnClickListener {
            AccessibilityUtil.openAccessibilitySettings(this)
        }
        findViewById<Button>(R.id.btnOpenNotifications).setOnClickListener {
            PermissionUtil.openNotificationSettings(this)
        }
        findViewById<Button>(R.id.btnOpenUsage).setOnClickListener {
            UsagePermissionUtil.openUsageStatsSettings(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val overlayOk = PermissionUtil.checkOverlayPermission(this)
        val a11yOk = AccessibilityUtil.isServiceEnabled(this, "com.example.imageoverlay.keybinding.KeyBindingService")
        val notifOk = PermissionUtil.checkNotificationPermission(this)
        val usageOk = UsagePermissionUtil.hasUsageStatsPermission(this)

        updateRow(R.id.rowOverlay, R.id.tvOverlayStatus, overlayOk)
        updateRow(R.id.rowA11y, R.id.tvA11yStatus, a11yOk)
        updateRow(R.id.rowNotifications, R.id.tvNotificationsStatus, notifOk)
        updateRow(R.id.rowUsage, R.id.tvUsageStatus, usageOk)
    }

    private fun updateRow(rowId: Int, statusId: Int, ok: Boolean) {
        val row = findViewById<LinearLayout>(rowId)
        val iv = row.findViewById<ImageView>(when (rowId) {
            R.id.rowOverlay -> R.id.ivStatus
            R.id.rowA11y -> R.id.ivA11yStatus
            R.id.rowNotifications -> R.id.ivNotificationsStatus
            R.id.rowUsage -> R.id.ivUsageStatus
            else -> R.id.ivStatus
        })
        val tv = row.findViewById<TextView>(statusId)
        iv.setImageResource(if (ok) R.drawable.dot_green else R.drawable.dot_red)
        tv.text = if (ok) "已满足" else "未开启"
        // 按需显示“去开启”按钮
        val btnId = when (rowId) {
            R.id.rowOverlay -> R.id.btnOpenOverlay
            R.id.rowA11y -> R.id.btnOpenA11y
            R.id.rowNotifications -> R.id.btnOpenNotifications
            R.id.rowUsage -> R.id.btnOpenUsage
            else -> 0
        }
        if (btnId != 0) {
            val btn = row.findViewById<Button>(btnId)
            btn.visibility = if (ok) View.GONE else View.VISIBLE
        }
    }
}


