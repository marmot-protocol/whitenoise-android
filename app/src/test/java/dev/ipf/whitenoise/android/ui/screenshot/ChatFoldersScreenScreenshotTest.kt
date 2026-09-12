package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.SystemFolderKind
import dev.ipf.whitenoise.android.ui.settings.ChatFolderManageItem
import dev.ipf.whitenoise.android.ui.settings.ChatFoldersContent
import dev.ipf.whitenoise.android.ui.settings.chatFoldersState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Folders with the three defaults and one custom folder, every default present so Restore is disabled. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatFoldersScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Light theme. */
    @Test
    fun chatFoldersScreenLight() = capture("chat_folders_screen_light", dark = false, amoled = false)

    /** Dark theme. */
    @Test
    fun chatFoldersScreenDark() = capture("chat_folders_screen_dark", dark = true, amoled = false)

    /** AMOLED: outlined rows on black. */
    @Test
    fun chatFoldersScreenAmoled() = capture("chat_folders_screen_amoled", dark = true, amoled = true)

    /** Renders the list for one fixed arrangement and records the window. */
    private fun capture(
        name: String,
        dark: Boolean,
        amoled: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                ChatFoldersContent(
                    state = previewState(),
                    onBack = {},
                    onCreate = {},
                    onMove = { _, _ -> },
                    onEdit = {},
                    onDelete = {},
                    onRestoreDefaults = {},
                )
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun previewState() =
        chatFoldersState(
            folders =
                listOf(
                    folder("unread", "Unread", SystemFolderKind.UNREAD, 3, canMoveUp = false),
                    folder("archived", "Archived", SystemFolderKind.ARCHIVED, 1),
                    folder("groups", "Groups", SystemFolderKind.GROUPS, 5),
                    folder("work", "Work", null, 2, canMoveDown = false, description = "Team chats"),
                ),
            defaultsMissing = false,
        )

    /** One row of the fixture list. */
    private fun folder(
        id: String,
        name: String,
        systemKind: SystemFolderKind?,
        chatCount: Int,
        canMoveUp: Boolean = true,
        canMoveDown: Boolean = true,
        description: String = "",
    ) = ChatFolderManageItem(id, name, systemKind, chatCount, canMoveUp, canMoveDown, description)
}
