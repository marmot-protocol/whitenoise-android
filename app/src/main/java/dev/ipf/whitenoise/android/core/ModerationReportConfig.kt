package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.ModerationReportConfigFfi
import dev.ipf.whitenoise.android.BuildConfig

/**
 * Where this build sends private reports to the White Noise team: its environment's reports key, published only to
 * the White Noise relays the team operates. A blank key leaves reporting unconfigured, and MDK then reports it as
 * unavailable.
 */
internal fun moderationReportConfig(recipientPubkey: String = BUILD_REPORTS_PUBKEY): ModerationReportConfigFfi? {
    val key = recipientPubkey.trim()
    return if (key.isEmpty()) null else ModerationReportConfigFfi(key, MarmotClient.bootstrapRelays)
}

/** This environment's White Noise team reports key, staging everywhere but production. */
private const val BUILD_REPORTS_PUBKEY = BuildConfig.WHITENOISE_MODERATION_REPORT_PUBKEY
