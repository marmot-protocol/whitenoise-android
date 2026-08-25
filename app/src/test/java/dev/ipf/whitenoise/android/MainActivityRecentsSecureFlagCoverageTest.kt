package dev.ipf.whitenoise.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivityRecentsSecureFlagCoverageTest {
    @Test
    fun persistedRecentsPrivacyAppliesBeforeFirstComposeFrame() {
        val source = mainActivitySource().readText()
        val onCreate = source.functionBody("onCreate")
        val installComposeContent = source.functionBody("installComposeContent")
        val composeInstallCall = onCreate.indexOf("installComposeContent()")

        assertTrue(
            "MainActivity must install Compose content from onCreate",
            composeInstallCall >= 0 && "setContent" in installComposeContent,
        )

        assertTrue(
            "MainActivity must read the persisted recents privacy preference before setContent",
            onCreate.indexOf("ChatScreenshotPreferences.readAllowChatScreenshots(this)") in
                0 until composeInstallCall,
        )
        assertTrue(
            "MainActivity must apply the persisted recents privacy preference before setContent",
            onCreate.indexOf("applyRecentsPreferenceSecureFlag") in 0 until composeInstallCall,
        )
    }

    @Test
    fun recentsPrivacyReappliesWhenActivityReturnsForeground() {
        val source = mainActivitySource().readText()
        val onStart = source.functionBody("onStart")
        val onResume = source.functionBody("onResume")

        assertTrue(
            "onStart must re-apply the current recents privacy state before the system snapshots the task",
            "applyRecentsPreferenceSecureFlag(appState.allowChatScreenshotsInChats)" in onStart,
        )
        assertTrue(
            "onResume must re-apply the current recents privacy state after Activity recreation",
            "applyRecentsPreferenceSecureFlag(appState.allowChatScreenshotsInChats)" in onResume,
        )
    }

    @Test
    fun recentsPrivacyRetainsAndReleasesOnlyItsSecureFlagReference() {
        val source = mainActivitySource().readText()
        val apply = source.functionBody("applyRecentsPreferenceSecureFlag")
        val release = source.functionBody("releaseRecentsPreferenceSecureFlag")

        assertTrue(
            "allowing chat screenshots must release only the recents preference secure flag reference",
            Regex(
                """if\s*\(\s*allowChatScreenshots\s*\)\s*\{\s*releaseRecentsPreferenceSecureFlag\(\)\s*\}""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(apply),
        )
        assertTrue(
            "disallowing chat screenshots must retain FLAG_SECURE once for this preference",
            "window.retainSecureFlag()" in apply &&
                "recentsPreferenceSecureFlagRetained = true" in apply,
        )
        assertTrue(
            "the recents secure flag release path must only release when this preference retained it",
            "if (recentsPreferenceSecureFlagRetained)" in release &&
                "window.releaseSecureFlag()" in release &&
                "recentsPreferenceSecureFlagRetained = false" in release,
        )
    }

    @Test
    fun callbackIsNotClearedFromANewerActivityInstance() {
        val onDestroy = mainActivitySource().readText().functionBody("onDestroy")

        assertTrue(
            "destroying an old Activity must not clear a newer Activity's recents privacy callback",
            "appState.onAllowChatScreenshotsChanged === allowChatScreenshotsCallback" in onDestroy &&
                "appState.onAllowChatScreenshotsChanged = null" in onDestroy,
        )
    }

    private fun mainActivitySource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/MainActivity.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/MainActivity.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing MainActivity.kt source file")
}
