package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
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
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
        var committedItem = item.value
        val appState = ChatRowPortFixtures.state(context)
        composeRule.setContent {
            val currentItem = item.value
            SideEffect { committedItem = currentItem }
            WhiteNoiseTheme {
                ChatRow(currentItem, appState, onClick = {}, onOpenProfile = {})
            }
        }
        composeRule.mainClock.autoAdvance = false
        publishChange {
            item.value =
                item.value.copy(
                    projection = requireNotNull(item.value.projection).copy(hasUnread = true, unreadCount = 1uL),
                )
        }
        assertEquals(item.value, committedItem)
        badge(1).assertIsDisplayed()
        val oneWidth = badge(1).fetchSemanticsNode().boundsInRoot.width
        publishChange {
            item.value = item.value.copy(projection = requireNotNull(item.value.projection).copy(unreadCount = 1_001uL))
        }
        assertEquals(item.value, committedItem)
        badge(1).assertDoesNotExist()
        badge(1_001).assertIsDisplayed()
        assertEquals(oneWidth, badge(1_001).fetchSemanticsNode().boundsInRoot.width, 0.5f)
        publishChange {
            item.value =
                item.value.copy(
                    projection = requireNotNull(item.value.projection).copy(hasUnread = false, unreadCount = 0uL),
                )
        }
        assertEquals(item.value, committedItem)
        badge(1_001).assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(240)
        badge(1_001).assertDoesNotExist()
    }

    /** The row becomes the sole selection target before its leading column reaches the settled width. */
    @Test
    fun selectionReversalKeepsOneTargetAndMetadataWhileWidthMoves() {
        val selecting = mutableStateOf(false)
        var committedSelection = false
        val appState = ChatRowPortFixtures.state(context)
        val item = ChatRowPortFixtures.item(unread = true)
        composeRule.setContent {
            val currentSelection = selecting.value
            SideEffect { committedSelection = currentSelection }
            WhiteNoiseTheme {
                Box(Modifier.width(320.dp)) {
                    ChatRow(
                        item,
                        appState,
                        onClick = {},
                        onOpenProfile = {},
                        selectionMode = currentSelection,
                        selected = currentSelection,
                        modifier = Modifier.testTag("row"),
                    )
                }
            }
        }
        composeRule.mainClock.autoAdvance = false
        val initial = titleLeft()
        publishChange { selecting.value = true }
        assertTrue(committedSelection)
        composeRule.onNodeWithTag("row").assertIsSelected()
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(1)
        composeRule.onNodeWithText("3").assertExists()
        composeRule.mainClock.advanceTimeBy(80)
        composeRule.waitForIdle()
        val middle = titleLeft()
        assertTrue(middle > initial)
        publishChange { selecting.value = false }
        assertFalse(committedSelection)
        composeRule.onNodeWithTag("row").assertIsNotSelected()
        // The target's recomposition frame first advances the old animation once.
        val reversalAdmission = titleLeft()
        val fullWidth = with(composeRule.density) { 32.dp.toPx() }
        assertTrue(reversalAdmission >= middle && reversalAdmission < initial + fullWidth)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        assertEquals(reversalAdmission, titleLeft(), 1f)
        composeRule.mainClock.advanceTimeBy(80)
        composeRule.waitForIdle()
        val reversed = titleLeft()
        assertTrue(reversed >= initial && reversed < reversalAdmission)
        composeRule.mainClock.advanceTimeBy(240)
        composeRule.waitForIdle()
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
                ).assertIsDisplayed()
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
        var committedSelection = false
        var observedAnimationsEnabled = true
        val appState = ChatRowPortFixtures.state(context)
        val item = ChatRowPortFixtures.item()
        composeRule.setContent {
            val currentSelection = selecting.value
            val animationsEnabled = rememberSystemAnimationsEnabled()
            SideEffect {
                committedSelection = currentSelection
                observedAnimationsEnabled = animationsEnabled
            }
            WhiteNoiseTheme {
                ChatRow(
                    item,
                    appState,
                    onClick = {},
                    onOpenProfile = {},
                    selectionMode = currentSelection,
                )
            }
        }
        val initial = titleLeft()
        composeRule.mainClock.autoAdvance = false
        publishChange { selecting.value = true }
        assertTrue(committedSelection)
        composeRule.mainClock.advanceTimeBy(64)
        composeRule.waitForIdle()
        val partial = titleLeft()
        publishChange {
            setAnimatorScale(0f)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertFalse(observedAnimationsEnabled)
        assertTrue(titleLeft() > partial)
        publishChange { selecting.value = false }
        assertFalse(committedSelection)
        assertEquals(initial, titleLeft(), 0.5f)
    }

    /** A manual unread mark exposes its current description immediately and removes it at mark-read. */
    @Test
    fun manualUnreadDotRetainsAuthoritativeSemanticsDuringExit() {
        val original = ChatRowPortFixtures.item()
        val item = mutableStateOf(original)
        var committedItem = original
        val appState = ChatRowPortFixtures.state(context)
        val description = context.getString(R.string.chat_row_marked_unread)
        composeRule.setContent {
            val currentItem = item.value
            SideEffect { committedItem = currentItem }
            WhiteNoiseTheme { ChatRow(currentItem, appState, onClick = {}, onOpenProfile = {}) }
        }
        composeRule.mainClock.autoAdvance = false
        publishChange {
            item.value =
                original.copy(
                    projection =
                        requireNotNull(original.projection).copy(
                            hasUnread = true,
                            manuallyMarkedUnread = true,
                        ),
                )
        }
        assertEquals(item.value, committedItem)
        composeRule.onNodeWithContentDescription(description).assertExists()
        publishChange { item.value = original }
        assertEquals(item.value, committedItem)
        composeRule.onNodeWithContentDescription(description).assertDoesNotExist()
    }

    /** Publishes fixture state and commits one frame, then lets Android draw without settling the animation. */
    private fun publishChange(block: () -> Unit) {
        composeRule.runOnUiThread {
            block()
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
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
