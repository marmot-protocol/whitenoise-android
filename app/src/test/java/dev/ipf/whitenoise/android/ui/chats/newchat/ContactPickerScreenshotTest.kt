package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTopBarNavigation
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.share.testAccount
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The people picker on the shared top bar: start-aligned title, selection count and the theme's Back arrow. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ContactPickerScreenshotTest {
    @get:Rule val rule = createComposeRule()

    /** Light theme: the count sits under the title and Back leads the bar. */
    @Test fun pickerLight() = capture("contact_picker_light")

    /** AMOLED keeps the bar on the black canvas with the same title and Back arrow. */
    @Test fun pickerAmoled() = capture("contact_picker_amoled", dark = true, amoled = true)

    /** Large RTL text mirrors the bar: Back and the start-aligned title move to the right. */
    @Test fun pickerLargeRtl() = capture("contact_picker_large_rtl", rtl = true)

    /** The shared bar's Back runs the caller's callback once. */
    @Test
    fun backRunsTheCallerCallback() {
        var backs = 0
        setPicker(onBack = { backs += 1 })
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    /** Busy submission disables Back so the picker cannot leave mid-commit. */
    @Test
    fun busyDisablesBack() {
        var backs = 0
        setPicker(onBack = { backs += 1 }, busy = true)
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(0, backs)
    }

    /** A picker that overlays a chat, like Share user, leads with Close and still returns through its callback. */
    @Test
    fun closeNavigationIsLabelledClose() {
        var closes = 0
        setPicker(onBack = { closes += 1 }, navigation = WhiteNoiseTopBarNavigation.Close)
        rule.onNodeWithContentDescription("Back").assertDoesNotExist()
        rule.onNodeWithContentDescription("Close").performClick()
        assertEquals(1, closes)
    }

    /** Renders the picker with one selected person and records the window. */
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
    ) {
        setPicker(dark = dark, amoled = amoled, rtl = rtl)
        rule.onNodeWithText("Choose people").assertExists()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Hosts the picker over an empty account with Alice already selected. */
    private fun setPicker(
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        busy: Boolean = false,
        onBack: () -> Unit = {},
        navigation: WhiteNoiseTopBarNavigation = WhiteNoiseTopBarNavigation.Back,
    ) {
        val state = emptyAppState(accounts = listOf(testAccount("Owner", "a".repeat(64))), activeAccountRef = "Owner")
        val selected =
            mutableStateListOf(
                RecipientSearch.Candidate(
                    REVIEW_FIRST_MEMBER_HEX,
                    "Alice",
                    REVIEW_FIRST_MEMBER_NPUB,
                    searchProfile = UserProfileMetadataFfi("Alice", null, null, null, null, null, null),
                ),
            )
        rule.setContent {
            val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 1.6f else 1f) {
                    ContactPickerScreen(
                        appState = state,
                        title = "Choose people",
                        selected = selected,
                        onBack = onBack,
                        onConfirm = {},
                        busy = busy,
                        navigation = navigation,
                    )
                }
            }
        }
    }
}
