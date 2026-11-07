package dev.ipf.whitenoise.android.media.sandbox

/** Selects only the three required raw, paced and real-control methods for the hosted AV trace. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class MediaAvTraceCase
