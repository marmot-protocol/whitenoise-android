package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Dp
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
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.state.ChatNotifyMode
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.HeaderShortcut
import dev.ipf.whitenoise.android.state.MarmotWindowTestFakes
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isConversationMutePending
import dev.ipf.whitenoise.android.ui.group.ConversationMuteControl
import dev.ipf.whitenoise.android.ui.group.MuteTarget
import dev.ipf.whitenoise.android.ui.group.canEditGroupSettings
import dev.ipf.whitenoise.android.ui.group.disappearingTimerEditable
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** The default header has no shortcut buttons. */
    @Test
    @Config(qualifiers = PHONE)
    fun headerWithoutShortcutsLight() = shortcutHeader("none_light", emptyList())

    /** The default header in dark theme. */
    @Test
    @Config(qualifiers = PHONE)
    fun headerWithoutShortcutsDark() = shortcutHeader("none_dark", emptyList(), dark = true)

    /** One chosen shortcut sits at the end of the header. */
    @Test
    @Config(qualifiers = PHONE)
    fun headerWithOneShortcutLight() = shortcutHeader("one_light", listOf(HeaderShortcut.Search))

    /** One shortcut in dark theme. */
    @Test
    @Config(qualifiers = PHONE)
    fun headerWithOneShortcutDark() = shortcutHeader("one_dark", listOf(HeaderShortcut.Search), dark = true)

    /** Two shortcuts keep the person's order and leave the title its width. */
    @Test
    @Config(qualifiers = PHONE)
    fun headerWithTwoShortcutsLight() = shortcutHeader("two_light", BOTH_SHORTCUTS)

    /** Two shortcuts in dark theme. */
    @Test
    @Config(qualifiers = PHONE)
    fun headerWithTwoShortcutsDark() = shortcutHeader("two_dark", BOTH_SHORTCUTS, dark = true)

    /** Search and an active Read aloud button share the header. */
    @Test
    @Config(qualifiers = PHONE)
    fun headerWithReadAloudLight() = shortcutHeader("read_aloud_light", READ_ALOUD_SHORTCUTS)

    /** Search and Read aloud in dark theme. */
    @Test
    @Config(qualifiers = PHONE)
    fun headerWithReadAloudDark() = shortcutHeader("read_aloud_dark", READ_ALOUD_SHORTCUTS, dark = true)

    /** A typical phone keeps room for the title when more shortcuts are configured than fit. */
    @Test
    @Config(qualifiers = PHONE)
    fun headerWithAllShortcutsLight() = shortcutHeader("all_light", HeaderShortcut.entries.toList())

    /** Every shortcut chosen in dark theme. */
    @Test
    @Config(qualifiers = PHONE)
    fun headerWithAllShortcutsDark() = shortcutHeader("all_dark", HeaderShortcut.entries.toList(), dark = true)

    /** Every shortcut chosen on a small phone at double font, with the members line and timer badge. */
    @Test
    @Config(qualifiers = SMALL_PHONE)
    fun crowdedHeaderLargeFont() = crowdedHeader("crowded_large_font", rtl = false)

    /** The crowded header mirrored for a right-to-left language. */
    @Test
    @Config(qualifiers = SMALL_PHONE)
    fun crowdedHeaderLargeFontRtl() = crowdedHeader("crowded_large_font_rtl", rtl = true)

    /** The first shortcuts that fit show in the chosen order; the rest wait for a wider window. */
    @Test
    fun headerShowsOnlyTheShortcutsThatFit() {
        val appState = appState()
        val controller = ConversationController(appState, group())
        render(appState, controller, actions = {
            ConversationHeaderShortcuts(
                capacity = it,
                shortcuts = HeaderShortcut.entries.reversed(),
                muteControl = muteControl(appState),
                onSearch = {},
                readAloudOn = true,
                onMembers = {},
                onAddMembers = {},
            )
        })
        // At 360 dp, only the first two full-size targets fit without crowding the title.
        composeRule.onNodeWithTag(CONVERSATION_HEADER_ADD_MEMBERS_TAG).assertExists()
        composeRule.onNodeWithTag(CONVERSATION_HEADER_MEMBERS_TAG).assertExists()
        composeRule.onNodeWithTag(CONVERSATION_HEADER_READ_ALOUD_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(CONVERSATION_HEADER_MUTE_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(CONVERSATION_HEADER_SEARCH_TAG).assertDoesNotExist()
    }

    /** Capacity keeps the chat name at least as wide as the buttons and readable at large font sizes. */
    @Test
    fun shortcutCapacityLeavesTheChatNameRoom() {
        assertEquals(2, headerShortcutCapacity(360.dp, 1f))
        assertEquals(3, headerShortcutCapacity(411.dp, 1f))
        assertEquals(1, headerShortcutCapacity(320.dp, 2f))
        assertEquals(5, headerShortcutCapacity(600.dp, 1f))
        assertEquals(0, headerShortcutCapacity(120.dp, 1f))
        assertEquals(Int.MAX_VALUE, headerShortcutCapacity(Dp.Infinity, 1f))
    }

    /** Members and Add members run their handlers and stay hidden when their handler is absent. */
    @Test
    @Config(qualifiers = "en-w600dp-h780dp-mdpi")
    fun groupShortcutsRunHandlersAndHideWithoutOne() {
        val appState = appState()
        val controller = ConversationController(appState, group())
        val available = mutableStateOf(true)
        val taps = mutableListOf<String>()
        render(appState, controller, actions = {
            val on = available.value
            ConversationHeaderShortcuts(
                capacity = it,
                shortcuts = listOf(HeaderShortcut.Members, HeaderShortcut.AddMembers),
                muteControl = null,
                onSearch = {},
                onMembers = if (on) ({ taps += "members" }) else null,
                onAddMembers = if (on) ({ taps += "add" }) else null,
            )
        })
        for (tag in GROUP_SHORTCUT_TAGS) {
            composeRule
                .onNodeWithTag(tag)
                .assertWidthIsEqualTo(48.dp)
                .assertTouchHeightIsEqualTo(48.dp)
                .performClick()
        }
        composeRule.runOnIdle {
            assertEquals(listOf("members", "add"), taps)
            available.value = false
        }
        for (tag in GROUP_SHORTCUT_TAGS) composeRule.onNodeWithTag(tag).assertDoesNotExist()
    }

    /** Search runs the supplied handler; Mute asks for a duration through the shared control, never directly. */
    @Test
    fun shortcutsRunTheirSharedHandlers() {
        val appState = appState()
        val controller = ConversationController(appState, group())
        val dialogOpen = mutableStateOf(false)
        var searches = 0
        render(appState, controller, actions = {
            ConversationHeaderShortcuts(
                capacity = it,
                shortcuts = listOf(HeaderShortcut.Search, HeaderShortcut.MuteUnmute),
                muteControl = muteControl(appState, dialogOpen = dialogOpen),
                onSearch = { searches += 1 },
            )
        })
        composeRule.onNodeWithTag(CONVERSATION_HEADER_SEARCH_TAG).assertTouchHeightIsEqualTo(48.dp).performClick()
        composeRule.onNodeWithTag(CONVERSATION_HEADER_MUTE_TAG).assertTouchHeightIsEqualTo(48.dp).performClick()
        composeRule.runOnIdle {
            assertEquals(1, searches)
            assertTrue(dialogOpen.value)
        }
    }

    /** Read aloud is a checkable button that flips this chat's override; without a speech engine it is absent. */
    @Test
    fun readAloudShortcutTogglesAndHidesWithoutEngine() {
        val appState = appState()
        val controller = ConversationController(appState, group())
        val readAloudOn = mutableStateOf<Boolean?>(false)
        render(appState, controller, actions = {
            ConversationHeaderShortcuts(
                capacity = it,
                shortcuts = listOf(HeaderShortcut.ReadAloud),
                muteControl = null,
                onSearch = {},
                readAloudOn = readAloudOn.value,
                onToggleReadAloud = { readAloudOn.value = !(readAloudOn.value ?: false) },
            )
        })
        composeRule
            .onNodeWithTag(CONVERSATION_HEADER_READ_ALOUD_TAG)
            .assertTouchHeightIsEqualTo(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
            .performClick()
        composeRule
            .onNodeWithTag(CONVERSATION_HEADER_READ_ALOUD_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        composeRule.runOnIdle { readAloudOn.value = null }
        composeRule.onNodeWithTag(CONVERSATION_HEADER_READ_ALOUD_TAG).assertDoesNotExist()
    }

    /** A mute command in flight disables the header button so a second tap cannot race it. */
    @Test
    fun pendingMuteDisablesTheHeaderShortcut() {
        val appState = appState()
        val controller = ConversationController(appState, group())
        val dialogOpen = mutableStateOf(false)
        render(appState, controller, actions = {
            ConversationHeaderShortcuts(
                capacity = it,
                shortcuts = listOf(HeaderShortcut.MuteUnmute),
                muteControl = muteControl(appState, pending = true, dialogOpen = dialogOpen),
                onSearch = {},
            )
        })
        composeRule.onNodeWithTag(CONVERSATION_HEADER_MUTE_TAG).assertIsNotEnabled().performClick()
        composeRule.runOnIdle { assertFalse(dialogOpen.value) }
    }

    /** A mute control captured before an account switch closes its dialog but writes nothing to the new session. */
    @Test
    fun staleMuteControlDoesNotWrite() {
        val appState = appState()
        val dialogOpen = mutableStateOf(true)
        val stale = muteControl(appState, dialogOpen = dialogOpen, isCurrent = { false })
        val modesBefore = appState.chatMutePreferences.state.value.notificationModes
        stale.mute(MuteTarget.Always)
        stale.unmute()
        assertFalse(dialogOpen.value)
        assertFalse(appState.isConversationMutePending(GROUP_ID))
        assertEquals(modesBefore, appState.chatMutePreferences.state.value.notificationModes)
    }

    /** The timer badge stays read-only inside the header target; tapping it opens details whether on or off. */
    @Test
    fun headerTimerIsReadOnlyAndHeaderOpensDetailsWithTimerOnAndOff() {
        val appState = appState()
        appState.markDisappearingTooltipShown()
        val controller = ConversationController(appState, group().copy(disappearingMessageSecs = 60uL))
        var detailsClicks = 0
        render(appState, controller, onOpenDetails = { detailsClicks += 1 })
        val detailsAncestor = hasAnyAncestor(hasTestTag(PerformanceTestTags.OPEN_GROUP_DETAILS))
        composeRule
            .onNodeWithTag("conversation.header.timer", useUnmergedTree = true)
            .assertExists()
            .assert(detailsAncestor)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
            .performClick()
        composeRule
            .onNodeWithText("1 minute", useUnmergedTree = true)
            .assertExists()
            .assert(detailsAncestor)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
            .performClick()
        composeRule.runOnIdle {
            assertEquals(2, detailsClicks)
            controller.applyGroupStateForTest(group().copy(disappearingMessageSecs = 0uL))
        }
        composeRule.onNodeWithTag("conversation.header.timer", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("1 minute", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(PerformanceTestTags.OPEN_GROUP_DETAILS).performClick()
        composeRule.runOnIdle { assertEquals(3, detailsClicks) }
    }

    /** Only an admin member whose group mutations are not blocked may edit the timer in Chat Info. */
    @Test
    fun timerEditabilityCoversEditorBlockedEditorAndNonEditor() {
        val appState = appState()
        val editor = ConversationController(appState, group())
        val nonEditor = ConversationController(appState, group().copy(admins = emptyList()))
        for (controller in listOf(editor, nonEditor)) {
            controller.members = listOf(AppGroupMemberRecordFfi(ACCOUNT_ID, ACCOUNT_REF, true))
        }
        assertTrue(editor.canEditGroupSettings)
        assertFalse(nonEditor.canEditGroupSettings)
        assertTrue(disappearingTimerEditable(canEdit = editor.canEditGroupSettings, mutationsBlocked = false))
        assertFalse(disappearingTimerEditable(canEdit = editor.canEditGroupSettings, mutationsBlocked = true))
        assertFalse(disappearingTimerEditable(canEdit = nonEditor.canEditGroupSettings, mutationsBlocked = false))
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
    @Suppress("LongParameterList")
    private fun render(
        appState: WhiteNoiseAppState,
        controller: ConversationController,
        compactHeight: Boolean = false,
        frozen: State<Boolean> = mutableStateOf(false),
        onOpenDetails: () -> Unit = {},
        fontScale: Float = 1f,
        dark: Boolean = false,
        actions: @Composable RowScope.(capacity: Int) -> Unit = {},
        rtl: Boolean = false,
    ) {
        composeRule.setContent {
            val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = fontScale) {
                    Surface {
                        TopBar(appState, controller, compactHeight, frozen.value, onOpenDetails, actions)
                    }
                }
            }
        }
    }

    @Composable
    @Suppress("LongParameterList")
    private fun TopBar(
        appState: WhiteNoiseAppState,
        controller: ConversationController,
        compactHeight: Boolean = false,
        frozen: Boolean = false,
        onOpenDetails: () -> Unit = {},
        actions: @Composable RowScope.(capacity: Int) -> Unit = {},
    ) {
        ConversationTopBar(
            selectionMode = false,
            selectedCount = 0,
            onCloseSelection = {},
            searchOpen = false,
            searchQuery = "",
            onSearchQueryChange = {},
            onClearSearch = {},
            onCloseSearch = {},
            onSearchAction = {},
            searchFocusRequester = remember { FocusRequester() },
            appState = appState,
            controller = controller,
            groupTitleCopy = GroupTitleCopy.Default,
            openedAsDmHint = false,
            freezeRoutePresentation = frozen,
            openDetailsDescription = OPEN_DETAILS_DESCRIPTION,
            onOpenDetails = onOpenDetails,
            onBack = {},
            compactHeight = compactHeight,
            performanceSelectorsEnabled = true,
            actions = actions,
        )
    }

    /** Records the header with [shortcuts] chosen, using an unmuted conversation's mute control. */
    private fun shortcutHeader(
        name: String,
        shortcuts: List<HeaderShortcut>,
        dark: Boolean = false,
    ) {
        val appState = appState()
        val controller = ConversationController(appState, group())
        render(appState, controller, dark = dark, actions = {
            ConversationHeaderShortcuts(
                shortcuts,
                capacity = it,
                muteControl = muteControl(appState),
                onSearch = {},
                readAloudOn = true,
                onMembers = {},
                onAddMembers = {},
            )
        })
        composeRule
            .onNodeWithTag(CONVERSATION_TOP_BAR_TAG)
            .captureRoboImage("src/test/snapshots/conversation_header_shortcuts_$name.png")
    }

    /** Records a three-member group with a one-day timer and every shortcut chosen at double font scale. */
    private fun crowdedHeader(
        name: String,
        rtl: Boolean,
    ) {
        val appState = appState()
        appState.markDisappearingTooltipShown()
        val controller = ConversationController(appState, group().copy(disappearingMessageSecs = 86_400uL))
        controller.members = (1..3).map { AppGroupMemberRecordFfi("0$it" + "00".repeat(31), ACCOUNT_REF, it == 1) }
        controller.membersLoaded = true
        render(appState, controller, fontScale = 2f, rtl = rtl, actions = {
            ConversationHeaderShortcuts(
                HeaderShortcut.entries.toList(),
                capacity = it,
                muteControl = muteControl(appState),
                onSearch = {},
                readAloudOn = false,
                onMembers = {},
                onAddMembers = {},
            )
        })
        composeRule.onNodeWithTag("conversation.header.timer", useUnmergedTree = true).assertExists()
        composeRule
            .onNodeWithTag(CONVERSATION_TOP_BAR_TAG)
            .captureRoboImage("src/test/snapshots/conversation_header_shortcuts_$name.png")
    }

    private fun muteControl(
        appState: WhiteNoiseAppState,
        pending: Boolean = false,
        dialogOpen: MutableState<Boolean> = mutableStateOf(false),
        isCurrent: () -> Boolean = { true },
    ) = ConversationMuteControl(
        appState,
        GROUP_ID,
        muted = false,
        mutedUntilMs = null,
        pending = pending,
        restoreMode = ChatNotifyMode.ALL,
        dialogOpen = dialogOpen,
        isCurrent = isCurrent,
    )

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
        val BOTH_SHORTCUTS = listOf(HeaderShortcut.MuteUnmute, HeaderShortcut.Search)
        val READ_ALOUD_SHORTCUTS = listOf(HeaderShortcut.Search, HeaderShortcut.ReadAloud)
        const val PHONE = "en-w411dp-h891dp-xxhdpi"
        const val SMALL_PHONE = "en-w320dp-h780dp-xxhdpi"
        val GROUP_SHORTCUT_TAGS = listOf(CONVERSATION_HEADER_MEMBERS_TAG, CONVERSATION_HEADER_ADD_MEMBERS_TAG)
    }
}
