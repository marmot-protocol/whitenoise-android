package dev.ipf.whitenoise.android.amber

import android.content.Intent
import dev.ipf.whitenoise.android.BuildConfig

/**
 * App-private relay correlation for NIP-55 signer prompts. Each relay Activity
 * instance stamps [EXTRA_REQUEST_ID] onto its result so [AmberActivityCoordinator]
 * can correlate cancellations even when the external signer returns null data.
 */
object AmberSignerRelay {
    const val EXTRA_REQUEST_ID = "dev.ipf.whitenoise.android.amber.SIGNER_RELAY_REQUEST_ID"
    const val EXTRA_SIGNER_INTENT = "dev.ipf.whitenoise.android.amber.SIGNER_RELAY_SIGNER_INTENT"
    const val EXTRA_LAUNCH_FAILED = "dev.ipf.whitenoise.android.amber.SIGNER_RELAY_LAUNCH_FAILED"

    internal fun buildLaunchIntent(
        requestId: String,
        signerIntent: Intent,
    ): Intent =
        Intent().apply {
            setClassName(BuildConfig.APPLICATION_ID, AmberSignerRelayActivity::class.java.name)
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_SIGNER_INTENT, signerIntent)
        }

    /** Preserve signer extras, then stamp the trusted per-launch correlation id. */
    internal fun buildResultIntent(
        requestId: String?,
        signerData: Intent?,
    ): Intent =
        Intent().apply {
            signerData?.extras?.let { putExtras(it) }
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_LAUNCH_FAILED, false)
        }
}
