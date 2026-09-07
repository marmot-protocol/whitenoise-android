package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BatchDeleteControllerCoverageTest {
    private val source = AppStateSendLockCoverageTest()

    @Test
    fun deleteMessageAuthorizationGuardPrecedesOptimisticAndFfiMutation() {
        val body = source.controllerFunctionBody("deleteMessageResult")
        val authorizationGate = body.indexOf("!deleteCapabilityFor(message).canDeleteForEveryone")
        val authorizationFailure = body.indexOf("Result.failure", startIndex = authorizationGate)
        val optimisticMutation = body.indexOf("deletedMessageIds = deletedMessageIds + target")
        val ffiDelete = body.indexOf("appState.marmotIo { deleteMessage(account, group.groupIdHex, target) }")

        assertTrue(
            "unauthorized deletes must produce a failure before mutating deletedMessageIds",
            authorizationGate >= 0 &&
                authorizationFailure > authorizationGate &&
                optimisticMutation > authorizationFailure,
        )
        assertTrue(
            "unauthorized deletes must produce a failure before reaching the FFI delete call",
            ffiDelete > authorizationFailure,
        )
    }

    @Test
    fun deleteMessageReturnsCommitResultAndBatchUsesIt() {
        val controllers = source.controllersSource().readText()
        val body = source.controllerFunctionBody("deleteMessageResult")
        val conversation = source.conversationScreenSource().readText()

        assertTrue(
            "deleteMessage must expose a Boolean commit result",
            Regex("""suspend\s+fun\s+deleteMessage\s*\([^)]*\)\s*:\s*Boolean""").containsMatchIn(controllers),
        )
        assertTrue(
            "deleteMessageResult guards must report why no commit occurred",
            "conversationAccountRef" in body &&
                "!deleteCapabilityFor(message).canDeleteForEveryone" in body &&
                "Result.failure" in body,
        )
        assertTrue(
            "deleteMessageResult must report success after the locked commit and failure after rollback",
            body.indexOf("appState.withGroupCommitLock") < body.indexOf("Result.success(Unit)") &&
                body.indexOf("deletedMessageIds = deletedMessageIds - target") < body.lastIndexOf("Result.failure"),
        )
        assertTrue(
            "deleteMessage must roll back optimistic deletion before propagating cancellation",
            body.indexOf("deletedMessageIds = deletedMessageIds - target") <
                body.indexOf("throwable.rethrowIfCancellation()"),
        )
        assertTrue(
            "batch deletion must retain structured commit failures without emitting one snackbar per item",
            "controller.deleteMessageResult(record, presentFailure = false)" in conversation &&
                "hideLocally = controller::hideMessageForMeResult" in conversation &&
                "BatchDeleteRetryState.from(result)" in conversation &&
                "if (presentFailure)" in body &&
                "record.messageIdHex !in controller.deletedMessageIds" !in conversation,
        )
    }

    @Test
    fun batchLocalHideRejectsMissingConversationOwner() {
        val body = source.controllerFunctionBody("hideMessageForMeResult")

        assertTrue(
            "batch local hide must bind to a concrete account before touching preferences or timeline state",
            body.indexOf("conversationAccountRef") < body.indexOf("appState.hideMessageForMe(account") &&
                "Result.failure" in body &&
                "Result.success(Unit)" in body,
        )
    }

    @Test
    fun batchDeleteRetryStateIsScopedToConversationOwnerAndGuardsRecomposition() {
        val conversation = source.conversationScreenSource().readText().replace(Regex("\\s+"), " ")
        val mainShell = mainShellSource().readText().replace(Regex("\\s+"), " ")
        // The owner account follows the conversation, not the live active ref,
        // so a notification-routed early open keeps one owner across the
        // account-switch flip while real owner changes still reset the state.
        val ownerKeys = "controller, chat.id, conversationAccountRef, appState.runtimeGeneration"
        val cancellationStart = conversation.indexOf("catch (cancellation: CancellationException)")
        val cancellationEnd = conversation.indexOf("finally", cancellationStart)

        assertTrue(
            "failed selections, retry state, and the submission guard must reset together when their owner changes",
            "val selectedMessages = presentationState.selectedMessages" in conversation &&
                "surfaceState ?: remember($ownerKeys) { ConversationSurfaceState() }" in conversation &&
                "var batchDeleteRetryState by remember($ownerKeys)" in conversation &&
                "val batchDeleteSubmissionGuard = remember($ownerKeys)" in conversation,
        )
        assertConversationSurfaceOwnership(mainShell)
        assertTrue(
            "recomposition or repeated taps must not start a second delete while the scoped attempt is active",
            "if (attempts.isEmpty() || !batchDeleteSubmissionGuard.tryStart()) return" in conversation &&
                "batchDeleteSubmissionGuard.finish()" in conversation,
        )
        assertTrue(
            "cancellation must preserve first-attempt failures, reconcile completed work, " +
                "and propagate without publishing an error",
            cancellationStart >= 0 &&
                cancellationEnd > cancellationStart &&
                "completedOutcomes" in conversation.substring(cancellationStart, cancellationEnd) &&
                "BatchDeleteRetryState.from(completedResult)" in
                conversation.substring(cancellationStart, cancellationEnd) &&
                "throw cancellation" in conversation.substring(cancellationStart, cancellationEnd) &&
                "appState.present" !in conversation.substring(cancellationStart, cancellationEnd),
        )
    }

    private fun assertConversationSurfaceOwnership(mainShell: String) {
        val surfaceOwner =
            "remember(selectedOrPendingConversationController, appState.runtimeGeneration) { " +
                "ConversationSurfaceState() }"
        assertTrue(surfaceOwner in mainShell)
        assertTrue("chatId = openChat.id" in mainShell)
        assertTrue("accountRef = accountRef" in mainShell)
        assertTrue("runtimeGeneration = appState.runtimeGeneration" in mainShell)
        assertTrue("surfaceState = selectedConversationSurfaceState" in mainShell)
    }

    private fun mainShellSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing MainShell.kt source file")
}
