package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatFoldersScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun chatFoldersScreenLight() = capture("chat_folders_screen_light", dark = false, amoled = false)

    @Test
    fun chatFoldersScreenDark() = capture("chat_folders_screen_dark", dark = true, amoled = false)

    @Test
    fun chatFoldersScreenAmoled() = capture("chat_folders_screen_amoled", dark = true, amoled = true)

    private fun capture(
        name: String,
        dark: Boolean,
        amoled: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatFoldersContent(
                        state = previewState(),
                        onBack = {},
                        onCreate = {},
                        onMove = { _, _ -> },
                        onEdit = {},
                        onDelete = {},
                    )
                }
            }
        }

        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun previewState() =
        chatFoldersState(
            folders =
                listOf(
                    ChatFolderManageItem(
                        id = "unread",
                        name = "Unread",
                        systemKind = SystemFolderKind.UNREAD,
                        chatCount = 3,
                        isCustom = false,
                        canMoveUp = false,
                        canMoveDown = true,
                    ),
                    ChatFolderManageItem(
                        id = "archived",
                        name = "Archived",
                        systemKind = SystemFolderKind.ARCHIVED,
                        chatCount = 1,
                        isCustom = false,
                        canMoveUp = true,
                        canMoveDown = true,
                    ),
                    ChatFolderManageItem(
                        id = "groups",
                        name = "Groups",
                        systemKind = SystemFolderKind.GROUPS,
                        chatCount = 5,
                        isCustom = false,
                        canMoveUp = true,
                        canMoveDown = true,
                    ),
                    ChatFolderManageItem(
                        id = "work",
                        name = "Work",
                        systemKind = null,
                        chatCount = 2,
                        isCustom = true,
                        canMoveUp = true,
                        canMoveDown = false,
                    ),
                ),
        )
}
