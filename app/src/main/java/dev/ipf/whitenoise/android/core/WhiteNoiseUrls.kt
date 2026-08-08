package dev.ipf.whitenoise.android.core

/**
 * External destinations opened from Help / About.
 */
internal object WhiteNoiseUrls {
    // GitHub's issue-template picker — opens a pre-filled bug report.
    const val BUG_REPORT = "https://github.com/marmot-protocol/whitenoise-android/issues/new/choose"

    const val PRIVACY_POLICY = "https://www.whitenoise.chat/privacy"

    // Keep agent-consumed setup guidance immutable: copied prompts grant an
    // external agent authority only after the user reviews and approves a plan.
    const val AGENT_CONNECTOR_DOCS =
        "https://github.com/marmot-protocol/mdk/blob/" +
            "e12f53666b5203f16cb4443af0440990493e23c7/crates/agent-connector/README.md"
}
