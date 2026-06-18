# Add project specific ProGuard rules here.
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
-renamesourcefileattribute SourceFile

# === Shizuku & AIDL rules (required for runtime binding) ===
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }

# === AIDL interface (used by Shizuku UserService) ===
-keep class com.nanomindexplorer.gamemappermind.shizuku.IGameMapperService** { *; }
-keep interface com.nanomindexplorer.gamemappermind.shizuku.IGameMapperService** { *; }

# === Shizuku UserService entry point (must not be renamed) ===
-keep class com.nanomindexplorer.gamemappermind.shizuku.GameMapperUserService { *; }
-keepclassmembers class com.nanomindexplorer.gamemappermind.shizuku.GameMapperUserService {
    public *;
}

# === Capacitor plugin bridge (referenced from JS by reflection) ===
-keep class com.nanomindexplorer.gamemappermind.plugin.GameMapperPlugin { *; }
-keepclassmembers class com.nanomindexplorer.gamemappermind.plugin.GameMapperPlugin {
    @com.getcapacitor.annotation.CapacitorPlugin <methods>;
    public *;
}

# === Capacitor core (required to keep JS bridge working) ===
-keep class com.getcapacitor.** { *; }
-keepclassmembers class * extends com.getcapacitor.Plugin {
    @com.getcapacitor.annotation.CapacitorPlugin <methods>;
    public <methods>;
}

# === Services referenced from AndroidManifest.xml (must keep class name) ===
-keep class com.nanomindexplorer.gamemappermind.FloatingOverlayService { *; }
-keep class com.nanomindexplorer.gamemappermind.TouchAccessibilityService { *; }
-keep class com.nanomindexplorer.gamemappermind.daemon.MapperDaemonService { *; }
-keep class com.nanomindexplorer.gamemappermind.MainActivity { *; }

# === JavaScript bridge interface in FloatingOverlayService ===
-keepclassmembers class com.nanomindexplorer.gamemappermind.FloatingOverlayService$WebAppInterface {
    public *;
}

# ============================================================
# GMM-AEC-002: Keep rules untuk class baru (Batch 1-4)
# ============================================================

# === GMM-AEC-002 §9.1: HarmonyOS detection ===
-keep class com.nanomindexplorer.gamemappermind.util.HarmonyOSHelper { *; }
-keep class com.nanomindexplorer.gamemappermind.util.HarmonyOSHelper$* { *; }

# === GMM-AEC-002 §9.4: Safe area probe ===
-keep class com.nanomindexplorer.gamemappermind.util.HarmonyOSSafeAreaHelper { *; }
-keep class com.nanomindexplorer.gamemappermind.util.HarmonyOSSafeAreaHelper$* { *; }

# === GMM-AEC-002 §12.1: Native daemon logger ===
-keep class com.nanomindexplorer.gamemappermind.util.NativeDaemonLogger { *; }
-keep class com.nanomindexplorer.gamemappermind.util.NativeDaemonLogger$LogEntry { *; }

# === GMM-AEC-002 §10.1: Shizuku binder watcher ===
-keep class com.nanomindexplorer.gamemappermind.shizuku.ShizukuBinderWatcher { *; }
-keep class com.nanomindexplorer.gamemappermind.shizuku.ShizukuBinderWatcher$WatcherState { *; }
-keep class com.nanomindexplorer.gamemappermind.shizuku.ShizukuBinderWatcher$* { *; }

# === GMM-AEC-002 §10.2: FixShizukuReceiver + BootCompletedReceiver ===
-keep class com.nanomindexplorer.gamemappermind.shizuku.FixShizukuReceiver { *; }
-keep class com.nanomindexplorer.gamemappermind.shizuku.BootCompletedReceiver { *; }

# === GMM-AEC-002: GameMapperUserService (referenced from AIDL) ===
-keep class com.nanomindexplorer.gamemappermind.shizuku.GameMapperUserService { *; }
-keep class com.nanomindexplorer.gamemappermind.shizuku.GameMapperUserService$* { *; }

