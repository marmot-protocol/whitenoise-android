package dev.ipf.darkmatter.notifications

/**
 * `zapstore`-flavor factory for the active [NativePushProvider]. This build
 * ships without the Firebase / Google Play Services SDKs, so native push is
 * always reported unavailable and the runtime falls back to local
 * notifications over the existing foreground-stream transport.
 *
 * Only the `zapstore` source set compiles this file, so the unqualified
 * [nativePushProvider] call in the shared [NativePush] accessor binds here for
 * the no-FCM build.
 */
fun nativePushProvider(): NativePushProvider = UnavailableNativePushProvider
