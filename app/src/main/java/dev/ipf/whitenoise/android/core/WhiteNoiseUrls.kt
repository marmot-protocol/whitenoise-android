package dev.ipf.whitenoise.android.core

/**
 * External destinations opened from Help / About.
 */
internal object WhiteNoiseUrls {
    // GitHub's issue-template picker — opens a pre-filled bug report.
    const val BUG_REPORT = "https://github.com/marmot-protocol/whitenoise-android/issues/new/choose"

    const val PRIVACY_POLICY = "https://www.whitenoise.chat/privacy"

    // Follow the latest reviewed connector guidance without requiring an Android release.
    const val AGENT_CONNECTOR_DOCS =
        "https://github.com/marmot-protocol/mdk/blob/master/crates/agent-connector/README.md"
}
