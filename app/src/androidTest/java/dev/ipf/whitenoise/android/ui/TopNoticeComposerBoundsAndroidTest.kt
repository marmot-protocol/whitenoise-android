package dev.ipf.whitenoise.android.ui

import android.content.Context
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.CONVERSATION_TRANSIENT_NOTICE_TAG
import dev.ipf.whitenoise.android.ui.common.GLOBAL_TRANSIENT_NOTICE_TAG
import dev.ipf.whitenoise.android.ui.common.LocalTopNoticeHost
import dev.ipf.whitenoise.android.ui.common.TopNoticeHost
import dev.ipf.whitenoise.android.ui.conversation.ConversationTransientNoticeLayout
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Temporary banners on a device with the real platform keyboard open (#3312). A Robolectric test can dispatch an
 * inset; only a device decides how tall the keyboard is and when it arrives. This case keeps a real focused
 * composer field above the real keyboard in the production shell and conversation banner slots, under the same
 * top-notice host the app root installs, and presents notices through the very calls the production paths make:
 * the invitation result a conversation controller presents and the transcription failure dictation presents.
 *
 * Each banner must land below the header and playback strip, and the composer must keep exactly the bounds it had
 * before the banner appeared, while it shows and after it clears, with its field still focused.
 */
@RunWith(AndroidJUnit4::class)
class TopNoticeComposerBoundsAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var appState: WhiteNoiseAppState
    private lateinit var view: View

    /** Gives the test window the production inset contract: edge to edge, and resized for the keyboard. */
    @Before
    @Suppress("DEPRECATION")
    fun matchTheProductionWindow() {
        appState = topNoticeTestState()
        composeRule.runOnUiThread {
            composeRule.activity.enableEdgeToEdge()
            composeRule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    /** An accepted invitation's confirmation shows in the conversation's top row and leaves the composer alone. */
    @Test
    fun invitationResultLandsBelowTheHeaderWithoutMovingTheComposer() {
        showConversation()
        val composerBefore = openTheKeyboard()

        composeRule.runOnUiThread {
            appState.presentConversationTransient(ACCOUNT_LABEL, GROUP_ID, R.string.toast_invite_accepted)
        }

        assertBannerBelowTheHeader(CONVERSATION_TRANSIENT_NOTICE_TAG, composerBefore)
        composeRule.onNodeWithText(context.getString(R.string.toast_invite_accepted)).assertIsDisplayed()
        assertTheComposerSurvivesClearing(composerBefore)
    }

    /** A blocked dictation send presents its failure at the top, and the composer keeps its place and focus. */
    @Test
    fun transcriptionFailureLandsBelowTheHeaderWithoutMovingTheComposer() {
        showConversation()
        val composerBefore = openTheKeyboard()

        composeRule.runOnUiThread { appState.presentTransient(R.string.dictation_send_blocked) }

        assertBannerBelowTheHeader(GLOBAL_TRANSIENT_NOTICE_TAG, composerBefore)
        composeRule.onNodeWithText(context.getString(R.string.dictation_send_blocked)).assertIsDisplayed()
        assertTheComposerSurvivesClearing(composerBefore)
    }

    /** The banner sits below the header and playback strip, the composer bounds are unchanged, the field focused. */
    private fun assertBannerBelowTheHeader(
        tag: String,
        composerBefore: Rect,
    ) {
        composeRule.waitUntil(BANNER_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        val banner = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val playback = composeRule.onNodeWithTag(PLAYBACK_TAG).fetchSemanticsNode().boundsInRoot
        val header = composeRule.onNodeWithTag(HEADER_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("the banner overlaps the header", banner.top >= header.bottom - TOLERANCE_PX)
        assertTrue("the banner overlaps the playback strip", banner.top >= playback.bottom - TOLERANCE_PX)
        val keyboard = keyboardInsetPx()
        assertTrue(
            "the keyboard was not up ($keyboard px), so the composer bounds were never under test",
            keyboard >= MINIMUM_KEYBOARD_PX,
        )
        assertEquals(composerBefore, composerBounds())
        composeRule.onNodeWithTag(FIELD_TAG).assertIsFocused()
    }

    /** Clearing the banner, as its timeout does, also leaves the composer in place. */
    private fun assertTheComposerSurvivesClearing(composerBefore: Rect) {
        composeRule.runOnUiThread { appState.clearTransientNotice() }
        composeRule.waitForIdle()
        assertEquals(composerBefore, composerBounds())
        composeRule.onNodeWithTag(FIELD_TAG).assertIsFocused()
    }

    /** Focuses and types into the composer, waits for the real keyboard, and returns the composer's resting bounds. */
    private fun openTheKeyboard(): Rect {
        composeRule.onNodeWithTag(FIELD_TAG).performClick()
        composeRule.onNodeWithTag(FIELD_TAG).performTextReplacement(DRAFT)
        composeRule.waitUntil(KEYBOARD_TIMEOUT_MILLIS) { keyboardInsetPx() >= MINIMUM_KEYBOARD_PX }
        composeRule.waitForIdle()
        return composerBounds()
    }

    /** The composer's bounds in the window. */
    private fun composerBounds(): Rect = composeRule.onNodeWithTag(COMPOSER_TAG).fetchSemanticsNode().boundsInRoot

    /** How far the platform keyboard currently reaches into the window, in pixels. */
    private fun keyboardInsetPx(): Int =
        composeRule.runOnUiThread {
            val insets = ViewCompat.getRootWindowInsets(view) ?: return@runOnUiThread 0
            val ime = WindowInsetsCompat.Type.ime()
            if (!insets.isVisible(ime)) 0 else insets.getInsets(ime).bottom
        }

    /** The production shell and conversation slots around a header, playback strip, transcript and composer. */
    private fun showConversation() {
        val noticeHost = TopNoticeHost(SnackbarHostState(), notice = { appState.transientNotice })
        composeRule.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalTopNoticeHost provides noticeHost) {
                WhiteNoiseTheme {
                    ShellTransientNoticeLayout(notice = appState.transientNotice) {
                        Column(Modifier.fillMaxSize()) {
                            Box(
                                Modifier
                                    .statusBarsPadding()
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .testTag(HEADER_TAG),
                            )
                            Box(Modifier.fillMaxWidth().height(40.dp).testTag(PLAYBACK_TAG))
                            ConversationTransientNoticeLayout(
                                notice = appState.transientNotice,
                                accountRef = ACCOUNT_LABEL,
                                groupIdHex = GROUP_ID,
                                modifier = Modifier.fillMaxWidth().weight(1f),
                            ) {
                                Text("Earlier messages")
                            }
                            var draft by remember { mutableStateOf("") }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .imePadding()
                                    .testTag(COMPOSER_TAG),
                            ) {
                                TextField(
                                    value = draft,
                                    onValueChange = { draft = it },
                                    modifier = Modifier.fillMaxWidth().testTag(FIELD_TAG),
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** One local account with no engine behind it, which is all the notice producers need. */
    private fun topNoticeTestState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_LABEL,
                        accountIdHex = ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_LABEL,
        )

    private companion object {
        const val HEADER_TAG = "top-notice.header"
        const val PLAYBACK_TAG = "top-notice.playback"
        const val COMPOSER_TAG = "top-notice.composer"
        const val FIELD_TAG = "top-notice.field"
        const val ACCOUNT_LABEL = "alice"
        val ACCOUNT_ID = "a0".repeat(32)
        val GROUP_ID = "b0".repeat(32)
        const val DRAFT = "A reply in progress"
        const val KEYBOARD_TIMEOUT_MILLIS = 10_000L
        const val BANNER_TIMEOUT_MILLIS = 5_000L

        // A keyboard covers a large part of a phone screen; anything shorter is a bar, not an IME.
        const val MINIMUM_KEYBOARD_PX = 200
        const val TOLERANCE_PX = 1f
    }
}
