package com.example.imageoverlay.tiles

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.imageoverlay.OverlayService
import com.example.imageoverlay.model.ConfigRepository
import com.example.imageoverlay.util.PermissionUtil

class DefaultToggleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.state = if (ConfigRepository.isDefaultActive(this)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }

    override fun onClick() {
        super.onClick()
        toggleOverlay()
    }

    private fun toggleOverlay() {
        val currentlyActive = ConfigRepository.isDefaultActive(this)
        if (currentlyActive) {
            // turn off
            val stopIntent = Intent(this, OverlayService::class.java)
            stopService(stopIntent)
            ConfigRepository.setDefaultActive(this, false)
        } else {
            // turn on – ensure overlay permission
            if (!PermissionUtil.checkOverlayPermission(this)) {
                // 在磁贴中不跳转权限设置页面，静默处理
                android.util.Log.w("DefaultToggleTileService", "Overlay permission not granted")
                return
            }
            val imageUri = ConfigRepository.getDefaultConfig(this)?.imageUri
            if (imageUri != null) {
                val intent = Intent(this, OverlayService::class.java)
                intent.putExtra("imageUri", imageUri)
                intent.putExtra("opacity", ConfigRepository.getDefaultOpacity(this))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                // 关闭预设中的激活状态
                ConfigRepository.load(this)
                ConfigRepository.getGroups().forEach { g -> g.configs.forEach { it.active = false } }
                ConfigRepository.save(this)
                ConfigRepository.setDefaultActive(this, true)
            }
        }
        qsTile?.state = if (ConfigRepository.isDefaultActive(this)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }



}


