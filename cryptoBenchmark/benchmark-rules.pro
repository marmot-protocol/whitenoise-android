# The benchmark test lives in a separate APK, so R8 cannot see its calls to
# this comparison entry point in the optimized target APK.
-keep class dev.ipf.whitenoise.android.core.nostr.Bip340ComparisonBridge { *; }
-keep class fr.acinq.secp256k1.** { *; }
-keep class kotlin.** { *; }
