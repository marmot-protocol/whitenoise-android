package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.MarmotWindowTestFakes
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationTopBarSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The speaker is an independent header action, not part of the title/details target. */
    @Test
    fun readAloudShortcutAppearsAtHeaderEndAndToggles() {
        val state = appState()
        state.ttsAutoReadPreferences.setHeaderShortcutVisible(true)
        val controller = ConversationController(state, group())
        var detailsCalls = 0
        render(state, controller, onOpenDetails = { detailsCalls += 1 })

        val speaker = composeRule.onNodeWithContentDescription("Read messages aloud")
        speaker.assertIsOff().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        val header = composeRule.onNodeWithTag(CONVERSATION_TOP_BAR_TAG).fetchSemanticsNode().boundsInRoot
        val bounds = speaker.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= header.center.x)
        assertTrue(bounds.right <= header.right)
        composeRule
            .onNodeWithTag(CONVERSATION_TOP_BAR_TAG)
            .captureRoboImage("src/test/snapshots/conversation_header_speaker_off_light.png")
        speaker.performClick().assertIsOn()
        composeRule.runOnIdle {
            assertTrue(state.conversationAutoReadFor(ACCOUNT_REF, GROUP_ID))
            assertEquals(0, detailsCalls)
        }
        composeRule
            .onNodeWithTag(CONVERSATION_TOP_BAR_TAG)
            .captureRoboImage("src/test/snapshots/conversation_header_speaker_on_light.png")
        speaker.performClick().assertIsOff()
    }

    /** Both speaker states remain legible in the existing dark header theme. */
    @Test
    fun readAloudShortcutDarkStates() {
        val state = appState()
        state.ttsAutoReadPreferences.setHeaderShortcutVisible(true)
        render(state, ConversationController(state, group()), darkTheme = true)
        val speaker = composeRule.onNodeWithContentDescription("Read messages aloud")
        speaker.assertIsOff()
        composeRule
            .onNodeWithTag(CONVERSATION_TOP_BAR_TAG)
            .captureRoboImage("src/test/snapshots/conversation_header_speaker_off_dark.png")
        speaker.performClick().assertIsOn()
        composeRule
            .onNodeWithTag(CONVERSATION_TOP_BAR_TAG)
            .captureRoboImage("src/test/snapshots/conversation_header_speaker_on_dark.png")
    }

    /** Long LTR titles leave the speaker visible and independently tappable in both states. */
    @Test
    fun readAloudShortcutLongLtrHeader() {
        val state = appState()
        state.ttsAutoReadPreferences.setHeaderShortcutVisible(true)
        val controller =
            ConversationController(state, group().copy(name = "White Noise friends and family community group"))
        render(state, controller, darkTheme = true)
        val speaker = composeRule.onNodeWithContentDescription("Read messages aloud")
        speaker.assertIsOff().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        val header = composeRule.onNodeWithTag(CONVERSATION_TOP_BAR_TAG).fetchSemanticsNode().boundsInRoot
        val bounds = speaker.fetchSemanticsNode().boundsInRoot
        val details =
            composeRule
                .onNodeWithTag(PerformanceTestTags.OPEN_GROUP_DETAILS)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(bounds.left >= details.right && bounds.right <= header.right)
        composeRule
            .onNodeWithTag(CONVERSATION_TOP_BAR_TAG)
            .captureRoboImage("src/test/snapshots/conversation_header_speaker_off_long_ltr_dark.png")
        speaker.performClick().assertIsOn()
        composeRule
            .onNodeWithTag(CONVERSATION_TOP_BAR_TAG)
            .captureRoboImage("src/test/snapshots/conversation_header_speaker_on_long_ltr_dark.png")
    }

    /** Narrow RTL headers keep the speaker's complete touch target clear of a long title. */
    @Test
    @Config(qualifiers = "en-w320dp-h780dp-mdpi")
    fun readAloudShortcutLargeRtlHeader() {
        val state = appState()
        state.ttsAutoReadPreferences.setHeaderShortcutVisible(true)
        val controller = ConversationController(state, group().copy(name = "A long conversation title"))
        render(state, controller, compactHeight = true, fontScale = 2f, rtl = true)
        val speaker = composeRule.onNodeWithContentDescription("Read messages aloud")
        speaker
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
            .assertIsOn()
        val header = composeRule.onNodeWithTag(CONVERSATION_TOP_BAR_TAG).fetchSemanticsNode().boundsInRoot
        val bounds = speaker.fetchSemanticsNode().boundsInRoot
        val details =
            composeRule
                .onNodeWithTag(PerformanceTestTags.OPEN_GROUP_DETAILS)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(bounds.left >= header.left && bounds.right <= details.left)
        assertTrue(bounds.top >= header.top && bounds.bottom <= header.bottom)
        composeRule
            .onNodeWithTag(CONVERSATION_TOP_BAR_TAG)
            .captureRoboImage("src/test/snapshots/conversation_header_speaker_on_large_rtl.png")
    }

    /** An opted-out chat leaves the header action absent rather than reserving a blank button. */
    @Test
    fun readAloudShortcutHiddenWhenNotConfigured() {
        val state = appState()
        render(state, ConversationController(state, group()))
        composeRule.onNodeWithContentDescription("Read messages aloud").assertDoesNotExist()
    }

    /** Message selection retains its own commands instead of competing with the speaker action. */
    @Test
    fun readAloudShortcutHiddenDuringSelection() {
        val state = appState()
        state.ttsAutoReadPreferences.setHeaderShortcutVisible(true)
        render(state, ConversationController(state, group()), selectionMode = true)
        composeRule.onNodeWithContentDescription("Read messages aloud").assertDoesNotExist()
    }

    /** Search retains its query and filter actions without a duplicate read-aloud button. */
    @Test
    fun readAloudShortcutHiddenDuringSearch() {
        val state = appState()
        state.ttsAutoReadPreferences.setHeaderShortcutVisible(true)
        render(state, ConversationController(state, group()), searchOpen = true)
        composeRule.onNodeWithContentDescription("Read messages aloud").assertDoesNotExist()
    }

    /**
     * The full title is one accessible 48dp heading action that reads the chat name, offers Details as its
     * click label and preserves its performance selector.
     */
    @Test
    fun performanceSelectorIsClickableWithoutReplacingAccessibilityDescription() {
        val appState = appState()
        val controller = ConversationController(appState = appState, initialGroup = group())
        var openDetailsCalls = 0
        render(appState, controller, onOpenDetails = { openDetailsCalls += 1 })

        composeRule
            .onNodeWithTag(PerformanceTestTags.OPEN_GROUP_DETAILS)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            .assert(
                SemanticsMatcher("click label is the details action") {
                    it.config[SemanticsActions.OnClick].label == OPEN_DETAILS_DESCRIPTION
                },
            ).assertTextContains("Benchmark group")
            .assert(
                SemanticsMatcher("the fallback monogram is not read") { node ->
                    val initials = IdentityFormatter.initials("Benchmark group")
                    node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.none { it.text == initials }
                },
            ).performClick()
        composeRule.runOnIdle { assertEquals(1, openDetailsCalls) }
        composeRule
            .onNodeWithTag("conversation.header.avatar", useUnmergedTree = true)
            .assertWidthIsEqualTo(40.dp)
            .assertHeightIsEqualTo(40.dp)
        composeRule
            .onNodeWithTag(CONVERSATION_TOP_BAR_TAG)
            .captureRoboImage("src/test/snapshots/conversation_header_regular_light.png")
    }

    /** Compact windows retain the native smaller avatar and 48dp details target; timer follows the 12dp design. */
    @Test
    fun compactHeaderKeepsAccessibleDetailsAndNativeTimer() {
        val appState = appState()
        appState.markDisappearingTooltipShown()
        val controller = ConversationController(appState, group().copy(disappearingMessageSecs = 60uL))
        render(appState, controller, compactHeight = true)
        composeRule.onNodeWithTag(PerformanceTestTags.OPEN_GROUP_DETAILS).assertHeightIsAtLeast(48.dp)
        composeRule
            .onNodeWithTag("conversation.header.avatar", useUnmergedTree = true)
            .assertWidthIsEqualTo(28.dp)
            .assertHeightIsEqualTo(28.dp)
        composeRule
            .onNodeWithTag("conversation.header.timer", useUnmergedTree = true)
            .assertWidthIsEqualTo(12.dp)
            .assertHeightIsEqualTo(12.dp)
        composeRule
            .onNodeWithTag(CONVERSATION_TOP_BAR_TAG)
            .captureRoboImage("src/test/snapshots/conversation_header_compact_timer_light.png")
    }

    /** Keeps the automatic-timer explanation inside the available window. */
    @Test
    fun automaticTimerExplanationFitsWindow() {
        val state = appState()
        val controller = ConversationController(state, group().copy(disappearingMessageSecs = 60uL))
        render(state, controller)
        val tooltip = composeRule.onNodeWithTag("conversation.timer.tooltip")
        // The first-time LaunchedEffect opens the production tooltip; use screen coordinates across popup roots.
        val node = tooltip.fetchSemanticsNode()
        val position = node.positionOnScreen
        assertTrue(position.x >= 0f && position.y >= 0f)
        assertTrue(position.x + node.size.width <= 360f && position.y + node.size.height <= 780f)
        tooltip.captureRoboImage("src/test/snapshots/conversation_timer_tooltip_light.png")
    }

    /** Makes the end of an oversized timer explanation reachable by scrolling. */
    @Test
    @Config(qualifiers = "en-w360dp-h120dp-mdpi")
    fun oversizedTimerExplanationScrollsToItsEnd() {
        val state = appState()
        val controller = ConversationController(state, group().copy(disappearingMessageSecs = 60uL))
        render(state, controller, compactHeight = true, fontScale = 2f)
        val tooltip = composeRule.onNodeWithTag("conversation.timer.tooltip")
        val node = tooltip.fetchSemanticsNode()
        assertTrue(node.positionOnScreen.y >= 0f)
        assertTrue(node.positionOnScreen.y + node.size.height <= 120f)
        val text = composeRule.onNodeWithTag("conversation.timer.tooltip.text", useUnmergedTree = true)
        val scroll = text.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("The oversized explanation must be scrollable", scroll.maxValue() > 0f)
        text.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10000f) }
        composeRule.waitForIdle()
        assertEquals(scroll.maxValue(), scroll.value(), 1f)
        composeRule
            .onNodeWithText(context.getString(dev.ipf.whitenoise.android.R.string.disappearing_tooltip_text))
            .assertExists()
        tooltip.captureRoboImage("src/test/snapshots/conversation_timer_tooltip_compact_large_end.png")
    }

    /** Keeps timer tooltips within the window when anchors approach its edges. */
    @Test
    @OptIn(ExperimentalMaterial3Api::class)
    fun timerPositionerClampsEdgeAnchors() {
        lateinit var provider: PopupPositionProvider
        composeRule.setContent {
            provider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above)
        }
        composeRule.runOnIdle {
            for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                for (anchor in listOf(IntRect(92, 20, 180, 48), IntRect(0, 740, 60, 770), IntRect(330, 20, 360, 48))) {
                    val position = provider.calculatePosition(anchor, IntSize(360, 780), direction, IntSize(320, 120))
                    assertTrue(position.x >= 0 && position.x + 320 <= 360)
                    assertTrue(position.y >= 0 && position.y + 120 <= 780)
                }
            }
        }
    }

    /** MDK's prepared title replaces the app's own projection once the window installs its first sidecar. */
    @Test
    fun preparedWindowTitleReplacesFallback() {
        val appState = appState()
        val controller = ConversationController(appState = appState, initialGroup = group())
        controller.window.install(MarmotWindowTestFakes.conversationFrame("Prepared by MDK"))
        render(appState, controller)

        composeRule.onNodeWithText("Prepared by MDK").assertExists()
        composeRule.onNodeWithText("Benchmark group").assertDoesNotExist()
        composeRule
            .onNodeWithTag(CONVERSATION_TOP_BAR_TAG)
            .captureRoboImage("src/test/snapshots/conversation_header_prepared_title_light.png")
    }

    /** Hydration cannot replace the opening route title until the existing route owner releases its freeze. */
    @Test
    fun frozenRouteRetainsTitleUntilTransitionCompletes() {
        val appState = appState()
        val controller = ConversationController(appState, group())
        val frozen = mutableStateOf(true)
        render(appState, controller, frozen = frozen)
        composeRule.runOnIdle { controller.applyGroupStateForTest(group().copy(name = "Hydrated group")) }
        composeRule.onNodeWithText("Benchmark group").assertExists()
        composeRule.onNodeWithText("Hydrated group").assertDoesNotExist()
        composeRule.runOnIdle { frozen.value = false }
        composeRule.onNodeWithText("Hydrated group").assertExists()
        composeRule.onNodeWithText("Benchmark group").assertDoesNotExist()
    }

    /** Real controller presentation with inert navigation; no native reads or mutations are supplied. */
    private fun render(
        appState: WhiteNoiseAppState,
        controller: ConversationController,
        compactHeight: Boolean = false,
        frozen: State<Boolean> = mutableStateOf(false),
        onOpenDetails: () -> Unit = {},
        fontScale: Float = 1f,
        darkTheme: Boolean = false,
        rtl: Boolean = false,
        selectionMode: Boolean = false,
        searchOpen: Boolean = false,
    ) {
        val searchFocusRequester = FocusRequester()
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(fontScale = fontScale, darkTheme = darkTheme) {
                    Surface {
                        ConversationTopBar(
                            selectionMode = selectionMode,
                            selectedCount = 0,
                            onCloseSelection = {},
                            searchOpen = searchOpen,
                            searchQuery = "",
                            onSearchQueryChange = {},
                            onClearSearch = {},
                            onCloseSearch = {},
                            onSearchAction = {},
                            searchFocusRequester = searchFocusRequester,
                            appState = appState,
                            controller = controller,
                            groupTitleCopy = GroupTitleCopy.Default,
                            openedAsDmHint = false,
                            freezeRoutePresentation = frozen.value,
                            openDetailsDescription = OPEN_DETAILS_DESCRIPTION,
                            onOpenDetails = onOpenDetails,
                            onBack = {},
                            compactHeight = compactHeight,
                            performanceSelectorsEnabled = true,
                        )
                    }
                }
            }
        }
    }

    /** Builds an app state with the test account active. */
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
                        unlinked = false,
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
            name = "Benchmark group",
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
            leaveBlocked = false,
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
        const val OPEN_DETAILS_DESCRIPTION = "Open group details"
        val ACCOUNT_ID = "01" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
    }
}
