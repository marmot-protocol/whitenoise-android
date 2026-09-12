package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.settings.CHAT_FOLDER_EDIT_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.settings.ChatFolderEditContent
import dev.ipf.whitenoise.android.ui.settings.ChatFolderEditFormState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The folder editor mid-edit: name, description, Included Chats, rules with a keyword, and the Preview count. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h1100dp-mdpi")
class ChatFolderEditScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Light theme. */
    @Test
    fun chatFolderEditScreenLight() = capture("chat_folder_edit_screen_light", dark = false, amoled = false)

    /** Dark theme. */
    @Test
    fun chatFolderEditScreenDark() = capture("chat_folder_edit_screen_dark", dark = true, amoled = false)

    /** AMOLED: outlined fields and groups on black. */
    @Test
    fun chatFolderEditScreenAmoled() = capture("chat_folder_edit_screen_amoled", dark = true, amoled = true)

    /** A missing folder keeps its populated draft visible and disables Save. */
    @Test fun folderUnavailableLight() =
        capture(
            "chat_folder_unavailable_light",
            dark = false,
            amoled = false,
            unavailable = true,
        )

    /** Large RTL text preserves field and Included Chats/People navigation layout. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h1100dp-mdpi")
    fun folderEditorRtlLarge() =
        capture(
            "chat_folder_editor_rtl_large",
            dark = false,
            amoled = false,
            largeRtl = true,
        )

    /** Scrolled rules and Preview retain native switches and 200% text without inventing memberships. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h1100dp-mdpi")
    fun folderEditorRulesRtlLarge() =
        capture(
            "chat_folder_editor_rules_rtl_large",
            dark = true,
            amoled = false,
            largeRtl = true,
            rules = true,
        )

    /** Renders the form for one fixed draft and records the window. */
    private fun capture(
        name: String,
        dark: Boolean,
        amoled: Boolean,
        unavailable: Boolean = false,
        largeRtl: Boolean = false,
        rules: Boolean = false,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (largeRtl) 2f else 1f) {
                    ChatFolderEditContent(
                        state =
                            previewState().copy(
                                canSave = !unavailable,
                                error = if (unavailable) context.getString(R.string.folder_unavailable) else null,
                            ),
                        onUnreadOnlyChange = {},
                        onIncludeMutedChange = {},
                        onGroupsOnlyChange = {},
                        onArchivedOnlyChange = {},
                        onOpenManualChats = {},
                        onOpenPeople = {},
                        onOpenPreview = {},
                        onSave = {},
                        onBack = {},
                    )
                }
            }
        }
        if (rules) {
            composeRule
                .onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG)
                .performScrollToNode(hasText(context.getString(R.string.folder_preview)))
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun previewState() =
        ChatFolderEditFormState(
            isNew = false,
            name = TextFieldState("Work"),
            description = TextFieldState("Team chats"),
            keyword = TextFieldState("release"),
            unreadOnly = true,
            includeMuted = false,
            groupsOnly = false,
            archivedOnly = false,
            manualChatCount = 2,
            peopleCount = 2,
            previewCount = 5,
            canSave = true,
        )
}
