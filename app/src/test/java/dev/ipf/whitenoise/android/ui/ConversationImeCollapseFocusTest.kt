package dev.ipf.whitenoise.android.ui

import android.content.Context
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsProperties.EditableText
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.ErrorPresentation
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.lifecycleOwner
import dev.ipf.whitenoise.android.ui.conversation.CONVERSATION_INITIAL_LOADING_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.CONVERSATION_TIMELINE_TAIL_GAP
import dev.ipf.whitenoise.android.ui.conversation.ConversationInitialLoadingOverlay
import dev.ipf.whitenoise.android.ui.conversation.ConversationScreen
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleRowTestTag
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * Screen-level coverage for the rule that an IME closure is not a composer
 * dismissal signal, asserted against a real [ConversationScreen] composition
 * rather than against its source text.
 *
 * The IME insets are driven through [ViewCompat.dispatchApplyWindowInsets],
 * which is what backs both `WindowInsets.ime` and `WindowInsets.imeAnimationTarget`,
 * so a listener keyed on either one sees this transition. What Robolectric
 * cannot reproduce is the platform's real ordering between an IME animation, a
 * keyboard-to-voice handoff and a Back dispatch — confirm that on a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationImeCollapseFocusTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun residualConversationLoadingIndicatorHonorsItsGracePeriod() {
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.setContent {
                WhiteNoiseTheme {
                    ConversationInitialLoadingOverlay(visible = true)
                }
            }

            composeRule.onNodeWithTag(CONVERSATION_INITIAL_LOADING_TEST_TAG).assertDoesNotExist()
            composeRule.mainClock.advanceTimeBy(149L)
            composeRule.onNodeWithTag(CONVERSATION_INITIAL_LOADING_TEST_TAG).assertDoesNotExist()
            composeRule.mainClock.advanceTimeBy(2L)
            composeRule.onNodeWithTag(CONVERSATION_INITIAL_LOADING_TEST_TAG).assertExists()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun anchoredTimelineDoesNotFlashLoadingDuringTheRouteTransition() {
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.setContent {
                WhiteNoiseTheme {
                    ConversationInitialLoadingOverlay(
                        visible = true,
                        graceMillis = 300L,
                    )
                }
            }

            composeRule.mainClock.advanceTimeBy(299L)
            composeRule.onNodeWithTag(CONVERSATION_INITIAL_LOADING_TEST_TAG).assertDoesNotExist()
            composeRule.mainClock.advanceTimeBy(2L)
            composeRule.onNodeWithTag(CONVERSATION_INITIAL_LOADING_TEST_TAG).assertExists()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    /** Captures the cached first frame with the #415 single-tail-gap contract. */
    @Test
    fun cachedConversationPaintsMessageWithoutProgressOnFirstFrameLight() {
        captureSeededConversationFirstFrame(
            snapshotName = "conversation_cached_first_frame_light",
            darkTheme = false,
        )
    }

    /** Repeats the cached #415 contract under large-font RTL rendering. */
    @Test
    fun cachedConversationPaintsMessageWithoutProgressOnFirstFrameDarkLargeFontRtl() {
        captureSeededConversationFirstFrame(
            snapshotName = "conversation_cached_first_frame_dark_large_font_rtl",
            darkTheme = true,
            fontScale = 1.5f,
            layoutDirection = LayoutDirection.Rtl,
        )
    }

    /**
     * A keyboard-to-voice handoff collapses the IME insets to zero while the
     * user is still composing. The composer has to keep focus and its draft
     * across that edge, while an explicit Back still dismisses it — the second
     * half is what stops "never clear focus" from passing as a fix.
     */
    @Test
    fun imeCollapseKeepsTheComposerFocusedButExplicitBackStillDismissesIt() {
        val view = showConversation()
        val composer = composeRule.onNode(hasSetTextAction())

        composer.performClick()
        composeRule.waitForIdle()
        composer.performTextInput(DRAFT)
        composeRule.waitForIdle()
        composer.assertIsFocused()

        dispatchImeBottom(view, 300)
        composer.assertIsFocused()

        dispatchImeBottom(view, 0)

        val draftAfterCollapse =
            composer
                .fetchSemanticsNode()
                .config
                .getOrNull(EditableText)
                ?.text
        composer.assertIsFocused()
        assertEquals(DRAFT, draftAfterCollapse)

        composeRule.runOnUiThread {
            (view.context.lifecycleOwner() as ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        val draftAfterBack =
            composer
                .fetchSemanticsNode()
                .config
                .getOrNull(EditableText)
                ?.text
        composer.assertIsNotFocused()
        assertEquals("dismissing the composer must not discard the draft", DRAFT, draftAfterBack)
    }

    @Test
    fun explicitBackWaitsForZeroImeInsetBeforeClearingFocusAndReclaimsTheGap() {
        var navigationCount = 0
        val view = showConversation { navigationCount++ }
        val composer = composeRule.onNode(hasSetTextAction())

        composer.performClick()
        composer.performTextInput(DRAFT)
        dispatchImeBottom(view, 300)
        val openImeComposerBottom = composer.fetchSemanticsNode().boundsInRoot.bottom

        composeRule.runOnUiThread {
            (view.context.lifecycleOwner() as ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        composer.assertIsFocused()
        assertEquals("the first Back must not leave the conversation", 0, navigationCount)

        dispatchImeBottom(view, 0)

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composer.fetchSemanticsNode().boundsInRoot.bottom > openImeComposerBottom
        }

        val closedImeComposerBottom = composer.fetchSemanticsNode().boundsInRoot.bottom
        val draftAfterDismissal =
            composer
                .fetchSemanticsNode()
                .config
                .getOrNull(EditableText)
                ?.text
        composer.assertIsNotFocused()
        assertTrue(
            "the composer must move into the released IME area",
            closedImeComposerBottom > openImeComposerBottom,
        )
        assertEquals(DRAFT, draftAfterDismissal)
        assertEquals(0, navigationCount)
        composeRule
            .onRoot()
            .captureRoboImage("src/test/snapshots/ime_back_dismissed_composer_light.png")
    }

    /** Keeps one production-screen tail interval before, during, and after IME resize. */
    @Test
    fun seededConversationKeepsSingleTailGapAcrossImeOpenAndClose() =
        withUtcTimeZone {
            val fixture = showSeededConversation()
            val composer = composeRule.onNode(hasSetTextAction())
            try {
                assertSingleTailGap(CACHED_MESSAGE_ID)
                composeRule.onRoot().captureRoboImage("src/test/snapshots/conversation_tail_gap_before_ime_light.png")

                composer.performClick()
                dispatchImeBottom(fixture.view, 300)
                assertSingleTailGap(CACHED_MESSAGE_ID)
                composeRule.onRoot().captureRoboImage("src/test/snapshots/conversation_tail_gap_ime_open_light.png")

                dispatchImeBottom(fixture.view, 0)
                assertSingleTailGap(CACHED_MESSAGE_ID)
                composeRule.onRoot().captureRoboImage("src/test/snapshots/conversation_tail_gap_after_ime_light.png")
            } finally {
                fixture.controller.onCleared()
            }
        }

    /** Seeds both leading structural rows and still aligns the real final message. */
    @Test
    fun seededConversationWithOlderHeaderAndTopErrorKeepsSingleTailGap() =
        withUtcTimeZone {
            val fixture =
                showSeededConversation(
                    hasOlderHeader = true,
                    topError =
                        ErrorPresentation(
                            message = AppText.Plain(TOP_ERROR_TEXT),
                            report = "operation=TEST",
                        ),
                )
            try {
                composeRule.onNodeWithText(TOP_ERROR_TEXT).assertIsDisplayed()
                composeRule
                    .onNodeWithTag(messageBubbleRowTestTag(CACHED_MESSAGE_ID), useUnmergedTree = true)
                    .assertIsDisplayed()
                assertSingleTailGap(CACHED_MESSAGE_ID)
                composeRule
                    .onRoot()
                    .captureRoboImage("src/test/snapshots/conversation_tail_gap_seeded_older_top_error_light.png")
            } finally {
                fixture.controller.onCleared()
            }
        }

    private fun showConversation(onBack: () -> Unit = {}): View {
        val appState = appState()
        val group = group()
        val controller = ConversationController(appState = appState, initialGroup = group)
        val chat =
            ChatListItem(
                group = group,
                latest = null,
                otherMemberAccount = null,
                memberCount = 2,
                memberSnapshot = null,
            )
        lateinit var view: View
        composeRule.setContent {
            view = LocalView.current
            WhiteNoiseTheme {
                ConversationScreen(
                    appState = appState,
                    chat = chat,
                    controller = controller,
                    onBack = onBack,
                )
            }
        }
        composeRule.waitForIdle()
        return view
    }

    /** Opens a cached production conversation with optional leading structural rows. */
    private fun showSeededConversation(
        hasOlderHeader: Boolean = false,
        topError: ErrorPresentation? = null,
    ): SeededConversationFixture {
        val appState = appState()
        val group = group()
        val preview = cachedPreview()
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group,
                initialTimelinePreview = preview,
            )
        controller.markAuthoritativeTimelinePublishedForTest()
        replaceControllerState(controller, "hasMoreBefore\$delegate", hasOlderHeader)
        replaceControllerState(controller, "subscriptionError\$delegate", topError)
        val chat =
            ChatListItem(
                group = group,
                latest = null,
                otherMemberAccount = null,
                memberCount = 2,
                memberSnapshot = null,
                projection = cachedProjection(preview),
            )
        lateinit var view: View
        composeRule.setContent {
            view = LocalView.current
            WhiteNoiseTheme {
                ConversationScreen(
                    appState = appState,
                    chat = chat,
                    controller = controller,
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(CACHED_MESSAGE).assertIsDisplayed()
        return SeededConversationFixture(view, controller)
    }

    private fun captureSeededConversationFirstFrame(
        snapshotName: String,
        darkTheme: Boolean,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        val appState = appState()
        val group = group()
        val preview = cachedPreview()
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group,
                initialTimelinePreview = preview,
            )
        // Model the promoted chat-list-tap open: navigation only promotes after
        // the authoritative page publishes, and the tapped row always carries
        // the projection its preview came from. A projection-less open is the
        // provisional direct route, which deliberately hides until anchored.
        controller.markAuthoritativeTimelinePublishedForTest()
        val chat =
            ChatListItem(
                group = group,
                latest = null,
                otherMemberAccount = null,
                memberCount = 2,
                memberSnapshot = null,
                projection = cachedProjection(preview),
            )

        // The bubble timestamp renders in the default zone — pin it so the
        // snapshot matches regardless of the recording machine's locale.
        val originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.setContent {
                val density = LocalDensity.current
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, fontScale),
                        LocalLayoutDirection provides layoutDirection,
                    ) {
                        ConversationScreen(
                            appState = appState,
                            chat = chat,
                            controller = controller,
                            onBack = {},
                        )
                    }
                }
            }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()

            composeRule.onNodeWithText(CACHED_MESSAGE).assertIsDisplayed()
            progressNodes().assertCountEquals(0)
            assertSingleTailGap(CACHED_MESSAGE_ID)
            composeRule
                .onRoot()
                .captureRoboImage("src/test/snapshots/$snapshotName.png")

            // Keep the cached transcript spinner-free after the loading grace,
            // not only during the first frame where the grace hides it anyway.
            composeRule.mainClock.advanceTimeBy(500L)
            composeRule.waitForIdle()
            progressNodes().assertCountEquals(0)
            composeRule.onNodeWithText(CACHED_MESSAGE).assertIsDisplayed()
        } finally {
            composeRule.mainClock.autoAdvance = true
            TimeZone.setDefault(originalTimeZone)
            controller.onCleared()
        }
    }

    private fun progressNodes() =
        composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        )

    /** Pins timestamp rendering to UTC for deterministic layout and screenshot assertions. */
    private inline fun <T> withUtcTimeZone(block: () -> T): T {
        val originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        return try {
            block()
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    /** Asserts that the real cached row, rather than a sentinel, owns the tail. */
    private fun assertSingleTailGap(messageId: String) {
        val transcriptBottom =
            composeRule
                .onNodeWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE)
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        val tailBottom =
            composeRule
                .onNodeWithTag(messageBubbleRowTestTag(messageId), useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        assertEquals(
            with(composeRule.density) { CONVERSATION_TIMELINE_TAIL_GAP.toPx() },
            transcriptBottom - tailBottom,
            1f,
        )
    }

    private fun cachedPreview() =
        ChatListMessagePreviewFfi(
            messageIdHex = CACHED_MESSAGE_ID,
            sender = "02" + "00".repeat(31),
            senderDisplayName = "Cached sender",
            plaintext = CACHED_MESSAGE,
            contentTokens =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = ByteArray(0),
                ),
            kind = 9uL,
            timelineAt = 10uL,
            deleted = false,
            attachmentKind = null,
            attachmentCount = 0u,
            deliveryState = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
        )

    private fun cachedProjection(preview: ChatListMessagePreviewFfi) =
        ChatListRowFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            unreadMentionCount = 0uL,
            unreadMention = false,
            groupIdHex = GROUP_ID,
            archived = false,
            pendingConfirmation = false,
            title = "Handoff group",
            groupName = "Handoff group",
            avatarUrl = null,
            avatar = null,
            lastMessage = preview,
            unreadCount = 0uL,
            hasUnread = false,
            firstUnreadMessageIdHex = null,
            lastReadMessageIdHex = preview.messageIdHex,
            lastReadTimelineAt = preview.timelineAt,
            conversationCreatedAt = 0uL,
            activitySortAt = preview.timelineAt,
            updatedAt = preview.timelineAt,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            manuallyMarkedUnread = false,
            conversationKind = ChatConversationKindFfi.UNKNOWN,
            muted = false,
            mutedUntilMs = null,
            pinned = false,
            pinnedPosition = null,
            lifecycleState = GroupLifecycleStateFfi.STABLE,
            disbanding = false,
            disbandRequest = null,
        )

    private fun dispatchImeBottom(
        view: View,
        bottomPx: Int,
    ) {
        composeRule.runOnUiThread {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottomPx))
                    .setVisible(WindowInsetsCompat.Type.ime(), bottomPx > 0)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(view.rootView, insets)
        }
        composeRule.waitForIdle()
    }

    /** Sets a private Compose state delegate needed to reproduce seeded header/error structure. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> replaceControllerState(
        controller: ConversationController,
        fieldName: String,
        value: T,
    ) {
        val field = ConversationController::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        (field.get(controller) as MutableState<T>).value = value
    }

    private fun appState() =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence()),
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

    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Handoff group",
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

    private data class SeededConversationFixture(
        val view: View,
        val controller: ConversationController,
    )

    private companion object {
        const val ACCOUNT_REF = "personal"
        const val DRAFT = "draft text"
        const val CACHED_MESSAGE = "Cached hello on the first frame"
        const val TOP_ERROR_TEXT = "Offline — showing messages on this device"
        val CACHED_MESSAGE_ID = "05" + "00".repeat(31)
        val ACCOUNT_ID = "01" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
    }
}
