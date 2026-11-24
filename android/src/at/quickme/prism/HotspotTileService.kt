// The Quick Settings tile "Hotspot" (Hotspot.kt). Active while the hotspot
// is on in either mode: "Always on" in normal mode, "Instant · on" while an
// instant hotspot runs (for the Mac, or switched on from the hotspot page);
// inactive in instant mode while it's off ("Instant"). A tap turns it off
// or on, as on any tile:
//
//  - Always on → instant mode (the hotspot goes off),
//  - Instant · on → off now, still in instant mode,
//  - Instant → normal mode (the hotspot comes on and stays).
//
// A long press opens the hotspot page (HotspotActivity, the activity with
// QS_TILE_PREFERENCES), which also has the configuration and the devices.

package at.quickme.prism

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import java.io.IOException

class HotspotTileService : TileService() {
    private val update = Runnable { show() }

    override fun onStartListening() {
        super.onStartListening()
        Hotspot.listen(update)
        // Whether it's on needs the tethering callback in this process.
        Hotspot.watch(this)
        show()
    }

    override fun onStopListening() {
        Hotspot.unlisten(update)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        when {
            Hotspot.mode(this) == Hotspot.NORMAL -> Hotspot.setMode(this, Hotspot.INSTANT)
            Hotspot.on -> {
                val app = applicationContext
                Thread({
                    try {
                        Hotspot.stopNow(app)
                    } catch (e: IOException) {
                        Log.w("PrismHotspot", "tile: off now: ${e.message}")
                    }
                }, "hotspot-tile").start()
            }
            else -> Hotspot.setMode(this, Hotspot.NORMAL)
        }
        show()
    }

    private fun show() {
        val tile = qsTile ?: return
        val normal = Hotspot.mode(this) == Hotspot.NORMAL
        tile.label = "Hotspot"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_hotspot)
        tile.state = if (normal || Hotspot.on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            normal -> "Always on"
            Hotspot.on -> "Instant · on"
            else -> "Instant"
        }
        tile.updateTile()
    }
}
