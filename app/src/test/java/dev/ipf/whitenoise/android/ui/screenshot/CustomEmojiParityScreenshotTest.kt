package dev.ipf.whitenoise.android.ui.screenshot

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.CustomEmoji
import dev.ipf.whitenoise.android.ui.CustomEmojiSet
import dev.ipf.whitenoise.android.ui.LocalCustomEmoji
import dev.ipf.whitenoise.android.ui.chats.ChatFolderChipModel
import dev.ipf.whitenoise.android.ui.chats.ChatFolderPills
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
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
import java.io.File

/**
 * Built-in and device-local custom emoji in folder names, an emoji text field and avatar monograms, drawn beside
 * the same layouts with Unicode emoji so the two read alike.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class CustomEmojiParityScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Folder pills, an emoji field and monograms in the light theme. */
    @Test
    fun foldersFieldAndMonogramsLight() = captureSurfaces("custom_emoji_parity_light", dark = false)

    /** The same surfaces in the dark theme. */
    @Test
    fun foldersFieldAndMonogramsDark() = captureSurfaces("custom_emoji_parity_dark", dark = true)

    /** Large-font RTL keeps the artwork the size of the surrounding text. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h780dp-mdpi")
    fun foldersFieldAndMonogramsLargeRtl() {
        captureSurfaces("custom_emoji_parity_large_rtl", dark = false, largeRtl = true)
    }

    /** Folder settings rows draw the artwork in names and descriptions. */
    @Test
    fun folderSettingsRowsLight() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                CompositionLocalProvider(LocalCustomEmoji provides localEmoji()) {
                    ChatFoldersContent(
                        state =
                            chatFoldersState(
                                folders =
                                    listOf(
                                        manageItem("work", ":marmot: Work", 2, ":party: Team chats"),
                                        manageItem("friends", "Friends :party:", 5, ""),
                                        manageItem("study", "📚 Study", 1, "Book group"),
                                    ),
                                defaultsMissing = false,
                            ),
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
        composeRule.onRoot().captureRoboImage("src/test/snapshots/custom_emoji_parity_folder_rows_light.png")
    }

    private fun captureSurfaces(
        name: String,
        dark: Boolean,
        largeRtl: Boolean = false,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = if (largeRtl) 2f else 1f) {
                    CompositionLocalProvider(LocalCustomEmoji provides localEmoji()) {
                        Surface(Modifier.testTag(ROOT)) {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                ChatFolderPills(
                                    chips =
                                        listOf(
                                            chip("work", ":marmot: Work", 3),
                                            chip("friends", "Friends :party:", 0),
                                            chip("study", "📚 Study", 1),
                                        ),
                                    selectedFolderId = "work",
                                    onSelect = {},
                                    onEditFolder = {},
                                    onFolders = null,
                                )
                                WhiteNoiseTextField(
                                    state = TextFieldState(":marmot: Work :party:"),
                                    emojiAction = true,
                                    label = { Text("Folder name") },
                                    lineLimits = TextFieldLineLimits.SingleLine,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                )
                                Row(
                                    Modifier.padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Avatar(":party:", "seed-a", 48.dp)
                                    Avatar(":marmot: Alice", "seed-b", 48.dp)
                                    Avatar("📚", "seed-c", 48.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag(ROOT).captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun chip(
        id: String,
        label: String,
        count: Int,
    ) = ChatFolderChipModel(folderId = id, systemKind = null, customLabel = label, trailingCount = count)

    private fun manageItem(
        id: String,
        name: String,
        count: Int,
        description: String,
    ) = ChatFolderManageItem(id, name, null, count, true, true, description)

    private fun localEmoji(): CustomEmojiSet {
        val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFEF6C00.toInt()) }
        return CustomEmojiSet(listOf(CustomEmoji(":party:", File("party.png"), image.asImageBitmap())))
    }

    private companion object {
        const val ROOT = "custom_emoji_parity"
    }
}
