package dev.ipf.whitenoise.android.ui.conversation.messages

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.TimelineRowMessageBubble
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class DeletedMessageLocalRemovalTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val backingPreferences
        get() = context.getSharedPreferences("deleted-message-local-removal-test", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        backingPreferences.edit().clear().commit()
    }

    @Test
    fun physicalLongPressOffersOnlyDeleteForMeAndSuccessfulRemovalHidesTheRow() {
        val surface = render(failCommits = false)

        placeholder().assertIsDisplayed()
        deleteAction().assertDoesNotExist()

        placeholder().performTouchInput { longClick() }

        assertDeleteOnlyMenu()
        deleteAction().performClick()
        composeRule.onNodeWithText(string(R.string.delete_for_me)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.delete_for_everyone)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.delete_for_me)).performClick()
        composeRule.waitUntil(timeoutMillis = ASYNC_TIMEOUT_MILLIS) {
            MESSAGE_ID in surface.hiddenMessageIds()
        }
        composeRule.waitUntil(timeoutMillis = ASYNC_TIMEOUT_MILLIS) {
            runCatching { placeholder().assertDoesNotExist() }.isSuccess
        }
        composeRule.waitForIdle()

        placeholder().assertDoesNotExist()
        assertTrue(surface.projected.deleted)
        assertTrue(surface.projected.messageIdHex in surface.hiddenMessageIds())
        assertEquals(1, surface.preferences.successfulCommits.get())

        val recreatedAppState = appState(backingPreferences)
        assertTrue(MESSAGE_ID in recreatedAppState.hiddenMessageIdsInGroup(ACCOUNT_REF, GROUP_ID))
        assertTrue(recreatedAppState.hiddenMessageIdsInGroup("other-account", GROUP_ID).isEmpty())
        assertTrue(recreatedAppState.hiddenMessageIdsInGroup(ACCOUNT_REF, "other-group").isEmpty())
    }

    @Test
    fun accessibilityLongClickOpensTheSameDeleteOnlySurface() {
        render(failCommits = false)

        composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .performSemanticsAction(SemanticsActions.OnLongClick)

        assertDeleteOnlyMenu()
    }

    @Test
    fun remoteDeletionClosesAnAlreadyOpenMessageInfoSheet() {
        val surface = renderLive()
        liveMessage().performTouchInput { longClick() }
        composeRule.onNodeWithText(string(R.string.message_info), substring = false).performClick()
        composeRule.onNodeWithText(string(R.string.message_info_message_id), substring = false).assertIsDisplayed()

        surface.markDeleted()

        composeRule.waitUntil(timeoutMillis = ASYNC_TIMEOUT_MILLIS) {
            runCatching {
                composeRule
                    .onNodeWithText(string(R.string.message_info_message_id), substring = false)
                    .assertDoesNotExist()
            }.isSuccess
        }
        placeholder().assertIsDisplayed()
    }

    @Test
    fun remoteDeletionClosesAnAlreadyOpenForwardSheet() {
        val surface = renderLive()
        liveMessage().performTouchInput { longClick() }
        composeRule.onNodeWithText(string(R.string.forward), substring = false).performClick()
        composeRule.onNodeWithText(string(R.string.forward_to), substring = false).assertIsDisplayed()

        surface.markDeleted()

        composeRule.waitUntil(timeoutMillis = ASYNC_TIMEOUT_MILLIS) {
            runCatching {
                composeRule.onNodeWithText(string(R.string.forward_to), substring = false).assertDoesNotExist()
            }.isSuccess
        }
        placeholder().assertIsDisplayed()
    }

    @Test
    fun cancelKeepsTheTombstoneVisible() {
        val surface = render(failCommits = false)
        placeholder().performTouchInput { longClick() }
        deleteAction().performClick()

        composeRule.onNodeWithText(string(R.string.cancel)).performClick()

        placeholder().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.delete_for_me)).assertDoesNotExist()
        assertTrue(surface.hiddenMessageIds().isEmpty())
        assertEquals(0, surface.preferences.successfulCommits.get())
    }

    @Test
    fun failedLocalPersistenceKeepsTheTombstoneAndDialogRetryable() {
        val surface = render(failCommits = true)
        placeholder().performTouchInput { longClick() }
        deleteAction().performClick()

        val deleteForMe = composeRule.onNodeWithText(string(R.string.delete_for_me))
        deleteForMe.performClick()
        composeRule.waitUntil(timeoutMillis = ASYNC_TIMEOUT_MILLIS) {
            surface.preferences.commitAttempts.get() >= 2
        }
        composeRule.waitForIdle()

        placeholder().assertIsDisplayed()
        deleteForMe.assertIsDisplayed().assertIsEnabled()
        assertTrue(surface.hiddenMessageIds().isEmpty())
        assertEquals(AppText.Resource(R.string.toast_couldnt_delete_message), surface.appState.toast?.title)
        assertEquals(0, surface.preferences.successfulCommits.get())

        composeRule.runOnIdle { surface.preferences.failCommits = false }
        deleteForMe.performClick()
        composeRule.waitUntil(timeoutMillis = ASYNC_TIMEOUT_MILLIS) {
            MESSAGE_ID in surface.hiddenMessageIds()
        }
        composeRule.waitUntil(timeoutMillis = ASYNC_TIMEOUT_MILLIS) {
            runCatching { placeholder().assertDoesNotExist() }.isSuccess
        }
        composeRule.waitForIdle()

        placeholder().assertDoesNotExist()
        assertTrue(surface.projected.deleted)
        assertEquals(1, surface.preferences.successfulCommits.get())
    }

    @Test
    fun controllerReloadAndRecreationKeepTheTombstoneLocallyHiddenWithoutOpeningMarmot() =
        runBlocking {
            val runtimeOpens = AtomicInteger()
            val page = TimelinePageFfi(messages = listOf(deletedRecord()), hasMoreBefore = true, hasMoreAfter = true)
            val firstState = integrationAppState(ACCOUNT_REF, ACCOUNT_ID, runtimeOpens)
            val firstController = ConversationController(appState = firstState, initialGroup = group())

            firstController.testRefreshCurrentTimeline(ACCOUNT_REF) { page }
            assertEquals(listOf(MESSAGE_ID), firstController.timeline.map { it.record.messageIdHex })

            assertTrue(firstController.hideMessageForMe(MESSAGE_ID))
            assertTrue(firstController.timeline.isEmpty())
            assertTrue(page.messages.single().deleted)

            firstController.testRefreshCurrentTimeline(ACCOUNT_REF) { page }
            assertTrue(firstController.timeline.isEmpty())

            val recreatedState = integrationAppState(ACCOUNT_REF, ACCOUNT_ID, runtimeOpens)
            val recreatedController = ConversationController(appState = recreatedState, initialGroup = group())
            recreatedController.testRefreshCurrentTimeline(ACCOUNT_REF) { page }
            assertTrue(recreatedController.timeline.isEmpty())

            val otherState = integrationAppState(OTHER_ACCOUNT_REF, OTHER_ACCOUNT_ID, runtimeOpens)
            val otherController = ConversationController(appState = otherState, initialGroup = group())
            otherController.testRefreshCurrentTimeline(OTHER_ACCOUNT_REF) { page }
            assertEquals(listOf(MESSAGE_ID), otherController.timeline.map { it.record.messageIdHex })
            assertEquals(0, runtimeOpens.get())
        }

    private fun assertDeleteOnlyMenu() {
        deleteAction().assertIsDisplayed()
        listOf(
            R.string.reply,
            R.string.edit,
            R.string.select,
            R.string.select_text,
            R.string.copy_text,
            R.string.speak_aloud,
            R.string.forward,
            R.string.shared_media_save,
            R.string.message_info,
        ).forEach { label ->
            composeRule.onNodeWithText(string(label), substring = false).assertDoesNotExist()
        }
    }

    private fun placeholder() = composeRule.onNodeWithText(string(R.string.message_deleted), substring = false)

    private fun liveMessage() = composeRule.onNodeWithText(LIVE_BODY, substring = false)

    private fun deleteAction() = composeRule.onNodeWithText(string(R.string.delete), substring = false)

    private fun string(resource: Int): String = app.getString(resource)

    @Suppress("LongMethod")
    private fun render(failCommits: Boolean): TestSurface {
        val preferences = CommitControllablePreferences(backingPreferences, failCommits)
        val appState = appState(preferences)
        val controller = ConversationController(appState = appState, initialGroup = group())
        val projected = deletedRecord()
        val item =
            TimelineMessage(
                id = "msg:$MESSAGE_ID",
                record = TimelineProjector.toAppMessageRecord(projected),
                status = MessageStatus.Received,
                projected = projected,
                timelineOrder = 1uL,
            )
        seedTimeline(controller, item)
        val composerTextState = ComposerTextState(TextFieldValue(""))

        composeRule.setContent {
            var actionMenuOpen by remember { mutableStateOf(false) }
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth()) {
                    controller.timeline.forEach { current ->
                        TimelineRowMessageBubble(
                            messageIdHex = current.record.messageIdHex,
                            item = current,
                            controller = controller,
                            appState = appState,
                            composerTextState = composerTextState,
                            highlighted = false,
                            selectionMode = false,
                            textSelectionMode = false,
                            onTextSelectionModeChange = {},
                            onTextSelectionBoundsChange = {},
                            batchSelectable = true,
                            selected = false,
                            onToggleSelection = {},
                            rangeDragActive = false,
                            onDragSelectionStart = {},
                            onDragSelection = { false },
                            onDragSelectionEnd = {},
                            onDragSelectionCancel = {},
                            quickReactionEmojis = listOf("👍"),
                            recentEmojis = emptyList(),
                            onEmojiUsed = {},
                            isActionMenuOpen = actionMenuOpen,
                            onActionMenuOpenChange = { actionMenuOpen = it },
                            onQuickReactionsSave = {},
                            onQuickReactionsReset = {},
                            onReplyPreviewClick = {},
                            composerGate = ComposerGate.COMPOSER,
                            onBack = {},
                            mentionCandidates = emptyList(),
                            mentionPickerEnabled = false,
                            showSenderName = false,
                            showSenderAvatar = false,
                            collapseLongMessages = false,
                            readOnly = false,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return TestSurface(preferences, projected, appState) {
            appState.hiddenMessageIdsInGroup(ACCOUNT_REF, GROUP_ID)
        }
    }

    @Suppress("LongMethod")
    private fun renderLive(
        body: String = LIVE_BODY,
        collapseLongMessages: Boolean = false,
    ): LiveTestSurface {
        val appState = appState(backingPreferences)
        val controller = ConversationController(appState = appState, initialGroup = group())
        val projected = messageRecord(body = body, deleted = false)
        val item =
            TimelineMessage(
                id = "msg:$MESSAGE_ID",
                record = TimelineProjector.toAppMessageRecord(projected),
                status = MessageStatus.Received,
                projected = projected,
                timelineOrder = 1uL,
            )
        seedTimeline(controller, item)
        val composerTextState = ComposerTextState(TextFieldValue(""))
        lateinit var markDeleted: () -> Unit

        composeRule.setContent {
            var actionMenuOpen by remember { mutableStateOf(false) }
            var current by remember { mutableStateOf(item) }
            markDeleted = {
                val tombstone = messageRecord(body = body, deleted = true)
                current =
                    current.copy(
                        record = TimelineProjector.toAppMessageRecord(tombstone),
                        projected = tombstone,
                    )
            }
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth()) {
                    TimelineRowMessageBubble(
                        messageIdHex = current.record.messageIdHex,
                        item = current,
                        controller = controller,
                        appState = appState,
                        composerTextState = composerTextState,
                        highlighted = false,
                        selectionMode = false,
                        textSelectionMode = false,
                        onTextSelectionModeChange = {},
                        onTextSelectionBoundsChange = {},
                        batchSelectable = true,
                        selected = false,
                        onToggleSelection = {},
                        rangeDragActive = false,
                        onDragSelectionStart = {},
                        onDragSelection = { false },
                        onDragSelectionEnd = {},
                        onDragSelectionCancel = {},
                        quickReactionEmojis = listOf("👍"),
                        recentEmojis = emptyList(),
                        onEmojiUsed = {},
                        isActionMenuOpen = actionMenuOpen,
                        onActionMenuOpenChange = { actionMenuOpen = it },
                        onQuickReactionsSave = {},
                        onQuickReactionsReset = {},
                        onReplyPreviewClick = {},
                        composerGate = ComposerGate.COMPOSER,
                        onBack = {},
                        mentionCandidates = emptyList(),
                        mentionPickerEnabled = false,
                        showSenderName = false,
                        showSenderAvatar = false,
                        collapseLongMessages = collapseLongMessages,
                        readOnly = false,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        return LiveTestSurface {
            composeRule.runOnIdle { markDeleted() }
            composeRule.waitForIdle()
        }
    }

    private fun appState(preferences: SharedPreferences) =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence()),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
            profileReader = { null },
            profileDisplayNameReader = { null },
            profileRefreshRequest = {},
            preferences = preferences,
        )

    private fun integrationAppState(
        accountRef: String,
        accountId: String,
        runtimeOpens: AtomicInteger,
    ) = WhiteNoiseAppState(
        context = context,
        draftStore = DraftStore(EmptyDraftPersistence()),
        accountIdHexResolver = { accountId },
        accounts =
            listOf(
                AccountSummaryFfi(
                    label = accountRef,
                    accountIdHex = accountId,
                    localSigning = true,
                    externalSigning = false,
                    signedOut = false,
                    running = true,
                ),
            ),
        activeAccountRef = accountRef,
        profileReader = { null },
        profileDisplayNameReader = { null },
        profileRefreshRequest = {},
        marmotRuntimeFactory = {
            runtimeOpens.incrementAndGet()
            error("Local tombstone removal must not open the Marmot runtime")
        },
        preferences = backingPreferences,
    )

    private fun seedTimeline(
        controller: ConversationController,
        item: TimelineMessage,
    ) {
        val projected = checkNotNull(item.projected)
        runBlocking {
            controller.testRefreshCurrentTimeline(ACCOUNT_REF) {
                TimelinePageFfi(
                    messages = listOf(projected),
                    hasMoreBefore = false,
                    hasMoreAfter = false,
                )
            }
        }
    }

    private fun deletedRecord() = messageRecord(body = LIVE_BODY, deleted = true)

    private fun messageRecord(
        body: String,
        deleted: Boolean,
    ) = TimelineMessageRecordFfi(
        messageIdHex = MESSAGE_ID,
        sourceMessageIdHex = null,
        direction = "received",
        groupIdHex = GROUP_ID,
        sender = SENDER_ID,
        plaintext = body,
        contentTokens =
            MarkdownDocumentFfi(
                truncated = false,
                blocks = emptyList(),
                blankLinesBefore = byteArrayOf(),
            ),
        kind = 9uL,
        tags = emptyList(),
        timelineAt = 1uL,
        receivedAt = 1uL,
        replyToMessageIdHex = null,
        replyPreview = null,
        mediaJson = null,
        media = emptyList(),
        agentTextStreamJson = null,
        groupSystem = null,
        reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
        deleted = deleted,
        deletedByMessageIdHex = if (deleted) "delete-event" else null,
        invalidationStatus = null,
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
    )

    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Deleted message group",
            description = "",
            admins = listOf(ACCOUNT_ID),
            relays = emptyList(),
            nostrGroupIdHex = "03".repeat(32),
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia =
                AppGroupEncryptedMediaComponentFfi(
                    componentId = 0x8008u,
                    component = "marmot.group.encrypted-media.v1",
                    required = true,
                    version = EncryptedMediaVersionFfi.V1,
                    mediaFormat = "encrypted-media-v1",
                    allowedLocatorKinds = listOf("blossom-v1"),
                    defaultBlobEndpoints =
                        listOf(
                            AppBlobEndpointFfi(
                                locatorKind = "blossom-v1",
                                baseUrl = "https://blossom.example",
                            ),
                        ),
                ),
            disappearingMessageSecs = 0uL,
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            selfMembership = SelfMembershipFfi.MEMBER,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbandRequest = null,
            disbanded = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
        )

    private data class TestSurface(
        val preferences: CommitControllablePreferences,
        val projected: TimelineMessageRecordFfi,
        val appState: WhiteNoiseAppState,
        val hiddenMessageIds: () -> Set<String>,
    )

    private data class LiveTestSurface(
        val markDeleted: () -> Unit,
    )

    private class CommitControllablePreferences(
        private val delegate: SharedPreferences,
        @Volatile var failCommits: Boolean,
    ) : SharedPreferences by delegate {
        val commitAttempts = AtomicInteger()
        val successfulCommits = AtomicInteger()

        override fun edit(): SharedPreferences.Editor =
            CommitControllableEditor(
                delegate = delegate.edit(),
                shouldFail = { failCommits },
                onCommitAttempt = { commitAttempts.incrementAndGet() },
                onSuccessfulCommit = { successfulCommits.incrementAndGet() },
            )
    }

    private class CommitControllableEditor(
        private val delegate: SharedPreferences.Editor,
        private val shouldFail: () -> Boolean,
        private val onCommitAttempt: () -> Unit,
        private val onSuccessfulCommit: () -> Unit,
    ) : SharedPreferences.Editor by delegate {
        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor {
            delegate.putStringSet(key, values)
            return this
        }

        override fun commit(): Boolean {
            val reportFailure = shouldFail()
            val persisted = delegate.commit()
            onCommitAttempt()
            val succeeded = persisted && !reportFailure
            if (succeeded) onSuccessfulCommit()
            return succeeded
        }
    }

    private class EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "personal"
        val ACCOUNT_ID = "01" + "00".repeat(31)
        const val OTHER_ACCOUNT_REF = "work"
        val OTHER_ACCOUNT_ID = "07" + "00".repeat(31)
        val SENDER_ID = "02" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
        val MESSAGE_ID = "05" + "00".repeat(31)
        const val LIVE_BODY = "secret body retained in protocol storage"
        const val ASYNC_TIMEOUT_MILLIS = 20_000L
    }
}
