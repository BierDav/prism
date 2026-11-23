// The device admin component `dpm set-device-owner` names (Dpc.kt).

package at.quickme.prism

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class DeviceAdmin : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Dpc.setUp(context)
        Updater.schedule(context)
    }
}
