package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.settings.ChatFolderEditContent
import dev.ipf.whitenoise.android.ui.settings.ChatFolderEditFormState
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
class ChatFolderEditScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun chatFolderEditScreenLight() = capture("chat_folder_edit_screen_light", dark = false, amoled = false)

    @Test
    fun chatFolderEditScreenDark() = capture("chat_folder_edit_screen_dark", dark = true, amoled = false)

    @Test
    fun chatFolderEditScreenAmoled() = capture("chat_folder_edit_screen_amoled", dark = true, amoled = true)

    private fun capture(
        name: String,
        dark: Boolean,
        amoled: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChatFolderEditContent(
                        state = previewState(),
                        onNameChange = {},
                        onDescriptionChange = {},
                        onKeywordChange = {},
                        onUnreadOnlyChange = {},
                        onIncludeMutedChange = {},
                        onOpenManualChats = {},
                        onOpenPeople = {},
                        onSave = {},
                        onBack = {},
                    )
                }
            }
        }

        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun previewState() =
        ChatFolderEditFormState(
            isNew = false,
            name = "Work",
            description = "Team chats",
            keyword = "release",
            unreadOnly = true,
            includeMuted = false,
            manualChatSummary = app.resources.getQuantityString(R.plurals.chat_folder_chat_count, 2, 2),
            peopleSummary = "Alice, Bob",
            canSave = true,
        )
}
