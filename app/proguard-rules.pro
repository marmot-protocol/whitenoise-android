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
