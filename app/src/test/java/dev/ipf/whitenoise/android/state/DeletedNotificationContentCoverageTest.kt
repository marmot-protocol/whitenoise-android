package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Invariant gate for #3323: every platform write of message content honors the current content invalidation, every
 * retained history entry carries the identity that lets it be removed, and every removal source reaches the
 * retraction. Behavioral coverage lives in NotificationContentRetractionTest, NotificationDeletedContentTest and
 * NotificationReactionRemovalRaceTest; this gate fails when a new write path or removal source bypasses them.
 */
class DeletedNotificationContentCoverageTest {
    /** Every content write from AppState rechecks the generation fence that carries the removal mark. */
    @Test
    fun everyCardWriteRechecksTheContentFence() {
        val source = source("state/AppState.kt")
        val shows = callArguments(source, "localNotificationPresenter.show(")
        val refreshes = callArguments(source, "localNotificationPresenter.refreshLateIdentityImage(")
        assertTrue("expected the initial, redacted and enrichment writes", shows.size >= 3)
        assertTrue("expected a late picture write", refreshes.isNotEmpty())
        val writes = shows.map { it to "isPostStillAllowed" } + refreshes.map { it to "isRefreshCurrent" }
        writes.forEach { (arguments, callback) ->
            assertTrue(
                "every card write must recheck the content fence in $callback: $arguments",
                hasFinalWriteFence(arguments, callback),
            )
        }
        assertTrue(
            "the generation fence must check the removal mark",
            "notificationContentInvalidations.isRemoved(" in
                functionBody(source, "isNotificationGenerationPostAllowed"),
        )
        assertTrue(
            "a reaction's own event id cannot verify its quoted target at the final write",
            "notificationContentInvalidations.hasRemovalInConversation(" in
                functionBody(source, "isNotificationGenerationPostAllowed"),
        )
    }

    /** Guard names in unrelated values cannot hide a missing or unfenced final-write callback. */
    @Test
    fun onlyTheFinalWriteCallbackCanSatisfyTheFenceGate() {
        val callbacks =
            listOf(
                "isPostStillAllowed" to "isNotificationGenerationPostAllowed",
                "isRefreshCurrent" to "isNotificationEnrichmentAllowed",
            )
        callbacks.forEach { (callback, guard) ->
            val unrelated = "previewTextOverride = $guard(update)"
            assertFalse(hasFinalWriteFence("($unrelated, $callback = { true })", callback))
            assertFalse(hasFinalWriteFence("($unrelated)", callback))
            assertTrue(hasFinalWriteFence("($callback = { $guard(update) })", callback))
        }
    }

    /** Every new history entry is stamped with its message id, and carried entries keep theirs. */
    @Test
    fun everyHistoryEntryCarriesItsIdentity() {
        val presenter = source("notifications/LocalNotificationPresenter.kt")
        val styles =
            callArguments(presenter, "messagingStyle(").filterNot { it.contains("content: LocalNotificationContent") }
        assertTrue("expected the first-write and fallback styles", styles.size >= 2)
        styles.forEach { arguments ->
            assertTrue("every new entry must be stamped: $arguments", "update.messageIdHex" in arguments)
        }
        assertTrue(
            "carried entries must keep their stamped extras",
            "copy.extras.putAll(message.extras)" in source("notifications/NotificationSenderReconciliation.kt"),
        )
    }

    /** Tray snapshots may lag app writes: carried history and every live rewrite need accepted-payload proof. */
    @Test
    fun stalePlatformPayloadsCannotBypassContentReconciliation() {
        val presenter = source("notifications/LocalNotificationPresenter.kt")
        assertTrue(
            "ConversationCardPostedRegistry.canCarryEntry(" in functionBody(presenter, "existingConversationMessages"),
        )
        val liveWrite =
            presenter
                .substringAfter("private fun notificationWriteIsLive(")
                .substringBefore("private fun canPublishPreviewShortcut(")
        assertTrue("ConversationCardPostedRegistry.isCurrentPayload(" in liveWrite)
        val reconcile = functionBody(presenter, "reconcileCard")
        assertTrue("ConversationCardPostedRegistry.latestGeneration(" in reconcile)
        assertTrue("cancelUnverifiedContentCard(" in reconcile)
        val cancel = functionBody(presenter, "cancelUnverifiedContentCard")
        assertTrue("ConversationCardPostedRegistry.clearPosted(" in cancel)
        assertTrue("cancelNotification(" in cancel)
        assertTrue("ConversationCardPostedRegistry.prepareCancellation(" in cancel)
        val cancelIndex = cancel.indexOf("cancelNotification(")
        assertTrue(cancelIndex < cancel.indexOf("ConversationCardPostedRegistry.clearPosted("))
        val grouping = source("notifications/NotificationGroupReconciler.kt")
        assertTrue("canAdoptLegacyPayload(live)" in grouping)
        assertTrue(
            "ConversationCardPostedRegistry.isCurrentPayload(" in
                grouping
                    .substringAfter("private fun canAdoptLegacyPayload(")
                    .substringBefore("private fun legacyCardBuilder("),
        )
    }

