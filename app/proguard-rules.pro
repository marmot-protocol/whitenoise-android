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
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# --- CameraX ---
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# --- Coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# --- Compose: usually safe out-of-box, but guard against odd reflection ---
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
