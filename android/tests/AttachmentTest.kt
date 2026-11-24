// The assistant's attachments (core/AttachmentRules.kt) on a plain JVM: how
// far a picture is scaled, which files are too big, MIME types, and the raw
// frame `screencap` writes.
//
//   java -cp <core classes>:kotlin-stdlib.jar AttachmentTestKt

import at.quickme.prism.core.AttachmentRules
import at.quickme.prism.core.AttachmentRules.Size
import at.quickme.prism.core.ScreencapRaw
import kotlin.system.exitProcess

private var failed = 0

private fun check(ok: Boolean, what: String) {
    println((if (ok) "ok   " else "FAIL ") + what)
    if (!ok) failed++
}

private fun raw(width: Int, height: Int, format: Int, header: Int): ByteArray {
    val bytes = ByteArray(header + width * height * 4)
    fun put(at: Int, v: Int) {
        for (i in 0 until 4) bytes[at + i] = (v shr (8 * i)).toByte()
    }
    put(0, width)
    put(4, height)
    put(8, format)
    if (header == 16) put(12, 0x08C20000)
    return bytes
}

fun main() {
    // --- Scaling ---
    check(AttachmentRules.fit(4000, 3000) == Size(1600, 1200), "a 12 MP photo to 1600 × 1200")
    check(AttachmentRules.fit(3000, 4000) == Size(1200, 1600), "upright too")
    check(AttachmentRules.fit(1080, 2400) == Size(720, 1600), "a phone's screenshot to 720 × 1600")
    check(AttachmentRules.fit(1600, 900) == Size(1600, 900), "already small enough: kept")
    check(AttachmentRules.fit(800, 600) == Size(800, 600), "small pictures are never enlarged")
    check(AttachmentRules.fit(1601, 1) == Size(1600, 1), "a sliver keeps at least a pixel")
    check(AttachmentRules.fit(9000, 9000) == Size(1600, 1600), "square")
    check(AttachmentRules.fit(0, 100) == Size(0, 0), "nothing to scale")
    check(AttachmentRules.fit(4000, 3000, maxEdge = 120) == Size(120, 90), "a thumbnail")

    // --- Size ---
    check(AttachmentRules.fits(0) && AttachmentRules.fits(AttachmentRules.MAX_FILE_BYTES), "up to 20 MB")
    check(!AttachmentRules.fits(AttachmentRules.MAX_FILE_BYTES + 1) && !AttachmentRules.fits(-1), "more refused")
    check(AttachmentRules.tooBig("a.pdf").contains("20 MB"), "the message names the limit")

    // --- MIME types ---
    check(AttachmentRules.mimeType("image/png", "x.jpg") == "image/png", "the provider's type wins")
    check(AttachmentRules.mimeType("IMAGE/JPG", null) == "image/jpeg", "image/jpg normalised")
    check(AttachmentRules.mimeType("text/plain; charset=utf-8", "a.txt") == "text/plain", "parameters dropped")
    check(AttachmentRules.mimeType("application/octet-stream", "Report.PDF") == "application/pdf", "octet-stream: by extension")
    check(AttachmentRules.mimeType(null, "photo.HEIC") == "image/heic", "none declared: by extension")
    check(AttachmentRules.mimeType("*/*", "notes.md") == "text/markdown", "a wildcard is no type")
    check(AttachmentRules.mimeType(null, "archive.unknown") == "application/octet-stream", "unknown extension")
    check(AttachmentRules.mimeType(null, null) == "application/octet-stream", "nothing known")
    check(AttachmentRules.reencodes("image/jpeg") && AttachmentRules.reencodes("image/heic") && AttachmentRules.reencodes("image/png"),
        "photos are re-encoded")
    check(!AttachmentRules.reencodes("image/gif") && !AttachmentRules.reencodes("image/svg+xml") && !AttachmentRules.reencodes("application/pdf"),
        "GIF, SVG and other files go as they are")
    check(AttachmentRules.jpegName("IMG_0042.HEIC") == "IMG_0042.jpg" && AttachmentRules.jpegName("scan") == "scan.jpg", "a JPEG's name")
    check(AttachmentRules.fallbackName("application/pdf") == "Document.pdf", "a nameless PDF")

    // --- screencap's raw frame ---
    val old = ScreencapRaw.parse(raw(3, 2, ScreencapRaw.RGBA_8888, 12))
    check(old != null && old.width == 3 && old.height == 2 && old.offset == 12, "a 12-byte header")
    val new = ScreencapRaw.parse(raw(1080, 2400, ScreencapRaw.RGBX_8888, 16))
    check(new != null && new.width == 1080 && new.height == 2400 && new.offset == 16 && new.format == ScreencapRaw.RGBX_8888,
        "a 16-byte header with the data space")
    check(ScreencapRaw.parse(raw(3, 2, 4, 12)) == null, "RGB 565 refused")
    check(ScreencapRaw.parse(raw(3, 2, ScreencapRaw.RGBA_8888, 12).copyOf(30)) == null, "a cut frame refused")
    check(ScreencapRaw.parse(ByteArray(8)) == null, "too short")
    check(ScreencapRaw.parse("\u0089PNG\r\n\u001a\n0000000000".toByteArray(Charsets.ISO_8859_1)) == null, "a PNG is no raw frame")

    println(if (failed == 0) "all attachment checks passed" else "$failed attachment check(s) failed")
    exitProcess(if (failed == 0) 0 else 1)
}
