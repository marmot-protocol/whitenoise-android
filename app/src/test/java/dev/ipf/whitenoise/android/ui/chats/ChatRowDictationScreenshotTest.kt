package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.audio.ConversationDictationActivity
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Chat rows show which conversation is listening and which is turning speech into text. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatRowDictationScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun dictationLight() = capture("chat_row_dictation_light")

    @Test fun dictationAmoled() = capture("chat_row_dictation_amoled", dark = true, amoled = true)

    @Test fun dictationLargeRtl() = capture("chat_row_dictation_large_rtl", dark = true, largeRtl = true)

    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        val state = ChatRowPortFixtures.state(context)
        val base = ChatRowPortFixtures.item(unread = true)
        val rows =
            listOf(
                base to ConversationDictationActivity.Listening,
                base.copy(group = base.group.copy(groupIdHex = "b".repeat(64))) to
                    ConversationDictationActivity.Transcribing,
                base.copy(group = base.group.copy(groupIdHex = "c".repeat(64))) to null,
            )
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (largeRtl) 2f else 1f) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        Column {
                            rows.forEach { (row, activity) ->
                                ChatRow(
                                    item = row,
                                    appState = state,
                                    onClick = {},
                                    onOpenProfile = {},
                                    dictationActivity = activity,
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("chat.row.dictation.listening", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("chat.row.dictation.transcribing", useUnmergedTree = true).assertExists()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
