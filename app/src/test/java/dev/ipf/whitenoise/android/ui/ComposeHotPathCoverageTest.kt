package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
import dev.ipf.whitenoise.android.motionOwnerSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ComposeHotPathCoverageTest {
    /** Reply preview projection and mention resolution stay remembered and avoid unguarded profile reads. */
    @Test
    fun composerReplyProjectionAndMentionResolverAreRemembered() {
        val source = source("conversation/composer/ComposerBar.kt").motionOwnerSource()
        val replyMarker = "if (replyingTo != null) {"
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

    /** Emoji search runs outside composition and off the main thread. */
    @Test
    fun emojiSearchRunsOutsideCompositionAndOffTheMainThread() {
        val source = source("conversation/composer/EmojiPickerContent.kt").motionOwnerSource()

        assertTrue(
            "emoji search must be produced asynchronously",
            "LaunchedEffect(query, entries)" in source,
        )
        assertTrue(
            "emoji filtering must run on the Default dispatcher",
            "withContext(Dispatchers.Default) { emojiSearchSections(EmojiData.search(entries, query)) }" in source,
        )
        assertFalse(
            "emoji filtering must not run synchronously from remember during composition",
            Regex("""remember\([^)]*\)\s*\{\s*(emojiSearchSections\()?EmojiData\.search""").containsMatchIn(source),
        )
        assertTrue(
            "results from a superseded query must not present an empty state",
            "model.searchedQuery == query" in source,
        )
    }

    /** Playback ticks stay row-scoped, while native confirmation preserves the optimistic row identity. */
    @Test
    fun conversationScreenDoesNotSubscribeToTtsPlaybackState() {
        val screenSource = source("conversation/ConversationScreen.kt").motionOwnerSource()
        val timelineRowSource = source("conversation/TimelineRow.kt").motionOwnerSource()
        val timelineRowTtsSource = source("conversation/TimelineRowTtsHighlight.kt").motionOwnerSource()

        assertFalse(
            "ConversationScreen must not collect TTS playback state",
            Regex("""ttsController\.state\s*\.?\s*collectAsState(WithLifecycle)?\s*\(""")
                .containsMatchIn(screenSource),
        )
        assertTrue(
            "TimelineRow must delegate bubble rendering to a row-scoped restart scope",
            "TimelineRowMessageBubble(" in timelineRowSource,
        )
        val bubbleScopeKey =
            "key(controller.boundAccountRef, controller.group.groupIdHex, item.presentationId)"
        assertTrue(
            "TimelineRow must key the restart scope by account, group and stable presentation identity",
            bubbleScopeKey in timelineRowSource,
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

    /** Chat list scroll and row search work are isolated from screen composition. */
    @Test
    fun chatListScrollAndRowSearchWorkAreIsolatedFromScreenComposition() {
        val source = source("chats/ChatsScreen.kt").motionOwnerSource()
        val searchProjectionRemember =
            "remember\\(\\s*scopedSourceList,\\s*normalizedSearchQuery,\\s*effectiveFolderChatIds,\\s*" +
                "groupTitleCopy,\\s*profileRev,\\s*messageSearchConstraints,?\\s*\\)"

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
            "val normalizedSearchQuery = remember(trimmedQuery) { localeInvariantFold(trimmedQuery) }" in source,
        )
        assertTrue(
            "title and metadata classification must be projected once per result dataset",
            Regex(searchProjectionRemember).containsMatchIn(source) &&
                "projectChatListSearchSections(" in source,
        )
    }

    /** Draft and chat-list scroll reads stay out of ConversationScreen and ChatsScreen bodies. */
    @Test
    fun conversationDraftAndChatsScrollReadsStayOutOfScreenBodies() {
        val conversationBody =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationScreen.kt")
                .functionBody("ConversationScreen")
        val draftRestoreLogic =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/ui/conversation/ComposerDraftRestoreLogic.kt")
        val restoredDraftHelper = draftRestoreLogic.functionBody("rememberRestoredDraftSnapshot")
        val entryRevisionHelper = draftRestoreLogic.functionBody("rememberUserTextEditRevisionOnEntry")
        assertFalse(
            "ConversationScreen must not read DraftStore directly during composition",
            "appState.draftSnapshotFor(" in conversationBody,
        )
        assertTrue(
            "ConversationScreen must read restored drafts through rememberRestoredDraftSnapshot",
            "rememberRestoredDraftSnapshot(" in conversationBody,
        )
        assertTrue(
            "ConversationScreen must capture entry edit revision without observation",
            "rememberUserTextEditRevisionOnEntry(" in conversationBody,
        )
        assertTrue(
            "Restored draft snapshot helper must avoid snapshot observation on DraftStore",
            "Snapshot.withoutReadObservation" in restoredDraftHelper,
        )
        assertTrue(
            "Entry edit revision helper must avoid snapshot observation on the composer text state",
            "Snapshot.withoutReadObservation" in entryRevisionHelper,
        )

        val chatsBody =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt")
                .functionBody("ChatsScreen")
        val anchorCallStart = chatsBody.indexOf("rememberSearchBodyAnchorRestoration(")
        assertTrue("ChatsScreen must call rememberSearchBodyAnchorRestoration", anchorCallStart >= 0)
        val anchorCall =
            chatsBody.kotlinBlockFrom(
                openDelimiter = chatsBody.indexOf('(', anchorCallStart),
                description = "rememberSearchBodyAnchorRestoration call",
                opening = '(',
                closing = ')',
            )
        assertTrue(
            "ChatsScreen must pass listState = chatListState to rememberSearchBodyAnchorRestoration",
            Regex("""listState\s*=\s*chatListState\b""").containsMatchIn(anchorCall),
        )
        val atTopStart = chatsBody.indexOf("atTop = {")
        require(atTopStart >= 0) { "Missing atTop callback in ChatsScreen" }
        val atTopCallback =
            chatsBody.kotlinBlockFrom(chatsBody.indexOf('{', atTopStart), "ChatsScreen unread-pull atTop callback")
        assertFalse(
            "ChatsScreen must not read chatListState.firstVisibleItemScrollOffset outside callbacks",
            "chatListState.firstVisibleItemScrollOffset" in chatsBody.replace(atTopCallback, ""),
        )
        val anchorRestorationBody =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/ui/chats/GlobalSearchAnchorRestoration.kt")
                .functionBody("rememberSearchBodyAnchorRestoration")
        assertTrue(
            "rememberSearchBodyAnchorRestoration must snapshot LazyListState without observation",
            "Snapshot.withoutReadObservation" in anchorRestorationBody,
        )
    }

    /** IME and header overlap threshold reads collapse through derivedStateOf. */
    @Test
    fun imeAndHeaderOverlapReadsCollapseThroughDerivedStateOf() {
        val appBody =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/ui/WhiteNoiseApp.kt")
                .functionBody("WhiteNoiseApp")
        val imeHelper =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/ui/WhiteNoiseAppDictationIme.kt")
                .functionBody("rememberDictationImeVisible")
        assertFalse(
            "WhiteNoiseApp must not read WindowInsets.ime.getBottom directly in its root body",
            "WindowInsets.ime.getBottom(" in appBody,
        )
        assertTrue(
            "WhiteNoiseApp must collapse dictation IME visibility via rememberDictationImeVisible",
            "rememberDictationImeVisible()" in appBody && "derivedStateOf" in imeHelper,
        )

        val folderPillsBody =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatFolderPills.kt")
                .functionBody("ChatFolderPills")
        val profileActionBody =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/ui/profile/PersonProfileContent.kt")
                .functionBody("PersonProfileBottomAction")
        assertTrue(
            "ChatFolderPills must collapse overlappedFraction threshold reads with derivedStateOf",
            Regex("""derivedStateOf\s*\{[^}]*overlappedFraction""").containsMatchIn(folderPillsBody),
        )
        assertTrue(
            "PersonProfileBottomAction must collapse overlappedFraction threshold reads with derivedStateOf",
            Regex("""derivedStateOf\s*\{[^}]*overlappedFraction""").containsMatchIn(profileActionBody),
        )
    }

    /** The message-menu keyboard hand-back reaches ConversationScreen as a reader that only layout calls. */
    @Test
    fun menuImeHandBackIsReadInLayoutNotConversationComposition() {
        val conversation = "src/main/java/dev/ipf/whitenoise/android/ui/conversation"
        val readers =
            listOf(
                "$conversation/MenuImeBackdrop.kt" to "animatedMenuImeBackdrop",
                "$conversation/ConversationMenuImeHandoff.kt" to "conversationMenuImeBackdrop",
            )
        for ((path, function) in readers) {
            assertTrue(
                "$function must return a reader: returning the animated value recomposes its caller per frame",
                Regex("""^\s*:\s*\(\(\)\s*->\s*Int\)\?\s*\{""").containsMatchIn(
                    appModuleSource(path).afterParameters(function),
                ),
            )
        }
        val conversationBody = appModuleSource("$conversation/ConversationScreen.kt").functionBody("ConversationScreen")
        assertTrue(
            "ConversationScreen must pad the transcript through heldImeBottomPadding",
            "heldImeBottomPadding(presentedImeBottom)" in conversationBody,
        )
    }

    /** Returns the source text that follows the parameter list of [function]. */
    private fun String.afterParameters(function: String): String {
        val start =
            Regex("""\bfun\s+${Regex.escape(function)}\s*\(""").find(this)?.range?.last
                ?: error("Missing function $function")
        val parameters = kotlinBlockFrom(start, "parameters for $function", opening = '(', closing = ')')
        return substring(start + parameters.length)
    }

    /** Resolves a main UI source file relative to `ui/`. */
    private fun source(relativePath: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/$relativePath"),
        ).firstOrNull { it.exists() }
            ?: error("Missing source file: $relativePath")
}
