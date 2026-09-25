package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/** Checks the resume contract against a native-shaped projection with two independently selected accounts. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppReviewDemoTest {
    private val original = ReviewDemoAccount("original", "a".repeat(64), localSigning = true, signedOut = false)
    private val johnny = ReviewDemoAccount("johnny", "b".repeat(64), localSigning = true, signedOut = false)
    private val group = "c".repeat(64)

    @Test
    fun initialReceiptLoadsAsynchronouslyIntoSnapshotState() =
        runTest {
            val store =
                MemoryStore(checkpoint(groupId = group, demoRef = johnny.ref, demoId = johnny.id, completed = true))
            val demo = AppReviewDemo(FakeBackend(), store, this, StandardTestDispatcher(testScheduler))

            assertEquals(ReviewDemoStatus.Idle, demo.status)
            assertFalse(demo.hasSavedSetup)
            advanceUntilIdle()
            assertEquals(ReviewDemoStatus.Ready(original.ref, group), demo.status)
            assertTrue(demo.hasSavedSetup)

            demo.clearSavedSetup()
            advanceUntilIdle()
            assertFalse(demo.hasSavedSetup)
            assertEquals(ReviewDemoStatus.Idle, demo.status)
        }

    @Test
    fun setupCreatesOneConversationAndVerifiesBothAccounts() =
        runTest {
            val backend = FakeBackend()
            val store = MemoryStore()
            val demo = AppReviewDemo(backend, store, this, StandardTestDispatcher(testScheduler))
            val openings = mutableListOf<Pair<String, String>>()

            demo.start { account, groupId -> openings += account to groupId }
            demo.start { account, groupId -> openings += account to groupId }
            advanceUntilIdle()

            assertEquals(ReviewDemoStatus.Ready(original.ref, group), demo.status)
            assertEquals(listOf(original.ref to group), openings)
            assertEquals(original.ref, backend.activeAccountRef)
            assertEquals(1, backend.accountCreates)
            assertEquals(1, backend.groupCreates)
            assertEquals(5, backend.messages.size)
            assertEquals(2, backend.reactionCreates)
            assertEquals(1, backend.inviteAccepts)
            assertEquals(
                5,
                backend.messages
                    .mapNotNull { it.token }
                    .distinct()
                    .size,
            )
            assertTrue(store.checkpoint?.completed == true)
        }

    @Test
    fun processResumeReusesCreatedAccountGroupAndClientTokens() =
        runTest {
            val backend =
                FakeBackend().apply {
                    accountList += johnny
                    groupExists = true
                    messages +=
                        ReviewDemoMessage(
                            "first",
                            "review-demo:$RUN_ID:original_greeting",
                            original.id,
                            "Hi Johnny! Welcome to White Noise. 👋",
                            null,
                            emptyList(),
                        )
                }
            val store = MemoryStore(checkpoint(demoRef = johnny.ref, demoId = johnny.id, groupId = group))
            val demo = AppReviewDemo(backend, store, this, StandardTestDispatcher(testScheduler))

            demo.start()
            advanceUntilIdle()

            assertTrue(demo.status is ReviewDemoStatus.Ready)
            assertEquals(0, backend.accountCreates)
            assertEquals(0, backend.groupCreates)
            assertEquals(1, backend.messages.count { it.token == "review-demo:$RUN_ID:original_greeting" })
            assertEquals(5, backend.messages.size)
            assertEquals(original.ref, backend.activeAccountRef)
        }

    @Test
    fun uncertainReactionIsNeverReplayed() =
        runTest {
            val backend =
                FakeBackend().apply {
                    accountList += johnny
                    groupExists = true
                }
            val store =
                MemoryStore(
                    checkpoint(
                        demoRef = johnny.ref,
                        demoId = johnny.id,
                        groupId = group,
                        reactionAttempts = setOf("johnny_like"),
                    ),
                )
            val demo = AppReviewDemo(backend, store, this, StandardTestDispatcher(testScheduler))

            demo.start()
            advanceUntilIdle()

            assertEquals(
                ReviewDemoStatus.Failed(ReviewDemoStage.SendingDemo, ReviewDemoProblem.ReactionUncertain),
                demo.status,
            )
            assertEquals(0, backend.reactionCreates)
            assertEquals(original.ref, backend.activeAccountRef)
        }

    @Test
    fun missingOriginalFailsWithoutCreatingOrTakingOverAnAccount() =
        runTest {
            val backend = FakeBackend().apply { accountList.clear() }
            val store = MemoryStore(checkpoint())
            val demo = AppReviewDemo(backend, store, this, StandardTestDispatcher(testScheduler))

            demo.start()
            advanceUntilIdle()

            assertEquals(
                ReviewDemoStatus.Failed(ReviewDemoStage.Preparing, ReviewDemoProblem.OriginalMissing),
                demo.status,
            )
            assertEquals(0, backend.accountCreates)
            assertEquals(0, backend.groupCreates)
            assertEquals(original.ref, backend.activeAccountRef)
        }

    @Test
    fun missingCrossAccountDeliveryNeverReportsReady() =
        runTest {
            val backend = FakeBackend().apply { crossDeliveryEnabled = false }
            val demo = AppReviewDemo(backend, MemoryStore(), this, StandardTestDispatcher(testScheduler))

            demo.start()
            advanceUntilIdle()

            assertEquals(
                ReviewDemoStatus.Failed(ReviewDemoStage.AcceptingInvitation, ReviewDemoProblem.DeliveryTimedOut),
                demo.status,
            )
            assertEquals(original.ref, backend.activeAccountRef)
            assertEquals(2, backend.messages.size)
        }

    @Test
    fun secondOriginalMessageMustReachJohnnyBeforeReady() =
        runTest {
            val backend = FakeBackend().apply { blockedDeliveryStep = "original_privacy" }
            val demo = AppReviewDemo(backend, MemoryStore(), this, StandardTestDispatcher(testScheduler))

            demo.start()
            advanceUntilIdle()

            assertEquals(
                ReviewDemoStatus.Failed(ReviewDemoStage.AcceptingInvitation, ReviewDemoProblem.DeliveryTimedOut),
                demo.status,
            )
            assertEquals(original.ref, backend.activeAccountRef)
            assertEquals(2, backend.messages.size)
        }

    @Test
    fun unrelatedSoleNewAccountIsNeverAdoptedByAResumedDemo() =
        runTest {
            val backend = FakeBackend().apply { accountList += johnny }
            val demo = AppReviewDemo(backend, MemoryStore(checkpoint()), this, StandardTestDispatcher(testScheduler))

            demo.start()
            advanceUntilIdle()

            assertEquals(
                ReviewDemoStatus.Failed(ReviewDemoStage.CreatingAccount, ReviewDemoProblem.AmbiguousAccount),
                demo.status,
            )
            assertEquals(0, backend.profilePublishes)
            assertEquals(0, backend.groupCreates)
            assertEquals(0, backend.accountCreates)
        }

    @Test
    fun lostAccountCreateReplyDoesNotCreateAgainBeforeAccountAppears() =
        runTest {
            val backend = FakeBackend().apply {
                loseAccountCreateReply = true
                hideCreatedAccount = true
            }
            val store = MemoryStore()
            val demo = AppReviewDemo(backend, store, this, StandardTestDispatcher(testScheduler))

            demo.start()
            advanceUntilIdle()
            assertEquals(1, backend.accountCreates)
            assertEquals(
                ReviewDemoStatus.Failed(ReviewDemoStage.CreatingAccount, ReviewDemoProblem.OperationFailed),
                demo.status,
            )

            demo.start()
            advanceUntilIdle()
            assertEquals(1, backend.accountCreates)
            assertEquals(0, backend.profilePublishes)
        }

    @Test
    fun lostCreateReplyReusesTheProjectedConversation() =
        runTest {
            val backend = FakeBackend().apply { loseGroupCreateReply = true }
            val demo = AppReviewDemo(backend, MemoryStore(), this, StandardTestDispatcher(testScheduler))

            demo.start()
            advanceUntilIdle()

            assertTrue(demo.status is ReviewDemoStatus.Ready)
            assertEquals(1, backend.groupCreates)
        }

    @Test
    fun lostProfileReplyUsesTheCachedPublishedProfileOnResume() =
        runTest {
            val backend = FakeBackend().apply { loseProfileReply = true }
            val store = MemoryStore()
            val demo = AppReviewDemo(backend, store, this, StandardTestDispatcher(testScheduler))

            demo.start()
            advanceUntilIdle()
            assertEquals(
                ReviewDemoStatus.Failed(ReviewDemoStage.PublishingProfile, ReviewDemoProblem.OperationFailed),
                demo.status,
            )
            assertEquals(1, backend.profilePublishes)

            demo.start()
            advanceUntilIdle()
            assertTrue(demo.status is ReviewDemoStatus.Ready)
            assertEquals(1, backend.profilePublishes)
        }

    @Test
    fun acceptedMessageWithLostReplyIsNotPublishedTwiceOnResume() =
        runTest {
            val backend = FakeBackend().apply { loseFirstMessageReply = true }
            val store = MemoryStore()
            val demo = AppReviewDemo(backend, store, this, StandardTestDispatcher(testScheduler))

            demo.start()
            advanceUntilIdle()
            assertTrue(demo.status is ReviewDemoStatus.Failed)
            assertEquals(1, backend.messages.size)

            demo.start()
            advanceUntilIdle()
            assertTrue(demo.status is ReviewDemoStatus.Ready)
            assertEquals(1, backend.messages.count { it.token == store.checkpoint?.token("original_greeting") })
            assertEquals(5, backend.messages.size)
        }

    @Test
    fun changedRuntimeOwnerStopsSetupWithoutSwitchingBack() =
        runTest {
            val backend = FakeBackend().apply { changeOwnerOnCreate = true }
            val demo = AppReviewDemo(backend, MemoryStore(), this, StandardTestDispatcher(testScheduler))

            demo.start()
            advanceUntilIdle()

            assertEquals(
                ReviewDemoStatus.Failed(ReviewDemoStage.CreatingAccount, ReviewDemoProblem.OwnerChanged),
                demo.status,
            )
            assertEquals(0, backend.groupCreates)
            assertEquals("other", backend.activeAccountRef)
        }

    @Test
    fun resumeFromUnrelatedActiveAccountNeverTakesOver() =
        runTest {
            val backend = FakeBackend().apply { activeAccountRef = "other" }
            val store = MemoryStore(checkpoint(demoRef = johnny.ref, demoId = johnny.id))
            val demo = AppReviewDemo(backend, store, this, StandardTestDispatcher(testScheduler))

            demo.start()
            advanceUntilIdle()

            assertEquals(
                ReviewDemoStatus.Failed(ReviewDemoStage.Preparing, ReviewDemoProblem.OwnerChanged),
                demo.status,
            )
            assertEquals("other", backend.activeAccountRef)
            assertEquals(0, backend.groupCreates)
        }

    @Test
    fun cancellationLeavesAResumableReceiptAndRestoresOriginal() =
        runTest {
            val backend = FakeBackend().apply { accountReady = false }
            val store = MemoryStore()
            val demo = AppReviewDemo(backend, store, this, StandardTestDispatcher(testScheduler))

            demo.start()
            runCurrent()
            demo.cancel()
            advanceUntilIdle()

            assertEquals(
                ReviewDemoStatus.Failed(ReviewDemoStage.CreatingAccount, ReviewDemoProblem.Interrupted),
                demo.status,
            )
            assertTrue(store.hasRecord)
            assertEquals(original.ref, backend.activeAccountRef)
            assertEquals(1, backend.accountCreates)
        }

    @Test
    fun activationRefreshFailureStillRestoresOriginalAccount() =
        runTest {
            val backend = FakeBackend().apply { failAfterFirstActivation = true }
            val demo = AppReviewDemo(backend, MemoryStore(), this, StandardTestDispatcher(testScheduler))

            demo.start()
            advanceUntilIdle()

            assertEquals(
                ReviewDemoStatus.Failed(ReviewDemoStage.AcceptingInvitation, ReviewDemoProblem.OperationFailed),
                demo.status,
            )
            assertEquals(original.ref, backend.activeAccountRef)
        }

    @Test
    fun clearDeletesOnlyTheReceipt() =
        runTest {
            val backend = FakeBackend()
            val store = MemoryStore()
            val demo = AppReviewDemo(backend, store, this, StandardTestDispatcher(testScheduler))
            demo.start()
            advanceUntilIdle()

            demo.clearSavedSetup()
            advanceUntilIdle()

            assertFalse(store.hasRecord)
            assertEquals(ReviewDemoStatus.Idle, demo.status)
            assertEquals(2, backend.accountList.size)
            assertEquals(5, backend.messages.size)
        }

    @Test
    fun encryptedReceiptSurvivesRecreationAndRejectsInvalidData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteSharedPreferences("review-demo-test-secure")
        val keyGenerator = javax.crypto.KeyGenerator.getInstance("AES")
        keyGenerator.init(256)
        val key = keyGenerator.generateKey()
        val provider =
            object : SecureStoreKeyProvider {
                override fun secretKey(): javax.crypto.SecretKey = key
            }

        fun secureStore() = KeystoreSecureStore(context, "review-demo-test-secure", provider)

        val first = SecureReviewDemoStore(secureStore())
        val saved = checkpoint(demoRef = johnny.ref, demoId = johnny.id, groupId = group)
        first.save(saved)

        assertEquals(saved, SecureReviewDemoStore(secureStore()).load())
        val preferences = context.getSharedPreferences("review-demo-test-secure", Context.MODE_PRIVATE)
        val raw = preferences.all.values.joinToString()
        assertFalse(raw.contains(saved.originalId))
        assertFalse(raw.contains(saved.demoId!!))
        preferences.edit().putString("payload", "corrupt ciphertext").commit()
        assertEquals(
            ReviewDemoProblem.InvalidCheckpoint,
            assertThrows(ReviewDemoFailure::class.java) { first.load() }.problem,
        )
        first.clear()
        assertFalse(first.hasRecord)
    }

    @Test
    fun legacyPlaintextReceiptMovesToEncryptedStorageBeforeResume() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteSharedPreferences("review-demo-migration-secure")
        val legacy = context.getSharedPreferences("review-demo-migration-legacy", Context.MODE_PRIVATE)
        legacy.edit().clear().commit()
        val keyGenerator = javax.crypto.KeyGenerator.getInstance("AES")
        keyGenerator.init(256)
        val key = keyGenerator.generateKey()
        val provider =
            object : SecureStoreKeyProvider {
                override fun secretKey(): javax.crypto.SecretKey = key
            }
        val encrypted = KeystoreSecureStore(context, "review-demo-migration-secure", provider)
        val saved = checkpoint(demoRef = johnny.ref, demoId = johnny.id, groupId = group)
        SecureReviewDemoStore(encrypted).save(saved)
        val json = encrypted.readAll().getValue("review_demo_checkpoint_v1")
        encrypted.clearDurably()
        legacy.edit().putString("review_demo_checkpoint_v1", json).commit()

        val migrating = SecureReviewDemoStore(encrypted, legacy)
        assertEquals(saved, migrating.load())
        assertFalse(legacy.contains("review_demo_checkpoint_v1"))
        assertEquals(saved, SecureReviewDemoStore(encrypted).load())
    }

    private fun checkpoint(
        demoRef: String? = null,
        demoId: String? = null,
        groupId: String? = null,
        reactionAttempts: Set<String> = emptySet(),
        completed: Boolean = false,
    ) = ReviewDemoCheckpoint(
        RUN_ID,
        original.ref,
        original.id,
        setOf(original.ref),
        demoRef,
        demoId,
        groupId,
        reactionAttempts = reactionAttempts,
        completed = completed,
    )

    private inner class FakeBackend : ReviewDemoBackend {
        override var activeAccountRef: String? = original.ref
        override var runtimeGeneration: Int = 1
        override val foregroundReady: Boolean = true
        val accountList = mutableListOf(original)
        val messages = mutableListOf<ReviewDemoMessage>()
        var groupExists = false
        var accountCreates = 0
        var groupCreates = 0
        var reactionCreates = 0
        var loseGroupCreateReply = false
        var changeOwnerOnCreate = false
        var loseFirstMessageReply = false
        var accountReady = true
        var inviteAccepts = 0
        var failAfterFirstActivation = false
        var crossDeliveryEnabled = true
        var blockedDeliveryStep: String? = null
        val deliveredMessageIds = mutableSetOf<String>()
        var profileStored = false
        var profilePublishes = 0
        var loseProfileReply = false
        var loseAccountCreateReply = false
        var hideCreatedAccount = false

        override suspend fun accounts() =
            accountList.filter { !hideCreatedAccount || it.ref == original.ref }

        override suspend fun createAccount(): ReviewDemoAccount {
            accountCreates++
            accountList += johnny
            if (loseAccountCreateReply) {
                loseAccountCreateReply = false
                throw IllegalStateException("lost account response")
            }
            if (changeOwnerOnCreate) {
                activeAccountRef = "other"
                runtimeGeneration++
            }
            return johnny
        }

        override suspend fun qualifyAccount(ref: String) = Unit

        override suspend fun accountNetworkReady(ref: String) = accountReady

        override suspend fun profilePublished(id: String) = profileStored

        override suspend fun publishProfile(ref: String) {
            profilePublishes++
            profileStored = true
            if (loseProfileReply) {
                loseProfileReply = false
                throw IllegalStateException("lost profile response")
            }
        }

        override suspend fun existingDirectConversation(
            ref: String,
            peerId: String,
        ) = group.takeIf { groupExists }

        override suspend fun createDirectConversation(
            ref: String,
            peerId: String,
        ): String {
            groupCreates++
            groupExists = true
            if (loseGroupCreateReply) throw IllegalStateException("lost response")
            return group
        }

        override suspend fun invitation(
            ref: String,
            groupId: String,
        ) = if (!groupExists) {
            null
        } else if (inviteAccepts == 0) {
            ReviewDemoInvitation.Pending
        } else {
            ReviewDemoInvitation.Accepted
        }

        override suspend fun acceptInvitation(
            ref: String,
            groupId: String,
        ) {
            inviteAccepts++
        }

        override suspend fun timeline(
            ref: String,
            groupId: String,
        ) = messages.filter { message ->
            message.sender == accountList.first { it.ref == ref }.id || message.id in deliveredMessageIds
        }

        override suspend fun submitMessage(
            ref: String,
            groupId: String,
            text: String,
            replyTo: String?,
            token: String,
        ) {
            messages +=
                ReviewDemoMessage(
                    "message-${messages.size}",
                    token,
                    accountList.first { it.ref == ref }.id,
                    text,
                    replyTo,
                    emptyList(),
                )
            if (loseFirstMessageReply) {
                loseFirstMessageReply = false
                throw IllegalStateException("lost response")
            }
        }

        override suspend fun submitReaction(
            ref: String,
            groupId: String,
            targetId: String,
            emoji: String,
        ) {
            reactionCreates++
            val index = messages.indexOfFirst { it.id == targetId }
            val prior = messages[index]
            messages[index] =
                prior.copy(
                    reactions =
                        prior.reactions +
                            ReviewDemoReaction(
                                accountList.first { it.ref == ref }.id,
                                emoji,
                            ),
                )
        }

        override suspend fun catchUp() {
            if (crossDeliveryEnabled && inviteAccepts > 0) {
                deliveredMessageIds +=
                    messages.filterNot { it.token?.endsWith(":$blockedDeliveryStep") == true }.map { it.id }
            }
        }

        override suspend fun activate(
            ref: String,
            stillOwned: () -> Boolean,
        ): Boolean {
            if (!stillOwned()) return false
            activeAccountRef = ref
            if (failAfterFirstActivation) {
                failAfterFirstActivation = false
                throw IllegalStateException("post-activation refresh failed")
            }
            return true
        }
    }

    private class MemoryStore(
        var checkpoint: ReviewDemoCheckpoint? = null,
    ) : ReviewDemoStore {
        override val hasRecord get() = checkpoint != null

        override fun load() = checkpoint

        override fun save(checkpoint: ReviewDemoCheckpoint) {
            this.checkpoint = checkpoint
        }

        override fun clear() {
            checkpoint = null
        }
    }

    private companion object {
        val RUN_ID: String = UUID.randomUUID().toString()
    }
}
