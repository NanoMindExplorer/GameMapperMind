# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

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
-keep class com.nanomindexplorer.gamemappermind.ITouchService** { *; }
-keep interface com.nanomindexplorer.gamemappermind.ITouchService** { *; }

# === Shizuku UserService entry point (must not be renamed) ===
-keep class com.nanomindexplorer.gamemappermind.TouchDaemonService { *; }

# === Capacitor plugin bridge (referenced from JS by reflection) ===
-keep class com.nanomindexplorer.gamemappermind.TouchInjectionPlugin { *; }
-keepclassmembers class com.nanomindexplorer.gamemappermind.TouchInjectionPlugin {
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
-keep class com.nanomindexplorer.gamemappermind.GamepadListenerService { *; }
-keep class com.nanomindexplorer.gamemappermind.TouchAccessibilityService { *; }
-keep class com.nanomindexplorer.gamemappermind.MainActivity { *; }

# === JavaScript bridge interface in FloatingOverlayService ===
-keepclassmembers class com.nanomindexplorer.gamemappermind.FloatingOverlayService$WebAppInterface {
    public *;
}
