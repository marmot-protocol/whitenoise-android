package dev.ipf.whitenoise.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.ConversationScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Controlled-frame contract for an accepted plain-text Send: the first
 * rendered frame after the tap already contains the optimistic outgoing
 * bubble, the composer clears, and the composer action never exposes progress
 * semantics — even while the Markdown parse hop and the network publish are
 * both still suspended. Delivery state belongs on the bubble.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationSendFirstFrameTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun firstFrameAfterAcceptedSendContainsTheBubbleWithoutComposerProgress() {
        val releaseParse = CompletableDeferred<MarkdownDocumentFfi>()
        val releasePublish = CompletableDeferred<Unit>()
        val appState = appState()
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group(),
                textPublisher = { _, _, _, _ ->
                    releasePublish.await()
                    sentSummary()
                },
                markdownParser = { releaseParse.await() },
            )
        controller.markAuthoritativeTimelinePublishedForTest()
        composeRule.setContent {
            WhiteNoiseTheme {
                ConversationScreen(
                    appState = appState,
                    chat =
                        ChatListItem(
                            group = group(),
                            latest = null,
                            otherMemberAccount = null,
                            memberCount = 2,
                            memberSnapshot = null,
                        ),
                    controller = controller,
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()
        val composer = composeRule.onNode(hasSetTextAction())
        composer.performClick()
        composer.performTextInput(SENT_TEXT)
        composeRule.waitForIdle()

        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
            composeRule.mainClock.advanceTimeByFrame()

            // First frame: the bubble is in the transcript, the composer is
            // cleared, and no progress indicator exists anywhere — while both
            // the parse hop and the publish are still suspended.
            composeRule.onNodeWithText(SENT_TEXT).assertIsDisplayed()
            assertEquals("", composerText(composer))
            progressNodes().assertCountEquals(0)

            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onNodeWithText(SENT_TEXT).assertIsDisplayed()
            progressNodes().assertCountEquals(0)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }

        // Late work settles onto the bubble, never back into the composer.
        releaseParse.complete(
            MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0)),
        )
        releasePublish.complete(Unit)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(SENT_TEXT).assertIsDisplayed()
        assertEquals("late completion must not resurrect composer state", "", composerText(composer))
        progressNodes().assertCountEquals(0)
    }

    /** The real screen must not treat its first locally persisted Paste as an entry restore. */
    @Test
    @Suppress("LongMethod")
    fun emptyComposerPasteAndSendDoNotOpenImeAfterDraftPersistence() {
        val appState = appState()
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group(),
                textPublisher = { _, _, _, _ -> sentSummary() },
                markdownParser = {
                    MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0))
                },
            )
        val keyboard = RecordingPasteKeyboard()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        controller.markAuthoritativeTimelinePublishedForTest()
        try {
            composeRule.setContent {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                    WhiteNoiseTheme {
                        ConversationScreen(
                            appState = appState,
                            chat =
                                ChatListItem(
                                    group = group(),
                                    latest = null,
                                    otherMemberAccount = null,
                                    memberCount = 2,
                                    memberSnapshot = null,
                                ),
                            controller = controller,
                            onBack = {},
                        )
                    }
                }
            }
            composeRule.waitForIdle()
            val composer = composeRule.onNode(hasSetTextAction())
            composer.assertIsNotFocused()
            assertEquals("entry must not open the keyboard", 0, keyboard.shows)
            // Initial lifecycle resume may clear focus before the Paste action begins.
            val idleKeyboard = keyboard.shows to keyboard.hides
            println("Paste IME settled-entry=$idleKeyboard")
            clipboard.setPrimaryClip(ClipData.newHtmlText("sample", "clipboard message", "<b>clipboard message</b>"))
            composeRule.onNodeWithTag("composer.paste").performClick()
            composeRule.waitForIdle()
            composer.assertIsNotFocused()
            assertEquals("clipboard message", composerText(composer))
            assertEquals("clipboard message", appState.draftSnapshotFor(ACCOUNT_REF, GROUP_ID)?.textFieldValue?.text)
            println("Paste IME after-paste=${keyboard.shows to keyboard.hides}")
            assertEquals(idleKeyboard, keyboard.shows to keyboard.hides)
            composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("clipboard message").assertIsDisplayed()
            assertEquals("", composerText(composer))
            println("Paste IME after-send=${keyboard.shows to keyboard.hides}")
            assertEquals(idleKeyboard, keyboard.shows to keyboard.hides)

            composer.performClick()
            composer.assertIsFocused()
            val before = keyboard.shows to keyboard.hides
            clipboard.setPrimaryClip(ClipData.newPlainText("sample", "typing paste"))
            composeRule.onNodeWithTag("composer.paste").performClick()
            composeRule.waitForIdle()
            composer.assertIsFocused()
            assertEquals("typing paste", composerText(composer))
            println("Paste IME typing-before=$before after=${keyboard.shows to keyboard.hides}")
            assertEquals(before, keyboard.shows to keyboard.hides)
        } finally {
            clipboard.clearPrimaryClip()
            controller.onCleared()
        }
    }

    private class RecordingPasteKeyboard : SoftwareKeyboardController {
        var shows = 0
        var hides = 0

        override fun show() {
            shows++
        }

        override fun hide() {
            hides++
        }
    }

    private fun composerText(composer: androidx.compose.ui.test.SemanticsNodeInteraction): String =
        composer
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()

    private fun progressNodes() =
        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        )

    private fun appState() =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptySendDraftPersistence()),
            accountIdHexResolver = { ACCOUNT_ID },
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

    private fun sentSummary() =
        SendSummaryFfi(
            published = 1u,
            messageIds = listOf("c3".repeat(32)),
            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
        )

    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Send group",
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

    private class EmptySendDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "alice"
        const val SENT_TEXT = "first frame message"
        val ACCOUNT_ID = "a1".repeat(32)
        val GROUP_ID = "b2".repeat(32)
    }
}
