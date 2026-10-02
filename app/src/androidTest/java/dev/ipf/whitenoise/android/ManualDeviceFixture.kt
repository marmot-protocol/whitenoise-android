package dev.ipf.whitenoise.android

/** Excludes tests that need an explicit device, account, relay, or performance fixture from CI. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class ManualDeviceFixture
