package com.example.imageoverlay.tiles

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.imageoverlay.util.DisplayUtil
import com.example.imageoverlay.util.OverlayToggler

class DefaultToggleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTileUi()
    }

    override fun onClick() {
        super.onClick()
        if (!OverlayToggler.isTileOperable(this)) {
            OverlayToggler.ensureTileAllOffForDisabled(this)
            refreshTileUi()
            return
        }
        OverlayToggler.handleTileClick(this)
        refreshTileUi()
    }

    private fun refreshTileUi() {
        val tile = qsTile ?: return
        if (!OverlayToggler.isTileOperable(this)) {
            OverlayToggler.ensureTileAllOffForDisabled(this)
            tile.state = Tile.STATE_UNAVAILABLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                tile.subtitle =
                    OverlayToggler.dualScreenTileModeLabel(
                        this,
                        OverlayToggler.DualScreenTileMode.ALL_OFF
                    )
            }
            tile.updateTile()
            return
        }

        if (!DisplayUtil.hasSecondaryDisplay(this)) {
            tile.state =
                if (OverlayToggler.isOverlayActive(this)) Tile.STATE_ACTIVE
                else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                tile.subtitle = null
            }
        } else {
            val mode = OverlayToggler.detectDualScreenTileMode(this)
            tile.state =
                if (mode == OverlayToggler.DualScreenTileMode.ALL_OFF) Tile.STATE_INACTIVE
                else Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                tile.subtitle = OverlayToggler.dualScreenTileModeLabel(this, mode)
            }
        }
        tile.updateTile()
    }
}
