package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.WeakReference

class DraftStoreTest {
    private fun store() = DraftStore(InMemoryDraftPersistence())

    @Test
    fun editingOneDraftDoesNotInvalidateReadersOfAnotherDraft() {
        // Regression for #76: a single shared revision counter made every
        // chat-list row (each reads its own draft) recompose on every keystroke
        // in any conversation. Drafts must be observed per (account, group) key
        // so only the affected row's read-set is invalidated.
        val s = store()
        s.set("a", "g1", TextFieldValue("one"))
        s.set("a", "g2", TextFieldValue("two"))

        // Capture the Compose state objects read when fetching g1's draft.
        val g1Reads = HashSet<Any>()
        val snapshot = Snapshot.takeSnapshot { g1Reads.add(it) }
        try {
            snapshot.enter { s.get("a", "g1") }
        } finally {
            snapshot.dispose()
        }

        // Capture the state objects changed when editing g2's draft.
        val g2Changed = HashSet<Any>()
        val handle = Snapshot.registerApplyObserver { changed, _ -> g2Changed.addAll(changed) }
        try {
            Snapshot.withMutableSnapshot { s.set("a", "g2", TextFieldValue("two-edited")) }
        } finally {
            handle.dispose()
        }

        assertTrue(
            "editing g2 must not touch the state g1's reader depends on",
            g1Reads.intersect(g2Changed).isEmpty(),
        )
    }

    @Test
    fun getReturnsNullWhenNoDraft() {
        assertNull(store().get(accountRef = "a", groupIdHex = "g"))
    }

    @Test
    fun repeatedReadMissesDoNotGrowInMemoryStateWithoutBound() {
        val s = store()

        repeat(DraftStore.MAX_IN_MEMORY_DRAFT_STATES + 25) { index ->
            assertNull(s.get(accountRef = "a", groupIdHex = "missing-$index"))
        }

        assertEquals(DraftStore.MAX_IN_MEMORY_DRAFT_STATES, s.draftStateCountForTest())
    }

    @Test
    fun repeatedAuthoritativeHydrationDoesNotGrowInMemoryStateWithoutBound() {
        val s = store()

        repeat(DraftStore.MAX_IN_MEMORY_DRAFT_STATES + 25) { index ->
            s.hydrate("a", "g-$index", "draft", draftedAtMs = index.toLong())
        }

        assertEquals(DraftStore.MAX_IN_MEMORY_DRAFT_STATES, s.draftStateCountForTest())
    }

    @Test
    fun clearingEvictedNonEmptyDraftRemovesItsCachedValue() {
        val s = store()
        s.set("a", "evicted", TextFieldValue("draft"))
        repeat(DraftStore.MAX_IN_MEMORY_DRAFT_STATES) { index ->
            s.hydrate("a", "g-$index", "draft-$index", draftedAtMs = index.toLong())
        }

        s.set("a", "evicted", TextFieldValue(""))

        assertNull(s.get("a", "evicted"))
    }

    @Test
    fun hydrationNotifiesWhenAuthoritativeSortTimestampChanges() {
        val s = store()
        var sortNotifications = 0
        s.onDraftSortOrderChanged = { sortNotifications += 1 }

        s.hydrate("a", "g", "draft", draftedAtMs = 1_000)
        s.hydrate("a", "g", "draft", draftedAtMs = 1_000, replaceExisting = true)
        s.hydrate("a", "g", "draft", draftedAtMs = 2_000, replaceExisting = true)

        assertEquals(2, sortNotifications)
    }

    @Test
    fun authoritativeTimestampUpdatesTheCapturedDraftSnapshot() {
        val s = DraftStore(InMemoryDraftPersistence()) { 100L }
        s.set("a", "g", TextFieldValue("draft"))

        s.applyAuthoritativeTimestamp("a", "g", draftedAtMs = 250_000)

        assertEquals(250uL, s.draftedAtSecondsFor("a", "g"))
        assertEquals(250L, s.getDraft("a", "g")?.draftedAtSeconds)
    }

    @Test
    fun repeatedAuthoritativeTimestampDoesNotResignalOrRegress() {
        val s = DraftStore(InMemoryDraftPersistence()) { 100L }
        s.set("a", "g", TextFieldValue("draft"))
        var sortNotifications = 0
        s.onDraftSortOrderChanged = { sortNotifications += 1 }

        s.applyAuthoritativeTimestamp("a", "g", draftedAtMs = 250_000)
        s.applyAuthoritativeTimestamp("a", "g", draftedAtMs = 250_999)
        s.applyAuthoritativeTimestamp("a", "g", draftedAtMs = 200_000)

        assertEquals(250uL, s.draftedAtSecondsFor("a", "g"))
        assertEquals(1, sortNotifications)
    }

