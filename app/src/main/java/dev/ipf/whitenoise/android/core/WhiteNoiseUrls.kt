package dev.ipf.whitenoise.android.core

/**
 * External destinations opened from settings.
 */
internal object WhiteNoiseUrls {
    const val DONATE =
        "https://ipf.dev/donate/?utm_source=whitenoise_android&utm_medium=app&utm_campaign=donations"

    // GitHub's issue-template picker — opens a pre-filled bug report.
    const val BUG_REPORT = "https://github.com/marmot-protocol/whitenoise-android/issues/new/choose"

    const val PRIVACY_POLICY = "https://www.whitenoise.chat/privacy"
    const val DOWNLOAD = "https://www.whitenoise.chat/download"

    // Follow the latest reviewed connector guidance without requiring an Android release.
    const val AGENT_CONNECTOR_DOCS =
        "https://github.com/marmot-protocol/mdk/blob/master/crates/agent-connector/README.md"
}
