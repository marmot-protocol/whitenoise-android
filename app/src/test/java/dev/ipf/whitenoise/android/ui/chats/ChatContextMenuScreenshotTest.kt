package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual Chats row callback opens the popup; native membership determines the commands pictured. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatContextMenuScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun light() = capture("chat_context_menu_light")

    @Test fun dark() = capture("chat_context_menu_dark", dark = true)

    @Test fun amoled() = capture("chat_context_menu_amoled", dark = true, amoled = true)

    @Test fun largeRtl() = capture("chat_context_menu_rtl_200", rtl = true)

    @Test fun endedMembership() = capture("chat_context_menu_left", left = true)

    @Test
    @Config(sdk = [36], qualifiers = "en-w360dp-h780dp-xxhdpi")
    fun highDensity() = capture("chat_context_menu_amoled_xxhdpi", dark = true, amoled = true)

    @Test
    @Config(sdk = [36], qualifiers = "en-w640dp-h300dp-land-mdpi")
    fun shortLandscapeLargeRtl() = capture("chat_context_menu_landscape_rtl_200", rtl = true)

    @Suppress("LongParameterList")
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        left: Boolean = false,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context
            .getSharedPreferences("whitenoise.chat_folders", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val app = ChatRowPortFixtures.state(context)
        val membership = if (left) SelfMembershipFfi.LEFT else SelfMembershipFfi.MEMBER
        val controller = leftScopeController(app, listOf(leftScopeRow("Study group", membership)))
        try {
            composeRule.setContent {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 2f else 1f) {
                    CompositionLocalProvider(
                        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    ) {
                        ChatsScreen(app, controller, {}, { _, _, _, _ -> })
                    }
                }
            }
            composeRule
                .onNodeWithTag("chat.row.Study group")
                .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
            composeRule.onNodeWithTag("chat.menu.Study group").assertIsDisplayed()
            composeRule
                .onNode(isPopup(), useUnmergedTree = true)
                .captureRoboImage("src/test/snapshots/$name.png")
        } finally {
            controller.onCleared()
            app.mutationsScope.cancel()
        }
    }
}
