package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
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

    /** Empty folders still show the exact create/restore recovery actions. */
    @Test fun emptyFoldersLight() =
        capture(
            "chat_folders_empty_light",
            dark = false,
            amoled = false,
            empty = true,
        )

    /** Native long-press anchors the move/edit/delete menu to the folder row. */
    @Test fun folderActionsDark() =
        capture(
            "chat_folders_actions_dark",
            dark = true,
            amoled = false,
            menu = true,
        )

    /** Large RTL labels preserve native row content, trailing actions and minimum targets. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h780dp-mdpi")
    fun foldersRtlLarge() =
        capture(
            "chat_folders_rtl_large",
            dark = false,
            amoled = false,
            largeRtl = true,
        )

    /** Renders the list for one fixed arrangement and records the window. */
    private fun capture(
        name: String,
        dark: Boolean,
        amoled: Boolean,
        empty: Boolean = false,
        menu: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (largeRtl) 2f else 1f) {
                    ChatFoldersContent(
                        state = if (empty) chatFoldersState(emptyList()) else previewState(),
                        onBack = {},
                        onCreate = {},
                        onMove = { _, _ -> },
                        onEdit = {},
                        onDelete = {},
                        onRestoreDefaults = {},
                    )
                }
            }
        }
        if (menu) {
            composeRule.onNodeWithTag("folder.row.work").performTouchInput { longClick() }
            composeRule.onNodeWithTag("folder.menu.work").captureRoboImage("src/test/snapshots/$name.png")
        } else {
            composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
        }
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
