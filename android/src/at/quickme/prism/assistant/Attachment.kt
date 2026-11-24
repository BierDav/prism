// What the assistant overlay hands its harness besides the text: pictures and
// files the owner attached with the composer's +, and screenshots of the
// phone's screen, which the harness can take itself whenever a request needs
// one (no confirmation).

package at.quickme.prism.assistant

/**
 * An image or file attached to a request: its bytes, MIME type
 * (`image/png`, `image/jpeg`, `application/pdf`, …) and a name to show.
 * [screenshot] marks a capture of the phone's screen.
 */
class AssistAttachment(
    val bytes: ByteArray,
    val mimeType: String,
    val name: String,
    val screenshot: Boolean = false,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

/** Captures the phone's screen as it is under the overlay; null when it can't. */
fun interface ScreenCapture {
    suspend fun capture(): AssistAttachment?

    companion object {
        /** No capture on this phone (previews, tests). */
        val None = ScreenCapture { null }
    }
}
