package dev.ipf.whitenoise.android.state

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
        assertTrue("eviction releases the retained upload source", held.sourceReleased)
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
            assertTrue(held.sourceReleased)
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
        val held = HeldState(states, groupIdHex)
        held.populate()
        assertEquals("optimistic", states.acceptedPendingTextOptimisticId(ACCOUNT, groupIdHex, "event"))
        return held
    }

    /** The live collections a controller would hold for one conversation. */
    private class HeldState(
        states: RetainedConversationStates,
        groupIdHex: String,
    ) {
        val messages = states.optimisticMessages(ACCOUNT, groupIdHex)
        val acceptanceCallbacks = states.durableAcceptanceCallbacks(ACCOUNT, groupIdHex)
        val projected = states.projectedMessageIds(ACCOUNT, groupIdHex)
        val orderOverrides = states.timelineOrderOverrides(ACCOUNT, groupIdHex)
        val timestampOverrides = states.timelineTimestampOverrides(ACCOUNT, groupIdHex)
        val positionPreserves = states.optimisticSendPositionPreserves(ACCOUNT, groupIdHex)
        val retentionAtSend = states.retentionAtSend(ACCOUNT, groupIdHex)
        val acceptedTextIds = states.acceptedPendingTextOptimisticIds(ACCOUNT, groupIdHex)
        val phases = states.optimisticSendPhases(ACCOUNT, groupIdHex)
        val cancellationGeneration = states.optimisticCancellationGeneration(ACCOUNT, groupIdHex)
        val uploads = states.retainedMediaUploads(ACCOUNT, groupIdHex)
        val activeUploadKeys = states.activeUploadKeys(ACCOUNT, groupIdHex)
        val projectionsAwaitingBridge = states.pendingProjectionsAwaitingBridge(ACCOUNT, groupIdHex)
        var sourceReleased = false
            private set
        private val message = localTimelineMessage(timelineAppMessage("message-$groupIdHex"))

        fun populate() {
            messages[message.id] = message
            acceptanceCallbacks["send"] = {}
            projected += "projected"
            orderOverrides["order"] = 1uL
            timestampOverrides["timestamp"] = 2uL
            positionPreserves.add("preserve")
            retentionAtSend["retention"] = 3uL
            acceptedTextIds["event"] = "optimistic"
            phases["send"] = OptimisticSendPhase.ACCEPTED_PENDING
            cancellationGeneration.value = 4L
            val upload = RetainedMediaUpload(emptyList(), null)
            upload.retainSource { sourceReleased = true }
            uploads.put("upload", upload)
            activeUploadKeys += "upload"
            projectionsAwaitingBridge["projection"] = requireNotNull(projectedTimelineMessage(message.record).projected)
        }
    }

    private companion object {
        const val ACCOUNT = "account"
    }
}
