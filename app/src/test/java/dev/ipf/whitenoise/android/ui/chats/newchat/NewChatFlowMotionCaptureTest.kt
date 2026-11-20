package dev.ipf.whitenoise.android.ui.chats.newchat

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Opt-in actual-owner rendering only. Does not submit a group or simulate native success. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class NewChatFlowMotionCaptureTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun captureNewMessageToNewGroupAndBack() {
        assumeTrue(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        val state = fixture()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1f) {
                CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                    NewChatFlowHost(state, onOpenConversation = { _, _ -> }, onClose = {})
                }
            }
        }
        try {
            composeRule.onNodeWithTag("new_message.screen").assertIsDisplayed()
            capture("new-message-to-new-group/forward") {
                composeRule.onNodeWithText(app.getString(R.string.new_group)).performClick()
            }
            composeRule.onNodeWithTag("new_group.screen").assertIsDisplayed()
            capture("new-message-to-new-group/reverse") {
                composeRule.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
            }
            composeRule.onNodeWithTag("new_message.screen").assertIsDisplayed()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun captureSelectedMembersToSetupAndBack() {
        assumeTrue(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        val state = fixture()
        // Existing production profile-start-group entry contract supplies a selected member.
        // This does not claim to exercise native recipient discovery.
        val member = RecipientSearch.Candidate("a".repeat(64), "Profile Alice", "npub-test-alice")
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1f) {
                CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                    NewGroupFlow(
                        appState = state,
                        onCreateCompletedOpen = { _, _ -> },
                        onClose = {},
                        initialMembers = listOf(member),
                    )
                }
            }
        }
        try {
            composeRule.onNodeWithTag("new_group.continue").assertIsEnabled()
            capture("new-group-selected-members-to-setup/forward") {
                composeRule.onNodeWithTag("new_group.continue").performClick()
            }
            composeRule.onNodeWithTag("group_setup.screen").assertIsDisplayed()
            capture("new-group-selected-members-to-setup/reverse") {
                composeRule.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
            }
            composeRule.onNodeWithTag("new_group.continue").assertIsDisplayed().assertIsEnabled()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun capture(
        name: String,
        action: () -> Unit,
    ) {
        check(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        val folder = File("build/animation-frames/$name").apply { mkdirs() }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        captureScreenRoboImage(File(folder, "source.png").path)
        action()
        composeRule.runOnIdle {
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
        }
        repeat(32) { frame ->
            composeRule.mainClock.advanceTimeBy(16L)
            composeRule.waitForIdle()
            captureScreenRoboImage(File(folder, "frame-%03d.png".format(frame)).path)
        }
    }

    private fun fixture() =
        WhiteNoiseAppState(
            context = app,
            draftStore =
                DraftStore(
                    object : DraftPersistence {
                        override fun read(): Map<String, String> = emptyMap()

                        override fun write(
                            key: String,
                            value: String?,
                        ) = Unit
                    },
                ),
            accountIdHexResolver = { ref -> "a".repeat(64).takeIf { ref == "npub-test-alice" } },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = "active",
                        accountIdHex = "b".repeat(64),
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                        unlinked = false,
                    ),
                ),
            activeAccountRef = "active",
        )
}
