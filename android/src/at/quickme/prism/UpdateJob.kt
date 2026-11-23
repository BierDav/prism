// The phone's published settings (ServerSettings.kt), the update check
// (Updater.kt) and T3 Code's chat check as a JobScheduler job: every 6
// hours, and once when asked (a push, a tap, a boot).

package at.quickme.prism

import android.app.job.JobParameters
import android.app.job.JobService

class UpdateJob : JobService() {
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = Thread({
            val settings = ServerSettings.sync(this)
            val chat = T3Chat.check(this)
            val done = Updater.check(this) && settings && chat
            jobFinished(params, !done)
        }, "update-check").apply { start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true
    }
}
