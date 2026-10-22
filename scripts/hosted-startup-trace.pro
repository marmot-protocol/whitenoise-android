# AndroidJUnitRunner calls this shared dependency before test discovery.
# Retain it only for the opt-in, minified startup-trace app variant.
-keep class androidx.tracing.Trace { *; }
