package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
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
import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.audio.tts.TtsVisibleTextSpan
import dev.ipf.whitenoise.android.core.EditState
import dev.ipf.whitenoise.android.core.EditVersion
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class MessageBubbleEditedMarkdownTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editedDisplayMarkdownIsIndependentOfTtsCandidateGate() {
        val source = messageBubbleSource().readText()

        assertFalse(
            "edited display markdown must not be gated on TTS candidate state",
            "effectiveTtsCandidate && editState" in source,
        )
        assertTrue(
            "edited display markdown must use dedicated display resolver",
            "rememberMessageBubbleEditedDisplayMarkdownDocument(" in source,
        )
        assertTrue(
            "active TTS must reuse the display parse instead of parsing an edit twice",
            "if (!ttsSpeakableSource.useStoredContentTokens)" in source &&
                "activeSpeakableDocument = editedMarkdownDocument" in source,
        )
    }

    @Test
    fun editedMarkdownSourceTextRequiresKindNineEdit() {
        val editState = editState("Edited *value*")

        assertEquals(
            "Edited *value*",
            messageBubbleEditedMarkdownSourceText(
                editState = editState,
                record = editedRecord(),
                deleted = false,
                persistedFailure = false,
            ),
        )
        assertNull(
            messageBubbleEditedMarkdownSourceText(
                editState = editState,
                record = editedRecord().copy(kind = 1uL),
                deleted = false,
                persistedFailure = false,
            ),
        )
        assertNull(
            messageBubbleEditedMarkdownSourceText(
                editState = editState,
                record = editedRecord(),
                deleted = true,
                persistedFailure = false,
            ),
        )
        assertNull(
            messageBubbleEditedMarkdownSourceText(
                editState = editState("   "),
                record = editedRecord(),
                deleted = false,
                persistedFailure = false,
            ),
        )
    }

    @Test
    fun editedDisplayMarkdownDocumentStaysAvailableWithoutTts() {
        val document = editedDocument("value")
        val editState = editState("Edited *value*")

        assertEquals(
            document,
            messageBubbleEditedDisplayMarkdownDocument(
                parsedDocument = document,
                editState = editState,
                record = editedRecord(),
            ),
        )
        assertNull(
            messageBubbleEditedDisplayMarkdownDocument(
                parsedDocument = document,
                editState = null,
                record = editedRecord(),
            ),
        )
    }

    @Test
    fun normalEditedMarkdownRendersWithoutActiveTts() {
        renderEditedMarkdownHarness(
            textSelectionMode = false,
            effectivePassage = null,
        )

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Edited value").assertExists()
        capture("message_bubble_edited_markdown_normal_light")
    }

    @Test
    fun editedMarkdownStaysRenderedDuringTextSelection() {
        renderEditedMarkdownHarness(
            textSelectionMode = true,
            effectivePassage = null,
        )

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Edited value").assertExists()
        capture("message_bubble_edited_markdown_text_selection_light")
    }

    @Test
    fun activeEditedMarkdownKeepsMarkdownWhileHighlighting() {
        val editedDocument = editedDocument("value")
        val projection =
            messageSpeakableProjection(
                bodyText = "Edited *value*",
                document = editedDocument,
                mentionDisplayName = null,
                isGroupMember = null,
            )!!
        val passage =
            TtsPassage(
                messageIdHex = MESSAGE_ID,
                sentenceIndex = 0,
                projectionId = projection.projectionId,
                visibleWord = listOf(TtsVisibleTextSpan("b0/n1/n0", 0, 5)),
            )
        val resolver = buildTtsLeafHighlightResolver(passage, MESSAGE_ID, projection, Locale.US)
        assertNotNull("resolver must be built for the active passage", resolver)
        assertNotNull(
            "the rendered leaf must resolve to a highlight range",
            resolver!!("b0/n1/n0", "value"),
        )

        renderEditedMarkdownHarness(
            textSelectionMode = false,
            effectivePassage = passage,
        )

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Edited value").assertExists()
        capture("message_bubble_edited_markdown_active_playback_light")
    }

    private fun capture(name: String) {
        composeRule.onNodeWithTag(EDITED_MARKDOWN_TAG).captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun renderEditedMarkdownHarness(
        textSelectionMode: Boolean,
        effectivePassage: TtsPassage?,
    ) {
        val editedDocument = editedDocument("value")
        val editedText = "Edited *value*"
        val record = editedRecord()
        val projection =
            messageSpeakableProjection(
                bodyText = editedText,
                document = editedDocument,
                mentionDisplayName = null,
                isGroupMember = null,
            )
        assertNotNull(projection)
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
                EditedMarkdownDisplayHarness(
                    item = item,
                    record = record,
                    controller = controller,
                    appState = appState,
                    bodyText = editedText,
                    bodyMarkdownDocument = editedDocument,
                    textSelectionMode = textSelectionMode,
                    effectivePassage =
                        effectivePassage?.copy(
                            projectionId = projection!!.projectionId,
                        ),
                    projection = projection!!,
                )
            }
        }
    }

    private fun editState(text: String) =
        EditState(
            latestText = text,
            count = 1,
            versions =
                listOf(
                    EditVersion(
                        messageIdHex = MESSAGE_ID,
                        text = text,
                        recordedAt = 2uL,
                    ),
                ),
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
            groupIdHex = "group",
            sender = "alice",
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

    private fun messageBubbleSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/MessageBubble.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/messages/MessageBubble.kt"),
        ).first { it.exists() }

    private fun appState() =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
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
            name = "Edited markdown group",
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
        val GROUP_ID = "04" + "00".repeat(31)
        val MESSAGE_ID = "05" + "00".repeat(31)
    }
}

private const val EDITED_MARKDOWN_TAG = "edited-markdown-display"

@Composable
private fun EditedMarkdownDisplayHarness(
    item: TimelineMessage,
    record: AppMessageRecordFfi,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    bodyText: String,
    bodyMarkdownDocument: MarkdownDocumentFfi,
    textSelectionMode: Boolean,
    effectivePassage: TtsPassage?,
    projection: dev.ipf.whitenoise.android.ui.SpeakableTextProjection,
) {
    val resolver =
        rememberTtsLeafHighlightResolver(
            passage = effectivePassage,
            messageIdHex = record.messageIdHex,
            projection = projection,
            locale = Locale.US,
        )
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.width(320.dp).testTag(EDITED_MARKDOWN_TAG),
    ) {
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
                textSelectionMode = textSelectionMode,
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
                bubbleContentColor = MaterialTheme.colorScheme.onSurface,
                timestampColor = MaterialTheme.colorScheme.onSurfaceVariant,
                showStatus = false,
                showRetention = false,
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
