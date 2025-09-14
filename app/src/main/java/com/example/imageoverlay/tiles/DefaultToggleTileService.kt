package com.example.imageoverlay.tiles

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.imageoverlay.util.OverlayToggler

class DefaultToggleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.state = if (OverlayToggler.isOverlayActive(this)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }

    override fun onClick() {
        super.onClick()
        toggleOverlay()
    }

    private fun toggleOverlay() {
        OverlayToggler.toggleDefaultOverlay(this)
        qsTile?.state = if (OverlayToggler.isOverlayActive(this)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }



}


