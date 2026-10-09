package dev.ipf.whitenoise.android.ui.conversation.reactions

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
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

    /** Removal belongs to the conversation account even during a notification-routed account switch. */
    @Test
    fun ownRemovalUsesTheBoundConversationAccount() {
        var removed: String? = null
        val participants =
            listOf(
                participant(ACCOUNT_ID, "👍", 1uL),
                participant(OTHER_ACCOUNT_ID, "🔥", 2uL),
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                ReactionDetailsContent(
                    participants,
                    appState(),
                    { removed = it },
                    readState = ReactionDetailsReadState(viewerAccountId = OTHER_ACCOUNT_ID),
                )
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.reaction_tap_to_remove)).performClick()
        composeRule.runOnIdle { assertEquals("🔥", removed) }
    }

    /** Loading has an explicit label and cannot report zero or a truncated preview as the complete All count. */
    @Test
    fun initialLoadingDoesNotDisplayAnAllCount() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ReactionDetailsContent(
                    emptyList(),
                    appState(),
                    null,
                    readState = ReactionDetailsReadState(loading = true, hasSnapshot = false),
                )
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.reaction_details_loading)).assertExists()
        composeRule.onNodeWithText("${context.getString(R.string.reaction_filter_all)} · 0").assertDoesNotExist()
    }

    /** A complete list remains readable in dark mode, RTL and large text. */
    @Test
    fun completeDetailsInDarkRtlLargeText() {
        renderCompleteVariant(dark = true, amoled = false, rtl = true, fontScale = 2f)
    }

    /** AMOLED keeps all reactor counts and removal affordances visible. */
    @Test
    fun completeDetailsInAmoled() = renderCompleteVariant(dark = true, amoled = true, rtl = false, fontScale = 1f)

    /** Records the same complete fixture under supported display settings. */
    private fun renderCompleteVariant(
        dark: Boolean,
        amoled: Boolean,
        rtl: Boolean,
        fontScale: Float,
    ) {
        val participants =
            listOf(
                participant(OTHER_ACCOUNT_ID, "👍", 1uL),
                participant(THIRD_ACCOUNT_ID, "👍", 2uL),
                participant(ACCOUNT_ID, "👍", 3uL),
                participant("04" + "00".repeat(31), "🔥", 4uL),
            )
        val state = appState()
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = fontScale) {
                    ReactionDetailsContent(participants, state, {})
                }
            }
        }
        composeRule.onNodeWithText("${context.getString(R.string.reaction_filter_all)} · 4").assertIsSelected()
        composeRule.onNodeWithText("👍 3").assertIsNotSelected()
        composeRule.onRoot().captureRoboImage(
            "src/test/snapshots/reaction_details_complete_${if (amoled) "amoled" else "dark_rtl_large_text"}.png",
        )
    }

    /** All and emoji filters include a third identical reactor plus a different emoji. */
    @Test
    fun completeDetailsShowThreeIdenticalReactionsAndOneOtherEmoji() {
        val participants =
            listOf(
                participant(OTHER_ACCOUNT_ID, "👍", 1uL),
                participant(THIRD_ACCOUNT_ID, "👍", 2uL),
                participant(ACCOUNT_ID, "👍", 3uL),
                participant("04" + "00".repeat(31), "🔥", 4uL),
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                ReactionDetailsContent(participants, appState(), {})
            }
        }
        val allLabel = context.getString(R.string.reaction_filter_all)
        composeRule.onNodeWithText("$allLabel · 4").assertIsSelected()
        composeRule.onNodeWithText("👍 3").assertIsNotSelected()
        composeRule.onNodeWithText("🔥 1").assertIsNotSelected()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/reaction_details_complete_three_plus_one.png")
        composeRule.onNodeWithText("👍 3").performClick().assertIsSelected()
        composeRule.onNodeWithText(context.getString(R.string.reaction_tap_to_remove)).assertHasClickAction()
        assertEquals(3, filteredReactionParticipants(participants, "👍").size)
        assertEquals(1, filteredReactionParticipants(participants, "🔥").size)
    }

    /** An initial failure shows Retry without reporting the bounded preview as a full participant count. */
    @Test
    fun initialFailureOffersRetryWithoutAFalseAllCount() {
        var retries = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                ReactionDetailsContent(
                    emptyList(),
                    appState(),
                    null,
                    readState = ReactionDetailsReadState(failed = true, hasSnapshot = false, onRetry = { retries++ }),
                )
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.retry)).performClick()
        composeRule.runOnIdle { assertEquals(1, retries) }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/reaction_details_initial_failure.png")
    }

    /** The sheet starts on All; user-selected filters survive updates until they disappear. */
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
                    onRemoveOwnReaction = {},
                )
            }
        }

        composeRule.onNodeWithText("$allLabel · 2", substring = false).assertIsSelected()
        composeRule.onNodeWithText("👍 1", substring = false).assertIsNotSelected()

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
