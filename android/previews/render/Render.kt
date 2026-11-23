// Renders every @Preview in a directory of compiled classes to a PNG, without
// a phone or Android Studio: Paparazzi's PaparazziSdk drives layoutlib (the
// renderer Android Studio uses) on a plain JVM, with the app's resources and
// its libraries' (default.nix, `previews`).
//
//   Render OUT CLASSES APP_RES PACKAGES LIBRARY_RES [ONLY]
//
// OUT gets <function>-<preview name>.png per preview. CLASSES is scanned for
// functions annotated with @Preview, its repeatable container, or an
// annotation that carries @Preview itself (a multipreview such as
// @PreviewLightDark). APP_RES is the app's res/; PACKAGES lists the packages
// with an R class (the app's first) and LIBRARY_RES the libraries' res/
// directories, one per line. ONLY, a regular expression, renders just the
// previews whose output name it finds.
//
// What a preview may say: name, uiMode (night), widthDp, heightDp,
// showBackground, backgroundColor, fontScale and device. With a device or
// heightDp the preview is a whole screen (the owner's Pixel 9 Pro unless
// widthDp or heightDp say otherwise); without, it is as tall as its content.

package at.quickme.prism.previews

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Typeface
import android.util.TypedValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Environment
import app.cash.paparazzi.PaparazziSdk
import androidx.core.content.res.ResourcesCompat
import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import net.bytebuddy.ByteBuddy
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy
import net.bytebuddy.implementation.MethodDelegation
import net.bytebuddy.implementation.bind.annotation.Argument
import net.bytebuddy.implementation.bind.annotation.This
import net.bytebuddy.jar.asm.AnnotationVisitor
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.takesArguments
import java.io.File
import java.lang.reflect.Method
import javax.imageio.ImageIO
import kotlin.system.exitProcess

private const val PREVIEW = "Landroidx/compose/ui/tooling/preview/Preview;"
private const val CONTAINER = "Landroidx/compose/ui/tooling/preview/Preview\$Container;"
private const val COMPOSABLE_NO_ARGS = "(Landroidx/compose/runtime/Composer;I)V"

/** The owner's phone. */
private val PHONE = DeviceConfig.PIXEL_9_PRO

/** An annotation as written: its type and values (nested annotations as Annotation, arrays as lists). */
private class Annotation(val desc: String, val values: MutableMap<String, Any?> = mutableMapOf())

private class Collector(private val into: (String?, Any?) -> Unit) : AnnotationVisitor(Opcodes.ASM9) {
    override fun visit(name: String?, value: Any?) = into(name, value)

    override fun visitEnum(name: String?, descriptor: String?, value: String?) = into(name, value)

    override fun visitAnnotation(name: String?, descriptor: String): AnnotationVisitor {
        val nested = Annotation(descriptor)
        into(name, nested)
        return Collector { n, v -> nested.values[n!!] = v }
    }

    override fun visitArray(name: String?): AnnotationVisitor {
        val list = mutableListOf<Any?>()
        into(name, list)
        return Collector { _, v -> list.add(v) }
    }
}

private fun collect(desc: String, sink: MutableList<Annotation>): AnnotationVisitor {
    val a = Annotation(desc)
    sink.add(a)
    return Collector { n, v -> a.values[n!!] = v }
}