    @Test
    fun replacingIdenticalSummariesDoesNotResignalAndKeepsAccountsIsolated() {
        val s = store()
        var sortNotifications = 0
        s.onDraftSortOrderChanged = { sortNotifications += 1 }

        s.replaceSummaries("a", mapOf("g" to 500_000L))
        s.replaceSummaries("a", mapOf("g" to 500_999L))
        s.replaceSummaries("b", mapOf("g" to 900_000L))

        assertEquals(500uL, s.draftedAtSecondsFor("a", "g"))
        assertEquals(900uL, s.draftedAtSecondsFor("b", "g"))
        assertEquals(2, sortNotifications)
    }

    @Test
    fun delayedSummaryCannotRegressANewerAcceptedSaveTimestamp() {
        val s = DraftStore(InMemoryDraftPersistence()) { 100L }
        s.set("a", "g", TextFieldValue("draft"))
        val request = s.captureSummaryRefresh("a")

        s.applyAuthoritativeTimestamp("a", "g", draftedAtMs = 250_000)
        s.replaceSummaries("a", mapOf("g" to 125_000L), expected = request)

        assertEquals(250uL, s.draftedAtSecondsFor("a", "g"))
        assertEquals(250L, s.getDraft("a", "g")?.draftedAtSeconds)
    }

    @Test
    fun delayedSummaryPreservesANewDraftButStillUpdatesUnchangedGroups() {
        val s = store()
        s.replaceSummaries("a", mapOf("unchanged" to 100_000L, "removed" to 200_000L))
        val request = s.captureSummaryRefresh("a")

        s.set("a", "new", TextFieldValue("typed after request"))
        s.replaceSummaries("a", mapOf("unchanged" to 300_000L), expected = request)

        assertEquals("typed after request", s.get("a", "new"))
        assertTrue(s.draftedAtSecondsFor("a", "new") != null)
        assertEquals(300uL, s.draftedAtSecondsFor("a", "unchanged"))
        assertNull(s.draftedAtSecondsFor("a", "removed"))
    }

    @Test
    fun summaryRefreshSeedsSortMetadataWithoutHydratingDraftPlaintext() {
        val s = store()
        val request = s.captureSummaryRefresh("a")

        s.replaceSummaries("a", mapOf("g" to 500_000L), expected = request)

        assertNull(s.get("a", "g"))
        assertEquals(500uL, s.draftedAtSecondsFor("a", "g"))
    }

    @Test
    fun lateSaveTimestampStaysHiddenAndWinsIfThePendingSendFails() {
        val s = DraftStore(InMemoryDraftPersistence()) { 100L }
        s.set("a", "g", TextFieldValue("try again"))
        val recovery = requireNotNull(s.getDraft("a", "g"))

        s.hideForPendingSend("a", "g")
        s.applyAuthoritativeTimestamp("a", "g", draftedAtMs = 250_000)

        assertNull(s.get("a", "g"))
        assertNull(s.draftedAtSecondsFor("a", "g"))

        s.restoreSnapshot("a", "g", recovery)

        assertEquals("try again", s.get("a", "g"))
        assertEquals(250uL, s.draftedAtSecondsFor("a", "g"))
        assertEquals(250L, s.getDraft("a", "g")?.draftedAtSeconds)
    }

    @Test
    fun summaryRefreshDoesNotResurfaceADraftHiddenForPendingSend() {
        val s = DraftStore(InMemoryDraftPersistence()) { 100L }
        s.set("a", "g", TextFieldValue("sending"))
        s.hideForPendingSend("a", "g")
        val request = s.captureSummaryRefresh("a")

        s.replaceSummaries("a", mapOf("g" to 250_000L), expected = request)

        assertNull(s.get("a", "g"))
        assertNull(s.draftedAtSecondsFor("a", "g"))
    }

