package dev.ipf.whitenoise.android.ui.chats.newchat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.DialogProperties
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.ChatsNewMessageFab
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.conversation.MeasuredPenDialogMotion
import dev.ipf.whitenoise.android.ui.conversation.MeasuredPlaybackDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in actual-owner rendering only. Does not submit a group or simulate native success. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class NewChatFlowMotionCaptureTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun realHomePenTransfersToTheActionGroupAndReturnsOnTheDialogClock() {
        val state = fixture()
        val show = mutableStateOf(false)
        val launch = mutableStateOf<MeasuredPenDialogMotion?>(null)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                            ChatsNewMessageFab(
                                state,
                                onNewMessage = { show.value = true },
                                loadRelays = { null },
                                onNewMessageOrigin = { launch.value = it },
                                hidePen = { show.value && launch.value?.drawsSource?.invoke() == true },
                            )
                        }
                        if (show.value) {
                            MeasuredPlaybackDialog(
                                origin = launch.value?.origin,
                                penMotion = launch.value,
                                onDismissRequest = { show.value = false },
                                properties = fullWindowDialog,
                            ) {
                                NewChatFlowHost(state, onOpenConversation = { _, _ -> }, onClose = dismiss)
                            }
                        }
                    }
                }
            }
        }
        val newMessage = composeRule.onNodeWithContentDescription(app.getString(R.string.new_message))
        val actionGroup = composeRule.onNodeWithTag("new_message.agentSettings")
        newMessage.assertIsDisplayed()
        composeRule.mainClock.autoAdvance = false
        try {
            newMessage.performClick()
            advanceOpeningFrames()
            composeRule.runOnIdle {
                val motion = checkNotNull(launch.value)
                assertTrue(motion.drawsSource())
            }
            actionGroup.assertIsNotDisplayed()
            composeRule.mainClock.advanceTimeBy(1_000L)
            actionGroup.assertIsDisplayed()
            composeRule.runOnIdle { assertFalse(checkNotNull(launch.value).drawsSource()) }
            composeRule.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
            composeRule.mainClock.advanceTimeBy(96L)
            composeRule.runOnIdle {
                assertTrue(show.value)
                assertTrue(checkNotNull(launch.value).drawsSource())
            }
            composeRule.mainClock.advanceTimeBy(1_000L)
            newMessage.assertIsDisplayed()
            composeRule.runOnIdle { assertFalse(show.value) }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun advanceOpeningFrames() {
        composeRule.runOnIdle { Snapshot.sendApplyNotifications() }
        repeat(6) {
            composeRule.mainClock.advanceTimeByFrame()
            ShadowLooper.idleMainLooper(16L, TimeUnit.MILLISECONDS)
        }
    }

    private val fullWindowDialog =
        DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        )

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
