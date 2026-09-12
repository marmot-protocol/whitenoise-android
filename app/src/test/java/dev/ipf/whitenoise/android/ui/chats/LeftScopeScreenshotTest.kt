package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
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

/** Actual completed native list projection renders Left rows and empty copy through the production screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class LeftScopeScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun lightRows() = capture("left_scope_light")

    @Test fun darkRows() = capture("left_scope_dark", dark = true)

    @Test fun amoledRows() = capture("left_scope_amoled", dark = true, amoled = true)

    @Test fun largeRtlRows() = capture("left_scope_rtl_200", dark = true, rtl = true)

    @Test fun empty() = capture("left_scope_empty", empty = true)

    @Test fun emptyRtl() = capture("left_scope_empty_rtl_200", empty = true, rtl = true)

    @Test fun emptySearch() = capture("left_scope_empty_search", empty = true, query = "missing")

    @Test
    @Config(sdk = [36], qualifiers = "en-w360dp-h780dp-xxhdpi")
    fun highDensity() = capture("left_scope_amoled_xxhdpi", dark = true, amoled = true)

    @Suppress("LongParameterList")
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        empty: Boolean = false,
        query: String = "",
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context
            .getSharedPreferences("whitenoise.chat_folders", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val app = ChatRowPortFixtures.state(context)
        val rows =
            listOf(leftScopeRow("Current group")) +
                if (empty) {
                    emptyList()
                } else {
                    listOf(
                        leftScopeRow("Project archive", SelfMembershipFfi.LEFT),
                        leftScopeRow("Former study group", SelfMembershipFfi.REMOVED),
                    )
                }
        val controller = leftScopeController(app, rows)
        try {
            composeRule.setContent {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 2f else 1f) {
                    CompositionLocalProvider(
                        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    ) {
                        ChatsScreen(
                            appState = app,
                            controller = controller,
                            onOpenSettings = {},
                            onOpenGroup = { _, _, _, _ -> },
                            globalSearchState = GlobalSearchState(isOpen = query.isNotEmpty(), query = query),
                            chatScope = ChatScope.Left,
                            onSelectScope = {},
                        )
                    }
                }
            }
            composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
        } finally {
            controller.onCleared()
            app.mutationsScope.cancel()
        }
    }
}
