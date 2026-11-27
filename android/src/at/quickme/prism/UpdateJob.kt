// The phone's published settings (ServerSettings.kt), the update check
// (Updater.kt) and T3 Code's chat check as a JobScheduler job: every 6
// hours, and once when asked (a push, a tap, a boot).

package at.quickme.prism

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import java.io.InterruptedIOException

class UpdateJob : JobService() {
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = jobThread(params, "update-check", "PrismUpdate", after = { Updater.finished(this) }) {
            val settings = ServerSettings.sync(this)
            val chat = T3Chat.check(this)
            Updater.check(this) && settings && chat
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true
    }
}

/**
 * A job's [work] on a thread of its own, started; true when it is done, false
 * to run it again later. Android stops a job when its constraints go (the
 * network changes) or the app schedules it again, and onStopJob interrupts
 * this thread: the work then ends with an InterruptedException (runBlocking,
 * a sleep) or an InterruptedIOException, which ends only this run, never the
 * process (uncaught, it took the overlay down mid-sentence on 2026-11-27).
 * Android runs a stopped job again itself, as onStopJob asked; a run that
 * failed otherwise is retried with the job's backoff. [after] runs once the
 * run told Android it ended.
 */
internal fun JobService.jobThread(
    params: JobParameters,
    name: String,
    tag: String,
    after: () -> Unit = {},
    work: () -> Boolean,
): Thread =
    Thread({
        val done = try {
            work()
        } catch (e: InterruptedException) {
            Log.i(tag, "$name stopped by Android")
            return@Thread
        } catch (e: InterruptedIOException) {
            Log.i(tag, "$name stopped by Android")
            return@Thread
        } catch (e: Exception) {
            Log.w(tag, "$name failed", e)
            false
        }
        jobFinished(params, !done)
        try {
            after()
        } catch (e: RuntimeException) {
            Log.w(tag, "after $name", e)
        }
    }, name).apply { start() }
