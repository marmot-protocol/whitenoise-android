package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.AdaptiveContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Target chrome snapshots supplement real persisted-store and callback tests; fixtures never claim native success. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatOrganizationScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun selectionLight() = selection("chat_selection_light")

    @Test fun selectionDarkAllSelected() = selection("chat_selection_dark_all", dark = true, all = true)

    @Test fun selectionAmoled() = selection("chat_selection_amoled", dark = true, amoled = true)

    @Test fun selectionMenu() = selection("chat_selection_menu", menu = true)

    @Test
    @Config(qualifiers = "en-w320dp-h480dp-mdpi")
    fun selectionShortRtlLargeText() = selection("chat_selection_short_rtl_large", scale = 2f, rtl = true)

    @Test fun folderMixedAndRule() = folder("chat_folder_picker_mixed_rule")

    @Test fun folderAmoledLargeText() = folder("chat_folder_picker_amoled_large", amoled = true, scale = 2f)

    @Test fun plainFab() = fab("chats_fab_plain", missing = false)

    @Test fun warningFab() = fab("chats_fab_warning", missing = true)

    @Test
    @Config(qualifiers = "en-w840dp-h900dp-mdpi")
    fun expandedAmoledFab() = fab("chats_fab_expanded_amoled", missing = true, amoled = true)

    @Suppress("LongParameterList")
    private fun selection(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        all: Boolean = false,
        menu: Boolean = false,
        scale: Float = 1f,
        rtl: Boolean = false,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = scale) {
                    Scaffold(topBar = { ChatListSelectionBar {} }, bottomBar = {
                        AdaptiveContent { chatSelectionFixture(all = all, single = menu, count = if (menu) 1 else 2) }
                    }) { padding -> Box(Modifier.fillMaxSize().padding(padding)) }
                }
            }
        }
        if (menu) composeRule.onNodeWithContentDescription(context.getString(R.string.actions)).performClick()
        if (menu) {
            composeRule.onNodeWithText(context.getString(R.string.archive)).assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.delete)).assertIsDisplayed()
            // Popup semantics capture can crop the activity window; draw the actual native popup root.
            val popup = checkNotNull(composeRule.onNode(isPopup()).fetchSemanticsNode().root as? View).rootView
            check(popup.javaClass.name == "androidx.compose.ui.window.PopupLayout")
            popup.captureRoboImage("src/test/snapshots/$name.png")
        } else {
            composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
        }
    }

    private fun folder(
        name: String,
        amoled: Boolean = false,
        scale: Float = 1f,
    ) {
        context
            .getSharedPreferences("whitenoise.chat_folders", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val app = chatOrganizationAppState(context)
        val folder = app.chatFolderPreferences.createFolder("alice", "Project conversations")!!
        app.chatFolderPreferences.setChatInFolder("alice", folder.id, "g1", true)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = amoled, amoled = amoled, fontScale = scale) {
                ChatFolderPickerSheet(app, listOf("g1", "g2"), {}, {}, setOf(folder.id))
            }
        }
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun fab(
        name: String,
        missing: Boolean,
        amoled: Boolean = false,
    ) {
        val app = chatOrganizationAppState(context)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = amoled, amoled = amoled) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomEnd) {
                        ChatsNewMessageFabContent(app, missing) {}
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
