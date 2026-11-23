package dev.ipf.whitenoise.android.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.NoticeTier
import dev.ipf.whitenoise.android.state.ToastMessage
import dev.ipf.whitenoise.android.ui.common.RootToastSnackbarEffect
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The root snackbar counts a group-change notice as seen only when it ran to the end on screen, so a notice that
 * arrives behind app lock or in the background is shown once the user can see it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RootToastSnackbarTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * A group-change notice waits while the app is off screen, one the app leaves the screen during is withdrawn
     * without being seen, and only one that runs to the end on screen is retired as seen.
     */
    @Test
    fun aGroupChangeNoticeRunsOnlyOnScreen() {
        val state = emptyAppState()
        val host = SnackbarHostState()
        val onScreen = MutableStateFlow(false)
        val key = "${state.activeAccountRef}|change"
        composeRule.setContent { RootToastSnackbarEffect(state, host, retryLabel = "Retry", onScreen = onScreen) }
        composeRule.runOnIdle { state.presentText(ToastMessage(AppText.Plain("Team"), groupChangeKey = key)) }
        composeRule.runOnIdle { assertNull(host.currentSnackbarData) }

        onScreen.value = true
        composeRule.runOnIdle { assertNotNull(host.currentSnackbarData) }
        onScreen.value = false
        composeRule.runOnIdle {
            assertNull(host.currentSnackbarData)
            assertNotNull(state.toast)
            assertFalse(key in state.groupChangeNotices.seenToasts)
        }

        onScreen.value = true
        composeRule.runOnIdle { requireNotNull(host.currentSnackbarData).dismiss() }
        composeRule.runOnIdle {
            assertNull(state.toast)
            assertTrue(key in state.groupChangeNotices.seenToasts)
        }
    }

    /** A plain confirmation becomes a transient notice instead of a snackbar, so the screen under it stays steady. */
    @Test
    fun aPlainConfirmationBecomesATransientNotice() {
        val state = emptyAppState()
        val host = SnackbarHostState()
        composeRule.setContent {
            RootToastSnackbarEffect(state, host, retryLabel = "Retry", onScreen = MutableStateFlow(true))
        }
        composeRule.runOnIdle { state.presentText(AppText.Plain("Saved"), tier = NoticeTier.Confirmation) }
        composeRule.runOnIdle {
            assertNull(host.currentSnackbarData)
            assertNull(state.toast)
            assertEquals(AppText.Plain("Saved"), state.transientNotice?.title)
        }
    }

    /** Any other toast runs while the app is off screen, so the root timeout retires it without a second showing. */
    @Test
    fun otherToastsRunOffScreen() {
        val state = emptyAppState()
        val host = SnackbarHostState()
        composeRule.setContent {
            RootToastSnackbarEffect(state, host, retryLabel = "Retry", onScreen = MutableStateFlow(false))
        }
        composeRule.runOnIdle { state.presentText(AppText.Plain("Saved")) }
        composeRule.runOnIdle { requireNotNull(host.currentSnackbarData).dismiss() }
        composeRule.runOnIdle { assertNull(state.toast) }
    }
}
