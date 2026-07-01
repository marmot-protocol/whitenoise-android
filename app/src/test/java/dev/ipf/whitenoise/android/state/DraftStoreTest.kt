package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftStoreTest {
    private fun store() = DraftStore(InMemoryDraftPersistence())

    @Test
    fun editingOneDraftDoesNotInvalidateReadersOfAnotherDraft() {
        // Regression for #76: a single shared revision counter made every
        // chat-list row (each reads its own draft) recompose on every keystroke
        // in any conversation. Drafts must be observed per (account, group) key
        // so only the affected row's read-set is invalidated.
        val s = store()
        s.set("a", "g1", "one")
        s.set("a", "g2", "two")

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
            Snapshot.withMutableSnapshot { s.set("a", "g2", "two-edited") }
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
        assertNull(store().get(accountIdHex = "a", groupIdHex = "g"))
    }

    @Test
    fun setThenGetRoundTrips() {
        val s = store()
        s.set("a", "g", "hello")
        assertEquals("hello", s.get("a", "g"))
    }

    @Test
    fun setEmptyClearsDraft() {
        val s = store()
        s.set("a", "g", "hello")
        s.set("a", "g", "")
        assertNull(s.get("a", "g"))
    }

    @Test
    fun setBlankClearsDraft() {
        val s = store()
        s.set("a", "g", "hello")
        s.set("a", "g", "   \n\t  ")
        assertNull(s.get("a", "g"))
    }

    @Test
    fun draftsAreIsolatedPerAccount() {
        val s = store()
        s.set("acctA", "g", "from A")
        s.set("acctB", "g", "from B")
        assertEquals("from A", s.get("acctA", "g"))
        assertEquals("from B", s.get("acctB", "g"))
    }

    @Test
    fun draftsAreIsolatedPerGroup() {
        val s = store()
        s.set("a", "g1", "in g1")
        s.set("a", "g2", "in g2")
        assertEquals("in g1", s.get("a", "g1"))
        assertEquals("in g2", s.get("a", "g2"))
    }

    @Test
    fun clearForAccountWipesOnlyThatAccount() {
        val s = store()
        s.set("a", "g", "keep B not A")
        s.set("b", "g", "keep B not A")
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
                            s.set("a", "late", "late draft")
                        }
                    } else {
                        map[key] = value
                    }
                }
            }
        s = DraftStore(backing)
        s.set("a", "g", "existing")

        s.clearAllForAccount("a")

        assertNull(s.get("a", "g"))
        assertEquals("late draft", s.get("a", "late"))
    }

    @Test
    fun persistenceLayerWritesWhenStored() {
        val backing = InMemoryDraftPersistence()
        val s = DraftStore(backing)
        s.set("a", "g", "persisted")
        assertEquals("persisted", backing.snapshot()[draftKey("a", "g")])
    }

    @Test
    fun persistenceLayerDeletesWhenCleared() {
        val backing = InMemoryDraftPersistence()
        backing.write(draftKey("a", "g"), "existing")
        val s = DraftStore(backing)
        s.set("a", "g", "")
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
