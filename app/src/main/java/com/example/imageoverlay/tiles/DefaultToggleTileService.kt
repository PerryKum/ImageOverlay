package com.example.imageoverlay.tiles

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.imageoverlay.OverlayService
import com.example.imageoverlay.model.ConfigRepository

class DefaultToggleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.state = if (ConfigRepository.isDefaultActive(this)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val currentlyActive = ConfigRepository.isDefaultActive(this)
        if (currentlyActive) {
            // turn off
            val stopIntent = Intent(this, OverlayService::class.java)
            stopService(stopIntent)
            ConfigRepository.setDefaultActive(this, false)
        } else {
            // turn on – ensure overlay permission
            if (!Settings.canDrawOverlays(this)) {
                // Show settings; tile cannot launch for result, but we can open settings
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivityAndCollapse(intent)
                return
            }
            val imageUri = ConfigRepository.getDefaultConfig(this)?.imageUri
            if (imageUri != null) {
                val intent = Intent(this, OverlayService::class.java)
                intent.putExtra("imageUri", imageUri)
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