    @Test
    fun settingEvictedObservedEmptyDraftUpdatesOriginalState() {
        val s = store()
        val observedReads = HashSet<Any>()
        val observedSnapshot = Snapshot.takeSnapshot { observedReads.add(it) }
        try {
            observedSnapshot.enter { assertNull(s.get("a", "observed")) }
        } finally {
            observedSnapshot.dispose()
        }

        repeat(DraftStore.MAX_IN_MEMORY_DRAFT_STATES + 1) { index ->
            s.get("a", "missing-$index")
        }

        val setChanges = HashSet<Any>()
        val setHandle = Snapshot.registerApplyObserver { changed, _ -> setChanges.addAll(changed) }
        try {
            Snapshot.withMutableSnapshot { s.set("a", "observed", TextFieldValue("typed")) }
        } finally {
            setHandle.dispose()
        }

        assertTrue(
            "setting an evicted draft must update the state its reader still observes",
            observedReads.intersect(setChanges).isNotEmpty(),
        )
        assertEquals("typed", s.get("a", "observed"))
    }

    @Test
    fun collectedEvictedStateMetadataIsDrainedOnNextRead() {
        val s = store()
        repeat(DraftStore.MAX_IN_MEMORY_DRAFT_STATES + 1) { index ->
            s.get("a", "missing-$index")
        }

        val evictedReferences = s.evictedDraftStateReferencesForTest()
        assertTrue(evictedReferences.isNotEmpty())
        evictedReferences.forEach { reference ->
            reference.clear()
            reference.enqueue()
        }

        s.get("a", "missing-${DraftStore.MAX_IN_MEMORY_DRAFT_STATES}")

        assertEquals(0, s.evictedDraftStateReferencesForTest().size)
    }

    @Test
    fun repeatedBlankWritesForAbsentDraftsDoNotCreateEmptyStates() {
        val s = store()

        repeat(DraftStore.MAX_IN_MEMORY_DRAFT_STATES + 25) { index ->
            s.set(accountRef = "a", groupIdHex = "missing-$index", value = TextFieldValue(" "))
        }

        assertEquals(0, s.draftStateCountForTest())
    }

    @Test
    fun pruningReadMissesKeepsNonEmptyDraftStates() {
        val s = store()
        s.set("a", "kept", TextFieldValue("draft"))

        repeat(DraftStore.MAX_IN_MEMORY_DRAFT_STATES + 25) { index ->
            s.get(accountRef = "a", groupIdHex = "missing-$index")
        }

        assertEquals("draft", s.get("a", "kept"))
    }

    @Test
    fun setThenGetRoundTrips() {
        val s = store()
        s.set("a", "g", TextFieldValue("hello"))
        assertEquals("hello", s.get("a", "g"))
    }

    @Test
    fun setPersistsExactCaretPosition() {
        val s = store()
        s.set("a", "g", TextFieldValue("hello world", TextRange(6, 11)))
        val restored = s.getDraft("a", "g")!!
        assertEquals(TextFieldValue("hello world", TextRange(6, 11)), restored.textFieldValue)
        assertTrue(restored.focusOnRestore)
    }

    @Test
    fun mergeText_appendsWithoutOverwritingExistingDraft() {
        val s = store()
        s.set("a", "g", TextFieldValue("existing"))
        s.mergeText("a", "g", "incoming")
        assertEquals("existing\nincoming", s.get("a", "g"))
    }

    @Test
    fun selectionOnlyChangePersistsToBackingStore() {
        val backing = InMemoryDraftPersistence()
        val s = DraftStore(backing)
        val key = draftKey("a", "g")
        s.set("a", "g", TextFieldValue("hello", TextRange(1)))
        val firstEncoded = backing.snapshot()[key]
        s.set("a", "g", TextFieldValue("hello", TextRange(4)))
        val secondEncoded = backing.snapshot()[key]
        assertTrue(firstEncoded != null && secondEncoded != null)
        assertTrue(firstEncoded != secondEncoded)
        assertEquals(TextFieldValue("hello", TextRange(4)), s.getDraft("a", "g")!!.textFieldValue)
    }

    @Test
    fun hydratesLegacyRawStringDraftWithEndSelectionAndNoFocus() {
        val backing =
            InMemoryDraftPersistence().apply {
                write(draftKey("a", "g"), "legacy draft")
            }
        val s = DraftStore(backing)
        val restored = s.getDraft("a", "g")!!
        assertEquals(TextFieldValue("legacy draft", TextRange("legacy draft".length)), restored.textFieldValue)
        assertFalse(restored.focusOnRestore)
    }

