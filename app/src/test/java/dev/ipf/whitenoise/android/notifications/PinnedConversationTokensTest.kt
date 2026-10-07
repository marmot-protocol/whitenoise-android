package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.KeystoreSecureStore
import dev.ipf.whitenoise.android.state.SecureStoreKeyProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Launcher credentials survive process recreation while account/group removal revokes old generations. */
@RunWith(RobolectricTestRunner::class)
class PinnedConversationTokensTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("pin-credential-test", Context.MODE_PRIVATE)
    private val securePreferences = context.getSharedPreferences("pin-encrypted-test", Context.MODE_PRIVATE)
    private val legacyPreferences = context.getSharedPreferences("pin-legacy-test", Context.MODE_PRIVATE)
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val store get() = tokenStore()

    /** Uses actual AES-GCM persistence with an in-memory key, since Robolectric has no Android Keystore. */
    private fun tokenStore(
        verifiers: SharedPreferences = preferences,
        provider: SecureStoreKeyProvider =
            object : SecureStoreKeyProvider {
                override fun secretKey(): SecretKey = key
            },
        legacy: SharedPreferences = legacyPreferences,
    ): PinnedConversationTokens =
        PinnedConversationTokens(
            KeystoreSecureStore(context, "pin-encrypted-test", provider),
            verifiers,
            legacy,
        )

    /** Production group IDs are 16 bytes, so the fixtures use the 32-hex shape MDK emits. */
    private val group = "ab".repeat(16)

    /** Each test starts with a private synthetic credential namespace. */
    @Before
    fun reset() {
        check(preferences.edit().clear().commit())
        check(securePreferences.edit().clear().commit())
        check(legacyPreferences.edit().clear().commit())
    }

    /** Repeated requests and a new store instance retain one stable platform identity until revocation. */
    @Test
    fun duplicateRequestAndProcessRecreationPreserveIdentity() {
        val first = store.issue("personal", group)!!
        val recreated = tokenStore()
        assertTrue(recreated.isValid(first))
        assertEquals(first.shortcutId, recreated.issue("personal", group.uppercase())!!.shortcutId)
    }

    /** A launcher request captured before deletion cannot become valid when the same group ID is re-created. */
    @Test
    fun groupDeletionRevokesPendingAndExistingInstances() {
        val old = store.issue("personal", group)!!
        val other = store.issue("personal", "cd".repeat(16))!!
        store.revokeGroup("personal", group)
        val recreated = tokenStore()
        assertFalse(recreated.isValid(old))
        assertTrue(recreated.isValid(other))
        val replacement = recreated.issue("personal", group)!!
        assertNotEquals(old.shortcutId, replacement.shortcutId)
        assertFalse(recreated.isValid(old))
    }

    /** Account-name prefixes cannot revoke each other; a signed-out label receives a new incarnation. */
    @Test
    fun signOutRevokesOnlyExactAccountAndCannotResurrectOldPins() {
        val old = store.issue("work", group)!!
        val other = store.issue("work-team", group)!!
        store.revokeAccount("work")
        assertFalse(store.isValid(old))
        assertTrue(store.isValid(other))
        assertNotEquals(old.shortcutId, store.issue("work", group)!!.shortcutId)
    }

    /** A queued background credential write cannot mint fresh authority after its foreground owner was deleted. */
    @Test
    fun queuedIssuanceIsRejectedAfterRevocation() {
        val beforeGroupRemoval = PinnedConversationTokens.captureRequest()
        store.revokeGroup("personal", group)
        assertNull(store.issue("personal", group, beforeGroupRemoval))
        val current = store.issue("personal", group)!!
        val beforeSignOut = PinnedConversationTokens.captureRequest()
        store.revokeAccount("personal")
        assertNull(store.issue("personal", group, beforeSignOut))
        assertFalse(store.isValid(current))
        assertTrue(store.isValid(store.issue("personal", group)!!))
    }

    /** Native work observes durable revocation and cannot race fresh issuance, even through another store instance. */
    @Test
    fun removalRevokesBeforeNativeWorkAndBlocksConcurrentIssuance() =
        runBlocking {
            for (groupScope in listOf(group, null)) {
                val old = store.issue("personal", group)!!
                val unrelated = store.issue("work", group)!!
                store.withRemovalRevoked("personal", groupScope) {
                    val recreated = tokenStore()
                    assertFalse(recreated.isValid(old))
                    assertNull(recreated.issue("personal", group))
                    assertTrue(recreated.isValid(unrelated))
                }
                val replacement = store.issue("personal", group)!!
                assertNotEquals(old.shortcutId, replacement.shortcutId)
                assertFalse(store.isValid(old))
            }
        }

    /** Cancellation after revocation does not restore old authority or leave the issuance lease permanently held. */
    @Test
    fun cancelledRemovalKeepsOldPinsRevokedAndAllowsAnExplicitNewRequest() =
        runBlocking {
            val old = store.issue("personal", group)!!
            try {
                store.withRemovalRevoked("personal", group) { throw CancellationException("native interrupted") }
            } catch (_: CancellationException) {
                assertFalse(tokenStore().isValid(old))
            }
            assertNotEquals(old.shortcutId, store.issue("personal", group)!!.shortcutId)
        }

    /** A failed durable credential write must not enter the native removal operation. */
    @Test
    fun failedRevocationPreventsNativeRemoval() =
        runBlocking {
            val old = store.issue("personal", group)!!
            val failedPreferences =
                object : SharedPreferences by preferences {
                    override fun edit(): SharedPreferences.Editor {
                        val original = preferences.edit()
                        return object : SharedPreferences.Editor by original {
                            override fun remove(key: String?): SharedPreferences.Editor = this

                            override fun commit(): Boolean = false
                        }
                    }
                }
            var nativeCalls = 0
            val failed =
                runCatching {
                    tokenStore(failedPreferences).withRemovalRevoked(
                        accountRef = "personal",
                        groupIdHex = group,
                    ) { nativeCalls += 1 }
                }
            assertTrue(failed.isFailure)
            assertEquals(0, nativeCalls)
            assertTrue(store.isValid(old))
        }

    /** Completed removal releases its fence even when unrelated credential persistence fails during native work. */
    @Test
    fun completedRemovalCannotBeReplacedByAnExitPersistenceFailure() =
        runBlocking {
            for (groupScope in listOf(group, null)) {
                val durable = DurableVerifierPreferences(preferences)
                val tokens = tokenStore(durable)
                val old = tokens.issue("personal", group)!!
                var queuedGeneration = 0L
                val result =
                    tokens.withRemovalRevoked("personal", groupScope) {
                        queuedGeneration = PinnedConversationTokens.captureRequest()
                        assertNull(tokens.issue("personal", group, queuedGeneration))
                        durable.failNextCommit = true
                        assertTrue(runCatching { tokens.issue("work", group) }.isFailure)
                        // Another commit would retry the dirty snapshot and fail after native success.
                        durable.failNextCommit = true
                        "native removal completed"
                    }
                assertEquals("native removal completed", result)
                assertTrue(durable.failNextCommit)
                assertNull(tokens.issue("personal", group, queuedGeneration))
                durable.failNextCommit = false
                durable.restart()
                assertFalse(tokens.isValid(old))
                val replacement = tokens.issue("personal", group)!!
                assertNotEquals(old.shortcutId, replacement.shortcutId)
            }
        }

    /** Possessing a valid pin for one account/group does not authorize modifying its destination. */
    @Test
    fun forgedAccountOrGroupIsRejected() {
        val original = store.issue("personal", group)!!
        val other = store.issue("work", group)!!
        val forged = PinnedConversationCapability("work", group, original.accountToken, original.groupToken)
        assertFalse(store.isValid(forged))
        assertTrue(store.isValid(other))
        val wrongGroup =
            PinnedConversationCapability(
                "personal",
                "ef".repeat(16),
                original.accountToken,
                original.groupToken,
            )
        assertFalse(store.isValid(wrongGroup))
    }

    /** Malformed routes and corrupted persisted credentials fail closed without creating a routing map. */
    @Test
    fun invalidIdentityAndCorruptCredentialsFailClosed() {
        assertNull(store.issue(" personal ", group))
        assertNull(store.issue("personal", "invalid"))
        assertNull(store.issue("personal", group + "a"))
        assertNotNull(store.issue("personal", "ab".repeat(32)))
        val original = store.issue("private-account-name", group)!!
        assertFalse(preferences.all.keys.any { it.contains("private-account-name") || it.contains(group) })
        preferences.all.keys.forEach { preferences.edit().putString(it, "invalid").commit() }
        assertFalse(store.isValid(original))
        assertFalse(original.toString().contains(original.accountToken))
    }

    /**
     * Persisted records expose only ciphertext and verification digests, while recreation preserves launcher
     * identity.
     */
    @Test fun reusableTokensAreEncryptedAndLegacyPreviewAuthorityIsDiscarded() {
        legacyPreferences.edit().putString("preview", "old-plaintext-token").commit()
        val capability = store.issue("personal", group)!!
        val bytes = (preferences.all.toString() + securePreferences.all.toString())
        assertFalse(bytes.contains(capability.accountToken))
        assertFalse(bytes.contains(capability.groupToken))
        assertTrue(securePreferences.contains("payload"))
        assertTrue(legacyPreferences.all.isEmpty())
        assertEquals(capability.shortcutId, tokenStore().issue("personal", group)!!.shortcutId)
    }

    /** A failed removal can mutate memory; its retry must flush the empty verifier map before native work. */
    @Test fun failedMemoryFirstRevocationRetriesDurablyBeforeNativeRemoval() =
        runBlocking {
            val durable = DurableVerifierPreferences(preferences)
            val tokens = tokenStore(durable)
            val old = tokens.issue("personal", group)!!
            durable.failNextCommit = true
            var nativeCalls = 0
            assertTrue(runCatching { tokens.withRemovalRevoked("personal", group) { nativeCalls++ } }.isFailure)
            assertEquals(0, nativeCalls)
            tokens.withRemovalRevoked("personal", group) { nativeCalls++ }
            assertEquals(1, nativeCalls)
            durable.restart()
            assertFalse(tokenStore(durable).isValid(old))
            assertNotEquals(old.shortcutId, tokenStore(durable).issue("personal", group)!!.shortcutId)
        }

    /** Ciphertext committed without a durable verifier cannot become authority after a crash. */
    @Test fun interruptedIssuanceRequiresASecondDurableVerifierPublication() {
        val durable = DurableVerifierPreferences(preferences)
        durable.failNextCommit = true
        assertTrue(runCatching { tokenStore(durable).issue("personal", group) }.isFailure)
        durable.restart()
        val current = tokenStore(durable).issue("personal", group)!!
        durable.restart()
        assertTrue(tokenStore(durable).isValid(current))
    }

    /** Revocation remains final even if encrypted cleanup fails; stale raw tokens cannot authorize fresh issuance. */
    @Test fun failedEncryptedCleanupCannotResurrectRevokedTokens() {
        var fail = false
        val provider =
            object : SecureStoreKeyProvider {
                override fun secretKey(): SecretKey {
                    check(!fail) { "Keystore temporarily unavailable" }
                    return key
                }
            }
        val tokens = tokenStore(provider = provider)
        val old = tokens.issue("personal", group)!!
        fail = true
        assertTrue(tokens.isValid(old)) // Validation uses the verifier, never a Keystore operation.
        tokens.revokeGroup("personal", group)
        assertFalse(tokens.isValid(old))
        fail = false
        val replacement = tokens.issue("personal", group)!!
        assertNotEquals(old.shortcutId, replacement.shortcutId)
        assertFalse(tokens.isValid(old))
        assertTrue(tokens.isValid(replacement))
    }

    /** Lost or corrupt sealed copies rotate only the keys being issued, so pinning recovers instead of failing. */
    @Test fun missingOrCorruptEncryptedTokensRotateAuthorityInsteadOfBlockingIssuance() {
        val old = store.issue("personal", group)!!
        val unrelated = store.issue("work", group)!!
        securePreferences.edit().clear().commit()
        val afterLoss = store.issue("personal", group)!!
        assertNotEquals(old.shortcutId, afterLoss.shortcutId)
        assertFalse(store.isValid(old))
        assertTrue(store.isValid(afterLoss))
        // Verifier-only validation keeps the other account's pin until its own key is issued again.
        assertTrue(store.isValid(unrelated))
        securePreferences.edit().putString("payload", "corrupt").commit()
        val afterCorruption = store.issue("personal", group)!!
        assertNotEquals(afterLoss.shortcutId, afterCorruption.shortcutId)
        assertFalse(store.isValid(afterLoss))
        assertTrue(store.isValid(afterCorruption))
        // The reset store is readable again, so a repeat request reuses the recovered token.
        assertEquals(afterCorruption.shortcutId, tokenStore().issue("personal", group)!!.shortcutId)
    }

    /** Removing an unrelated conversation or account cannot reject a request that only concerns another key. */
    @Test fun unrelatedRevocationLeavesAQueuedRequestCurrent() {
        val queued = PinnedConversationTokens.captureRequest()
        store.revokeGroup("personal", "cd".repeat(16))
        store.revokeAccount("work")
        val issued = store.issue("personal", group, queued)!!
        assertTrue(store.isValid(issued))
        store.revokeGroup("personal", group)
        assertNull(store.issue("personal", group, queued))
        assertFalse(store.isValid(issued))
    }

    /** Preview cleanup is best-effort: a legacy commit that never lands blocks neither issuance nor revocation. */
    @Test fun legacyCleanupFailureCannotBlockIssuanceOrRevocation() {
        legacyPreferences.edit().putString("preview", "old-plaintext-token").commit()
        val tokens = tokenStore(legacy = UncommittableLegacyPreferences(legacyPreferences))
        val capability = tokens.issue("personal", group)!!
        assertTrue(tokens.isValid(capability))
        tokens.revokeGroup("personal", group)
        assertFalse(tokens.isValid(capability))
        tokens.revokeAccount("personal")
        assertTrue(legacyPreferences.all.isNotEmpty())
    }

    /** A slow off-main encryption operation never holds the lock used by synchronous launcher validation. */
    @Test fun validationDoesNotWaitForAnotherIssuanceKeystoreOperation() {
        val old = store.issue("personal", group)!!
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val provider =
            object : SecureStoreKeyProvider {
                override fun secretKey(): SecretKey {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    return key
                }
            }
        val pool =
            java.util.concurrent.Executors
                .newFixedThreadPool(2)
        try {
            val issuance = pool.submit { tokenStore(provider = provider).issue("work", group) }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(pool.submit<Boolean> { store.isValid(old) }.get(1, java.util.concurrent.TimeUnit.SECONDS))
            release.countDown()
            issuance.get(5, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    /** Models a legacy preference file whose edits never reach disk. */
    private class UncommittableLegacyPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        /** Hands out an editor whose commit always reports failure. */
        override fun edit(): SharedPreferences.Editor {
            val inner = delegate.edit()
            return object : SharedPreferences.Editor by inner {
                /** Keeps the chained editor so the production call shape reaches the failing commit. */
                override fun clear(): SharedPreferences.Editor = apply { inner.clear() }

                /** Models a write that never reaches disk. */
                override fun commit(): Boolean = false
            }
        }
    }

    /** Models Android's memory-first commit contract and restores only acknowledged disk state after restart. */
    private class DurableVerifierPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        private var memory = emptyMap<String, String>()
        private var disk = emptyMap<String, String>()
        var failNextCommit = false

        /** Returns an atomic snapshot, matching the production validation read. */
        override fun getAll(): Map<String, *> = memory.toMap()

        override fun getString(
            key: String?,
            defValue: String?,
        ): String? = key?.let(memory::get) ?: defValue

        /** Drops unacknowledged memory changes to model a newly started process. */
        fun restart() {
            memory = disk.toMap()
        }

        /** Publishes edits to memory before a scripted disk failure, as Android SharedPreferences does. */
        override fun edit(): SharedPreferences.Editor {
            val updated = memory.toMutableMap()
            return object : SharedPreferences.Editor by delegate.edit() {
                override fun putString(
                    key: String?,
                    value: String?,
                ): SharedPreferences.Editor =
                    apply {
                        if (key == null) return@apply
                        if (value == null) {
                            updated.remove(key)
                        } else {
                            updated[key] = value
                        }
                    }

                override fun remove(key: String?): SharedPreferences.Editor = apply { key?.let(updated::remove) }

                override fun clear(): SharedPreferences.Editor = apply { updated.clear() }

                override fun commit(): Boolean {
                    memory = updated.toMap()
                    if (failNextCommit) {
                        failNextCommit = false
                        return false
                    }
                    disk = memory.toMap()
                    return true
                }
            }
        }
    }
}
