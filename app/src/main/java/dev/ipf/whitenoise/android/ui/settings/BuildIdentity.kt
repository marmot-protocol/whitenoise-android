package dev.ipf.whitenoise.android.ui.settings

/** Plain-text installed-build identity; unknown metadata never claims a source commit or pull request. */
internal fun buildIdentity(
    versionName: String,
    versionCode: Int,
    fullSha: String,
    sourcePr: String,
): String =
    buildString {
        append("White Noise $versionName ($versionCode)")
        if (fullSha.matches(Regex("[0-9a-fA-F]{40}"))) append("\nCommit: $fullSha")
        if (sourcePr.matches(Regex("[1-9][0-9]*"))) append("\nPR: #$sourcePr")
    }
