package dev.ipf.whitenoise.android.ui.profile

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.GLOBAL_TRANSIENT_NOTICE_TAG
import dev.ipf.whitenoise.android.ui.ShellTransientNoticeLayout
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app-global confirmation races a keyboard the destination still owns, and the platform decides
 * how tall that keyboard is and when it arrives. A Robolectric test can dispatch an inset that says
 * 300 px; only a device can say what the real keyboard does, so this case runs the real editor with
 * the real platform IME open over a real focused field.
 *
 * Two seams are faked, both outside the behaviour under test. Publication itself goes through
 * MarmotKit and the network, which no instrumented harness here can stand up, so the editor's
 * publish boundary is a fake that succeeds and emits exactly what the publisher emits. And the
 * editor drops focus the instant Save is pressed — it disables its fields while publishing — so the
 * confirmation is presented while the field still holds the keyboard, which is the state the issue
 * is about, rather than after the editor has already dismissed it.
 */
@RunWith(AndroidJUnit4::class)
class ProfileEditImeNoticeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var appState: WhiteNoiseAppState
    private lateinit var view: View

    /** Gives the test window the production inset contract: edge to edge, and resized for the keyboard. */
    @Before
    @Suppress("DEPRECATION")
    fun matchTheProductionWindow() {
        appState = profileImeTestState()
        composeRule.runOnUiThread {
            composeRule.activity.enableEdgeToEdge()
            composeRule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    /**
     * A profile save confirmation that lands while the name field still owns the platform keyboard
     * shows its whole title and relay-count detail above that keyboard, and the field keeps focus.
     */
    @Test
    fun profileSaveConfirmationClearsTheLiveKeyboard() {
        showProfileEditor { publishedProfileConfirmation() }
        editTheNameWithTheKeyboardOpen()

        composeRule.runOnUiThread { publishedProfileConfirmation() }
        assertTheConfirmationClearsTheLiveKeyboard()

        composeRule.onNodeWithText(context.getString(R.string.toast_profile_published)).assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.toast_profile_published_detail, RELAY_COUNT))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(NAME_FIELD_TAG).assertIsFocused()
    }

    /** A real successful save reaches the same host, so the fix covers the path a reader actually takes. */
    @Test
    fun aSuccessfulSaveConfirmsThroughTheSameHost() {
        showProfileEditor { publishedProfileConfirmation() }
        editTheNameWithTheKeyboardOpen()

        composeRule.onNodeWithTag(SAVE_TAG).performClick()

        composeRule.waitUntil(CONFIRMATION_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(GLOBAL_TRANSIENT_NOTICE_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(context.getString(R.string.toast_profile_published)).assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.toast_profile_published_detail, RELAY_COUNT))
            .assertIsDisplayed()
    }

    /**
     * Relay addition publishes through the same shell host with a URL field still focused. Its own
     * screen needs an engine-backed relay projection that no instrumented harness here can load, so
     * this case keeps a real field, a real keyboard and the real host, and presents the confirmation
     * through the very call the relay publisher makes on success.
     */
    @Test
    fun relayAddConfirmationClearsTheLiveKeyboardFromTheSameHost() {
        showProfileEditor { true }
        editTheNameWithTheKeyboardOpen()

        composeRule.runOnUiThread { appState.presentTransient(R.string.toast_relay_list_updated) }
        assertTheConfirmationClearsTheLiveKeyboard()

        composeRule.onNodeWithText(context.getString(R.string.toast_relay_list_updated)).assertIsDisplayed()
        composeRule.onNodeWithTag(NAME_FIELD_TAG).assertIsFocused()
    }

    /** Emits the confirmation `publishProfile` emits on success, and reports that success. */
    private fun publishedProfileConfirmation(): Boolean {
        appState.presentTransient(
            AppText.Resource(R.string.toast_profile_published),
            AppText.Resource(R.string.toast_profile_published_detail, listOf(RELAY_COUNT)),
        )
        return true
    }

    /** Starts an edit, types into the name field, and waits for the platform keyboard to actually arrive. */
    private fun editTheNameWithTheKeyboardOpen() {
        composeRule.onNodeWithTag(EDIT_TAG).performClick()
        composeRule.onNodeWithTag(NAME_FIELD_TAG).performScrollTo().performClick()
        composeRule.onNodeWithTag(NAME_FIELD_TAG).performTextReplacement(PUBLISHED_NAME)
        composeRule.waitUntil(KEYBOARD_TIMEOUT_MILLIS) { keyboardInsetPx() >= MINIMUM_REAL_KEYBOARD_PX }
    }

    /**
     * Samples the live keyboard and the confirmation together, and requires the keyboard to still be
     * covering a real share of the screen: a keyboard on its way out makes the comparison weaker,
     * not stronger, so a test that accepted one would prove nothing.
     */
    private fun assertTheConfirmationClearsTheLiveKeyboard() {
        val deadline = SystemClock.uptimeMillis() + SAMPLE_WINDOW_MILLIS
        var samples = 0
        do {
            val keyboard = keyboardInsetPx()
            assertTrue(
                "the keyboard was not up, so the confirmation was never covered",
                keyboard >= MINIMUM_REAL_KEYBOARD_PX,
            )
            val notice = composeRule.onNodeWithTag(GLOBAL_TRANSIENT_NOTICE_TAG).fetchSemanticsNode().boundsInRoot
            val windowHeight =
                composeRule
                    .onRoot()
                    .fetchSemanticsNode()
                    .size
                    .height
            val available = windowHeight - keyboard
            assertTrue(
                "the confirmation ran into the keyboard: bottom ${notice.bottom} past $available",
                notice.bottom <= available + TOLERANCE_PX,
            )
            samples += 1
        } while (SystemClock.uptimeMillis() < deadline)
        assertTrue("no sample was taken", samples > 0)
    }

    /** How far the platform keyboard currently reaches into the window, in pixels. */
    private fun keyboardInsetPx(): Int =
        composeRule.runOnUiThread {
            val insets = ViewCompat.getRootWindowInsets(view) ?: return@runOnUiThread 0
            if (!insets.isVisible(WindowInsetsCompat.Type.ime())) {
                0
            } else {
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            }
        }

    /** Renders the real editor inside the real shell host, with only publication faked. */
    private fun showProfileEditor(publish: suspend () -> Boolean) {
        composeRule.setContent {
            view = LocalView.current
            WhiteNoiseTheme {
                ShellTransientNoticeLayout(notice = appState.transientNotice) {
                    ProfileEditScreen(
                        appState = appState,
                        onBack = {},
                        cachedProfile = { profileEditMetadata(SAVED_NAME, ABOUT, "", "", "", "") },
                        loadProfile = { awaitCancellation() },
                        publishProfile = { publish() },
                        resolveAddress = { null },
                        resolveLightning = { true },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** One local account with no engine behind it, which is all the editor needs to compose. */
    private fun profileImeTestState(): WhiteNoiseAppState =
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
        const val EDIT_TAG = "profile.edit"
        const val NAME_FIELD_TAG = "profile.name_field"
        const val SAVE_TAG = "profile.save"
        const val ACCOUNT_LABEL = "alice"
        val ACCOUNT_ID = "a0".repeat(32)
        const val SAVED_NAME = "Alice"
        const val ABOUT = "A public biography"
        const val PUBLISHED_NAME = "Alice Published"
        const val RELAY_COUNT = 3
        const val KEYBOARD_TIMEOUT_MILLIS = 10_000L
        const val CONFIRMATION_TIMEOUT_MILLIS = 5_000L
        const val SAMPLE_WINDOW_MILLIS = 300L

        // A keyboard covers a large part of a phone screen; anything shorter is a bar, not an IME.
        const val MINIMUM_REAL_KEYBOARD_PX = 200
        const val TOLERANCE_PX = 1f
    }
}
