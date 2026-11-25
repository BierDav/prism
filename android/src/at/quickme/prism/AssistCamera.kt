// The + menu's camera, in place: Gemini's inline camera (the Google app,
// GSA 17.76: assistant_robin_inline_camera_*). The Camera tile turns the
// attach menu into a live preview, and the photo taken becomes an attachment
// chip as a picked photo does; the overlay never leaves for the camera app.
//
// - The preview fills the menu's card, 12 dp in, its corners 20 dp, 3:4 (at
//   most 55 % of the screen's height), cropped to fill.
// - On it, Gemini's controls: 48 dp circles in black at 50 % with white
//   glyphs, 12 dp in: Back to the menu at the top left, Flip camera at the
//   top right (when the phone has a front and a back camera), and at the
//   bottom the shutter, a white disc in a 6 dp white ring (72 dp), dimmed
//   while the photo is taken.
// - Back (the system's too) closes the camera and shows the menu again.
//
// CameraX (deps/roots.txt): a Preview shown by camera-compose's viewfinder
// and an ImageCapture, bound to the overlay's lifecycle (unbound when the
// camera closes; CameraX closes the device when the overlay stops). The
// build merges no library manifests, so CameraX's Camera2 backend is set in
// code (OverlayCamera.provider) rather than by camera-camera2's manifest.
// The photo is written upright as a JPEG in Prism's cache and read like any
// pick (AttachmentReader, through CameraShots' FileProvider URI). CAMERA is a
// runtime permission the Device Owner grants itself (Grants), as for the
// phone's tools; AssistActivity asks for it otherwise.

package at.quickme.prism

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.util.Size
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.camera.camera2.Camera2Config
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import at.quickme.prism.ui.GeminiIcons
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.guava.await

/** Gemini's inline camera controls: black at 50 % under white glyphs. */
private val ControlScrim = Color.Black.copy(alpha = 0.5f)
private val ControlSize = 48.dp
private val ShutterSize = 72.dp
private val ShutterRing = 6.dp
private val PreviewRadius = 20.dp

private const val UNAVAILABLE = "Camera unavailable"
private const val CAPTURE_FAILED = "Couldn't take photo. Try again."

/**
 * The camera as it shows in the + menu, without the camera itself:
 * [viewfinder] draws the preview (CameraX's in the app, a picture in the
 * previews). [onFlip] is null when there is one camera; [busy] while a
 * photo is taken; [error] over the preview when there is one.
 */
@Composable
internal fun CameraSheet(
    viewfinder: @Composable (Modifier) -> Unit,
    busy: Boolean,
    error: String?,
    onShutter: () -> Unit,
    onFlip: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(12.dp)
            .semantics { contentDescription = "Camera preview" },
    ) {
        val height = minOf(maxWidth * 4f / 3f, maxHeight)
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(PreviewRadius))
                .background(Color.Black),
        ) {
            viewfinder(Modifier.fillMaxWidth().height(height))
            CameraControl(Icons.AutoMirrored.Rounded.ArrowBack, "Back to Add Menu", onClose, Modifier.align(Alignment.TopStart).padding(12.dp))
            if (onFlip != null) {
                CameraControl(GeminiIcons.FlipCamera, "Flip camera", onFlip, Modifier.align(Alignment.TopEnd).padding(12.dp), iconSize = 40.dp)
            }
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 24.dp)
                        .background(ControlScrim, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            Shutter(busy, onShutter, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun CameraControl(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier, iconSize: androidx.compose.ui.unit.Dp = 24.dp) {
    Box(
        modifier
            .size(ControlSize)
            .clip(CircleShape)
            .background(ControlScrim)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(iconSize), tint = Color.White)
    }
}

/** Gemini's shutter: a white disc in a white ring; dimmed while the photo is taken. */
@Composable
private fun Shutter(busy: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .size(ShutterSize)
            .clip(CircleShape)
            .clickable(enabled = !busy, role = Role.Button, onClickLabel = "Take photo", onClick = onClick)
            .semantics { contentDescription = "Take photo" },
    ) {
        Canvas(Modifier.size(ShutterSize)) {
            val ring = ShutterRing.toPx()
            drawCircle(Color.Black.copy(alpha = 0.2f))
            drawCircle(Color.White, radius = size.minDimension / 2 - ring / 2, style = Stroke(ring))
            drawCircle(Color.White.copy(alpha = if (busy) 0.4f else 1f), radius = size.minDimension / 2 - ring - 4.dp.toPx())
        }
    }
}

