# The test runner discovers instrumentation and JUnit test members reflectively.
-keep class androidx.test.** { *; }
-keep class androidx.tracing.** { *; }
-keep class org.junit.** { *; }
-keep class dev.ipf.whitenoise.android.core.nostr.Bip340PhysicalBenchmark { *; }
-keep class kotlin.** { *; }

# AndroidX Test references source-retention Error Prone annotations only.
-dontwarn com.google.errorprone.annotations.**
