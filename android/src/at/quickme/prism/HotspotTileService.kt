// The Quick Settings tile "Hotspot" (Hotspot.kt): the Always-on switch.
// Active in normal mode ("Always on"), inactive in instant mode; a tap
// switches between the two, as the hotspot page's "Always on" does. It never
// starts or stops an instant hotspot by itself: going to Always on keeps the
// hotspot on with normal mode's configuration, and leaving it switches the
// Always-on hotspot off.
//
// Apart from that, the icon shows whether an instant hotspot runs (for the
// Mac, or switched on from the hotspot page): a bolt in place of the dot,
// with "Instant · on" below the label. Instant and Always on never run at
// once (normal mode restarts any other hotspot as its own), so the bolt only
// shows on the inactive tile.
//
// A long press opens the hotspot page (HotspotActivity, the activity with
// QS_TILE_PREFERENCES), which also has Turn on/off now for an instant
// hotspot, the configuration and the devices.

package at.quickme.prism

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

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
        val normal = Hotspot.mode(this) == Hotspot.NORMAL
        Hotspot.setMode(this, if (normal) Hotspot.INSTANT else Hotspot.NORMAL)
        show()
    }

    private fun show() {
        val tile = qsTile ?: return
        val normal = Hotspot.mode(this) == Hotspot.NORMAL
        val instant = !normal && Hotspot.on
        tile.label = "Hotspot"
        tile.icon = Icon.createWithResource(this, if (instant) R.drawable.ic_hotspot_instant else R.drawable.ic_hotspot)
        tile.state = if (normal) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            normal -> "Always on"
            instant -> "Instant · on"
            else -> "Instant"
        }
        // Read out with the state (Always on is off): the bolt can't be heard.
        tile.stateDescription = if (instant) "Off, instant hotspot on" else null
        tile.updateTile()
    }
}
