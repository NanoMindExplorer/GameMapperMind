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
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# GameMapper Shizuku & AIDL rules
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-keep class com.nanomindexplorer.gamemappermind.** { *; }
-keep class com.getcapacitor.** { *; }

# BUG-K3 FIX: Reflection targets used in TouchDaemonService (InputManager.getInstance, injectInputEvent)
# Without these rules, R8 may strip/rename these methods and touch injection will fail at runtime.
-keep class android.view.InputManager { *; }
-keep class android.view.InputEvent { *; }
-keep class android.view.MotionEvent { *; }
-keep class android.view.MotionEvent$PointerProperties { *; }
-keep class android.view.MotionEvent$PointerCoords { *; }

# BUG-PROGUARD-DEAD FIX: Removed Choreographer keep rules — GamepadJniPlugin now uses
# Handler(Looper.getMainLooper()) for event batching (Choreographer crashed on binder threads
# because it requires a Looper). The Choreographer class is no longer referenced anywhere
# in the codebase, so the rules were dead weight and misled readers.

# AIDL generated classes (must be kept for Shizuku IPC)
-keep class com.nanomindexplorer.gamemappermind.ITouchService { *; }
-keep class com.nanomindexplorer.gamemappermind.ITouchService$Stub { *; }
-keep class com.nanomindexplorer.gamemappermind.ICommandOutputListener { *; }
-keep class com.nanomindexplorer.gamemappermind.ICommandOutputListener$Stub { *; }

# Keep @Keep annotated classes
-keep,allowobfuscation @interface androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }

# BUG-LOG1 FIX: Strip debug logs in release builds to reduce APK size and prevent info leakage.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
