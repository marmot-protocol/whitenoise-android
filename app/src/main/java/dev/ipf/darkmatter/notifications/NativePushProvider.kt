package dev.ipf.darkmatter.notifications

import android.content.Context

/**
 * Flavor-provided access to the platform's native push transport (FCM via
 * Google Play Services).
 *
 * The Firebase / Play Services SDKs are only present in the `play` product
 * flavor; the `zapstore` flavor ships without them. Keeping every Firebase
 * symbol behind this interface lets the shared (`main`) code — most notably
 * [dev.ipf.darkmatter.state.DarkMatterAppState] — compile and run against both
 * flavors without referencing Firebase classes directly. Without that
 * indirection a `zapstore` build would fail to compile (missing symbols) or
 * crash at runtime (`NoClassDefFoundError`) the moment it touched the gate.
 *
 * Each flavor supplies its own [nativePushProvider] factory in a file with
 * the same package: the `play` source set returns a Firebase-backed
 * implementation; the `zapstore` source set returns
 * [UnavailableNativePushProvider]. Only one flavor's source set compiles into
 * any given build, so the unqualified [nativePushProvider] call in [NativePush]
 * resolves to the active flavor's factory with no reflection.
 */
interface NativePushProvider {
    /**
     * Whether real push can run on this device + build. True only when Google
     * Play Services is available AND the Firebase app has actually been
     * initialized at process start. False on builds without the FCM SDK
     * (the `zapstore` flavor), on devices lacking GMS (F-Droid/Zapstore
     * installs, emulators), and on builds where Firebase didn't initialize.
     *
     * Implementations must never throw: a missing/unusable transport reports
     * `false` rather than propagating an SDK exception into the foreground /
     * account-switch / token-rotation paths.
     */
    fun isPlatformAvailable(context: Context): Boolean

    /**
     * Fetch the current FCM registration token, or null when the transport is
     * unavailable or the fetch fails. Implementations own all SDK-specific
     * plumbing (the Firebase Task API, its threading, and exception handling)
     * and must surface failures as null rather than throwing.
     */
    suspend fun fetchToken(context: Context): String?
}

/**
 * Process-wide accessor for the active [NativePushProvider]. The concrete
 * provider is supplied by the active product flavor through [nativePushProvider]
 * (a `play`/`zapstore` source-set factory), so the shared code never names a
 * flavor implementation directly. The resolved instance is cached.
 */
object NativePush {
    private val provider: NativePushProvider by lazy { nativePushProvider() }

    fun isPlatformAvailable(context: Context): Boolean = provider.isPlatformAvailable(context)

    suspend fun fetchToken(context: Context): String? = provider.fetchToken(context)
}

/**
 * No-op provider used by the `zapstore` flavor: native push is never available
 * and the token fetch always yields null. Lives in `main` so it can also serve
 * as a shared default for any future flavor that omits FCM.
 */
object UnavailableNativePushProvider : NativePushProvider {
    override fun isPlatformAvailable(context: Context): Boolean = false

    override suspend fun fetchToken(context: Context): String? = null
}