/** One preview to render. */
private data class Spec(
    val output: String,
    val method: Method,
    val values: Map<String, Any?>,
) {
    private fun int(key: String) = (values[key] as? Int) ?: 0

    val night get() = (int("uiMode") and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val widthDp get() = int("widthDp")
    val heightDp get() = int("heightDp")
    val device get() = values["device"] as? String ?: ""
    val fontScale get() = (values["fontScale"] as? Float)?.takeIf { it > 0f } ?: 1f
    val showBackground get() = values["showBackground"] == true
    val backgroundColor get() = (values["backgroundColor"] as? Long) ?: 0L
    val screen get() = device.isNotEmpty() || heightDp > 0
}

private class Scanner(private val loader: ClassLoader) {
    private val expanded = HashMap<String, List<Map<String, Any?>>>()

    /** The @Preview values an annotation stands for: itself, its container's, or a multipreview's. */
    fun previews(a: Annotation, seen: Set<String> = emptySet()): List<Map<String, Any?>> = when (a.desc) {
        PREVIEW -> listOf(a.values)
        CONTAINER -> (a.values["value"] as? List<*>).orEmpty().filterIsInstance<Annotation>().map { it.values }
        else -> if (a.desc in seen) emptyList() else expanded.getOrPut(a.desc) { multipreview(a.desc, seen + a.desc) }
    }

    private fun multipreview(desc: String, seen: Set<String>): List<Map<String, Any?>> {
        val path = desc.removePrefix("L").removeSuffix(";") + ".class"
        val bytes = loader.getResourceAsStream(path)?.use { it.readBytes() } ?: return emptyList()
        val found = mutableListOf<Annotation>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(descriptor: String, visible: Boolean) = collect(descriptor, found)
        }, ClassReader.SKIP_CODE)
        return found.flatMap { previews(it, seen) }
    }

    fun scan(dir: File): List<Spec> {
        val specs = mutableListOf<Spec>()
        dir.walk().filter { it.isFile && it.name.endsWith(".class") }.sortedBy { it.path }.forEach { file ->
            val methods = mutableListOf<Pair<String, List<Annotation>>>()
            var className = ""
            ClassReader(file.readBytes()).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(v: Int, access: Int, name: String, sig: String?, sup: String?, ifs: Array<out String>?) {
                    className = name.replace('/', '.')
                }

                override fun visitMethod(access: Int, name: String, desc: String, sig: String?, ex: Array<out String>?): MethodVisitor? {
                    if (access and Opcodes.ACC_STATIC == 0 || desc != COMPOSABLE_NO_ARGS) return null
                    val annotations = mutableListOf<Annotation>()
                    methods.add(name to annotations)
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(descriptor: String, visible: Boolean) = collect(descriptor, annotations)
                    }
                }
            }, ClassReader.SKIP_CODE)
            for ((name, annotations) in methods) {
                val all = annotations.flatMap { previews(it) }
                if (all.isEmpty()) continue
                val method = Class.forName(className, false, loader)
                    .getDeclaredMethod(name, Composer::class.java, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                val base = name.removeSuffix("Preview")
                all.forEachIndexed { i, values ->
                    val label = (values["name"] as? String)?.takeIf { it.isNotEmpty() }
                        ?.lowercase()?.replace(Regex("[^a-z0-9]+"), "-")?.trim('-')
                        ?: if (all.size > 1) "$i" else ""
                    specs.add(Spec(if (label.isEmpty()) base else "$base-$label", method, values))
                }
            }
        }
        return specs
    }
}

/**
 * The app's own fonts (res/font): layoutlib's Resources.getFont hands back
 * its fallback for them and ResourcesCompat.getFont (which Compose's
 * Font(resId) uses) fails, though layoutlib reads the same file well from a
 * path. Both are redirected to that.
 */
object AppFonts {
    private val cache = HashMap<String, Typeface>()

    private fun load(resources: Resources, id: Int): Typeface {
        val value = TypedValue()
        resources.getValue(id, value, true)
        val path = value.string.toString()
        return cache.getOrPut(path) { Typeface.createFromFile(path) }
    }

    object FromResources {
        @JvmStatic
        fun getFont(@This resources: Resources, @Argument(0) id: Int): Typeface = load(resources, id)
    }

    object FromCompat {
        @JvmStatic
        fun getFont(@Argument(0) context: Context, @Argument(1) id: Int): Typeface = load(context.resources, id)
    }

    private fun redirect(type: Class<*>, arguments: List<Class<*>>, to: Class<*>) {
        ByteBuddy().redefine(type)
            .method(named<MethodDescription>("getFont").and(takesArguments(*arguments.toTypedArray())))
            .intercept(MethodDelegation.to(to))
            .make()
            .load(type.classLoader, ClassReloadingStrategy.fromInstalledAgent())
    }

    fun install() {
        redirect(Resources::class.java, listOf(Int::class.javaPrimitiveType!!), FromResources::class.java)
        redirect(ResourcesCompat::class.java, listOf(Context::class.java, Int::class.javaPrimitiveType!!), FromCompat::class.java)
    }
}

