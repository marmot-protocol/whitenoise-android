package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.audio.tts.TtsVisibleTextSpan
import dev.ipf.whitenoise.android.audio.tts.projectTtsSpeakableEntry
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageBubbleTtsProjectionWiringTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun staleProjectionIdSuppressesReadAloudProgressSemantics() {
        val progress = progress()
        val gateInput = gateInput()
        val state =
            resolveMessageBubbleTtsProjectionState(
                gateInput = gateInput,
                projectionId = "current-projection",
                progress = progress,
            )

        assertNull(state.effectivePassage)
        assertNull(state.effectiveProgress)

        composeRule.setContent {
            WhiteNoiseTheme {
                readAloudMessageSemantics(progress = state.effectiveProgress) {
                    Surface(Modifier.testTag("message-body")) {}
                }
            }
        }

        composeRule.onNodeWithTag("tts-read-aloud-progress").assertDoesNotExist()
    }

    @Test
    fun matchingProjectionIdKeepsReadAloudProgressSemantics() {
        val progress = progress()
        val gateInput = gateInput()
        val state =
            resolveMessageBubbleTtsProjectionState(
                gateInput = gateInput,
                projectionId = MATCHING_PROJECTION_ID,
                progress = progress,
            )

        assertEquals(passage(), state.effectivePassage)
        assertEquals(progress, state.effectiveProgress)

        composeRule.setContent {
            WhiteNoiseTheme {
                readAloudMessageSemantics(progress = state.effectiveProgress) {
                    Surface(Modifier.testTag("message-body")) {}
                }
            }
        }

        composeRule
            .onNodeWithTag("tts-read-aloud-progress")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf(
                        app.getString(
                            R.string.tts_bar_progress,
                            2,
                            3,
                            1,
                            2,
                        ),
                    ),
                ),
            )
    }

    @Test
    fun deletedCandidateGateSuppressesReadAloudProgressSemantics() {
        val progress = progress()
        val gateInput =
            gateInput(
                deleted = true,
                speakableIdentity = null,
            )
        val state =
            resolveMessageBubbleTtsProjectionState(
                gateInput = gateInput,
                projectionId = MATCHING_PROJECTION_ID,
                progress = progress,
            )

        assertFalse(state.candidate)
        assertNull(state.effectivePassage)
        assertNull(state.effectiveProgress)

        composeRule.setContent {
            WhiteNoiseTheme {
                readAloudMessageSemantics(progress = state.effectiveProgress) {
                    Surface(Modifier.testTag("message-body")) {}
                }
            }
        }

        composeRule.onNodeWithTag("tts-read-aloud-progress").assertDoesNotExist()
    }

    @Test
    @Suppress("LongMethod")
    fun activeEditedMarkdownBubbleResolvesHighlightFromSharedProjection() =
        runBlocking {
            val editedDocument = editedDocument("value")
            val record = editedRecord()
            val editedText = "Edited *value*"
            val ttsEntry =
                projectTtsSpeakableEntry(
                    message = record,
                    editedText = editedText,
                    senderDisplayName = "Alice",
                    parseMarkdown = { editedDocument },
                )!!
            val projection =
                messageSpeakableProjection(
                    bodyText = editedText,
                    document = editedDocument,
                    mentionDisplayName = null,
                    isGroupMember = null,
                )!!
            val passage =
                TtsPassage(
                    messageIdHex = MESSAGE_ID,
                    sentenceIndex = 0,
                    projectionId = ttsEntry.projectionId,
                    visibleWord = listOf(TtsVisibleTextSpan("b0/n1/n0", 0, 5)),
                )
            val gateInput =
                MessageBubbleTtsGateInput(
                    messageIdHex = MESSAGE_ID,
                    ttsHighlightPassage = passage,
                    textSelectionMode = false,
                    deleted = false,
                    persistedFailure = false,
                    speakableIdentity =
                        MessageBubbleTtsSpeakableIdentity(
                            bodyText = editedText,
                        ),
                )
            val state =
                resolveMessageBubbleTtsProjectionState(
                    gateInput = gateInput,
                    projectionId = projection.projectionId,
                    progress = null,
                )
            val appState = appState()
            val controller = ConversationController(appState = appState, initialGroup = group())
            val item =
                TimelineMessage(
                    id = "msg:$MESSAGE_ID",
                    record = record,
                    status = MessageStatus.Received,
                )

            composeRule.setContent {
                WhiteNoiseTheme {
                    ActiveEditedMarkdownBubbleBodyHarness(
                        item = item,
                        record = record,
                        controller = controller,
                        appState = appState,
                        bodyText = editedText,
                        bodyMarkdownDocument = editedDocument,
                        effectivePassage = state.effectivePassage!!,
                        projection = projection,
                    )
                }
            }

            composeRule.waitForIdle()
            composeRule.onNodeWithText("Edited value").assertExists()
            val semantics =
                composeRule
                    .onNodeWithText("Edited value")
                    .fetchSemanticsNode()
                    .config
            assertEquals(7 until 12, semantics.getOrNull(TtsReadAloudHighlightRangeKey))
        }

    private fun gateInput(
        deleted: Boolean = false,
        speakableIdentity: MessageBubbleTtsSpeakableIdentity? =
            MessageBubbleTtsSpeakableIdentity(
                bodyText = "Hello world",
            ),
    ) = MessageBubbleTtsGateInput(
        messageIdHex = MESSAGE_ID,
        ttsHighlightPassage = passage(),
        textSelectionMode = false,
        deleted = deleted,
        persistedFailure = false,
        speakableIdentity = speakableIdentity,
    )

    private fun passage() =
        TtsPassage(
            messageIdHex = MESSAGE_ID,
            sentenceIndex = 0,
            projectionId = MATCHING_PROJECTION_ID,
        )

    private fun progress() =
        TtsReadAloudProgress(
            sentenceIndex = 1,
            sentenceCount = 3,
            messageIndex = 0,
            messageCount = 2,
        )

    private fun editedDocument(word: String) =
        MarkdownDocumentFfi(
            truncated = false,
            blankLinesBefore = byteArrayOf(),
            blocks =
                listOf(
                    MarkdownBlockFfi.Paragraph(
                        inlines =
                            listOf(
                                MarkdownInlineFfi.Text("Edited "),
                                MarkdownInlineFfi.Emph(listOf(MarkdownInlineFfi.Text(word))),
                            ),
                    ),
                ),
        )

    private fun editedRecord() =
        AppMessageRecordFfi(
            messageIdHex = MESSAGE_ID,
            direction = "received",
            groupIdHex = GROUP_ID,
            sender = SENDER_ID,
            plaintext = "Original **value**",
            contentTokens =
                MarkdownDocumentFfi(
                    truncated = false,
                    blankLinesBefore = byteArrayOf(),
                    blocks =
                        listOf(
                            MarkdownBlockFfi.Paragraph(
                                inlines =
                                    listOf(
                                        MarkdownInlineFfi.Text("Original "),
                                        MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("value"))),
                                    ),
                            ),
                        ),
                ),
            kind = 9uL,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1uL,
            receivedAt = 1uL,
        )

    private fun appState() =
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
        )

    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "TTS projection group",
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
        val SENDER_ID = "02" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
        val MESSAGE_ID = "05" + "00".repeat(31)
        const val MATCHING_PROJECTION_ID = "shared-projection"
    }
}

