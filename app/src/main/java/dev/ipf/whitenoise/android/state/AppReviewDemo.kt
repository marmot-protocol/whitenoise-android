package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.util.UUID

/** A local resume receipt, never a second source of truth for accounts, groups, or messages. */
internal data class ReviewDemoCheckpoint(
    val runId: String,
    val originalRef: String,
    val originalId: String,
    val initialAccountRefs: Set<String>,
    val demoRef: String? = null,
    val demoId: String? = null,
    val groupId: String? = null,
    val profilePublished: Boolean = false,
    val reactionAttempts: Set<String> = emptySet(),
    val completed: Boolean = false,
) {
    fun token(step: String): String = "review-demo:$runId:$step"
}

internal interface ReviewDemoStore {
    val hasRecord: Boolean

    /** Malformed or newer records fail closed until the user clears them. */
    fun load(): ReviewDemoCheckpoint?

    /** Commit synchronously before a native side effect can be accepted. */
    fun save(checkpoint: ReviewDemoCheckpoint)

    /** Deletes only this local receipt. Published accounts and events remain. */
    fun clear()
}

internal class SecureReviewDemoStore(
    private val secureStore: KeystoreSecureStore,
    private val legacyPreferences: SharedPreferences? = null,
) : ReviewDemoStore {
    override val hasRecord: Boolean
        get() =
            try {
                KEY in secureStore.readAll() || legacyPreferences?.contains(LEGACY_KEY) == true
            } catch (_: GeneralSecurityException) {
                // An unreadable encrypted receipt must still offer Clear in the UI.
                true
            }

    override fun load(): ReviewDemoCheckpoint? {
        val encrypted =
            try {
                secureStore.readAll()[KEY]
            } catch (_: GeneralSecurityException) {
                throw ReviewDemoFailure(ReviewDemoProblem.InvalidCheckpoint)
            }
        val raw =
            try {
                encrypted ?: legacyPreferences?.getString(LEGACY_KEY, null) ?: return null
            } catch (_: RuntimeException) {
                throw ReviewDemoFailure(ReviewDemoProblem.InvalidCheckpoint)
            }
        val checkpoint =
            try {
                val json = JSONObject(raw)
                require(json.getInt("version") == VERSION)
                val initial = json.getJSONArray("initialRefs")
                val initialRefs = (0 until initial.length()).map(initial::getString).toSet()
                val attempts = json.optJSONArray("reactionAttempts") ?: JSONArray()
                val reactionAttempts = (0 until attempts.length()).map(attempts::getString).toSet()
                ReviewDemoCheckpoint(
                    runId = json.getString("runId"),
                    originalRef = json.getString("originalRef"),
                    originalId = json.getString("originalId"),
                    initialAccountRefs = initialRefs,
                    demoRef = json.optString("demoRef").takeIf(String::isNotBlank),
                    demoId = json.optString("demoId").takeIf(String::isNotBlank),
                    groupId = json.optString("groupId").takeIf(String::isNotBlank),
                    profilePublished = json.optBoolean("profilePublished"),
                    reactionAttempts = reactionAttempts,
                    completed = json.optBoolean("completed"),
                ).also { checkpoint ->
                    require(runCatching { UUID.fromString(checkpoint.runId) }.isSuccess)
                    require(checkpoint.originalRef.isNotBlank() && checkpoint.originalId.isHexId())
                    require(checkpoint.originalRef in initialRefs && initialRefs.none(String::isBlank))
                    require((checkpoint.demoRef == null) == (checkpoint.demoId == null))
                    require(checkpoint.demoId == null || checkpoint.demoId.isHexId())
                    require(checkpoint.demoId == null || !checkpoint.demoId.equals(checkpoint.originalId, ignoreCase = true))
                    require(checkpoint.demoRef == null || checkpoint.demoRef !in initialRefs)
                    require(checkpoint.groupId == null || checkpoint.groupId.isHexGroupId())
                    require(!checkpoint.profilePublished || checkpoint.demoRef != null)
                    require(checkpoint.groupId == null || checkpoint.demoRef != null)
                    require(reactionAttempts.isEmpty() || checkpoint.groupId != null)
                    require(!checkpoint.completed || checkpoint.groupId != null)
                    require(reactionAttempts.all { it == JOHNNY_LIKE || it == ORIGINAL_HEART })
                }
            } catch (_: Exception) {
                throw ReviewDemoFailure(ReviewDemoProblem.InvalidCheckpoint)
            }
        // Preview builds stored this receipt in plaintext. Move it before any
        // resumed native side effect, then remove the old copy.
        if (encrypted == null) save(checkpoint)
        return checkpoint
    }

    override fun save(checkpoint: ReviewDemoCheckpoint) {
        val json =
            JSONObject()
                .put("version", VERSION)
                .put("runId", checkpoint.runId)
                .put("originalRef", checkpoint.originalRef)
                .put("originalId", checkpoint.originalId)
                .put("initialRefs", JSONArray(checkpoint.initialAccountRefs.sorted()))
                .put("profilePublished", checkpoint.profilePublished)
                .put("reactionAttempts", JSONArray(checkpoint.reactionAttempts.sorted()))
                .put("completed", checkpoint.completed)
        checkpoint.demoRef?.let { json.put("demoRef", it) }
        checkpoint.demoId?.let { json.put("demoId", it) }
        checkpoint.groupId?.let { json.put("groupId", it) }
        check(secureStore.replaceAllDurably(mapOf(KEY to json.toString()))) { "demo receipt could not be saved" }
        check(legacyPreferences?.edit()?.remove(LEGACY_KEY)?.commit() != false) { "old demo receipt could not be cleared" }
    }

    override fun clear() {
        check(secureStore.clearDurably()) { "demo receipt could not be cleared" }
        check(legacyPreferences?.edit()?.remove(LEGACY_KEY)?.commit() != false) { "old demo receipt could not be cleared" }
    }

    private companion object {
        const val VERSION = 1
        const val KEY = "review_demo_checkpoint_v1"
        const val LEGACY_KEY = KEY
    }
}

