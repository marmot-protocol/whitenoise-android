package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.appModuleFile
import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariant gate: a reply never names a client token. MDK writes any non-empty reply target into the `e` and `q`
 * tags, while the user's own message keeps its send's client token until MDK accepts it. Every hop from the composer
 * to an MDK call that carries a reply target is pinned here, and each entry takes its target from the reply resolver,
 * which maps the token to the accepted event id or refuses the send.
 */
class ReplyTargetPublicationCoverageTest {
    /** Each hop toward an MDK call that carries a reply target is called only from its pinned function. */
    @Test
    fun replyCarryingCallsStayOnTheirPinnedPaths() {
        val root = appModuleFile("src/main/java")
        val calls =
            root
                .walkTopDown()
                .filter { it.extension == "kt" }
                .flatMap { file ->
                    val path = file.relativeTo(root).invariantSeparatorsPath.removePrefix(PACKAGE_PATH)
                    replyCalls(file.readText()).map { (call, caller) -> "$call <- $path#$caller" }
                }.sorted()
                .toList()
        assertEquals(PINNED_CALLS.sorted(), calls)
    }

    /** A text reply, plain or carrying the user's own emoji, resolves its target before its bubble is shown. */
    @Test
    fun textRepliesResolveTheirTarget() {
        val controller = appModuleSource(CONTROLLER)
        val send = controller.functionBody("send")
        val resolution =
            "val replyTarget = replyTargetForSend(" +
                "replyingTo?.messageIdHex?.takeIf { consumeReply })"
        val resolved = send.indexOf(resolution)
        assertTrue("send must resolve the reply target", resolved >= 0)
        assertTrue("the target is resolved before the bubble exists", resolved < send.indexOf("UUID.randomUUID()"))
        assertTrue("ordinary text sends keep reply resolution enabled", "consumeReply: Boolean = true" in controller)
        assertTrue("only a send carrying the draft's reply may clear it", "if (consumeReply) replyingTo = null" in send)
    }

    /** A retry republishes the target its failed bubble was built with, which the first send already resolved. */
    @Test
    fun retriesReuseTheResolvedTarget() {
        val retry = appModuleSource(CONTROLLER).functionBody("retryFailedSend")
        assertTrue("val replyTarget = MessageProjector.replyTargetMessageId(current.record)" in retry)
        assertFalse("a retry never reads the current selection", "replyingTo" in retry)
    }

    /** A media reply resolves its target first; only the resolved one is shown, staged as a draft or kept for retry. */
    @Test
    fun mediaRepliesResolveTheirTarget() {
        val queue = appModuleSource(CONTROLLER).functionBody("queueAttachments")
        val resolution = "val target = replyTargetForSend(replyTarget)"
        val resolved = queue.indexOf(resolution)
        assertTrue("queueAttachments must resolve the reply target", resolved >= 0)
        val afterResolution = queue.substring(resolved + resolution.length).replace("replyTarget = target", "")
        assertFalse("the unresolved target is never read after resolution", "replyTarget" in afterResolution)
    }

    /** Polls and stickers resolve the reply captured at submit through the same resolver, never the live selection. */
    @Test
    fun pollAndStickerRepliesResolveTheirTarget() {
        mapOf(POLL to "createPoll", STICKER to "sendSticker").forEach { (file, function) ->
            val body = appModuleSource(file).functionBody(function)
            assertTrue("$function must resolve the reply", "replyTagsForSend(reply).getOrNull()" in body)
        }
    }

    /** The only detached text send carries no reply, since no mounted composer is left to resolve one. */
    @Test
    fun detachedDictationSendsNoReply() {
        val detached = appModuleSource(DRAFT_SEND).functionBody("sendDetachedDictationText")
        assertTrue("sendComposerText(request.accountRef, request.groupIdHex, null," in detached)
    }

    /** Names each reply-carrying call in [source] with its enclosing function, skipping declarations and comments. */
    private fun replyCalls(source: String): Sequence<Pair<String, String>> =
        CALL.findAll(source).mapNotNull { match ->
            val start = match.range.first
            val linePrefix = source.substring(source.lastIndexOf('\n', start) + 1, start).trimStart()
            val comment = linePrefix.startsWith("*") || linePrefix.startsWith("//")
            val before = source.substring(maxOf(0, start - DECLARATION_WINDOW), start)
            if (comment || DECLARATION_TAIL.containsMatchIn(before)) return@mapNotNull null
            val caller =
                FUNCTION
                    .findAll(source.substring(0, start))
                    .lastOrNull()
                    ?.groupValues
                    ?.get(1) ?: "<top level>"
            match.groupValues[1] to caller
        }

    private companion object {
        const val PACKAGE_PATH = "dev/ipf/whitenoise/android/"
        const val CONTROLLER = "src/main/java/dev/ipf/whitenoise/android/state/ConversationController.kt"
        const val POLL = "src/main/java/dev/ipf/whitenoise/android/state/PollConversationActions.kt"
        const val STICKER = "src/main/java/dev/ipf/whitenoise/android/state/StickerConversationActions.kt"
        const val DRAFT_SEND = "src/main/java/dev/ipf/whitenoise/android/state/DraftSend.kt"
        const val DECLARATION_WINDOW = 64

        /** MDK calls that carry a reply target, and the host hops that hand one down to them. */
        val REPLY_CALLS =
            listOf(
                "createTaggedPoll",
                "prepareQueuedMediaReply",
                "publishComposerTextOnce",
                "publishDurableComposerText",
                "publishTextWithRetry",
                "replyToMessage",
                "replyToMessageWithClientToken",
                "sendComposerText",
                "sendComposerTextWithToken",
                "sendTaggedSticker",
                "sendTextWithCustomEmoji",
                "stageMediaReply",
            )

        /**
         * The App Review demo seeds its own conversation and replies only to messages it has already seen delivered,
         * so its target is always an event id.
         */
        val PINNED_CALLS =
            listOf(
                "createTaggedPoll <- state/PollConversationActions.kt#createPoll",
                "prepareQueuedMediaReply <- state/ConversationController.kt#queueAttachments",
                "publishComposerTextOnce <- state/ConversationControllerSend.kt#publishTextWithRetry",
                "publishDurableComposerText <- state/ConversationControllerSend.kt#publishComposerTextOnce",
                "publishTextWithRetry <- state/ConversationController.kt#retryFailedSend",
                "publishTextWithRetry <- state/ConversationController.kt#send",
                "replyToMessage <- state/DraftSend.kt#sendComposerText",
                "replyToMessageWithClientToken <- state/DurableLocalSends.kt#sendComposerTextWithToken",
                "sendComposerText <- state/DraftSend.kt#sendDetachedDictationText",
                "sendComposerTextWithToken <- state/AppReviewDemoNative.kt#submitMessage",
                "sendComposerTextWithToken <- state/DurableLocalSends.kt#publishDurableComposerText",
                "sendTaggedSticker <- state/StickerConversationActions.kt#sendSticker",
                "sendTextWithCustomEmoji <- state/ConversationControllerSend.kt#publishComposerTextOnce",
                "stageMediaReply <- state/ConversationControllerMedia.kt#prepareQueuedMediaReply",
            )
        val CALL = Regex("""(?<!\w)(${REPLY_CALLS.joinToString("|")})\s*\(""")
        val DECLARATION_TAIL = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w<>?,*. ]+\.)?$""")
        val FUNCTION = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w<>?,*. ]+\.)?(\w+)\s*\(""")
    }
}