# === GMM-AEC-002: GameMapperPluginImpl (referenced from GameMapperUserService) ===
-keep class com.nanomindexplorer.gamemappermind.plugin.GameMapperPluginImpl { *; }

# === GMM-AEC-002: InputPipelineWorker (referenced from GameMapperPluginImpl) ===
-keep class com.nanomindexplorer.gamemappermind.daemon.InputPipelineWorker { *; }
-keep class com.nanomindexplorer.gamemappermind.daemon.InputPipelineWorker$* { *; }

# === GMM-AEC-002: TouchInjector + AnalogProcessor (referenced from InputPipelineWorker) ===
-keep class com.nanomindexplorer.gamemappermind.input.TouchInjector { *; }
-keep class com.nanomindexplorer.gamemappermind.input.TouchInjector$PointerState { *; }
-keep class com.nanomindexplorer.gamemappermind.input.AnalogProcessor { *; }
-keep class com.nanomindexplorer.gamemappermind.input.AnalogProcessor$* { *; }

# === GMM-AEC-002: MapperDaemonService (referenced from AndroidManifest.xml) ===
-keep class com.nanomindexplorer.gamemappermind.daemon.MapperDaemonService { *; }

# === GMM-AEC-002: IGameMapperService AIDL interface ===
-keep interface com.nanomindexplorer.gamemappermind.shizuku.IGameMapperService { *; }
-keep class com.nanomindexplorer.gamemappermind.shizuku.IGameMapperService$* { *; }

# === GMM-AEC-002: GameMapperPlugin Capacitor plugin ===
-keep class com.nanomindexplorer.gamemappermind.plugin.GameMapperPlugin { *; }
-keepclassmembers class com.nanomindexplorer.gamemappermind.plugin.GameMapperPlugin {
    @com.getcapacitor.annotation.CapacitorPlugin <methods>;
    @com.getcapacitor.annotation.PluginMethod <methods>;
    public *;
}

# === GMM-AEC-002: Reflection targets (InputManager.injectInputEvent) ===
-keep class android.hardware.input.InputManager { *; }
-keepclassmembers class android.hardware.input.InputManager {
    public *;
}

# === GMM-AEC-002: MotionEvent reflection (setDisplayId, setFlags, mFlags) ===
-keep class android.view.MotionEvent { *; }
-keepclassmembers class android.view.MotionEvent {
    public *;
    private *;
}

# === GMM-AEC-002 §11.3: kotlinx-serialization (ProfileModels, etc.) ===
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# === GMM-AEC-002: Keep all classes in shizuku package (AIDL-generated + reflection) ===
-keep class com.nanomindexplorer.gamemappermind.shizuku.** { *; }

# === GMM-AEC-002: Keep all classes in daemon package ===
-keep class com.nanomindexplorer.gamemappermind.daemon.** { *; }

# === GMM-AEC-002: Keep all classes in input package ===
-keep class com.nanomindexplorer.gamemappermind.input.** { *; }

# === GMM-AEC-002: Keep all classes in util package ===
-keep class com.nanomindexplorer.gamemappermind.util.** { *; }

# === Input pipeline classes (used via reflection in shell process) ===
-keep class com.nanomindexplorer.gamemappermind.input.TouchInjector { *; }
-keep class com.nanomindexplorer.gamemappermind.input.AnalogProcessor { *; }
-keep class com.nanomindexplorer.gamemappermind.daemon.InputPipelineWorker { *; }
-keep class com.nanomindexplorer.gamemappermind.plugin.GameMapperPluginImpl { *; }

# === ShizukuHelper (manages binder lifecycle) ===
-keep class com.nanomindexplorer.gamemappermind.shizuku.ShizukuHelper { *; }

# === Kotlin Serialization (runtime reflection) ===
-keepattributes *Annotation*
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
