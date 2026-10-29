# Keep stack traces readable
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- JNA (used by UniFFI) ---
-dontwarn java.awt.**
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }

# --- UniFFI / marmotkit bindings ---
-keep class dev.ipf.marmotkit.** { *; }
-keepclassmembers class dev.ipf.marmotkit.** { *; }

# --- ML Kit barcode scanning ---
-dontwarn com.google.mlkit.**

# --- CameraX ---
-dontwarn androidx.camera.**

# --- Coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# --- Still-image worker application zygote ---
# The manifest names this class only through android:zygotePreloadName, so keep its
# exact name and entry point so the zygote can load it in minified builds.
-keep class dev.ipf.whitenoise.android.media.sandbox.MediaZygotePreload {
    public <init>();
    public void doPreload(android.content.pm.ApplicationInfo);
}
