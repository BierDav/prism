// What the assistant overlay does with the pictures, files and screenshots
// the owner attaches (AssistActivity's +, assistant/AttachmentReader.kt):
// how small a picture gets for the model, which files are too big, the MIME
// type when the picker gives none, and the raw frame `screencap` writes. Pure,
// so the JVM tests check it (tests/AttachmentTest.kt).

package at.quickme.prism.core

object AttachmentRules {
    /** Pictures are scaled so their longer edge is at most this (px), and sent as JPEG. */
    const val MAX_IMAGE_EDGE = 1600

    const val JPEG_QUALITY = 85

    /** Other files are sent as they are, up to this. */
    const val MAX_FILE_MB = 20
    const val MAX_FILE_BYTES = MAX_FILE_MB * 1024L * 1024L

    /** At most this many attachments with one request (Gemini's limit too). */
    const val MAX_ATTACHMENTS = 10

    /** A width and height in pixels. */
    data class Size(val width: Int, val height: Int)

    /**
     * [width] × [height] scaled down, aspect kept, so the longer edge is at
     * most [maxEdge]; a picture already that small keeps its size.
     */
    fun fit(width: Int, height: Int, maxEdge: Int = MAX_IMAGE_EDGE): Size {
        require(maxEdge > 0) { "maxEdge $maxEdge" }
        if (width <= 0 || height <= 0) return Size(0, 0)
        val long = maxOf(width, height)
        if (long <= maxEdge) return Size(width, height)
        val scale = maxEdge.toDouble() / long
        fun side(v: Int) = Math.round(v * scale).toInt().coerceIn(1, maxEdge)
        return if (width >= height) Size(maxEdge, side(height)) else Size(side(width), maxEdge)
    }

    /** Whether a file of [bytes] may be attached as it is. */
    fun fits(bytes: Long): Boolean = bytes in 0..MAX_FILE_BYTES

    /** What the overlay says when [name] is too big. */
    fun tooBig(name: String): String = "“$name” is larger than $MAX_FILE_MB MB; files must be $MAX_FILE_MB MB or less."

    /** What it says when the owner picked more than fit with the ones attached. */
    fun tooMany(): String = "You can attach up to $MAX_ATTACHMENTS photos and files at a time."

    private val byExtension = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
        "gif" to "image/gif", "heic" to "image/heic", "heif" to "image/heif", "avif" to "image/avif",
        "bmp" to "image/bmp", "svg" to "image/svg+xml",
        "pdf" to "application/pdf", "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv",
        "json" to "application/json", "xml" to "application/xml", "html" to "text/html", "htm" to "text/html",
        "log" to "text/plain", "yaml" to "application/yaml", "yml" to "application/yaml",
        "zip" to "application/zip", "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "ogg" to "audio/ogg", "wav" to "audio/wav",
        "mp4" to "video/mp4", "webm" to "video/webm", "mov" to "video/quicktime",
    )

    /**
     * The MIME type to send: the one the provider [declared] when it is a
     * real one, else the one [name]'s extension says, else
     * application/octet-stream.
     */
    fun mimeType(declared: String?, name: String?): String {
        val d = declared?.trim()?.lowercase()?.substringBefore(';')?.trim()
        if (d != null && d.matches(Regex("[a-z0-9.+-]+/[a-z0-9.+-]+")) && d != "application/octet-stream" && !d.endsWith("/*")) {
            return if (d == "image/jpg") "image/jpeg" else d
        }
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return byExtension[ext] ?: "application/octet-stream"
    }

    /**
     * Whether the overlay decodes and re-encodes [mimeType] as a JPEG (which
     * also drops its location and other metadata). SVG and GIF go as files:
     * one has no pixels to scale, the other would lose its animation.
     */
    fun reencodes(mimeType: String): Boolean =
        mimeType.startsWith("image/") && mimeType != "image/svg+xml" && mimeType != "image/gif"

    /** [name] as it is once re-encoded: its extension .jpg. */
    fun jpegName(name: String): String {
        val base = name.substringBeforeLast('.', name).ifBlank { "Photo" }
        return "$base.jpg"
    }

    /** A file name for something the provider gave no name for. */
    fun fallbackName(mimeType: String): String = when {
        mimeType.startsWith("image/") -> "Photo.jpg"
        mimeType == "application/pdf" -> "Document.pdf"
        else -> "File"
    }
}

/**
 * The frame `screencap` writes without -p: a header of little-endian 32-bit
 * words (width, height, pixel format, and on newer Android the data space),
 * then the rows.
 */
object ScreencapRaw {
    /** android.graphics.PixelFormat values screencap writes. */
    const val RGBA_8888 = 1
    const val RGBX_8888 = 2

    class Frame(val width: Int, val height: Int, val format: Int, val offset: Int)

    /** The frame's layout in [bytes], or null when it isn't one of 4 bytes a pixel. */
    fun parse(bytes: ByteArray): Frame? {
        if (bytes.size < 12) return null
        fun word(i: Int) = (bytes[i].toInt() and 0xff) or ((bytes[i + 1].toInt() and 0xff) shl 8) or
            ((bytes[i + 2].toInt() and 0xff) shl 16) or ((bytes[i + 3].toInt() and 0xff) shl 24)
        val width = word(0)
        val height = word(4)
        val format = word(8)
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384) return null
        if (format != RGBA_8888 && format != RGBX_8888) return null
        val pixels = width.toLong() * height * 4
        // The header is what is left over: 12 bytes, or 16 with the data space.
        val header = bytes.size - pixels
        if (header != 12L && header != 16L) return null
        return Frame(width, height, format, header.toInt())
    }
}
