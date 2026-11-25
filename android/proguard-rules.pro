# R8 rules of the app itself (default.nix adds the manifest's components,
# the libraries' rules and ServiceLoader's classes).

# Stack traces stay readable; the APK is for one phone, not for hiding code.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# The shell server's entry points: app_process runs them by name from the
# APK (android/shizuku/README.md, the starter's contract; shell/UserServices.kt).
-keep class at.quickme.prism.shell.Server { public static void main(java.lang.String[]); }
-keep class at.quickme.prism.shell.UserServiceStarter { public static void main(java.lang.String[]); }
# The hotspot's tool, run the same way by Hotspot.kt (shell/HotspotTool.kt).
-keep class at.quickme.prism.shell.HotspotTool { public static void main(java.lang.String[]); }
# Shizuku apps unparcel it by this name (moe/shizuku/api/BinderContainer.kt).
-keep class moe.shizuku.api.BinderContainer { *; }

# NetBird's gobind bindings: Go calls into them, and into the app's
# implementations of their interfaces, through JNI.
-keep class go.** { *; }
-keep class io.netbird.gomobile.** { *; }
-keep class * implements io.netbird.gomobile.android.** { *; }

# androidx.window (through Compose's window size classes) calls the device's
# window extensions and sidecar only when the device has them; they are not in
# android.jar.
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**

# shared/ (Kotlin Multiplatform): kotlinx-serialization's own rules (in its
# jar, META-INF/com.android.tools/r8) keep the serializers it looks up; these
# keep them for the shared module's classes even where a lookup goes by
# reflection alone (Ktor's body<T>(), serializer<T>()), and their
# annotations.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep,includedescriptorclasses class at.quickme.prism.shared.**$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class at.quickme.prism.shared.** {
    static ** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class at.quickme.prism.shared.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# shared/'s Rocky replica: JNA binds the generated bindings'
# (at.quickme.prism.rocky) native methods and structures by name, and its own
# classes are called from libjnidispatch.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class at.quickme.prism.rocky.** { *; }
-dontwarn java.awt.**

# The assistant's harness (assistant/Harness.kt) on JetBrains Koog: its
# prompts and messages are kotlinx-serialization classes (the conversation is
# stored as JSON), looked up through their companions; Koog's optional
# integrations (JVM server, Jackson, schema generation by reflection, SLF4J
# bindings) aren't on Android's class path and never run.
-keep,includedescriptorclasses class ai.koog.**$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ai.koog.** {
    static ** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class ai.koog.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class at.quickme.prism.assistant.**$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class at.quickme.prism.assistant.** {
    static ** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# Ktor's debugger detection (through Koog's Ktor server modules) asks the
# JVM's management API, which Android doesn't have; it's never reached here.
-dontwarn java.lang.management.**

# CameraX's Guava (the overlay's camera, AssistCamera.kt) and its other
# annotation-only dependencies: annotations deps/resolve.py leaves out
# (jspecify) or that only checkers read; nothing reads them at run time.
-dontwarn org.jspecify.annotations.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