@Composable
private fun ActiveEditedMarkdownBubbleBodyHarness(
    item: TimelineMessage,
    record: AppMessageRecordFfi,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    bodyText: String,
    bodyMarkdownDocument: MarkdownDocumentFfi,
    effectivePassage: TtsPassage,
    projection: dev.ipf.whitenoise.android.ui.SpeakableTextProjection,
) {
    val resolver =
        rememberTtsLeafHighlightResolver(
            passage = effectivePassage,
            messageIdHex = record.messageIdHex,
            projection = projection,
            locale = Locale.US,
        )
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.width(320.dp)) {
        Column {
            BubbleBodyFooterAndRetry(
                item = item,
                record = record,
                controller = controller,
                appState = appState,
                bodyText = bodyText,
                bodyMarkdownDocument = bodyMarkdownDocument,
                deleted = false,
                persistedFailure = false,
                textSelectionMode = false,
                customBubbleColorActive = false,
                selectableTextLayoutReporter = { _, _, _ -> },
                markdownLinkLayoutReporter = { _, _, _, _ -> },
                onCopyMarkdownLink = {},
                plainTextSelectionModifier = Modifier,
                onPlainTextLayout = {},
                ttsLeafHighlightResolver = resolver,
                ttsReadAloudProgress = null,
                selectionWrapper = { content -> content() },
                collapsible = false,
                replyPreviewPresent = false,
                hasMedia = false,
                bubbleBackgroundColor = MaterialTheme.colorScheme.surface,
                bubbleContentColor = MaterialTheme.colorScheme.onSurface,
                timestampColor = MaterialTheme.colorScheme.onSurfaceVariant,
                showStatus = false,
                editedLabel = null,
                onEditedClick = null,
                footerOnVisualMedia = false,
                footerOnPendingVisual = false,
                invalidationWarning = null,
                mine = false,
                onExpand = {},
            )
        }
    }
}
