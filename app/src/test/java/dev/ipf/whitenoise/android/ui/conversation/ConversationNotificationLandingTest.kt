package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationJumpOutcome
import dev.ipf.whitenoise.android.state.ConversationUnreadJumpState
import dev.ipf.whitenoise.android.state.ConversationWindowUnchangedReason
import dev.ipf.whitenoise.android.state.MessageAvailability
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.TimelinePageOutcome
import dev.ipf.whitenoise.android.state.conversationTimelineTestAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * The production entry positioning, effect and owner on a real reversed list: a notified message is
 * measured by its physical top edge against the transcript's own top, never by a screenshot. The
 * notified message is always a different row from both the oldest unread and the newest, so a landing
 * on either of those cannot pass for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationNotificationLandingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val harnesses = mutableListOf<Harness>()

    /** Clears every controller a test built, so no scripted subscription outlives its case. */
    @After
    fun tearDown() {
        harnesses.forEach { it.controller.onCleared() }
    }

    /** A short notified message in the middle of the backlog starts at the physical top, above the newer rows. */
    @Test
    fun shortNotifiedMessageStartsAtThePhysicalTop() {
        val harness = Harness(targetHeightDp = 80)
        harness.mount()
        harness.awaitAnchored()
        harness.assertLandedAtTop(NOTIFIED)
    }

    /** A message taller than the viewport lands at its beginning, where offset zero would show its end. */
    @Test
    fun viewportTallerNotifiedMessageStartsAtItsBeginning() {
        val harness = Harness(targetHeightDp = 720)
        harness.mount()
        harness.awaitAnchored()
        harness.assertLandedAtTop(NOTIFIED)
    }

    /** A keyboard-sized viewport changes the reading height but not where the beginning must sit. */
    @Test
    fun reducedViewportStillStartsAtThePhysicalTop() {
        val harness = Harness(targetHeightDp = 720, viewportHeightDp = 260)
        harness.mount()
        harness.awaitAnchored()
        harness.assertLandedAtTop(NOTIFIED)
    }

    /** Right-to-left layout mirrors the horizontal axis only, so the vertical landing is unchanged. */
    @Test
    fun rtlLayoutStartsAtThePhysicalTop() {
        val harness = Harness(targetHeightDp = 720, rtl = true)
        harness.mount()
        harness.awaitAnchored()
        harness.assertLandedAtTop(NOTIFIED)
    }

    /** A message that fits at the default font scale starts at the physical top. */
    @Test
    fun defaultTextBodyStartsAtThePhysicalTop() {
        val harness = Harness(targetHeightDp = 0, targetBodyLines = 14, fontScale = 1f)
        harness.mount()
        harness.awaitAnchored()
        harness.assertLandedAtTop(NOTIFIED)
    }

    /** The same body grows past the viewport at a large font scale and still starts at the physical top. */
    @Test
    fun largeTextBodyTallerThanTheViewportStartsAtThePhysicalTop() {
        val harness = Harness(targetHeightDp = 0, targetBodyLines = 14, fontScale = 2f)
        harness.mount()
        harness.awaitAnchored()
        harness.assertLandedAtTop(NOTIFIED)
    }

    /** The landing never reads the newest or oldest unread row's place, and it keeps the backlog pending. */
    @Test
    fun theLandingSeedsTheUnreadJumpWithTheOlderBacklogAndDoesNotTouchTheNewest() {
        val harness = Harness(targetHeightDp = 80)
        harness.mount()
        harness.awaitAnchored()
        assertEquals(listOf(OLDEST_UNREAD), harness.landedBacklogs)
        assertEquals(ConversationScrollMode.ReadingHistory(NOTIFIED, harness.landedOffset()), harness.coordinator.mode)
        assertTrue(harness.unavailable.isEmpty())
    }

    /** A message that is the oldest unread has no separate backlog to point the jump button back to. */
    @Test
    fun aNotifiedMessageThatIsTheOldestUnreadSeedsNoBacklog() {
        val harness = Harness(targetHeightDp = 80, notified = OLDEST_UNREAD)
        harness.mount()
        harness.awaitAnchored()
        assertEquals(listOf<String?>(null), harness.landedBacklogs)
        harness.assertLandedAtTop(OLDEST_UNREAD)
    }

    /** A target older than the loaded window is reached by the exact window jump, then landed on. */
    @Test
    fun anOlderRetainedTargetIsFoundByTheBoundedWindowJump() {
        val harness =
            Harness(targetHeightDp = 80, loadedFrom = 5, notified = OLDER_RETAINED, jump = JumpScript.OLDER_WINDOW)
        harness.mount()
        harness.awaitAnchored()
        harness.assertLandedAtTop(OLDER_RETAINED)
        assertTrue(harness.unavailable.isEmpty())
    }

    /** A message MDK proves is gone says so honestly and falls back to the oldest unread boundary. */
    @Test
    fun aMissingTargetSaysSoAndFallsBackToTheOldestUnread() {
        val harness = Harness(targetHeightDp = 80, notified = ABSENT, jump = JumpScript.MISSING)
        harness.mount()
        harness.awaitAnchored()
        assertEquals(listOf(MessageAvailability.MISSING), harness.unavailable)
        assertTrue(harness.landedBacklogs.isEmpty())
        assertEquals(ConversationScrollMode.ReadingHistory(OLDEST_UNREAD, 0), harness.coordinator.mode)
    }

    /** A target that cannot be reached right now is retryable, never claimed gone, and the entry still reveals. */
    @Test
    fun aRetryableTargetFallsBackWithTheRetryableOutcome() {
        val harness = Harness(targetHeightDp = 80, notified = ABSENT, jump = JumpScript.NOT_READY)
        harness.mount()
        harness.awaitAnchored()
        assertEquals(listOf(MessageAvailability.RETRYABLE), harness.unavailable)
        assertEquals(ConversationScrollMode.ReadingHistory(OLDEST_UNREAD, 0), harness.coordinator.mode)
    }

    /** A group system row, such as an admin change, keeps the ordinary entry and shows no feedback. */
    @Test
    fun aGroupSystemRowKeepsTheOrdinaryEntryWithoutFeedback() {
        val harness = Harness(targetHeightDp = 80, targetKind = 1210uL)
        harness.mount()
        harness.awaitAnchored()
        assertTrue(harness.unavailable.isEmpty())
        assertTrue(harness.landedBacklogs.isEmpty())
        assertEquals(ConversationScrollMode.ReadingHistory(OLDEST_UNREAD, 0), harness.coordinator.mode)
    }

    /** A request that a newer navigation already replaced never positions, so the ordinary entry owns the reveal. */
    @Test
    fun aSupersededRequestNeverLands() {
        val harness = Harness(targetHeightDp = 80, requestIsCurrent = false)
        harness.mount()
        harness.awaitAnchored()
        assertTrue(harness.landedBacklogs.isEmpty())
        assertTrue(harness.unavailable.isEmpty())
        assertEquals(ConversationScrollMode.ReadingHistory(OLDEST_UNREAD, 0), harness.coordinator.mode)
    }

    /** An owner disposed while the target resolved abandons the landing without writing anywhere. */
    @Test
    fun anOwnerDisposedDuringResolutionAbandonsTheLanding() {
        val harness = Harness(targetHeightDp = 80, disposeOwnerOnBegin = true)
        harness.mount()
        composeRule.waitForIdle()
        assertFalse(harness.anchored.value)
        assertTrue(harness.landedBacklogs.isEmpty())
        assertTrue(harness.unavailable.isEmpty())
    }

    /** Repeated taps are distinct requests: only the latest one's message ends up at the top. */
    @Test
    fun aSecondTapOnAnotherMessageReplacesTheFirstLanding() {
        val harness = Harness(targetHeightDp = 80)
        harness.mount()
        harness.awaitAnchored()
        harness.assertLandedAtTop(NOTIFIED)
        harness.tapAgain(OLDEST_UNREAD)
        harness.awaitAnchored()
        harness.assertLandedAtTop(OLDEST_UNREAD)
        assertEquals(2, harness.landedBacklogs.size)
    }

    /** A drag after the landing retires its geometry reruns, so a later resize cannot move the reader. */
    @Test
    fun aGestureAfterTheLandingStopsLaterGeometryReruns() {
        val harness = Harness(targetHeightDp = 80)
        harness.mount()
        harness.awaitAnchored()
        harness.coordinator.onUserGestureStarted(ConversationScrollAnchor(3, 0, "item", "other"))
        composeRule.waitForIdle()
        assertNull(harness.owner.readingStartGeometry())
    }

    /** The pairing check refuses a stale or foreign tap, whatever message id it carries. */
    @Test
    fun theLandingTargetOnlyResolvesForItsOwnAccountAndGroup() {
        val target = NotificationLandingTarget("account-a", "AB".repeat(32), NOTIFIED)
        assertEquals(NOTIFIED, target.messageIdFor("account-a", "ab".repeat(32)))
        assertNull(target.messageIdFor("account-b", "ab".repeat(32)))
        assertNull(target.messageIdFor(null, "ab".repeat(32)))
        assertNull(target.messageIdFor("account-a", "cd".repeat(32)))
        val blank = NotificationLandingTarget("account-a", "ab".repeat(32), " ")
        assertNull(blank.messageIdFor("account-a", "ab".repeat(32)))
    }

    /** Unavailability is reported with the string that matches its cause. */
    @Test
    fun unavailabilityFeedbackDistinguishesGoneFromRetryable() {
        val gone = notificationLandingFeedback(MessageAvailability.MISSING)
        val retryable = notificationLandingFeedback(MessageAvailability.RETRYABLE)
        assertEquals(R.string.toast_original_message_unavailable, gone)
        assertEquals(R.string.error_loaded_content_kept, retryable)
    }

    /** Only a loaded backlog seeds the button, and it never replaces a state the landing did not own. */
    @Test
    fun theBacklogSeedOnlyAppliesWhenThereIsABacklog() {
        val untouched = ConversationUnreadJumpState()
        assertSame(untouched, untouched.seedBacklogAfterLanding(null))
        val seeded = untouched.seedBacklogAfterLanding("backlog")
        val expected =
            ConversationUnreadJumpState(pendingMessageId = "backlog", unreadStackActive = true, initialized = true)
        assertEquals(expected, seeded)
    }

    /** One mounted conversation with a scripted window, a real reversed list and the production effects. */
    private inner class Harness(
        private val targetHeightDp: Int,
        private val viewportHeightDp: Int = 420,
        private val rtl: Boolean = false,
        private val targetBodyLines: Int? = null,
        private val fontScale: Float = 1f,
        private val loadedFrom: Int = 0,
        private val notified: String = NOTIFIED,
        private val targetKind: ULong = 9uL,
        private val requestIsCurrent: Boolean = true,
        private val disposeOwnerOnBegin: Boolean = false,
        private val jump: JumpScript = JumpScript.NONE,
    ) {
        private val timelineScript =
            ScriptedConversationTimelineSubscription(
                snapshotPage = page(from = loadedFrom),
                jumpOutcomes = jumpOutcomes(),
            )
        private val scripted =
            ScriptedConversationLiveSubscriptions(listOf(timelineScript), conversationTimelineTestGroup())
        val controller =
            ConversationController(
                appState = conversationTimelineTestAppState(scripted.subscriptions),
                initialGroup = conversationTimelineTestGroup(),
                startOnConstruction = true,
            )
        val anchored = mutableStateOf(false)
        val landedBacklogs = mutableListOf<String?>()
        val unavailable = mutableListOf<MessageAvailability>()
        private val targetMessageId = mutableStateOf(notified)
        private val requestId = mutableLongStateOf(1L)
        lateinit var coordinator: ConversationScrollCoordinator
        lateinit var owner: ConversationViewportRestorationOwner

        /** The exact-message jump answer the engine gives while the target resolves. */
        private fun jumpOutcomes(): MutableList<ConversationJumpOutcome> =
            when (jump) {
                JumpScript.NONE -> mutableListOf()
                JumpScript.MISSING -> mutableListOf(ConversationJumpOutcome.Missing)
                JumpScript.NOT_READY ->
                    mutableListOf(
                        ConversationJumpOutcome.Window(
                            TimelinePageOutcome.Unchanged(ConversationWindowUnchangedReason.TERMINAL, null),
                        ),
                    )
                JumpScript.OLDER_WINDOW ->
                    mutableListOf(ConversationJumpOutcome.Window(TimelinePageOutcome.Advanced(page(from = 0))))
            }

        /** Window records from index [from] through the newest, with the notified row's kind overridden when asked. */
        fun page(from: Int): TimelinePageFfi =
            timelinePage(
                *(from until MESSAGE_COUNT)
                    .map { index ->
                        val record = timelineRecord(messageId(index), (index + 1).toULong())
                        if (messageId(index) == notified) record.copy(kind = targetKind) else record
                    }.toTypedArray(),
            ).copy(hasMoreBefore = from > 0)

        /** Mounts the reversed list with the production restoration effects wired to recording callbacks. */
        fun mount() {
            composeRule.setContent {
                WhiteNoiseTheme {
                    CompositionLocalProvider(
                        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                        LocalDensity provides Density(LocalDensity.current.density, fontScale),
                    ) {
                        val listState = rememberLazyListState()
                        val viewport = remember(listState) { ConversationTimelineViewport(listState) }
                        coordinator =
                            remember(listState) {
                                ConversationScrollCoordinator(LazyListConversationScrollWriter(listState))
                            }
                        val gate = remember(listState) { ConversationPostInitialReanchorGate() }
                        owner = rememberConversationViewportRestorationOwner(controller, coordinator, gate)
                        val rendered = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
                        Box(Modifier.fillMaxWidth().height(viewportHeightDp.dp)) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().testTag(LIST_TAG),
                                reverseLayout = true,
                            ) {
                                items(rendered.asReversed(), key = { it.presentationId }) { item ->
                                    Row(item.record.messageIdHex)
                                }
                            }
                        }
                        ConversationViewportRestorationEffects(
                            controller = controller,
                            viewport = viewport,
                            owner = owner,
                            inputs = inputs(rendered.map { it.id to it.record.messageIdHex }),
                            callbacks = callbacks(),
                        )
                    }
                }
            }
        }

        /** One row whose height is fixed, or follows the font scale when it is the target with body lines. */
        @Composable
        private fun Row(messageId: String) {
            val isTarget = messageId == notified
            if (isTarget && targetBodyLines != null) {
                Column(Modifier.fillMaxWidth().testTag(messageId)) {
                    repeat(targetBodyLines) { Text("Notified line $it") }
                }
            } else {
                val rowHeightDp = if (isTarget) targetHeightDp else ROW_HEIGHT_DP
                Text("Message", Modifier.fillMaxWidth().height(rowHeightDp.dp).testTag(messageId))
            }
        }

        /** The restoration inputs for the current timeline rows and the harness's notification target. */
        private fun inputs(rows: List<Pair<String, String>>) =
            ConversationViewportRestorationInputs(
                scrollRestore = null,
                presentation = ConversationViewportPresentation(anchored.value, false),
                structure = ConversationTimelineStructure(rows, 0),
                entryUnread = ConversationEntryUnreadSnapshot(count = 10, firstUnreadMessageId = OLDEST_UNREAD),
                entryProjectionAvailable = true,
                notificationOpenRequestId = requestId.longValue,
                seedTailAwaitingAuthoritative = false,
                notificationTargetMessageId = targetMessageId.value,
            )

        /** Recording callbacks for anchoring, backlog seeding and unavailable-target feedback. */
        private fun callbacks() =
            ConversationViewportRestorationCallbacks(
                navigation = ConversationViewportNavigation(this::listIndexOf) { 0 },
                onAnchored = { anchored.value = true },
                retireUnreadDivider = {},
                notification =
                    ConversationNotificationLandingCallbacks(
                        beginNavigation = this::beginRequest,
                        onLanded = { landedBacklogs += it },
                        onUnavailable = { unavailable += it },
                    ),
            )

        /** Begins a latest-wins navigation request, optionally already superseded or disposing the owner first. */
        private fun beginRequest(): MessageTargetNavigationOwner.Request {
            if (disposeOwnerOnBegin) owner.dispose()
            val navigation = MessageTargetNavigationOwner()
            val request = navigation.begin()
            if (!requestIsCurrent) navigation.begin()
            return request
        }

        /** Resolves a message's list index by identity on the live rendered timeline. */
        private fun listIndexOf(anchor: ConversationScrollAnchor): Int? {
            val live = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
            val timelineIndex = live.indexOfFirst { it.record.messageIdHex == anchor.messageId }
            if (timelineIndex < 0) return null
            val trailingRows = controller.conversationTrailingRowCount(live.size)
            return conversationTimelineListIndex(timelineIndex, live.size, trailingRows)
        }

        /** Waits for the hidden landing to commit and publish its anchored state. */
        fun awaitAnchored() {
            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_MILLIS)
            while (!anchored.value && System.nanoTime() <= deadlineNanos) {
                composeRule.waitForIdle()
                ShadowLooper.idleMainLooper()
                Thread.sleep(POLL_MILLIS)
            }
            assertTrue("the entry never anchored the transcript", anchored.value)
            composeRule.waitForIdle()
        }

        /** The offset the settle recorded, which the durable reading anchor must equal. */
        fun landedOffset(): Int = (coordinator.mode as ConversationScrollMode.ReadingHistory).pixelOffset

        /** Simulates another tap on the retained conversation: a fresh request for [messageId]. */
        fun tapAgain(messageId: String) {
            composeRule.runOnIdle {
                anchored.value = false
                targetMessageId.value = messageId
                requestId.longValue += 1L
            }
        }

        /** Asserts the row's beginning meets the transcript's physical top and the reader is in reading mode. */
        fun assertLandedAtTop(messageId: String) {
            val listTop =
                composeRule
                    .onNodeWithTag(LIST_TAG)
                    .getUnclippedBoundsInRoot()
                    .top.value
            val rowTop =
                composeRule
                    .onNodeWithTag(messageId)
                    .getUnclippedBoundsInRoot()
                    .top.value
            assertEquals(listTop, rowTop, 1f)
            assertEquals(messageId, (coordinator.mode as ConversationScrollMode.ReadingHistory).anchorMessageId)
        }

        init {
            harnesses += this
        }
    }

    /** How the scripted engine answers the exact-message jump for the target. */
    private enum class JumpScript { NONE, MISSING, NOT_READY, OLDER_WINDOW }

    private companion object {
        const val POLL_MILLIS = 10L
        const val MESSAGE_COUNT = 12
        const val ROW_HEIGHT_DP = 72
        const val AWAIT_MILLIS = 10_000L
        const val LIST_TAG = "landing-list"

        /** A deterministic 64-hex message id for one list position. */
        fun messageId(index: Int): String = index.toString(16).padStart(2, '0').repeat(32)

        val ABSENT = "ee".repeat(32)
        val OLDER_RETAINED = messageId(3)
        val OLDEST_UNREAD = messageId(2)
        val NOTIFIED = messageId(6)
    }
}
