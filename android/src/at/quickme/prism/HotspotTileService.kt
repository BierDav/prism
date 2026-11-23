// The Quick Settings tile "Hotspot": switches the hotspot's mode (Hotspot.kt).
// Active is normal mode ("Always on"); inactive is instant mode ("Instant",
// "Instant · on" while the Mac's hotspot runs), so the tile reads as "keep
// the hotspot on" and a tap is all it takes to let it go again.

package at.quickme.prism

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class HotspotTileService : TileService() {
    private val update = Runnable { show() }

    override fun onStartListening() {
        super.onStartListening()
        Hotspot.listen(update)
        // "Instant · on" needs the tethering callback in this process.
        Hotspot.watch(this)
        show()
    }

    override fun onStopListening() {
        Hotspot.unlisten(update)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val next = if (Hotspot.mode(this) == Hotspot.NORMAL) Hotspot.INSTANT else Hotspot.NORMAL
        Hotspot.setMode(this, next)
        show()
    }

    private fun show() {
        val tile = qsTile ?: return
        val normal = Hotspot.mode(this) == Hotspot.NORMAL
        tile.label = "Hotspot"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_hotspot)
        tile.state = if (normal) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            normal -> "Always on"
            Hotspot.on -> "Instant · on"
            else -> "Instant"
        }
        tile.updateTile()
    }
}
