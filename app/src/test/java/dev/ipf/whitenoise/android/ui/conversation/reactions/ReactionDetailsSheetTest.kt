package dev.ipf.whitenoise.android.ui.conversation.reactions

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.ReactionParticipant
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Regression coverage for reactor-filter stability while participant projections update. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ReactionDetailsSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** User-selected filters survive live updates and fall back to All only when they disappear. */
    @Test
    fun liveParticipantUpdatesPreserveTheSelectedFilter() {
        val initialParticipants =
            listOf(
                participant(sender = ACCOUNT_ID, emoji = "👍", reactedAt = 1uL),
                participant(sender = OTHER_ACCOUNT_ID, emoji = "🔥", reactedAt = 2uL),
            )
        lateinit var updateParticipants: (List<ReactionParticipant>) -> Unit
        val allLabel = context.getString(R.string.reaction_filter_all)
        val appState = appState()

        composeRule.setContent {
            var participants by remember { mutableStateOf(initialParticipants) }
            updateParticipants = { participants = it }
            WhiteNoiseTheme {
                ReactionDetailsContent(
                    participants = participants,
                    appState = appState,
                    initialEmoji = "👍",
                    onRemoveOwnReaction = {},
                )
            }
        }

        composeRule.onNodeWithText("👍 1", substring = false).assertIsSelected()
        composeRule.onNodeWithText("$allLabel · 2", substring = false).performClick().assertIsSelected()

        val joinedParticipants =
            initialParticipants + participant(sender = THIRD_ACCOUNT_ID, emoji = "👍", reactedAt = 3uL)
        composeRule.runOnIdle { updateParticipants(joinedParticipants) }
        composeRule
            .onNodeWithText("$allLabel · 3", substring = false)
            .assertIsSelected()
        composeRule
            .onRoot()
            .captureRoboImage("src/test/snapshots/reaction_details_filter_retained_after_live_update.png")
        composeRule.onNodeWithText("🔥 1", substring = false).performClick().assertIsSelected()

        val thumbOnlyParticipants = joinedParticipants.filterNot { it.emoji == "🔥" }
        composeRule.runOnIdle { updateParticipants(thumbOnlyParticipants) }
        composeRule.onNodeWithText("$allLabel · 2", substring = false).assertIsSelected()
        composeRule.onNodeWithText("👍 2", substring = false).assertIsNotSelected()
    }

    /** The pure retention rule keeps an existing emoji and clears a missing selection. */
    @Test
    fun retainedFilterRequiresAnExistingEmoji() {
        val participants = listOf(participant(sender = ACCOUNT_ID, emoji = "👍", reactedAt = 1uL))

        assertEquals("👍", retainedReactionFilter("👍", participants))
        assertEquals(null, retainedReactionFilter("🔥", participants))
        assertEquals(null, retainedReactionFilter(null, participants))
    }

    /** An own reactor row retains the explicit tap-to-remove action. */
    @Test
    fun ownReactionRowRetainsTapToRemove() {
        val participants =
            listOf(
                participant(sender = ACCOUNT_ID, emoji = "👍", reactedAt = 1uL),
                participant(sender = OTHER_ACCOUNT_ID, emoji = "🔥", reactedAt = 2uL),
            )
        var removedEmoji: String? = null

        composeRule.setContent {
            WhiteNoiseTheme {
                ReactionDetailsContent(
                    participants = participants,
                    appState = appState(),
                    onRemoveOwnReaction = { removedEmoji = it },
                )
            }
        }

        val tapToRemove = context.getString(R.string.reaction_tap_to_remove)
        composeRule
            .onNodeWithText(tapToRemove)
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals("👍", removedEmoji) }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/reaction_details_tap_to_remove.png")
    }

    /** Builds the minimal application state needed by the reaction-details surface. */
    private fun appState() =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
            profileReader = { null },
            profileDisplayNameReader = { null },
            profileRefreshRequest = {},
        )

    /** Builds one deterministic reactor row. */
    private fun participant(
        sender: String,
        emoji: String,
        reactedAt: ULong,
    ) = ReactionParticipant(sender = sender, emoji = emoji, reactedAt = reactedAt)

    private companion object {
        const val ACCOUNT_REF = "personal"
        val ACCOUNT_ID = "01" + "00".repeat(31)
        val OTHER_ACCOUNT_ID = "02" + "00".repeat(31)
        val THIRD_ACCOUNT_ID = "03" + "00".repeat(31)
    }
}
