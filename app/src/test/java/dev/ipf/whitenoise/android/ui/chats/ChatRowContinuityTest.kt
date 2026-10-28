package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Production-row regressions: metadata semantics never wait for decorative motion to finish. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatRowContinuityTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var originalAnimatorScale = 1f

    /** Make decorative timing deterministic within the emulated Android settings provider. */
    @Before
    fun enableTestMotion() {
        originalAnimatorScale =
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        setAnimatorScale(1f)
    }

    /** Restore the fixture's emulated setting so no subsequent test inherits its motion policy. */
    @After
    fun restoreMotionPolicy() {
        setAnimatorScale(originalAnimatorScale)
    }

    /** A read incoming row must retain the motion host and expose the latest count at its first frame. */
    @Test
    fun unreadArrivalRapidReplacementAndMarkReadKeepAuthoritativeSemantics() {
        val item = mutableStateOf(ChatRowPortFixtures.item())
        val appState = ChatRowPortFixtures.state(context)
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatRow(item.value, appState, onClick = {}, onOpenProfile = {})
            }
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle {
            item.value =
                item.value.copy(
                    projection = requireNotNull(item.value.projection).copy(hasUnread = true, unreadCount = 1uL),
                )
        }
        composeRule.mainClock.advanceTimeByFrame()
        badge(1).assertIsDisplayed()
        val oneWidth = badge(1).fetchSemanticsNode().boundsInRoot.width
        composeRule.runOnIdle {
            item.value = item.value.copy(projection = requireNotNull(item.value.projection).copy(unreadCount = 1_001uL))
        }
        composeRule.mainClock.advanceTimeByFrame()
        badge(1).assertDoesNotExist()
        badge(1_001).assertIsDisplayed()
        assertEquals(oneWidth, badge(1_001).fetchSemanticsNode().boundsInRoot.width, 0.5f)
        composeRule.runOnIdle {
            item.value =
                item.value.copy(
                    projection = requireNotNull(item.value.projection).copy(hasUnread = false, unreadCount = 0uL),
                )
        }
        composeRule.mainClock.advanceTimeByFrame()
        badge(1_001).assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(240)
        badge(1_001).assertDoesNotExist()
    }

    /** The row becomes the sole selection target before its leading column reaches the settled width. */
    @Test
    fun selectionReversalKeepsOneTargetAndMetadataWhileWidthMoves() {
        val selecting = mutableStateOf(false)
        val appState = ChatRowPortFixtures.state(context)
        val item = ChatRowPortFixtures.item(unread = true)
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.width(320.dp)) {
                    ChatRow(
                        item,
                        appState,
                        onClick = {},
                        onOpenProfile = {},
                        selectionMode = selecting.value,
                        selected = selecting.value,
                        modifier = Modifier.testTag("row"),
                    )
                }
            }
        }
        composeRule.mainClock.autoAdvance = false
        val initial = titleLeft()
        composeRule.runOnIdle { selecting.value = true }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag("row").assertIsSelected()
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
        composeRule.onNodeWithText("3").assertExists()
        composeRule.mainClock.advanceTimeBy(80)
        val middle = titleLeft()
        assertTrue(middle > initial)
        composeRule.runOnIdle { selecting.value = false }
        composeRule.mainClock.advanceTimeByFrame()
        val reversed = titleLeft()
        assertTrue(reversed >= initial && reversed <= middle + 1f)
        composeRule.mainClock.advanceTimeBy(240)
        val settled = titleLeft()
        assertEquals(initial, settled, 0.5f)
        composeRule.onNodeWithText("3").assertExists()
    }

    /** Real count, mention, delivery and timestamp slots remain within a selected RTL row at double text scale. */
    @Test
    fun selectedRtlProductionMetadataRetainsItsMeasuredTrailingBounds() {
        val appState = ChatRowPortFixtures.state(context)
        val original = ChatRowPortFixtures.item(unread = true, delivery = ChatListMessageDeliveryStateFfi.DELIVERED)
        val item =
            original.copy(
                projection = requireNotNull(original.projection).copy(unreadCount = 1_001uL, unreadMention = true),
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, 2f),
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    ChatRow(
                        item,
                        appState,
                        onClick = {},
                        onOpenProfile = {},
                        selectionMode = true,
                        selected = true,
                        modifier = Modifier.width(280.dp).testTag("row"),
                    )
                }
            }
        }
        val row = composeRule.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot
        val title = titleLeft()
        val count = badge(1_001).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val mention =
            composeRule
                .onNodeWithContentDescription(
                    context.getString(R.string.chat_list_mention_badge),
                    useUnmergedTree = true,
                )
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val delivered =
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.sent), useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val timestamp =
            composeRule
                .onNodeWithText(context.getString(R.string.relative_time_now), useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        listOf(count, mention, delivered, timestamp).forEach { bounds ->
            assertTrue(bounds.width > 0f && bounds.left >= row.left && bounds.right <= row.right)
        }
        assertTrue(timestamp.right <= title)
        assertTrue(count.right <= mention.left && mention.right <= delivered.left)
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    /** Changing the live system policy settles the leading strip immediately, including a subsequent exit. */
    @Test
    fun liveReducedMotionSettlesSelectionWithoutWaitingForItsOldAnimation() {
        val selecting = mutableStateOf(false)
        val appState = ChatRowPortFixtures.state(context)
        val item = ChatRowPortFixtures.item()
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatRow(
                    item,
                    appState,
                    onClick = {},
                    onOpenProfile = {},
                    selectionMode = selecting.value,
                )
            }
        }
        val initial = titleLeft()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { selecting.value = true }
        composeRule.mainClock.advanceTimeBy(64)
        val partial = titleLeft()
        composeRule.runOnIdle { setAnimatorScale(0f) }
        composeRule.mainClock.advanceTimeByFrame()
        assertTrue(titleLeft() > partial)
        composeRule.runOnIdle { selecting.value = false }
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals(initial, titleLeft(), 0.5f)
    }

    /** A manual unread mark exposes its current description immediately and removes it at mark-read. */
    @Test
    fun manualUnreadDotRetainsAuthoritativeSemanticsDuringExit() {
        val original = ChatRowPortFixtures.item()
        val item = mutableStateOf(original)
        val appState = ChatRowPortFixtures.state(context)
        val description = context.getString(R.string.chat_row_marked_unread)
        composeRule.setContent {
            WhiteNoiseTheme { ChatRow(item.value, appState, onClick = {}, onOpenProfile = {}) }
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle {
            item.value =
                original.copy(
                    projection =
                        requireNotNull(original.projection).copy(
                            hasUnread = true,
                            manuallyMarkedUnread = true,
                        ),
                )
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithContentDescription(description).assertExists()
        composeRule.runOnIdle { item.value = original }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithContentDescription(description).assertDoesNotExist()
    }

    /** Reads production text geometry without adding a test-only layout seam. */
    private fun titleLeft(): Float =
        composeRule
            .onNodeWithText(ChatRowPortFixtures.TITLE, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot.left

    /** Selects the unmerged authoritative host, whose complete count is independent of the capped visual text. */
    private fun badge(count: Int): SemanticsNodeInteraction {
        val label = if (count > 999) "999+" else count.toString()
        val description = context.resources.getQuantityString(R.plurals.unread_messages_count, count, count)
        return composeRule.onNode(
            hasText(label) and hasContentDescription(description),
            useUnmergedTree = true,
        )
    }

    /** Notify the same observer used by production; this fixture never touches a physical device. */
    private fun setAnimatorScale(scale: Float) {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        context.contentResolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
    }
}