    @Test
    fun hydratesMalformedVersionedDraftSafely() {
        val malformed = "${COMPOSER_DRAFT_VERSION_PREFIX}bad"
        val backing =
            InMemoryDraftPersistence().apply {
                write(draftKey("a", "g"), malformed)
            }
        val s = DraftStore(backing)
        val restored = s.getDraft("a", "g")!!
        assertEquals(malformed, restored.textFieldValue.text)
        assertFalse(restored.focusOnRestore)
    }

    @Test
    fun legacyMigrationDropsMalformedVersionedBlobButKeepsRawText() {
        val malformed = "${COMPOSER_DRAFT_VERSION_PREFIX}bad"

        assertNull(decodeLegacyDraftForMigration(malformed))
        assertEquals("legacy draft", decodeLegacyDraftForMigration("legacy draft"))
    }

    @Test
    fun authoritativeReconcileClearsStaleLifecycleText() {
        val s = store()
        s.set("a", "g", TextFieldValue("stale"))

        s.replaceFromAuthoritative("a", "g", content = null, draftedAtMs = null)

        assertNull(s.get("a", "g"))
        assertNull(s.draftedAtSecondsFor("a", "g"))
    }

    @Test
    fun setEmptyClearsDraft() {
        val s = store()
        s.set("a", "g", TextFieldValue("hello"))
        s.set("a", "g", TextFieldValue(""))
        assertNull(s.get("a", "g"))
    }

    @Test
    fun setBlankClearsDraft() {
        val s = store()
        s.set("a", "g", TextFieldValue("hello"))
        s.set("a", "g", TextFieldValue("   \n\t  "))
        assertNull(s.get("a", "g"))
    }

    @Test
    fun draftsAreIsolatedPerAccount() {
        val s = store()
        s.set("acctA", "g", TextFieldValue("from A"))
        s.set("acctB", "g", TextFieldValue("from B"))
        assertEquals("from A", s.get("acctA", "g"))
        assertEquals("from B", s.get("acctB", "g"))
    }

    @Test
    fun draftsAreIsolatedPerGroup() {
        val s = store()
        s.set("a", "g1", TextFieldValue("in g1"))
        s.set("a", "g2", TextFieldValue("in g2"))
        assertEquals("in g1", s.get("a", "g1"))
        assertEquals("in g2", s.get("a", "g2"))
    }

    @Test
    fun clearForAccountWipesOnlyThatAccount() {
        val s = store()
        s.set("a", "g", TextFieldValue("keep B not A"))
        s.set("b", "g", TextFieldValue("keep B not A"))
        s.clearAllForAccount("a")
        assertNull(s.get("a", "g"))
        assertEquals("keep B not A", s.get("b", "g"))
    }

    @Test
    fun clearForAccountToleratesDraftsCreatedDuringClear() {
        lateinit var s: DraftStore
        var createdDuringClear = false
        val backing =
            object : DraftPersistence {
                private val map = mutableMapOf<String, String>()

                override fun read(): Map<String, String> = map.toMap()

                override fun write(
                    key: String,
                    value: String?,
                ) {
                    if (value == null) {
                        map.remove(key)
                        if (!createdDuringClear) {
                            createdDuringClear = true
                            s.set("a", "late", TextFieldValue("late draft"))
                        }
                    } else {
                        map[key] = value
                    }
                }
            }
        s = DraftStore(backing)
        s.set("a", "g", TextFieldValue("existing"))

        s.clearAllForAccount("a")

        assertNull(s.get("a", "g"))
        assertEquals("late draft", s.get("a", "late"))
    }

    @Test
    fun clearForAccountDoesNotClobberDraftReplacedAfterSnapshot() {
        val s = store()
        val replacingState = ReplacingOnFirstReadState(initial = "old draft", replacement = "new draft")
        s.replaceDraftStateForTest(draftKey("a", "g"), replacingState)

        s.clearAllForAccount("a")

        assertEquals("new draft", replacingState.rawValue)
    }

    @Test
    fun persistenceLayerWritesWhenStored() {
        val backing = InMemoryDraftPersistence()
        val s = DraftStore(backing)
        s.set("a", "g", TextFieldValue("persisted"))
        val stored = backing.snapshot()[draftKey("a", "g")]!!
        assertEquals("persisted", decodeComposerDraftStored(stored).textFieldValue.text)
    }