private val HEX_ID = Regex("[0-9a-fA-F]{64}")
private val HEX_GROUP = Regex("(?:[0-9a-fA-F]{2})+")

private fun String.isHexId(): Boolean = HEX_ID.matches(this)

private fun String.isHexGroupId(): Boolean = HEX_GROUP.matches(this)

internal data class ReviewDemoAccount(
    val ref: String,
    val id: String,
    val localSigning: Boolean,
    val signedOut: Boolean,
)

internal data class ReviewDemoReaction(
    val sender: String,
    val emoji: String,
)

internal data class ReviewDemoMessage(
    val id: String,
    val token: String?,
    val sender: String,
    val text: String,
    val replyTo: String?,
    val reactions: List<ReviewDemoReaction>,
)

/** The narrow native boundary keeps coordinator retry and ownership rules testable. */
internal interface ReviewDemoBackend {
    val activeAccountRef: String?
    val runtimeGeneration: Int
    val foregroundReady: Boolean

    suspend fun accounts(): List<ReviewDemoAccount>

    suspend fun createAccount(): ReviewDemoAccount

    suspend fun qualifyAccount(ref: String)

    suspend fun accountNetworkReady(ref: String): Boolean

    suspend fun profilePublished(id: String): Boolean

    suspend fun publishProfile(ref: String)

    suspend fun existingDirectConversation(
        ref: String,
        peerId: String,
    ): String?

    suspend fun createDirectConversation(
        ref: String,
        peerId: String,
    ): String

    suspend fun invitation(
        ref: String,
        groupId: String,
    ): ReviewDemoInvitation?

    suspend fun acceptInvitation(
        ref: String,
        groupId: String,
    )

    suspend fun timeline(
        ref: String,
        groupId: String,
    ): List<ReviewDemoMessage>

    suspend fun submitMessage(
        ref: String,
        groupId: String,
        text: String,
        replyTo: String?,
        token: String,
    )

    suspend fun submitReaction(
        ref: String,
        groupId: String,
        targetId: String,
        emoji: String,
    )

    suspend fun catchUp()

    suspend fun activate(
        ref: String,
        stillOwned: () -> Boolean,
    ): Boolean
}

internal enum class ReviewDemoInvitation { Pending, Accepted }

