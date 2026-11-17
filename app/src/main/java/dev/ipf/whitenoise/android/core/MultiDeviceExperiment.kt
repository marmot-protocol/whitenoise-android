package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.MarmotKitException

/**
 * Compile-time switch for linked devices. It is a literal so that only a source change can turn the feature on,
 * never a Gradle property, environment variable, intent, deep link or preference. While it is false the runtime
 * opens through MDK's ordinary constructor and every linked-device entry point stays out of the app.
 */
internal const val MULTI_DEVICE_EXPERIMENT = false

/** The details MDK attaches to its runtime error when an ordinary opener meets an account that needs device groups. */
internal const val EXPERIMENTAL_DEVICE_GROUPS_REQUIRED = "experimental_device_groups_required"

/**
 * Opens the runtime through exactly one of MDK's two constructors, passing the same root, relays and options to
 * either. Device groups are on only for [experimental]; MDK requires every opener of one root to agree, so the
 * foreground runtime and the cold worker preflight both come through here.
 */
internal fun <Options, Runtime> openMarmot(
    experimental: Boolean,
    rootPath: String,
    relayUrls: List<String>,
    options: Options,
    ordinary: (String, List<String>, Options) -> Runtime,
    experimentalDeviceGroups: (String, List<String>, Options) -> Runtime,
): Runtime =
    if (experimental) {
        experimentalDeviceGroups(rootPath, relayUrls, options)
    } else {
        ordinary(rootPath, relayUrls, options)
    }

/**
 * Whether MDK refused to open an account because it holds or is joining a device group and this build opened the
 * runtime without device groups. The account's data is intact and only a build that opens with device groups can
 * use it, so callers explain that instead of offering a retry. MDK reports the refusal as its generic runtime error
 * with fixed details; this is the one place that matches them, including through a startup-stage wrapper.
 */
internal fun Throwable.requiresExperimentalDeviceGroups(): Boolean =
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }
        .take(MAX_CAUSE_DEPTH)
        .any { it is MarmotKitException.Runtime && it.details == EXPERIMENTAL_DEVICE_GROUPS_REQUIRED }

private const val MAX_CAUSE_DEPTH = 8
