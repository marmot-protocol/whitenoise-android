# The benchmark test lives in a separate APK, so R8 cannot see its calls to
# this comparison entry point in the optimized target APK.
-keep class dev.ipf.whitenoise.android.core.nostr.Bip340ComparisonBridge { *; }
-keep class dev.ipf.whitenoise.android.core.nostr.NativeVerifierCorrectnessBridge { *; }
# JNA loads generated interface/callback names reflectively, as in the app.
-dontwarn java.awt.**
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keep class dev.ipf.marmotkit.** { *; }
-keep class io.crates.keyring.** { *; }
-keep class kotlin.** { *; }
