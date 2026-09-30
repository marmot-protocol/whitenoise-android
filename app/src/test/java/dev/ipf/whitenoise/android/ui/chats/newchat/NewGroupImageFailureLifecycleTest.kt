package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Recreates the real new-group screen around a failed image preparation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class NewGroupImageFailureLifecycleTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Activity recreation restores the authored name but drops the old image failure. */
    @Test
    fun activityRecreationDropsImageFailureAndKeepsDraftName() {
        val state = appState()
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { WhiteNoiseTheme { screen(state) } }
        composeRule.onNodeWithTag("group_setup.name").performTextReplacement("Release group")
        failWebImage(state)

        restoration.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle { assertNull(state.toast) }
        composeRule.onNodeWithText(context.getString(R.string.group_photo_error)).assertDoesNotExist()
        composeRule.onNodeWithText("Release group").assertExists()
    }

    /** Removing the task's screen disposes its notice owner before another route appears. */
    @Test
    fun taskRemovalClearsOwnedImageFailure() {
        val state = appState()
        var inTask by mutableStateOf(true)
        composeRule.setContent { WhiteNoiseTheme { if (inTask) screen(state) } }
        failWebImage(state)

        composeRule.runOnIdle { inTask = false }

        composeRule.runOnIdle { assertNull(state.toast) }
    }

    /** A new process owner has no transient failure to replay from the prior owner. */
    @Test
    fun freshAppStateDoesNotReplayImageFailure() {
        val oldState = appState()
        var currentState by mutableStateOf(oldState)
        composeRule.setContent { WhiteNoiseTheme { screen(currentState) } }
        failWebImage(oldState)

        val freshState = appState()
        composeRule.runOnIdle { currentState = freshState }

        composeRule.runOnIdle {
            assertNull(oldState.toast)
            assertNull(freshState.toast)
        }
        composeRule.onNodeWithText(context.getString(R.string.group_photo_error)).assertDoesNotExist()
    }

    /** Drives a real image choice through the screen with a deterministic preparation failure. */
    private fun failWebImage(state: WhiteNoiseAppState) {
        composeRule.onNodeWithTag("group_setup.photoAction").performClick()
        composeRule.onNodeWithText(context.getString(R.string.group_photo_web)).performClick()
        composeRule.onNodeWithTag("image_search.url").performTextReplacement("https://example.com/image.svg")
        composeRule.onNodeWithText(context.getString(R.string.group_image_search_apply)).performClick()
        composeRule.waitUntil(5_000) { state.toast != null }
        composeRule.onNodeWithText(context.getString(R.string.group_photo_error)).assertExists()
    }

    /** Uses the production setup route while replacing only the remote download. */
    @androidx.compose.runtime.Composable
    private fun screen(state: WhiteNoiseAppState) {
        NewGroupSetupScreen(
            appState = state,
            members = emptyList(),
            onBack = {},
            onCreateCompletedOpen = { _, _ -> },
            prepareRemoteImage = { throw IllegalStateException("invalid SVG fixture") },
        )
    }

    /** Creates the same signed-in shell with a separate transient notice owner. */
    private fun appState() =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(NoDrafts),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts = listOf(AccountSummaryFfi(ACCOUNT_REF, ACCOUNT_ID, true, false, false, true)),
            activeAccountRef = ACCOUNT_REF,
        )

    private object NoDrafts : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "alice"
        val ACCOUNT_ID = "a1".repeat(32)
    }
}