/**
 * The camera in the + menu, live: the back camera first, flipped to the
 * front and back; [onPhoto] gets the photo taken (a content URI in Prism's
 * cache), [onClose] goes back to the menu.
 */
@Composable
internal fun InlineCamera(onPhoto: (Uri) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val rotation = LocalView.current.display?.rotation ?: Surface.ROTATION_0
    var front by rememberSaveable { mutableStateOf(false) }
    var request by remember { mutableStateOf<SurfaceRequest?>(null) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var flippable by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val preview = remember {
        Preview.Builder().build().apply {
            setSurfaceProvider(object : Preview.SurfaceProvider {
                override fun onSurfaceRequested(surface: SurfaceRequest) {
                    request = surface
                }
            })
        }
    }
    val capture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)
            // About what goes to the model (1600 px, AttachmentReader), not the sensor's full size.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(2048, 1536), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build(),
            )
            .build()
    }
    // The photo is decoded, turned upright and written off the main thread.
    val saver = remember { Executors.newSingleThreadExecutor() }
    BackHandler(onBack = onClose)

    LaunchedEffect(front) {
        val p = provider ?: try {
            OverlayCamera.provider(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = UNAVAILABLE
            return@LaunchedEffect
        }
        provider = p
        val back = runCatching { p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
        val selfie = runCatching { p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
        flippable = back && selfie
        val selector = if (front && selfie || !back) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        error = try {
            p.unbind(preview, capture)
            p.bindToLifecycle(owner, selector, preview, capture)
            null
        } catch (e: Exception) {
            UNAVAILABLE
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { provider?.unbind(preview, capture) }
            saver.shutdown()
        }
    }

    CameraSheet(
        viewfinder = { m -> request?.let { CameraXViewfinder(surfaceRequest = it, modifier = m) } },
        busy = busy,
        error = error,
        onShutter = shutter@{
            if (busy || provider == null) return@shutter
            busy = true
            error = null
            val main = context.mainExecutor
            capture.takePicture(
                saver,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val uri = runCatching { image.use { CameraShots.save(context, it) } }.getOrNull()
                        main.execute {
                            busy = false
                            if (uri != null) onPhoto(uri) else error = CAPTURE_FAILED
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        main.execute {
                            busy = false
                            error = CAPTURE_FAILED
                        }
                    }
                },
            )
        },
        onFlip = if (flippable) ({ front = !front }) else null,
        onClose = onClose,
        modifier = modifier,
    )
}

/** CameraX for the overlay: its Camera2 backend, set once in code (no manifest merging in this build). */
internal object OverlayCamera {
    private var configured = false

    suspend fun provider(context: Context): ProcessCameraProvider {
        synchronized(this) {
            if (!configured) {
                // Throws when CameraX was set up already (a second overlay in this process).
                runCatching { ProcessCameraProvider.configureInstance(Camera2Config.defaultConfig()) }
                configured = true
            }
        }
        return ProcessCameraProvider.getInstance(context.applicationContext).await()
    }
}

/**
 * Where the overlay's photos go: a file in Prism's cache (the ones before
 * are deleted), read back through Prism's FileProvider
 * (res/xml/attach_paths.xml) as the pickers' picks are.
 */
internal object CameraShots {
    private fun next(context: Context): File {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, "photo-${System.currentTimeMillis()}.jpg")
    }

    /** Writes [image] upright as a JPEG and returns its URI. */
    fun save(context: Context, image: ImageProxy): Uri {
        val taken = image.toBitmap()
        val degrees = image.imageInfo.rotationDegrees
        val upright = if (degrees == 0) taken else
            Bitmap.createBitmap(taken, 0, 0, taken.width, taken.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        val file = next(context)
        FileOutputStream(file).use { upright.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".attach", file)
    }
}
