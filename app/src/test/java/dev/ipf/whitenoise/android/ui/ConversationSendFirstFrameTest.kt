package dev.ipf.whitenoise.android.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