    @Test
    fun persistenceLayerDeletesWhenCleared() {
        val backing = InMemoryDraftPersistence()
        backing.write(draftKey("a", "g"), "existing")
        val s = DraftStore(backing)
        s.set("a", "g", TextFieldValue(""))
        assertTrue(backing.snapshot().isEmpty())
    }

    @Test
    fun hydratesFromPersistenceOnInit() {
        val backing =
            InMemoryDraftPersistence().apply {
                write(draftKey("a", "g"), "preloaded")
            }
        val s = DraftStore(backing)
        assertEquals("preloaded", s.get("a", "g"))
    }

    @Test
    fun migrationCopiesEveryDraftThenWipesLegacyPlaintext() {
        // The encrypted-store migration must be one-way: every plaintext draft
        // lands in the secure store and the plaintext source is then cleared,
        // so no draft survives in cleartext on disk.
        val secure = mutableMapOf<String, String>()
        var legacyWiped = false
        migrateDrafts(
            legacy = mapOf(draftKey("a", "g1") to "one", draftKey("a", "g2") to "two"),
            existingSecureKeys = emptySet(),
            persistSecure = { drafts ->
                secure.putAll(drafts)
                true
            },
            clearLegacy = { legacyWiped = true },
        )
        assertEquals(
            mapOf(draftKey("a", "g1") to "one", draftKey("a", "g2") to "two"),
            secure,
        )
        assertTrue("legacy plaintext must be wiped after migration", legacyWiped)
    }

    @Test
    fun migrationKeepsLegacyPlaintextWhenDurableWriteFails() {
        // If the encrypted copy did not durably commit, the plaintext source
        // must survive — wiping it on a non-durable write would lose drafts to
        // process death between the queued write and its disk commit.
        var legacyWiped = false
        migrateDrafts(
            legacy = mapOf(draftKey("a", "g1") to "one"),
            existingSecureKeys = emptySet(),
            persistSecure = { false },
            clearLegacy = { legacyWiped = true },
        )
        assertFalse("legacy plaintext must survive a failed durable write", legacyWiped)
    }

    @Test
    fun migrationDoesNotOverwriteDraftsAlreadyInEncryptedStore() {
        // A plaintext file that outlived a failed wipe must never clobber a
        // newer encrypted edit: keys already present in the secure store are
        // skipped, and the superseded plaintext is still wiped.
        val persisted = mutableMapOf<String, String>()
        var legacyWiped = false
        migrateDrafts(
            legacy = mapOf(draftKey("a", "g1") to "stale", draftKey("a", "g2") to "fresh"),
            existingSecureKeys = setOf(draftKey("a", "g1")),
            persistSecure = { drafts ->
                persisted.putAll(drafts)
                true
            },
            clearLegacy = { legacyWiped = true },
        )
        assertEquals(mapOf(draftKey("a", "g2") to "fresh"), persisted)
        assertTrue("superseded plaintext should still be wiped", legacyWiped)
    }

    @Test
    fun migrationWipesPlaintextWithoutWritingWhenAllKeysAlreadyEncrypted() {
        // Every legacy key already has an encrypted (newer) value: nothing is
        // written, but the redundant plaintext is wiped.
        var persistCalled = false
        var legacyWiped = false
        migrateDrafts(
            legacy = mapOf(draftKey("a", "g1") to "stale"),
            existingSecureKeys = setOf(draftKey("a", "g1")),
            persistSecure = {
                persistCalled = true
                true
            },
            clearLegacy = { legacyWiped = true },
        )
        assertFalse("no encrypted write when all keys are superseded", persistCalled)
        assertTrue("redundant plaintext should be wiped", legacyWiped)
    }

    private fun draftKey(
        account: String,
        group: String,
    ): String = "$account $group"

    private fun DraftStore.replaceDraftStateForTest(
        key: String,
        state: MutableState<String?>,
    ) {
        val draftsField = DraftStore::class.java.getDeclaredField("drafts").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val drafts = draftsField.get(this) as MutableMap<String, MutableState<String?>>
        drafts[key] = state
    }

    private fun DraftStore.draftStateCountForTest(): Int {
        val draftsField = DraftStore::class.java.getDeclaredField("drafts").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val drafts = draftsField.get(this) as Map<String, MutableState<String?>>
        return drafts.size
    }

    private fun DraftStore.evictedDraftStateReferencesForTest(): List<WeakReference<*>> {
        val referencesField =
            DraftStore::class.java.getDeclaredField("evictedDraftStates").apply { isAccessible = true }
        val references = referencesField.get(this) as Map<*, *>
        return references.values.map { it as WeakReference<*> }
    }

