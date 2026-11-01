package dev.ipf.whitenoise.android.state

import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.core.nostrEventReferences
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.NOTE
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.READ_BLOCK_SECONDS
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.SECOND_NOTE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Characterizes the extracted owner directly; first-frame tests retain the real controller wiring. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationEventCardPresentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun cancelReads() {
        scope.cancel()
    }

    @Test
    fun retiredSessionReturnsTheOriginalPageWithoutConsultingWindowState() {
        val fixture = fixture {}
        try {
            val page = timelinePage(timelineRecord(FIRST_ID, 1uL, plaintext = "nostr:$NOTE"))
            val owner =
                ConversationEventCardPresentation(
                    appState = fixture.appState,
                    conversationAccountRef = ConversationTimelineTestIds.ACCOUNT_REF,
                    currentScope = { null },
                    latestInstalledPage = { error("retired session must not inspect a window") },
                    shownRecords = { error("retired session must not inspect shown rows") },
                    initialPresentationReady = { error("retired session must not inspect readiness") },
                )
            var result: TimelinePageFfi? = null
            scope.launch { result = owner.warmLaterPageEventCards(page) }
            fixture.awaitIdleCondition { result != null }
            assertSame(page, result)
            assertEquals(0, fixture.cacheReads.get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun newerInstalledWindowIsReadWithinTheSameWarmAndKeepsItsMetadata() {
        val blocked = CountDownLatch(1)
        val fixture = fixture { blocked.await(READ_BLOCK_SECONDS, TimeUnit.SECONDS) }
        try {
            val first = timelineRecord(FIRST_ID, 1uL, plaintext = "nostr:$NOTE")
            val second = timelineRecord(SECOND_ID, 2uL, plaintext = "nostr:$SECOND_NOTE")
            val page = timelinePage(first)
            var installed = page
            val owner = owner(fixture) { installed }
            var result: TimelinePageFfi? = null
            scope.launch { result = owner.warmLaterPageEventCards(page) }
            fixture.awaitIdleCondition { fixture.cacheReads.get() >= 1 }
            installed = timelinePage(first.copy(hasReports = true), second)
            blocked.countDown()
            fixture.awaitIdleCondition { result != null }

            val warmed = checkNotNull(result)
            assertEquals(listOf(FIRST_ID, SECOND_ID), warmed.messages.map { it.messageIdHex })
            assertTrue(warmed.messages.first().hasReports)
            assertTrue(warmed.messages.all { it.contentTokens.blocks.isNotEmpty() })
            assertEquals("each text is parsed once across the replacement", 2, fixture.cardParses.get())
            assertEquals("the newer window's extra card is read too", 2, fixture.cacheReads.get())
        } finally {
            blocked.countDown()
            fixture.close()
        }
    }

    @Test
    fun budgetKeepsTheCompletedParseButRejectsSnapshotsFromALateCacheRead() {
        val blocked = CountDownLatch(1)
        val fixture = fixture { blocked.await(READ_BLOCK_SECONDS, TimeUnit.SECONDS) }
        try {
            val page = timelinePage(timelineRecord(FIRST_ID, 1uL, plaintext = "nostr:$NOTE"))
            val owner = owner(fixture) { page }
            var result: TimelinePageFfi? = null
            scope.launch { result = owner.warmLaterPageEventCards(page) }
            fixture.awaitIdleCondition { fixture.cacheReads.get() >= 1 }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LATER_PAGE_EVENT_CARD_WARM_BUDGET_MILLIS + 1L))
            fixture.awaitIdleCondition { result != null }
            val warmed = checkNotNull(result)
            val reference = nostrEventReferences(warmed.messages.single().contentTokens).single().reference.stableId
            assertFalse(owner.eventCardSnapshots.isKnown(reference))

            val pendingReads = checkNotNull(scope.coroutineContext[Job]).children.toList()
            blocked.countDown()
            var readsFinished = false
            scope.launch {
                pendingReads.forEach { it.join() }
                readsFinished = true
            }
            fixture.awaitIdleCondition { readsFinished }
            assertFalse("an abandoned read cannot write after its budget", owner.eventCardSnapshots.isKnown(reference))
            val parsed = warmed.messages.single().contentTokens
            assertTrue("parsed Markdown remains usable", parsed.blocks.isNotEmpty())
        } finally {
            blocked.countDown()
            fixture.close()
        }
    }

    private fun fixture(cacheRead: () -> Unit) =
        EventCardConversationFixture(
            composeRule = composeRule,
            snapshotPage = timelinePage(timelineRecord(SHOWN_ID, 3uL, plaintext = "plain shown row")),
            cacheRead = cacheRead,
        )

    private fun owner(
        fixture: EventCardConversationFixture,
        installedPage: () -> TimelinePageFfi?,
    ) = ConversationEventCardPresentation(
        appState = fixture.appState,
        conversationAccountRef = ConversationTimelineTestIds.ACCOUNT_REF,
        currentScope = { scope },
        latestInstalledPage = installedPage,
        shownRecords = { emptyMap() },
        initialPresentationReady = { true },
    )

    private companion object {
        val FIRST_ID = "c1".repeat(32)
        val SECOND_ID = "c2".repeat(32)
        val SHOWN_ID = "d1".repeat(32)
    }
}
