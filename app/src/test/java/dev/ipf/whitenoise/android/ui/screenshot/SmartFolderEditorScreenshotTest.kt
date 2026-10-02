package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.FolderField
import dev.ipf.whitenoise.android.state.FolderMode
import dev.ipf.whitenoise.android.state.SmartFolderFilter
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerItem
import dev.ipf.whitenoise.android.ui.settings.CHAT_FOLDER_EDIT_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.settings.ChatFolderEditContent
import dev.ipf.whitenoise.android.ui.settings.ChatFolderEditFormState
import dev.ipf.whitenoise.android.ui.settings.LegacyFolderRuleControls
import dev.ipf.whitenoise.android.ui.settings.SmartFolderPanelState
import dev.ipf.whitenoise.android.ui.settings.SmartFolderRulePanel
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h1100dp-mdpi")
class SmartFolderEditorScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun light() = capture("smart_folder_light")

    @Test fun dark() = capture("smart_folder_dark", dark = true)

    @Test fun amoled() = capture("smart_folder_amoled", dark = true, amoled = true)

    @Test fun nested() = capture("smart_folder_nested", nested = true)

    @Test fun absenceDialog() = capture("smart_folder_absence_dialog", dialog = true)

    @Test fun unresolvedChat() = capture("smart_folder_unresolved", unresolved = 1)

    @Test fun moreOptions() = capture("smart_folder_options", options = true)

    @Test fun presetReplacement() = capture("smart_folder_replace", replace = true)

    @Test fun manualFolder() = capture("smart_folder_manual")

    @Test fun singleMatch() = capture("smart_folder_single_match")

    @Test fun simpleExpanded() = capture("smart_folder_simple_expanded")

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h1100dp-mdpi")
    fun simpleExpandedRtlLarge() = capture("smart_folder_simple_rtl_large", rtl = true)

    @Test fun empty() = capture("smart_folder_empty", empty = true)

    @Test fun addFilterSheet() = capture("smart_folder_add_filter", add = true)

    @Test fun moreFiltersSheet() = capture("smart_folder_more_filters", add = true, more = true)

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h1100dp-mdpi")
    fun addFilterRtlLarge() = capture("smart_folder_add_filter_rtl_large", add = true, rtl = true)

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h1100dp-mdpi")
    fun rtlLarge() = capture("smart_folder_rtl_large", rtl = true)

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h1100dp-mdpi")
    fun rtlRulesLarge() = capture("smart_folder_rtl_rules_large", rtl = true, rules = true)

    @Suppress("LongMethod") // Render one fixed full form consistently across visual configurations.
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        nested: Boolean = false,
        dialog: Boolean = false,
        empty: Boolean = false,
        rtl: Boolean = false,
        rules: Boolean = false,
        options: Boolean = false,
        replace: Boolean = false,
        unresolved: Int = 0,
        add: Boolean = false,
        more: Boolean = false,
    ) {
        var mentionLabel = ""
        val simpleExpanded = name.startsWith("smart_folder_simple_")
        val manual = name == "smart_folder_manual" || simpleExpanded
        val conditions =
            listOf(
                SmartFolderFilter.Condition(
                    FolderField.PARTICIPANTS,
                    FolderMode.ANY_OF,
                    setOf("a".repeat(64)),
                ),
                SmartFolderFilter.Condition(FolderField.UNREAD, FolderMode.NONE),
            )
        val root =
            when {
                empty -> SmartFolderFilter.Group()
                nested ->
                    SmartFolderFilter.Group(
                        children =
                            conditions +
                                SmartFolderFilter.Group(
                                    all = false,
                                    not = true,
                                    children =
                                        listOf(
                                            SmartFolderFilter.Condition(FolderField.DRAFT),
                                            SmartFolderFilter.Condition(FolderField.MENTIONS),
                                        ),
                                ),
                    )
                else -> SmartFolderFilter.Group(children = conditions)
            }
        composeRule.setContent {
            val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 2f else 1f) {
                    mentionLabel = stringResource(R.string.smart_folder_preset_mentions)
                    ChatFolderEditContent(
                        state =
                            formState(name, manual, empty).copy(unresolvedCount = unresolved),
                        onUnreadOnlyChange = {},
                        onIncludeMutedChange = {},
                        onGroupsOnlyChange = {},
                        onArchivedOnlyChange = {},
                        onUnreadMentionsOnlyChange = {},
                        onDirectChatsOnlyChange = {},
                        onPinnedOnlyChange = {},
                        onOpenManualChats = {},
                        onOpenPeople = {},
                        onOpenPreview = {},
                        onSave = {},
                        onDelete = {},
                        onBack = {},
                        rulesContent = {
                            SmartFolderRulePanel(
                                SmartFolderPanelState(
                                    !manual,
                                    root,
                                    confirmSimpleReplacement = simpleExpanded,
                                ),
                                listOf(WhiteNoisePickerItem("a".repeat(64), "Agent", "agent")),
                                resolveKey = {
                                    null
                                },
                                onChange = {},
                                legacyControls = {
                                    LegacyFolderRuleControls(
                                        keyword = TextFieldState(),
                                        unread = false,
                                        muted = true,
                                        groups = false,
                                        archived = false,
                                        mentions = false,
                                        direct = false,
                                        pinned = false,
                                        peopleCount = 0,
                                        onUnread = {},
                                        onMuted = {},
                                        onGroups = {},
                                        onArchived = {},
                                        onMentions = {},
                                        onDirect = {},
                                        onPinned = {},
                                        onPeople = {},
                                    )
                                },
                            )
                        },
                    )
                }
            }
        }
        prepareCapture(
            CaptureActions(simpleExpanded, rtl, rules, add, more, options, replace, dialog),
            mentionLabel,
        )
        val target =
            when {
                add -> composeRule.onNodeWithTag("sheet.surface")
                dialog || replace -> composeRule.onNode(isDialog())
                else -> composeRule.onRoot()
            }
        target.captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun prepareCapture(
        actions: CaptureActions,
        mentionLabel: String,
    ) {
        if (actions.simple) expandSimple(actions.rtl)
        if (actions.rules) {
            composeRule.onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG).performScrollToNode(hasTestTag("folder.group."))
        }
        if (actions.add) {
            composeRule.onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG).performScrollToNode(hasTestTag("folder.add."))
            composeRule.onNodeWithTag("folder.add.").performClick()
            settleSheet()
            if (actions.more) {
                composeRule.onNodeWithTag("folder.moreFilters").performClick()
                settleSheet()
                composeRule.onNodeWithTag("folder.addField.DRAFT").assertExists()
            }
        }
        if (actions.options) composeRule.onNodeWithTag("folder.options.").performClick()
        if (actions.replace) {
            composeRule.onNodeWithTag("folder.presets").performClick()
            composeRule.onNodeWithText(mentionLabel).performClick()
            composeRule.mainClock.advanceTimeBy(PRESET_MENU_SETTLE_MILLIS)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("folder.confirmPreset").assertExists()
        }
        if (actions.dialog) composeRule.onNodeWithTag("folder.condition.1").performClick()
    }

    private fun settleSheet() {
        composeRule.mainClock.advanceTimeBy(SHEET_SETTLE_MILLIS)
        composeRule.waitForIdle()
    }

    private fun formState(
        name: String,
        manual: Boolean,
        empty: Boolean,
    ): ChatFolderEditFormState =
        ChatFolderEditFormState(
            isNew = true,
            name = TextFieldState(if (manual) "Personal" else "Agent chats"),
            description = TextFieldState(if (manual) "" else "All read"),
            keyword = TextFieldState(),
            unreadOnly = false,
            includeMuted = true,
            groupsOnly = false,
            archivedOnly = false,
            manualChatCount = 0,
            peopleCount = if (manual) 0 else 1,
            previewCount =
                if (empty || manual) {
                    0
                } else if (name == "smart_folder_single_match") {
                    1
                } else {
                    8
                },
            advancedRules = !manual,
            canSave = true,
        )

    private fun expandSimple(rtl: Boolean) {
        val content = composeRule.onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG)
        content.performScrollToNode(hasTestTag("folder.legacyEdit"))
        composeRule.onNodeWithTag("folder.legacyEdit").performClick()
        if (rtl) content.performScrollToNode(hasTestTag("folder.keyword"))
    }
}

private const val PRESET_MENU_SETTLE_MILLIS = 300L

private data class CaptureActions(
    val simple: Boolean,
    val rtl: Boolean,
    val rules: Boolean,
    val add: Boolean,
    val more: Boolean,
    val options: Boolean,
    val replace: Boolean,
    val dialog: Boolean,
)

private const val SHEET_SETTLE_MILLIS = 1000L
