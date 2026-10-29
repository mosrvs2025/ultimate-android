package app.ultimate.send

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import app.ultimate.UltimateApp

/** "Computer link" in quick settings: one tap turns sharing with your computer on or off, from anywhere. */
class LinkTileService : TileService() {
    private val server get() = (application as UltimateApp).sendServer

    override fun onStartListening() { super.onStartListening(); refresh() }

    override fun onClick() {
        super.onClick()
        if (server.state.value.running) server.stop()
        else {
            server.start()
            val address = server.state.value.address
            Toast.makeText(applicationContext, address?.let { "Open ${it.removePrefix("http://")} on your computer" } ?: (server.state.value.error ?: "Couldn't turn on"), Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = server.state.value.running
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (android.os.Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) server.state.value.address?.removePrefix("http://")?.substringBefore('/') else "Off"
        tile.updateTile()
    }
}