    /** Accepted deletions, local hides, read changes and stream opens all reach the reconciliation. */
    @Test
    fun everyRemovalSourceReachesTheRetraction() {
        val source = source("state/AppState.kt")
        val receiptLoop = functionBody(source, "runMutedChatReceiptLoop")
        assertTrue("handleNotificationContentEvent(event, readReconciliations)" in receiptLoop)
        assertTrue("NotificationReadReconciliationCoordinator(" in receiptLoop)
        assertTrue("scope = this" in receiptLoop)
        assertTrue("followUpMillis = NOTIFICATION_READ_RECONCILE_FOLLOW_UP_MILLIS" in receiptLoop)
        assertTrue("reconcileConversationNotificationCards(account, group, marmot)" in receiptLoop)
        assertTrue("requestActiveCardReconciliation(readReconciliations)" in receiptLoop)
        assertFalse("openReconciliation" in receiptLoop)
        val onOpen = functionBody(source, "requestActiveCardReconciliation")
        assertTrue("activeConversationCards()" in onOpen)
        assertTrue("coordinator.request(account, group)" in onOpen)
        assertTrue("retractNotificationContent(" in functionBody(source, "hideMessageForMe"))
        val eventBody = functionBody(source, "handleNotificationContentEvent")
        assertTrue("removedNotificationMessages(event)" in eventBody)
        assertTrue("readStateChangedConversation(event)" in eventBody)
        assertTrue("readReconciliations.request(accountRef, groupIdHex)" in eventBody)
    }

    /** The source file under the app's main package. */
    private fun source(relative: String): String =
        listOf("src/main", "app/src/main")
            .map { "$it/java/dev/ipf/whitenoise/android/$relative" }
            .map(::File)
            .firstOrNull { it.exists() }
            ?.readText()
            ?: error("Missing $relative")

    /** The argument list of every call to [call] in [source]. */
    private fun callArguments(
        source: String,
        call: String,
    ): List<String> =
        Regex(Regex.escape(call))
            .findAll(source)
            .map { match ->
                val open = match.range.last
                source.substring(open, matchingClose(source, open, '(', ')') + 1)
            }.toList()

    /** Requires the fence inside the presenter's chosen callback, rather than another value in the call. */
    private fun hasFinalWriteFence(
        arguments: String,
        callback: String,
    ): Boolean {
        val opening =
            Regex("""\b${Regex.escape(callback)}\s*=\s*\{""").find(arguments)?.range?.last ?: return false
        val body = arguments.substring(opening, matchingClose(arguments, opening, '{', '}') + 1)
        return "isNotificationEnrichmentAllowed(" in body ||
            (callback == "isPostStillAllowed" && "isNotificationGenerationPostAllowed(" in body)
    }

    /** The body of the named function in [source]. */
    private fun functionBody(
        source: String,
        name: String,
    ): String {
        val declaration = Regex("""\bfun\s+(?:\w+\.)?${Regex.escape(name)}\s*\(""")
        val start = declaration.find(source)?.range?.first ?: error("Missing $name")
        val signatureEnd = matchingClose(source, source.indexOf('(', start), '(', ')')
        val brace = source.indexOf('{', signatureEnd)
        return source.substring(brace, matchingClose(source, brace, '{', '}') + 1)
    }

    /** The index of the bracket closing the one at [open]. */
    private fun matchingClose(
        source: String,
        open: Int,
        opening: Char,
        closing: Char,
    ): Int {
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                opening -> depth++
                closing -> if (--depth == 0) return index
            }
        }
        error("Unbalanced $opening at $open")
    }
}
