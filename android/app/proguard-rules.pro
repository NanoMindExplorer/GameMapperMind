# GameMapperMind ProGuard Rules

# ============================================================
# CRITICAL: Disable R8 optimization
# R8 was optimizing away the handleTrigger hysteresis logic
# and lastState map access, causing LT/RT/LB/RB to not inject
# in release builds. -dontoptimize keeps code as-is (no inlining,
# no dead code elimination within kept classes).
# Shrinking (removing truly unused classes) still works.
# ============================================================
-dontoptimize

# Keep all class members (fields, methods) in app package
-keep class com.nanomindexplorer.gamemappermind.** { *; }
-keep interface com.nanomindexplorer.gamemappermind.** { *; }

# ============================================================
# Shizuku & AIDL (must keep for IPC)
# ============================================================
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }

# AIDL generated classes
-keep class com.nanomindexplorer.gamemappermind.ITouchService { *; }
-keep class com.nanomindexplorer.gamemappermind.ITouchService$Stub { *; }
-keep class com.nanomindexplorer.gamemappermind.ICommandOutputListener { *; }
-keep class com.nanomindexplorer.gamemappermind.ICommandOutputListener$Stub { *; }

# ============================================================
# Capacitor (plugin registration via reflection)
# ============================================================
-keep class com.getcapacitor.** { *; }
-keep @com.getcapacitor.annotation.CapacitorPlugin class * { *; }
-keepclassmembers class * {
    @com.getcapacitor.annotation.PluginMethod *;
}

# ============================================================
# Reflection targets in TouchDaemonService (Path A/B injection)
# ============================================================
-keep class android.os.ServiceManager { *; }
-keep class android.os.IServiceManager { *; }
-keep class android.hardware.input.IInputManager { *; }
-keep class android.hardware.input.IInputManager$Stub { *; }
-keep class android.hardware.input.IInputManager$Stub$Proxy { *; }
-keep class android.hardware.input.InputManager { *; }
-keep class android.view.InputEvent { *; }
-keep class android.view.MotionEvent { *; }
-keep class android.view.MotionEvent$PointerProperties { *; }
-keep class android.view.MotionEvent$PointerCoords { *; }
-keep class android.app.ActivityThread { *; }

# ============================================================
# Kotlin metadata (prevent R8 from stripping Kotlin-specific info)
# ============================================================
-keepattributes KotlinMetadata,*Annotation*
-keep class kotlin.Metadata { *; }

# ============================================================
# Keep line numbers for crash reports
# ============================================================
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ============================================================
# Strip debug logs in release (but keep warnings/errors)
# ============================================================
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