private fun deviceFor(s: Spec): DeviceConfig {
    val dpi = PHONE.density.dpiValue
    fun px(dp: Int) = (dp * dpi + 80) / 160
    return PHONE.copy(
        screenWidth = if (s.widthDp > 0) px(s.widthDp) else PHONE.screenWidth,
        screenHeight = if (s.heightDp > 0) px(s.heightDp) else PHONE.screenHeight,
        // Wider than tall (a tablet or a TV): layoutlib keeps it so only in landscape.
        orientation = if (s.widthDp > s.heightDp && s.heightDp > 0) ScreenOrientation.LANDSCAPE else PHONE.orientation,
        nightMode = if (s.night) NightMode.NIGHT else NightMode.NOTNIGHT,
        fontScale = s.fontScale,
        locale = "en",
    )
}

fun main(args: Array<String>) {
    if (args.size < 5) {
        System.err.println("usage: Render OUT CLASSES APP_RES PACKAGES LIBRARY_RES [ONLY]")
        exitProcess(2)
    }
    val out = File(args[0]).apply { mkdirs() }
    val classes = File(args[1])
    val packages = File(args[3]).readLines().map { it.trim() }.filter { it.isNotEmpty() }
    val libraryRes = File(args[4]).readLines().map { it.trim() }.filter { it.isNotEmpty() }
    val only = args.getOrNull(5)?.let(::Regex)

    val environment = Environment(
        appTestDir = out.path,
        packageName = packages.first(),
        compileSdkVersion = 36,
        resourcePackageNames = packages,
        localResourceDirs = listOf(args[2]),
        moduleResourceDirs = emptyList(),
        libraryResourceDirs = libraryRes,
        allModuleAssetDirs = emptyList(),
        libraryAssetDirs = emptyList(),
    )

    val specs = Scanner(Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()).scan(classes)
        .filter { only == null || only.containsMatchIn(it.output) }
    val names = specs.groupBy { it.output }.filterValues { it.size > 1 }.keys
    require(names.isEmpty()) { "two previews render to the same file: $names" }
    if (specs.isEmpty()) {
        System.err.println("no previews in $classes")
        exitProcess(1)
    }

    val failed = mutableListOf<String>()
    var patched = false
    for (s in specs) {
        val started = System.nanoTime()
        val file = File(out, s.output + ".png")
        var frames = 0
        val sdk = PaparazziSdk(
            environment = environment,
            deviceConfig = deviceFor(s),
            // The window behind the content, light or dark like the preview.
            theme = if (s.night) "android:Theme.Material.NoActionBar" else "android:Theme.Material.Light.NoActionBar",
            renderingMode = if (s.screen) RenderingMode.NORMAL else RenderingMode.SHRINK,
            useDeviceResolution = true,
            onNewFrame = { image ->
                frames++
                ImageIO.write(image, "PNG", file)
            },
        )
        try {
            sdk.setup()
            if (!patched) {
                AppFonts.install()
                patched = true
            }
            sdk.prepare()
            try {
                sdk.snapshot {
                    // As in Android Studio: screens leave out what only works on a phone.
                    // No activity: a root dispatcher for Back (Navigation 3's NavDisplay needs one).
                    CompositionLocalProvider(
                        LocalInspectionMode provides true,
                        LocalNavigationEventDispatcherOwner provides rememberNavigationEventDispatcherOwner(parent = null),
                    ) {
                        if (s.showBackground) {
                            val color = if (s.backgroundColor != 0L) Color(s.backgroundColor.toInt()) else
                                if (s.night) Color.Black else Color.White
                            Box(Modifier.background(color)) { s.method.invoke(null, currentComposer, 0) }
                        } else {
                            s.method.invoke(null, currentComposer, 0)
                        }
                    }
                }
            } finally {
                sdk.teardown()
            }
            check(frames == 1 && file.isFile) { "no image" }
            println("%-40s %5d ms  %s".format(s.output, (System.nanoTime() - started) / 1_000_000, file))
        } catch (e: Throwable) {
            failed.add(s.output)
            System.err.println("${s.output}: failed")
            e.printStackTrace()
        }
    }
    if (failed.isNotEmpty()) {
        System.err.println("${failed.size} of ${specs.size} previews failed: ${failed.joinToString(", ")}")
        exitProcess(1)
    }
    println("${specs.size} previews in $out")
    // Layoutlib leaves threads behind.
    exitProcess(0)
}