    private class ReplacingOnFirstReadState(
        initial: String?,
        private val replacement: String?,
    ) : MutableState<String?> {
        private var current = initial
        private var replaced = false

        val rawValue: String?
            get() = current

        override var value: String?
            get() {
                val observed = current
                if (!replaced) {
                    replaced = true
                    current = replacement
                }
                return observed
            }
            set(value) {
                current = value
            }

        override fun component1(): String? = value

        override fun component2(): (String?) -> Unit = { value = it }
    }

    @Test
    fun draftedAtStampsTheStartOfDraftingAndClearsWithTheDraft() {
        var clock = 100L
        val s = DraftStore(InMemoryDraftPersistence()) { clock }
        var signals = 0
        s.onDraftSortOrderChanged = { signals++ }

        assertNull(s.draftedAtSecondsFor("a", "g"))

        // empty→non-empty: stamped, one re-sort signal.
        s.set("a", "g", TextFieldValue("hi"))
        assertEquals(100uL, s.draftedAtSecondsFor("a", "g"))
        assertEquals(1, signals)

        // editing a non-empty draft must not restamp or re-signal — this is the
        // per-keystroke path the chat list must not churn on.
        clock = 200L
        s.set("a", "g", TextFieldValue("hi there"))
        assertEquals(100uL, s.draftedAtSecondsFor("a", "g"))
        assertEquals(1, signals)

        // clearing drops the stamp and signals a re-sort.
        s.set("a", "g", TextFieldValue(""))
        assertNull(s.draftedAtSecondsFor("a", "g"))
        assertEquals(2, signals)
    }

    @Test
    fun clearingADraftThatWasNeverSetDoesNotSignal() {
        val s = store()
        var signals = 0
        s.onDraftSortOrderChanged = { signals++ }
        s.set("a", "g", TextFieldValue(""))
        assertEquals(0, signals)
        assertNull(s.draftedAtSecondsFor("a", "g"))
    }

    @Test
    fun retypingAfterAClearRestampsWithTheCurrentTime() {
        var clock = 10L
        val s = DraftStore(InMemoryDraftPersistence()) { clock }
        s.set("a", "g", TextFieldValue("first"))
        s.set("a", "g", TextFieldValue(""))
        clock = 999L
        s.set("a", "g", TextFieldValue("again"))
        assertEquals(999uL, s.draftedAtSecondsFor("a", "g"))
    }

    @Test
    fun clearAllForAccountDropsDraftedAtAndSignalsOnce() {
        val s = DraftStore(InMemoryDraftPersistence()) { 50L }
        s.set("a", "g1", TextFieldValue("x"))
        s.set("a", "g2", TextFieldValue("y"))
        var signals = 0
        s.onDraftSortOrderChanged = { signals++ }
        s.clearAllForAccount("a")
        assertNull(s.draftedAtSecondsFor("a", "g1"))
        assertNull(s.draftedAtSecondsFor("a", "g2"))
        assertEquals(1, signals)
    }

    @Test
    fun draftedAtSurvivesAProcessRestartThroughPersistence() {
        val persistence = InMemoryDraftPersistence()
        DraftStore(persistence) { 500L }.set("a", "g", TextFieldValue("hi"))
        // A fresh store over the same persistence simulates a process restart.
        val restored = DraftStore(persistence)
        assertEquals(500uL, restored.draftedAtSecondsFor("a", "g"))
    }

    @Test
    fun editingALegacyRestoredDraftStampsItSoItCanPromote() {
        val persistence = InMemoryDraftPersistence()
        // A legacy raw-string draft, as an older build would have persisted it.
        persistence.write("a g", "old draft text")
        val restored = DraftStore(persistence) { 700L }
        assertNull(restored.draftedAtSecondsFor("a", "g"))

        restored.set("a", "g", TextFieldValue("old draft text!"))
        assertEquals(700uL, restored.draftedAtSecondsFor("a", "g"))
    }
}

private class InMemoryDraftPersistence : DraftPersistence {
    private val map = mutableMapOf<String, String>()

    override fun read(): Map<String, String> = map.toMap()

    override fun write(
        key: String,
        value: String?,
    ) {
        if (value == null) map.remove(key) else map[key] = value
    }

    fun snapshot(): Map<String, String> = map.toMap()
}
