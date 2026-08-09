package dev.ipf.whitenoise.android.media.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSessionStoreTest {
    @Test
    fun pendingSessionIsInvisibleUntilMatchingDigestPromotes() {
        val store = EditorSessionStore(InMemorySessionStrings(), nowMs = { 10L })
        val pending = session(digest = "a".repeat(64))

        assertTrue(store.savePending(pending))
        assertNull(store.committed("account", "group", "attachment", pending.attachmentDigest))
        assertNull(store.promote("account", "group", "attachment", "b".repeat(64)))

        val committed = store.promote("account", "group", "attachment", pending.attachmentDigest)

        assertEquals(EditorSessionPhase.Committed, committed?.phase)
        assertEquals(
            pending.recipe,
            store.committed("account", "group", "attachment", pending.attachmentDigest)?.recipe,
        )
    }

    @Test
    fun digestMismatchNeverReopensSession() {
        val persistence = InMemorySessionStrings()
        val store = EditorSessionStore(persistence)
        val pending = session(digest = "a".repeat(64))
        store.savePending(pending)
        store.promote("account", "group", "attachment", pending.attachmentDigest)

        assertNull(store.committed("account", "group", "attachment", "c".repeat(64)))
    }

    @Test
    fun pendingReplacementDoesNotHidePriorCommittedSession() {
        val store = EditorSessionStore(InMemorySessionStrings())
        val original = session(sourceLeaseId = "original", digest = "a".repeat(64))
        val replacement = session(sourceLeaseId = "replacement", digest = "b".repeat(64))
        store.savePending(original)
        store.promote("account", "group", "attachment", original.attachmentDigest)

        assertTrue(store.savePending(replacement))

        assertEquals(
            "original",
            store.committed("account", "group", "attachment", original.attachmentDigest)?.sourceLeaseId,
        )
        assertTrue(store.discardPending("account", "group", "attachment"))
        assertEquals(
            "original",
            store.committed("account", "group", "attachment", original.attachmentDigest)?.sourceLeaseId,
        )
    }

    @Test
    fun startupReconciliationPromotesMatchingPendingAndDropsStale() {
        val persistence = InMemorySessionStrings()
        val store = EditorSessionStore(persistence, nowMs = { 20L })
        val keep = session(attachmentId = "keep", sourceLeaseId = "source-keep", digest = "a".repeat(64))
        val stale = session(attachmentId = "stale", sourceLeaseId = "source-stale", digest = "b".repeat(64))
        store.savePending(keep)
        store.savePending(stale)

        val liveLeases =
            store.reconcile(
                mapOf(
                    Triple("account", "group", "keep") to keep.attachmentDigest,
                ),
            )

        assertEquals(setOf("source-keep"), liveLeases)
        assertEquals(
            EditorSessionPhase.Committed,
            store.committed("account", "group", "keep", keep.attachmentDigest)?.phase,
        )
        assertNull(store.committed("account", "group", "stale", stale.attachmentDigest))
    }

    @Test
    fun liveLeaseCountsIncludeEveryAttachmentSharingASource() {
        val store = EditorSessionStore(InMemorySessionStrings())
        val first = session(attachmentId = "first", sourceLeaseId = "shared", digest = "a".repeat(64))
        val second = session(attachmentId = "second", sourceLeaseId = "shared", digest = "b".repeat(64))
        store.savePending(first)
        store.savePending(second)
        store.reconcile(
            mapOf(
                Triple("account", "group", "first") to first.attachmentDigest,
                Triple("account", "group", "second") to second.attachmentDigest,
            ),
        )

        assertEquals(mapOf("shared" to 2), store.sourceLeaseReferenceCounts())
    }

    @Test
    fun reconcileKeepsPriorLeaseOwnershipWhenPersistenceFails() {
        val persistence = InMemorySessionStrings()
        val store = EditorSessionStore(persistence)
        val keep = session(attachmentId = "keep", sourceLeaseId = "source-keep", digest = "a".repeat(64))
        val stale = session(attachmentId = "stale", sourceLeaseId = "source-stale", digest = "b".repeat(64))
        assertTrue(store.savePending(keep))
        assertTrue(store.savePending(stale))
        persistence.failWrites = true

        val live =
            store.reconcile(
                mapOf(Triple("account", "group", "keep") to keep.attachmentDigest),
            )

        assertEquals(setOf("source-keep", "source-stale"), live)
        assertEquals(setOf("source-keep", "source-stale"), store.sourceLeaseReferenceCounts().keys)
    }

    @Test
    fun removeAccountPurgesOnlyThatAccountsSessionsAndIsIdempotent() {
        val store = EditorSessionStore(InMemorySessionStrings())
        val removed = session(accountRef = "removed", sourceLeaseId = "removed-source", digest = "a".repeat(64))
        val retained = session(accountRef = "retained", sourceLeaseId = "retained-source", digest = "b".repeat(64))
        assertTrue(store.savePending(removed))
        assertTrue(store.savePending(retained))

        assertTrue(store.removeAccount("removed"))
        assertTrue(store.removeAccount("removed"))

        assertEquals(mapOf("retained-source" to 1), store.sourceLeaseReferenceCounts())
        assertEquals(setOf(Triple("retained", "group", "attachment")), store.attachmentKeys())
    }

    @Test
    fun oversizedRecipeFailsWithoutReplacingExistingRecord() {
        val original = session(digest = "a".repeat(64))
        val measurement = InMemorySessionStrings()
        assertTrue(EditorSessionStore(measurement, nowMs = { 1L }).savePending(original))
        val baselineBytes =
            measurement.values.values
                .single()
                .toByteArray(Charsets.UTF_8)
                .size
        val persistence = InMemorySessionStrings()
        val store = EditorSessionStore(persistence, nowMs = { 1L }, maxSerializedBytes = baselineBytes)
        assertTrue(store.savePending(original))
        val huge =
            original.copy(
                attachmentDigest = "b".repeat(64),
                recipe =
                    PhotoEditRecipe(
                        strokes =
                            listOf(
                                PhotoEditStroke(
                                    id = "large",
                                    mode = PhotoStrokeMode.Draw,
                                    widthFraction = 0.01f,
                                    colorArgb = 0,
                                    points = List(100) { NormalizedPoint(it / 100f, it / 100f) },
                                ),
                            ),
                    ),
            )

        assertFalse(store.savePending(huge))
        assertEquals(1, persistence.values.size)
    }

    @Test
    fun attachmentDigestChangesWhenAnyRelevantFieldChanges() {
        val base = digest()
        val changes =
            listOf(
                digest(attachmentId = "other"),
                digest(fileName = "other.jpg"),
                digest(mediaType = "image/png"),
                digest(plaintext = byteArrayOf(1, 3)),
                digest(dim = "20x10"),
                digest(thumbhash = "other-hash"),
                digest(durationSeconds = 1.5),
                digest(waveformSamples = listOf(0.1, 0.2)),
            )

        assertTrue(changes.all { it != base })
        assertEquals(changes.size, changes.toSet().size)
        assertEquals(64, base.length)
    }

    private fun digest(
        attachmentId: String = "id",
        fileName: String = "photo.jpg",
        mediaType: String = "image/jpeg",
        plaintext: ByteArray = byteArrayOf(1, 2),
        dim: String? = "10x20",
        thumbhash: String? = "hash",
        durationSeconds: Double? = null,
        waveformSamples: List<Double> = emptyList(),
    ): String =
        editorAttachmentDigest(
            attachmentId = attachmentId,
            fileName = fileName,
            mediaType = mediaType,
            plaintext = plaintext,
            dim = dim,
            thumbhash = thumbhash,
            durationSeconds = durationSeconds,
            waveformSamples = waveformSamples,
        )

    private fun session(
        accountRef: String = "account",
        attachmentId: String = "attachment",
        sourceLeaseId: String = "source",
        digest: String,
    ) = EditorAttachmentSession(
        accountRef = accountRef,
        groupIdHex = "group",
        attachmentId = attachmentId,
        attachmentDigest = digest,
        sourceLeaseId = sourceLeaseId,
        qualityPreference = "standard",
        recipe = PhotoEditRecipe(quarterTurnsClockwise = 1),
        phase = EditorSessionPhase.Pending,
        updatedAtMs = 0L,
    )
}

private class InMemorySessionStrings : EditorStringStore {
    var values = linkedMapOf<String, String>()
    var failWrites = false

    override fun readAll(): Map<String, String> = values.toMap()

    override fun replaceAll(values: Map<String, String>): Boolean {
        if (failWrites) return false
        this.values = LinkedHashMap(values)
        return true
    }

    override fun clear() = values.clear()
}
