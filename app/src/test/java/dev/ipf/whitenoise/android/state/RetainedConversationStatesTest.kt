package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.snapshots.SnapshotStateMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedConversationStatesTest {
    private var activeKey: String? = null
    private val protectedKeys = mutableSetOf<String>()
    private val states =
        RetainedConversationStates(
            maxRetained = 2,
            isProtected = { it in protectedKeys },
            activeConversationKey = { activeKey },
            closeStagedUploadSource = {},
        )

    @Test
    fun accessorsCreateEachCollectionOnceAndReturnTheSameLiveInstance() {
        assertEquals(0L, states.optimisticCancellationGeneration(ACCOUNT, "a").value)

        assertStable { optimisticMessages(ACCOUNT, "a") }
        assertStable { durableAcceptanceCallbacks(ACCOUNT, "a") }
        assertStable { projectedMessageIds(ACCOUNT, "a") }
        assertStable { timelineOrderOverrides(ACCOUNT, "a") }
        assertStable { timelineTimestampOverrides(ACCOUNT, "a") }
        assertStable { optimisticSendPositionPreserves(ACCOUNT, "a") }
        assertStable { retentionAtSend(ACCOUNT, "a") }
        assertStable { acceptedPendingTextOptimisticIds(ACCOUNT, "a") }
        assertStable { optimisticSendPhases(ACCOUNT, "a") }
        assertStable { optimisticCancellationGeneration(ACCOUNT, "a") }
        assertStable { retainedMediaUploads(ACCOUNT, "a") }
        assertStable { activeUploadKeys(ACCOUNT, "a") }
        assertStable { pendingProjectionsAwaitingBridge(ACCOUNT, "a") }

        assertNotSame(states.optimisticMessages(ACCOUNT, "a"), states.optimisticMessages("other", "a"))
        assertNotSame(states.optimisticMessages(ACCOUNT, "a"), states.optimisticMessages(ACCOUNT, "b"))
    }

    @Test
    fun evictionClearsOnlyAcceptanceCallbacksAndRetainedUploadsHeldByAController() {
        val held = fill("a")
        states.recordNativeComposerCleanup(ACCOUNT, "a")
        assertTrue(states.nativeComposerCleanupRevision(ACCOUNT, "a") > 0L)

        states.projectedMessageIds(ACCOUNT, "b")
        states.projectedMessageIds(ACCOUNT, "c")

        assertTrue(held.acceptanceCallbacks.isEmpty())
        assertEquals(0, held.uploads.size())
        assertTrue("eviction releases the retained upload source", held.sourceReleased())
        assertEquals(1, held.messages.size)
        assertEquals(setOf("projected"), held.projected)
        assertEquals(mapOf("order" to 1uL), held.orderOverrides)
        assertEquals(mapOf("timestamp" to 2uL), held.timestampOverrides)
        assertTrue("preserve" in held.positionPreserves)
        assertEquals(mapOf("retention" to 3uL), held.retentionAtSend)
        assertEquals(mapOf("event" to "optimistic"), held.acceptedTextIds)
        assertEquals(mapOf("send" to OptimisticSendPhase.ACCEPTED_PENDING), held.phases)
        assertEquals(4L, held.cancellationGeneration.value)
        assertEquals(setOf("upload"), held.activeUploadKeys)
        assertEquals(setOf("projection"), held.projectionsAwaitingBridge.keys)

        assertEquals(0L, states.nativeComposerCleanupRevision(ACCOUNT, "a"))
        assertNull(states.acceptedPendingTextOptimisticId(ACCOUNT, "a", "event"))
        assertNotSame(held.messages, states.optimisticMessages(ACCOUNT, "a"))
    }

    @Test
    fun accountWipeEmptiesEveryHeldCollectionAndDropsAllRecords() {
        val first = fill("a")
        val second = fill("b")
        states.recordNativeComposerCleanup(ACCOUNT, "a")
        val revisionBeforeWipe = states.nativeComposerCleanupRevision(ACCOUNT, "a")

        states.clearAll()

        listOf(first, second).forEach { held ->
            assertTrue(held.messages.isEmpty())
            assertTrue(held.acceptanceCallbacks.isEmpty())
            assertTrue(held.projected.isEmpty())
            assertTrue(held.orderOverrides.isEmpty())
            assertTrue(held.timestampOverrides.isEmpty())
            assertTrue(held.positionPreserves.snapshot().isEmpty())
            assertTrue(held.retentionAtSend.isEmpty())
            assertTrue(held.acceptedTextIds.isEmpty())
            assertTrue(held.phases.isEmpty())
            assertEquals(0, held.uploads.size())
            assertTrue(held.sourceReleased())
            assertTrue(held.activeUploadKeys.isEmpty())
            assertTrue(held.projectionsAwaitingBridge.isEmpty())
        }
        assertEquals(0L, states.nativeComposerCleanupRevision(ACCOUNT, "a"))
        assertNull(states.acceptedPendingTextOptimisticId(ACCOUNT, "a", "event"))
        assertNotSame(first.cancellationGeneration, states.optimisticCancellationGeneration(ACCOUNT, "a"))
        assertNotSame(first.messages, states.optimisticMessages(ACCOUNT, "a"))

        states.recordNativeComposerCleanup(ACCOUNT, "a")
        assertTrue(
            "a cleanup stamp is never reused after the wipe",
            states.nativeComposerCleanupRevision(ACCOUNT, "a") > revisionBeforeWipe,
        )
    }

    @Test
    fun activeAndUploadingConversationsSurvivePruningUntilProtectionEnds() {
        activeKey = RetainedConversationStates.key(ACCOUNT, "active")
        protectedKeys += RetainedConversationStates.key(ACCOUNT, "first-upload")
        protectedKeys += RetainedConversationStates.key(ACCOUNT, "second-upload")
        val active = states.projectedMessageIds(ACCOUNT, "active")
        val firstUpload = states.projectedMessageIds(ACCOUNT, "first-upload")
        val secondUpload = states.projectedMessageIds(ACCOUNT, "second-upload")

        repeat(4) { states.projectedMessageIds(ACCOUNT, "other-$it") }

        assertSame(active, states.projectedMessageIds(ACCOUNT, "active"))
        assertSame(firstUpload, states.projectedMessageIds(ACCOUNT, "first-upload"))
        assertSame(secondUpload, states.projectedMessageIds(ACCOUNT, "second-upload"))

        protectedKeys.clear()
        states.prune()

        assertSame(active, states.projectedMessageIds(ACCOUNT, "active"))
        assertSame(secondUpload, states.projectedMessageIds(ACCOUNT, "second-upload"))
        assertNotSame(
            "settled overflow is evicted oldest first",
            firstUpload,
            states.projectedMessageIds(ACCOUNT, "first-upload"),
        )
    }

    @Test
    fun promotedConversationOutlivesOlderStateAndBridgeLookupsDoNotRetain() {
        val promoted = states.projectedMessageIds(ACCOUNT, "a")
        val older = states.projectedMessageIds(ACCOUNT, "b")
        states.acceptedPendingTextOptimisticIds(ACCOUNT, "b")["event"] = "optimistic"

        states.promote(ACCOUNT, "a")
        assertNull(
            "a lookup neither retains nor creates state",
            states.acceptedPendingTextOptimisticId(ACCOUNT, "missing", "x"),
        )
        assertEquals("optimistic", states.acceptedPendingTextOptimisticId(ACCOUNT, "b", "event"))
        states.projectedMessageIds(ACCOUNT, "c")

        assertSame(promoted, states.projectedMessageIds(ACCOUNT, "a"))
        assertNotSame(older, states.projectedMessageIds(ACCOUNT, "b"))
        assertFalse(states.acceptedPendingTextOptimisticIds(ACCOUNT, "b").containsKey("event"))
    }

    @Test
    fun keyKeepsTheAccountGroupFormatTheUploadRegistryMatches() {
        assertEquals("account\u0000group", RetainedConversationStates.key("account", "group"))
        assertEquals("\u0000group", RetainedConversationStates.key(null, "group"))
    }

    private fun assertStable(access: RetainedConversationStates.() -> Any) {
        assertSame(states.access(), states.access())
    }

    private fun fill(groupIdHex: String): HeldState {
        var released = false
        val upload = RetainedMediaUpload(emptyList(), null).apply { retainSource { released = true } }
        val message = localTimelineMessage(timelineAppMessage("message-$groupIdHex"))
        val held =
            HeldState(
                messages = states.optimisticMessages(ACCOUNT, groupIdHex).apply { put(message.id, message) },
                acceptanceCallbacks = states.durableAcceptanceCallbacks(ACCOUNT, groupIdHex).apply { put("send") {} },
                projected = states.projectedMessageIds(ACCOUNT, groupIdHex).apply { add("projected") },
                orderOverrides = states.timelineOrderOverrides(ACCOUNT, groupIdHex).apply { put("order", 1uL) },
                timestampOverrides =
                    states.timelineTimestampOverrides(ACCOUNT, groupIdHex).apply { put("timestamp", 2uL) },
                positionPreserves =
                    states.optimisticSendPositionPreserves(ACCOUNT, groupIdHex).apply { add("preserve") },
                retentionAtSend = states.retentionAtSend(ACCOUNT, groupIdHex).apply { put("retention", 3uL) },
                acceptedTextIds =
                    states.acceptedPendingTextOptimisticIds(ACCOUNT, groupIdHex).apply { put("event", "optimistic") },
                phases =
                    states.optimisticSendPhases(ACCOUNT, groupIdHex).apply {
                        put("send", OptimisticSendPhase.ACCEPTED_PENDING)
                    },
                cancellationGeneration =
                    states.optimisticCancellationGeneration(ACCOUNT, groupIdHex).apply { value = 4L },
                uploads = states.retainedMediaUploads(ACCOUNT, groupIdHex).apply { put("upload", upload) },
                activeUploadKeys = states.activeUploadKeys(ACCOUNT, groupIdHex).apply { add("upload") },
                projectionsAwaitingBridge =
                    states.pendingProjectionsAwaitingBridge(ACCOUNT, groupIdHex).apply {
                        put("projection", requireNotNull(projectedTimelineMessage(message.record).projected))
                    },
                sourceReleased = { released },
            )
        assertEquals("optimistic", states.acceptedPendingTextOptimisticId(ACCOUNT, groupIdHex, "event"))
        return held
    }

    private class HeldState(
        val messages: SnapshotStateMap<String, TimelineMessage>,
        val acceptanceCallbacks: MutableMap<String, () -> Unit>,
        val projected: MutableSet<String>,
        val orderOverrides: MutableMap<String, ULong>,
        val timestampOverrides: MutableMap<String, ULong>,
        val positionPreserves: OptimisticSendPositionPreserves,
        val retentionAtSend: MutableMap<String, ULong>,
        val acceptedTextIds: MutableMap<String, String>,
        val phases: MutableMap<String, OptimisticSendPhase>,
        val cancellationGeneration: kotlinx.coroutines.flow.MutableStateFlow<Long>,
        val uploads: dev.ipf.whitenoise.android.media.ByteSizeLruCache<String, RetainedMediaUpload>,
        val activeUploadKeys: MutableSet<String>,
        val projectionsAwaitingBridge: MutableMap<String, dev.ipf.marmotkit.TimelineMessageRecordFfi>,
        val sourceReleased: () -> Boolean,
    )

    private companion object {
        const val ACCOUNT = "account"
    }
}
