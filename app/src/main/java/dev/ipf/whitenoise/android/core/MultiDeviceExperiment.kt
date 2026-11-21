package dev.ipf.whitenoise.android.core

/**
 * Compile-time switch for linked devices. It is a literal so that only a source change can alter it, never a Gradle
 * property, environment variable, intent, deep link or preference. MDK opens every runtime with device groups, so
 * there is no working `false` on this library: a build without these screens could not answer a link request its
 * own account receives. It stays only as the one name every entry point asks.
 */
internal const val MULTI_DEVICE_EXPERIMENT = true

/** Whether linked-device entry points are part of this build. Every screen, row and prompt asks this. */
internal fun linkedDevicesAvailable(): Boolean = MULTI_DEVICE_EXPERIMENT
