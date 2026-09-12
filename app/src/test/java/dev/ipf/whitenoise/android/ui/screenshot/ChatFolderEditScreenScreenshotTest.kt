package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
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

    /** Renders the form for one fixed draft and records the window. */
    private fun capture(
        name: String,
        dark: Boolean,
        amoled: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                ChatFolderEditContent(
                    state = previewState(),
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
