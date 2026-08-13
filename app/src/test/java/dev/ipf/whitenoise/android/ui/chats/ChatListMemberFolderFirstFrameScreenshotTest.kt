package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Visual contract for #1534: member-derived folders are present on the first local frame. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatListMemberFolderFirstFrameScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun memberFolderFirstFrameLight() {
        captureMemberFolder(darkTheme = false, amoled = false, themeName = "light")
    }

    @Test
    fun memberFolderFirstFrameDark() {
        captureMemberFolder(darkTheme = true, amoled = false, themeName = "dark")
    }

    @Test
    fun memberFolderFirstFrameAmoled() {
        captureMemberFolder(darkTheme = true, amoled = true, themeName = "amoled")
    }

    private fun captureMemberFolder(
        darkTheme: Boolean,
        amoled: Boolean,
        themeName: String,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .testTag(SCREENSHOT_TAG),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    ChatListFilterChips(
                        chips =
                            listOf(
                                ChatFolderChipModel(
                                    folderId = "folder-collaborators",
                                    systemKind = null,
                                    customLabel = "Collaborators",
                                    trailingCount = 3,
                                ),
                            ),
                        selectedFolderId = null,
                        onSelect = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(SCREENSHOT_TAG)
            .captureRoboImage("src/test/snapshots/chat_list_member_folder_first_frame_$themeName.png")
    }

    private companion object {
        const val SCREENSHOT_TAG = "chat-list-member-folder-first-frame"
    }
}