internal enum class ReviewDemoStage {
    Preparing,
    CreatingAccount,
    PublishingProfile,
    CreatingConversation,
    SendingOriginal,
    AcceptingInvitation,
    SendingDemo,
    VerifyingDelivery,
    Returning,
}

internal enum class ReviewDemoProblem {
    Unavailable,
    InvalidCheckpoint,
    OriginalMissing,
    DemoMissing,
    AmbiguousAccount,
    OwnerChanged,
    AccountSetupTimedOut,
    DeliveryTimedOut,
    ReactionUncertain,
    Interrupted,
    OperationFailed,
}

internal sealed interface ReviewDemoStatus {
    data object Idle : ReviewDemoStatus

    data class Running(
        val stage: ReviewDemoStage,
    ) : ReviewDemoStatus

    data class Failed(
        val stage: ReviewDemoStage,
        val problem: ReviewDemoProblem,
    ) : ReviewDemoStatus

    data class Ready(
        val accountRef: String,
        val groupId: String,
    ) : ReviewDemoStatus
}

internal class ReviewDemoFailure(
    val problem: ReviewDemoProblem,
) : IllegalStateException()

private const val JOHNNY_LIKE = "johnny_like"
private const val ORIGINAL_HEART = "original_heart"

/** Process-owned setup; resumed calls inspect MDK before every native mutation. */
internal class AppReviewDemo(
    private val backend: ReviewDemoBackend,
    private val store: ReviewDemoStore,
    private val scope: CoroutineScope,
) {
    var status by mutableStateOf<ReviewDemoStatus>(initialStatus())
        private set

    val hasSavedSetup: Boolean
        get() = store.hasRecord

    val canBegin: Boolean
        get() = backend.foregroundReady

    private var task: Job? = null

    /** A second tap never starts a second account or publisher. */
    fun start(onReady: (String, String) -> Unit = { _, _ -> }) {
        if (task?.isActive == true || status is ReviewDemoStatus.Ready) return
        task =
            scope.launch {
                run(onReady)
            }
    }

    fun cancel() {
        task?.cancel()
    }

    fun clearSavedSetup() {
        if (task?.isActive == true) return
        store.clear()
        status = ReviewDemoStatus.Idle
    }

    fun reportOpenFailure() {
        if (status is ReviewDemoStatus.Ready) {
            status = ReviewDemoStatus.Failed(ReviewDemoStage.Returning, ReviewDemoProblem.OperationFailed)
        }
    }

    private fun initialStatus(): ReviewDemoStatus =
        try {
            store.load()?.takeIf { it.completed }?.let { ReviewDemoStatus.Ready(it.originalRef, requireNotNull(it.groupId)) }
                ?: ReviewDemoStatus.Idle
        } catch (failure: ReviewDemoFailure) {
            ReviewDemoStatus.Failed(ReviewDemoStage.Preparing, failure.problem)
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun run(onReady: (String, String) -> Unit) {
        var stage = ReviewDemoStage.Preparing
        var expectedActive = backend.activeAccountRef
        val generation = backend.runtimeGeneration
        var originalRef: String? = null
        var restoreAllowed = false

        fun owned() = backend.runtimeGeneration == generation && backend.activeAccountRef == expectedActive

        fun requireOwned() {
            if (!owned()) throw ReviewDemoFailure(ReviewDemoProblem.OwnerChanged)
            if (!backend.foregroundReady) throw ReviewDemoFailure(ReviewDemoProblem.Unavailable)
        }

        suspend fun activate(ref: String) {
            requireOwned()
            if (backend.activeAccountRef != ref) {
                val activated =
                    try {
                        backend.activate(ref, ::owned)
                    } catch (failure: Exception) {
                        // Account activation can publish the selection before its post-activation refresh fails.
                        if (backend.runtimeGeneration == generation && backend.activeAccountRef == ref) expectedActive = ref
                        throw failure
                    }
                if (!activated) throw ReviewDemoFailure(ReviewDemoProblem.OwnerChanged)
                expectedActive = ref
            }
            requireOwned()
        }
        try {
            if (!backend.foregroundReady) throw ReviewDemoFailure(ReviewDemoProblem.Unavailable)
            status = ReviewDemoStatus.Running(stage)
            val accounts = backend.accounts()
            val saved = store.load()
            var checkpoint =
                saved ?: run {
                    val original =
                        accounts.firstOrNull { it.ref == expectedActive && it.localSigning && !it.signedOut }
                            ?: throw ReviewDemoFailure(ReviewDemoProblem.Unavailable)
                    ReviewDemoCheckpoint(
                        runId = UUID.randomUUID().toString(),
                        originalRef = original.ref,
                        originalId = original.id,
                        initialAccountRefs = accounts.map(ReviewDemoAccount::ref).toSet(),
                    ).also(store::save)
                }
            originalRef = checkpoint.originalRef
            val original =
                accounts
                    .firstOrNull { it.ref == checkpoint.originalRef && it.id == checkpoint.originalId }
                    ?.takeIf { it.localSigning && !it.signedOut }
                    ?: throw ReviewDemoFailure(ReviewDemoProblem.OriginalMissing)
            if (expectedActive != checkpoint.originalRef && expectedActive != checkpoint.demoRef) {
                throw ReviewDemoFailure(ReviewDemoProblem.OwnerChanged)
            }
            restoreAllowed = true
            requireOwned()

            stage = ReviewDemoStage.CreatingAccount
            status = ReviewDemoStatus.Running(stage)
            val demo = ensureDemoAccount(checkpoint, ::requireOwned)
            if (demo.id.equals(original.id, ignoreCase = true)) {
                throw ReviewDemoFailure(ReviewDemoProblem.AmbiguousAccount)
            }
            if (checkpoint.demoRef == null) {
                checkpoint = checkpoint.copy(demoRef = demo.ref, demoId = demo.id)
                store.save(checkpoint)
            }
            requireOwned()
            backend.qualifyAccount(demo.ref)
            waitForSetup(demo.ref, ::requireOwned)

            stage = ReviewDemoStage.PublishingProfile
            status = ReviewDemoStatus.Running(stage)
            if (!checkpoint.profilePublished) {
                requireOwned()
                // MDK caches a successfully published own profile. A lost
                // response can therefore be reconciled without a second event.
                if (!backend.profilePublished(demo.id)) backend.publishProfile(demo.ref)
                checkpoint = checkpoint.copy(profilePublished = true)
                store.save(checkpoint)
            }

            stage = ReviewDemoStage.CreatingConversation
            status = ReviewDemoStatus.Running(stage)
            activate(original.ref)
            val groupId =
                checkpoint.groupId ?: run {
                    requireOwned()
                    val found = backend.existingDirectConversation(original.ref, demo.id)
                    val id =
                        found ?: try {
                            backend.createDirectConversation(original.ref, demo.id)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            // A native create can succeed before its reply is lost.
                            backend.existingDirectConversation(original.ref, demo.id) ?: throw failure
                        }
                    checkpoint = checkpoint.copy(groupId = id)
                    store.save(checkpoint)
                    id
                }

            stage = ReviewDemoStage.SendingOriginal
            status = ReviewDemoStatus.Running(stage)
            val greeting = ensureMessage(checkpoint, original, groupId, "original_greeting", ORIGINAL_GREETING, null, ::requireOwned)
            val privacy = ensureMessage(checkpoint, original, groupId, "original_privacy", ORIGINAL_PRIVACY, null, ::requireOwned)

            stage = ReviewDemoStage.AcceptingInvitation
            status = ReviewDemoStatus.Running(stage)
            waitForInvitation(demo.ref, groupId, ::requireOwned)
            activate(demo.ref)
            if (backend.invitation(demo.ref, groupId) == ReviewDemoInvitation.Pending) {
                requireOwned()
                backend.acceptInvitation(demo.ref, groupId)
            }
            waitForMessage(demo.ref, groupId, greeting.id, original.id, ::requireOwned)
            waitForMessage(demo.ref, groupId, privacy.id, original.id, ::requireOwned)

            stage = ReviewDemoStage.SendingDemo
            status = ReviewDemoStatus.Running(stage)
            val reply = ensureMessage(checkpoint, demo, groupId, "demo_reply", DEMO_REPLY, greeting.id, ::requireOwned)
            val feature = ensureMessage(checkpoint, demo, groupId, "demo_feature", DEMO_FEATURE, null, ::requireOwned)
            checkpoint = ensureReaction(checkpoint, JOHNNY_LIKE, demo, groupId, greeting.id, "👍", ::requireOwned)

            stage = ReviewDemoStage.VerifyingDelivery
            status = ReviewDemoStatus.Running(stage)
            activate(original.ref)
            waitForMessage(original.ref, groupId, reply.id, demo.id, ::requireOwned)
            waitForMessage(original.ref, groupId, feature.id, demo.id, ::requireOwned)
            waitForReaction(original.ref, groupId, greeting.id, demo.id, "👍", ::requireOwned)
            val finalReply =
                ensureMessage(
                    checkpoint,
                    original,
                    groupId,
                    "original_reply",
                    ORIGINAL_REPLY,
                    feature.id,
                    ::requireOwned,
                )
            checkpoint = ensureReaction(checkpoint, ORIGINAL_HEART, original, groupId, reply.id, "❤️", ::requireOwned)
            waitForMessage(demo.ref, groupId, finalReply.id, original.id, ::requireOwned)
            waitForReaction(demo.ref, groupId, reply.id, original.id, "❤️", ::requireOwned)

            stage = ReviewDemoStage.Returning
            status = ReviewDemoStatus.Running(stage)
            activate(original.ref)
            checkpoint = checkpoint.copy(completed = true)
            store.save(checkpoint)
            status = ReviewDemoStatus.Ready(original.ref, groupId)
            runCatching { onReady(original.ref, groupId) }
        } catch (_: CancellationException) {
            status = ReviewDemoStatus.Failed(stage, ReviewDemoProblem.Interrupted)
        } catch (failure: ReviewDemoFailure) {
            status = ReviewDemoStatus.Failed(stage, failure.problem)
        } catch (_: Exception) {
            status = ReviewDemoStatus.Failed(stage, ReviewDemoProblem.OperationFailed)
        } finally {
            val restore = originalRef
            if (restoreAllowed &&
                restore != null &&
                backend.activeAccountRef != restore &&
                backend.activeAccountRef == expectedActive &&
                backend.runtimeGeneration == generation
            ) {
                withContext(NonCancellable) {
                    runCatching { backend.activate(restore, ::owned) }
                }
            }
        }
    }

    private suspend fun ensureDemoAccount(
        checkpoint: ReviewDemoCheckpoint,
        requireOwned: () -> Unit,
    ): ReviewDemoAccount {
        val current = backend.accounts()
        checkpoint.demoRef?.let { ref ->
            return current.firstOrNull { it.ref == ref && it.id == checkpoint.demoId && it.localSigning && !it.signedOut }
                ?: throw ReviewDemoFailure(ReviewDemoProblem.DemoMissing)
        }
        val candidates = current.filter { it.ref !in checkpoint.initialAccountRefs && it.localSigning && !it.signedOut }
        if (candidates.size > 1) throw ReviewDemoFailure(ReviewDemoProblem.AmbiguousAccount)
        if (candidates.size == 1) return candidates.single()
        requireOwned()
        return try {
            backend.createAccount()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val after = backend.accounts().filter { it.ref !in checkpoint.initialAccountRefs && it.localSigning && !it.signedOut }
            if (after.size == 1) after.single() else throw failure
        }
    }

    private suspend fun waitForSetup(
        ref: String,
        requireOwned: () -> Unit,
    ) {
        repeat(60) {
            requireOwned()
            if (backend.accountNetworkReady(ref)) return
            delay(1_000)
        }
        throw ReviewDemoFailure(ReviewDemoProblem.AccountSetupTimedOut)
    }

    private suspend fun waitForInvitation(
        ref: String,
        groupId: String,
        requireOwned: () -> Unit,
    ) {
        repeat(60) {
            requireOwned()
            backend.catchUp()
            if (backend.invitation(ref, groupId) != null) return
            delay(1_000)
        }
        throw ReviewDemoFailure(ReviewDemoProblem.DeliveryTimedOut)
    }

    private suspend fun ensureMessage(
        checkpoint: ReviewDemoCheckpoint,
        sender: ReviewDemoAccount,
        groupId: String,
        step: String,
        text: String,
        replyTo: String?,
        requireOwned: () -> Unit,
    ): ReviewDemoMessage {
        val token = checkpoint.token(step)
        backend
            .timeline(sender.ref, groupId)
            .firstOrNull {
                it.token == token &&
                    it.sender.equals(sender.id, ignoreCase = true) &&
                    it.text == text &&
                    it.replyTo.equals(replyTo, ignoreCase = true)
            }?.let { return it }
        requireOwned()
        backend.submitMessage(sender.ref, groupId, text, replyTo, token)
        repeat(60) {
            requireOwned()
            backend.catchUp()
            backend
                .timeline(sender.ref, groupId)
                .firstOrNull {
                    it.token == token &&
                        it.sender.equals(sender.id, ignoreCase = true) &&
                        it.text == text &&
                        it.replyTo.equals(replyTo, ignoreCase = true)
                }?.let { return it }
            delay(1_000)
        }
        throw ReviewDemoFailure(ReviewDemoProblem.DeliveryTimedOut)
    }

    private suspend fun waitForMessage(
        ref: String,
        groupId: String,
        messageId: String,
        senderId: String,
        requireOwned: () -> Unit,
    ) {
        repeat(60) {
            requireOwned()
            backend.catchUp()
            if (backend.timeline(ref, groupId).any {
                    it.id.equals(messageId, ignoreCase = true) && it.sender.equals(senderId, ignoreCase = true)
                }
            ) {
                return
            }
            delay(1_000)
        }
        throw ReviewDemoFailure(ReviewDemoProblem.DeliveryTimedOut)
    }

    private suspend fun ensureReaction(
        checkpoint: ReviewDemoCheckpoint,
        step: String,
        sender: ReviewDemoAccount,
        groupId: String,
        targetId: String,
        emoji: String,
        requireOwned: () -> Unit,
    ): ReviewDemoCheckpoint {
        if (hasReaction(sender.ref, groupId, targetId, sender.id, emoji)) return checkpoint
        if (step in checkpoint.reactionAttempts) {
            // A dropped native reply may already have published. Never replay
            // an unkeyed reaction while its outcome is still uncertain.
            repeat(20) {
                requireOwned()
                backend.catchUp()
                if (hasReaction(sender.ref, groupId, targetId, sender.id, emoji)) return checkpoint
                delay(500)
            }
            throw ReviewDemoFailure(ReviewDemoProblem.ReactionUncertain)
        }
        requireOwned()
        val attempted = checkpoint.copy(reactionAttempts = checkpoint.reactionAttempts + step)
        store.save(attempted)
        backend.submitReaction(sender.ref, groupId, targetId, emoji)
        waitForReaction(sender.ref, groupId, targetId, sender.id, emoji, requireOwned)
        return attempted
    }

    private suspend fun waitForReaction(
        ref: String,
        groupId: String,
        targetId: String,
        senderId: String,
        emoji: String,
        requireOwned: () -> Unit,
    ) {
        repeat(60) {
            requireOwned()
            backend.catchUp()
            if (hasReaction(ref, groupId, targetId, senderId, emoji)) return
            delay(1_000)
        }
        throw ReviewDemoFailure(ReviewDemoProblem.DeliveryTimedOut)
    }

    private suspend fun hasReaction(
        ref: String,
        groupId: String,
        targetId: String,
        senderId: String,
        emoji: String,
    ): Boolean =
        backend
            .timeline(ref, groupId)
            .firstOrNull { it.id.equals(targetId, ignoreCase = true) }
            ?.reactions
            ?.any { it.sender.equals(senderId, ignoreCase = true) && it.emoji == emoji } == true

    private companion object {
        const val ORIGINAL_GREETING = "Hi Johnny! Welcome to White Noise. 👋"
        const val ORIGINAL_PRIVACY = "This is a real end-to-end encrypted conversation for app review."
        const val DEMO_REPLY = "Hi! I can read your messages on my profile."
        const val DEMO_FEATURE = "Replies and reactions work across both profiles."
        const val ORIGINAL_REPLY = "Great — the demo conversation is ready to explore."
    }
}
