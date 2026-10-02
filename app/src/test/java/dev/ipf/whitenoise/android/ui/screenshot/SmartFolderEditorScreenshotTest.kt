package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.FolderField
import dev.ipf.whitenoise.android.state.FolderMode
import dev.ipf.whitenoise.android.state.SmartFolderFilter
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerItem
import dev.ipf.whitenoise.android.ui.settings.CHAT_FOLDER_EDIT_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.settings.ChatFolderEditContent
import dev.ipf.whitenoise.android.ui.settings.ChatFolderEditFormState
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

    @Test fun empty() = capture("smart_folder_empty", empty = true)

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
    ) {
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
                    ChatFolderEditContent(
                        state =
                            ChatFolderEditFormState(
                                isNew = true,
                                name = TextFieldState("Agent chats"),
                                description = TextFieldState("All read"),
                                keyword = TextFieldState(),
                                unreadOnly = false,
                                includeMuted = true,
                                groupsOnly = false,
                                archivedOnly = false,
                                manualChatCount = 0,
                                peopleCount = 1,
                                previewCount = if (empty) 0 else 8,
                                canSave = true,
                            ),
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
                                    true,
                                    root,
                                ),
                                listOf(WhiteNoisePickerItem("a".repeat(64), "Agent", "agent")),
                                resolveKey = {
                                    null
                                },
                                onStart = {},
                                onChange = {},
                                legacyControls = {},
                            )
                        },
                    )
                }
            }
        }
        if (rules) {
            composeRule.onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG).performScrollToNode(hasTestTag("folder.group."))
        }
        if (dialog) composeRule.onNodeWithTag("folder.condition.1").performClick()
        val target = if (dialog) composeRule.onNode(isDialog()) else composeRule.onRoot()
        target.captureRoboImage("src/test/snapshots/$name.png")
    }
}
