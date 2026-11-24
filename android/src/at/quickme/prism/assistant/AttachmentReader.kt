// Turns what the owner picks with the overlay's + (a photo from the camera
// or the Photo Picker, any document) and the phone's screen into
// AssistAttachments: pictures decoded at most 1600 px on their longer edge
// and sent as JPEG (orientation applied, metadata dropped), other files as
// they are up to 20 MB (core/AttachmentRules.kt). Each comes with a small
// thumbnail for the composer's chips.

package at.quickme.prism.assistant

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import at.quickme.prism.core.AttachmentRules
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An attachment in the composer: what goes with the request, and its chip's picture. */
internal class Attached(val attachment: AssistAttachment, val thumbnail: ImageBitmap?)

/** What reading a pick gave: an attachment, or why not. */
internal sealed interface Read {
    class Ok(val attached: Attached) : Read
    class Failed(val message: String) : Read
}

internal object AttachmentReader {
    private const val TAG = "PrismAttach"

    /** The thumbnails' longer edge (px): the chips show them 40 dp round. */
    private const val THUMBNAIL_EDGE = 160

    /** Reads [uri] (off the main thread). */
    suspend fun read(context: Context, uri: Uri): Read = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val (name, size) = describe(resolver, uri)
        val mime = AttachmentRules.mimeType(resolver.getType(uri), name)
        val shown = name ?: AttachmentRules.fallbackName(mime)
        try {
            if (AttachmentRules.reencodes(mime)) {
                picture(resolver, uri, shown)?.let { return@withContext Read.Ok(it) }
                // Not a picture Android decodes: it goes as a file.
            }
            if (size != null && !AttachmentRules.fits(size)) return@withContext Read.Failed(AttachmentRules.tooBig(shown))
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > AttachmentRules.MAX_FILE_BYTES) return@withContext Read.Failed(AttachmentRules.tooBig(shown))
                }
                out.toByteArray()
            } ?: return@withContext Read.Failed("Couldn't open “$shown”.")
            if (bytes.isEmpty()) return@withContext Read.Failed("“$shown” is empty.")
            Read.Ok(Attached(AssistAttachment(bytes, mime, shown), null))
        } catch (e: IOException) {
            Log.w(TAG, "read $uri", e)
            Read.Failed("Couldn't read “$shown”.")
        } catch (e: SecurityException) {
            Log.w(TAG, "read $uri", e)
            Read.Failed("Prism may not read “$shown”.")
        }
    }

    /** A capture of the phone's screen as an attachment (off the main thread). */
    suspend fun screenshot(bitmap: Bitmap): Attached = withContext(Dispatchers.Default) {
        val name = "Screenshot " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT).format(Date()) + ".jpg"
        val scaled = scale(bitmap)
        val attached = Attached(AssistAttachment(jpeg(scaled), "image/jpeg", name, screenshot = true), thumbnail(scaled))
        if (scaled !== bitmap) scaled.recycle()
        attached
    }

    /** The display name and size the provider knows. */
    private fun describe(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val file = uri.path?.let { java.io.File(it) }
            return file?.name to file?.length()
        }
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null to null
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
                val s = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
                n?.takeIf { it.isNotBlank() } to s
            } ?: (null to null)
        } catch (e: RuntimeException) {
            null to null
        }
    }

    /** [uri] decoded no larger than the model needs, as a JPEG; null if Android can't decode it. */
    private fun picture(resolver: ContentResolver, uri: Uri, name: String): Attached? {
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val fit = AttachmentRules.fit(info.size.width, info.size.height)
                if (fit.width > 0) decoder.setTargetSize(fit.width, fit.height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: IOException) {
            Log.w(TAG, "decode $uri", e)
            return null
        }
        return try {
            Attached(AssistAttachment(jpeg(bitmap), "image/jpeg", AttachmentRules.jpegName(name)), thumbnail(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    private fun scale(bitmap: Bitmap): Bitmap {
        val fit = AttachmentRules.fit(bitmap.width, bitmap.height)
        if (fit.width == bitmap.width && fit.height == bitmap.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, fit.width, fit.height, true)
    }

    /** [bitmap] as a JPEG, on white where it was transparent. */
    private fun jpeg(bitmap: Bitmap): ByteArray {
        val opaque = if (bitmap.hasAlpha()) {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
            }
        } else bitmap
        return try {
            ByteArrayOutputStream().also { opaque.compress(Bitmap.CompressFormat.JPEG, AttachmentRules.JPEG_QUALITY, it) }.toByteArray()
        } finally {
            if (opaque !== bitmap) opaque.recycle()
        }
    }

    private fun thumbnail(bitmap: Bitmap): ImageBitmap {
        val fit = AttachmentRules.fit(bitmap.width, bitmap.height, THUMBNAIL_EDGE)
        // Its own copy even when small enough: the picture itself is recycled.
        val small = if (fit.width == bitmap.width && fit.height == bitmap.height) bitmap.copy(Bitmap.Config.ARGB_8888, false)
        else Bitmap.createScaledBitmap(bitmap, fit.width.coerceAtLeast(1), fit.height.coerceAtLeast(1), true)
        return small.asImageBitmap()
    }
}
