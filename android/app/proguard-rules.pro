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
