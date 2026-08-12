package dev.ipf.whitenoise.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ComposeHotPathCoverageTest {
    @Test
    fun composerReplyProjectionAndMentionResolverAreRemembered() {
        val source = source("conversation/composer/ComposerBar.kt").readText()
        val replyMarker = "} else if (replyingTo != null) {"
        val replyStart = source.indexOf(replyMarker)
        require(replyStart >= 0) { "Reply branch marker missing" }
        val replyBodyStart = replyStart + replyMarker.length
        val mentionPickerMarker = "// #414: live @-mention picker"
        val mentionPickerStart = source.indexOf(mentionPickerMarker, replyBodyStart)
        require(mentionPickerStart >= 0) { "Mention picker marker missing" }
        val replyBlock = source.substring(replyBodyStart, mentionPickerStart)

        assertTrue(
            "reply mention resolver must be stable until profile presentation changes",
            Regex(
                """remember\(appState,\s*profileRevision\)\s*\{.*?state\.mentionDisplayName\(bech32\).*?}""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(replyBlock),
        )
        assertTrue(
            "reply body projection must be cached by the reply and localized message copy",
            Regex(
                """remember\(replyingTo,\s*messageTextCopy\)\s*\{\s*MessageProjector\.displayBody\(replyingTo,\s*messageTextCopy\)""",
            ).containsMatchIn(replyBlock),
        )
        assertFalse(
            "ReplyPreviewCard must not receive a fresh mention resolver lambda",
            "mentionDisplayName = appState?.let" in source,
        )
        assertFalse(
            "the composer must not subscribe to profile revisions without a reply preview",
            "profileRevisionForCompose" in source.substring(0, replyStart),
        )
    }

    @Test
    fun emojiSearchRunsOutsideCompositionAndOffTheMainThread() {
        val source = source("conversation/composer/EmojiPicker.kt").readText()

        assertTrue(
            "emoji search must be produced asynchronously",
            "initialValue = EmojiSearchSnapshot(query = \"\", results = emptyList())" in source,
        )
        assertTrue(
            "emoji filtering must run on the Default dispatcher",
            "withContext(Dispatchers.Default) { EmojiData.search(browseEmoji, query) }" in source,
        )
        assertFalse(
            "emoji filtering must not run synchronously from remember during composition",
            Regex("""remember\(searchQuery,\s*browseEmoji\)\s*\{\s*EmojiData\.search""")
                .containsMatchIn(source),
        )
        assertTrue(
            "results from a superseded query must not remain selectable",
            "searchSnapshot.results.takeIf { searchSnapshot.query == searchQuery }.orEmpty()" in source,
        )
    }

    @Test
    fun conversationScreenDoesNotSubscribeToTtsPlaybackState() {
        val screenSource = source("conversation/ConversationScreen.kt").readText()
        val timelineRowSource = source("conversation/TimelineRow.kt").readText()
        val timelineRowTtsSource = source("conversation/TimelineRowTtsHighlight.kt").readText()

        assertFalse(
            "ConversationScreen must not collect TTS playback state",
            "ttsController.state.collectAsState()" in screenSource,
        )
        assertTrue(
            "TimelineRow must delegate bubble rendering to a row-scoped restart scope",
            "TimelineRowMessageBubble(" in timelineRowSource,
        )
        assertTrue(
            "TimelineRow must key the row-scoped restart scope by message id",
            "key(item.record.messageIdHex)" in timelineRowSource,
        )
        assertTrue(
            "Row-scoped TTS highlight projection must filter by message id",
            "timelineRowTtsHighlightPassage(" in timelineRowTtsSource,
        )
        assertTrue(
            "Row-scoped TTS subscription must filter playback updates per message",
            "produceState" in timelineRowTtsSource &&
                "distinctUntilChanged()" in timelineRowTtsSource,
        )
    }

    @Test
    fun chatListScrollAndRowSearchWorkAreIsolatedFromScreenComposition() {
        val source = source("chats/ChatsScreen.kt").readText()

        assertTrue(
            "scroll index must be observed from snapshotFlow",
            "snapshotFlow { chatListState.firstVisibleItemIndex }" in source,
        )
        assertFalse(
            "ChatsScreen must not subscribe to the scroll index through derivedStateOf",
            "derivedStateOf { chatListState.firstVisibleItemIndex }" in source,
        )
        assertTrue(
            "the normalized search needle must be remembered once per query",
            "val ciSearchNeedle = remember(trimmedQuery) { trimmedQuery.lowercase(Locale.ROOT) }" in source,
        )
        assertTrue(
            "per-row title and preview classification must be memoized",
            Regex(
                """remember\(\s*item,\s*appState,\s*groupTitleCopy,\s*ciSearchNeedle,\s*profileRev,\s*rawBodyMatch,""",
            ).containsMatchIn(source),
        )
    }

    private fun source(relativePath: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/$relativePath"),
        ).firstOrNull { it.exists() }
            ?: error("Missing source file: $relativePath")
}
