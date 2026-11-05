package com.dotmusic.app

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: show or hide the dots on the live wallpaper, straight from the shade. */
class DotTile : TileService() {
    override fun onStartListening() { refresh() }

    override fun onClick() {
        DotPrefs.setWallpaperDots(this, !DotPrefs.wallpaperDots(this))
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = if (DotPrefs.wallpaperDots(this)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
