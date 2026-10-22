package dev.ipf.whitenoise.android.state

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
        (shows + refreshes).forEach { arguments ->
            assertTrue(
                "every card write must recheck the content fence at its final write: $arguments",
                "isNotificationGenerationPostAllowed(" in arguments || "isNotificationEnrichmentAllowed(" in arguments,
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
        assertTrue("reconcileActiveNotificationCards(marmot)" in functionBody(source, "runMutedChatReceiptLoop"))
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
