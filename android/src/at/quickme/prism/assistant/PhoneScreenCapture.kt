// The phone's screen as it is under the assistant overlay, without asking
// the owner (assistant/Attachment.kt's ScreenCapture), two ways:
//
// - Now: `screencap` through the shell Prism runs as uid 2000
//   (shell/Shell.kt), with the overlay's window made fully transparent for
//   that frame, so the capture shows the app underneath, not Prism. This is
//   what the harness's take_screenshot gets whenever the shell runs.
// - At invocation: the screenshot Android hands the assistant session when
//   it opens (VoiceInteractionSession.onHandleScreenshot), taken before the
//   overlay appeared. Android sends it only when the owner allows it
//   (Settings › Apps › Default apps › Digital assistant › Use screenshot).
//   Without the shell, a capture falls back to it.
//
// The + menu's "Screen" takes the invocation screenshot when there is one
// (exactly what the owner saw when they opened Prism, no flicker), else a
// capture now.

package at.quickme.prism.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import at.quickme.prism.core.ScreencapRaw
import at.quickme.prism.shell.Shell
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The overlay's window, which hides itself while [block] captures the screen under it. */
internal interface OverlayWindow {
    suspend fun <T> withoutOverlay(block: suspend () -> T): T
}

internal class PhoneScreenCapture(
    private val overlay: OverlayWindow?,
    /** The session's screenshot from when the overlay opened, if Android sent one. */
    private val atInvocation: () -> Bitmap?,
) : ScreenCapture {
    override suspend fun capture(): AssistAttachment? = captureAttached(preferInvocation = false)?.attachment

    /**
     * The screen with its thumbnail: the invocation screenshot first when
     * [preferInvocation], else a capture now and the invocation screenshot
     * only without the shell.
     */
    suspend fun captureAttached(preferInvocation: Boolean): Attached? {
        if (preferInvocation) atInvocation()?.let { return AttachmentReader.screenshot(it) }
        now()?.let { bitmap ->
            return try {
                AttachmentReader.screenshot(bitmap)
            } finally {
                bitmap.recycle()
            }
        }
        return atInvocation()?.let { AttachmentReader.screenshot(it) }
    }

    /** `screencap` as uid 2000 with the overlay hidden, or null without the shell. */
    private suspend fun now(): Bitmap? {
        if (!Shell.available()) return null
        val capture: suspend () -> Shell.Result? = {
            withContext(Dispatchers.IO) {
                try {
                    // Raw: no PNG to encode on the phone, so the overlay is gone for less long.
                    Shell.run(15_000, "screencap")
                } catch (e: IOException) {
                    Log.w(TAG, "screencap", e)
                    null
                }
            }
        }
        val result = (overlay?.withoutOverlay(capture) ?: capture()) ?: return null
        if (!result.ok() || result.truncated) {
            Log.w(TAG, "screencap: $result ${result.stderr.take(200)}")
            return null
        }
        return withContext(Dispatchers.Default) { decode(result.stdoutBytes) }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val frame = ScreencapRaw.parse(bytes)
        if (frame == null) {
            // Some other pixel format, or a PNG: let Android read what it can.
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        // ARGB_8888 is RGBA in memory, as screencap writes it.
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes, frame.offset, bytes.size - frame.offset))
        if (frame.format == ScreencapRaw.RGBX_8888) bitmap.setHasAlpha(false)
        return bitmap
    }

    private companion object {
        const val TAG = "PrismCapture"
    }
}
