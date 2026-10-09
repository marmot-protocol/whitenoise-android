package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The labeled Select all action remains visible at compact widths and across the supported themes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatListSelectionBarScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Multiple selected chats in light theme. */
    @Test fun multipleLight() = capture("selection_actions_multiple_light")

    /** Multiple selected chats in dark theme. */
    @Test fun multipleDark() = capture("selection_actions_multiple_dark", dark = true)

    /** Multiple selected chats in AMOLED theme. */
    @Test fun multipleAmoled() = capture("selection_actions_multiple_amoled", dark = true, amoled = true)

    /** One selected chat exposes its conditional commands. */
    @Test fun single() = capture("selection_actions_single", single = true)

    /** Narrow screen still wraps every command into reachable rows. */
    @Test
    @Config(sdk = [36], qualifiers = "en-w280dp-h700dp-mdpi")
    fun narrow() = capture("selection_actions_narrow", single = true)

    /** Long labels and 200 percent text in right-to-left layout. */
    @Test
    @Config(sdk = [36], qualifiers = "ar-ldrtl-w360dp-h780dp-mdpi")
    fun rtlLarge() = capture("selection_actions_rtl_large", single = true, rtl = true, large = true)

    @Test
    fun groupDepartureLight() =
        capture(
            "selection_actions_departure_light",
            departureLabel = R.string.leave_and_delete,
        )

    @Test
    fun groupDepartureDark() =
        capture(
            "selection_actions_departure_dark",
            dark = true,
            departureLabel = R.string.leave_and_delete,
        )

    @Test
    fun groupDepartureAmoled() =
        capture(
            "selection_actions_departure_amoled",
            dark = true,
            amoled = true,
            departureLabel = R.string.leave_and_delete,
        )

    @Test
    @Config(sdk = [36], qualifiers = "ar-ldrtl-w360dp-h780dp-mdpi")
    fun groupDepartureLargeRtl() =
        capture(
            "selection_actions_departure_large_rtl",
            rtl = true,
            large = true,
            departureLabel = R.string.leave_and_delete,
        )

    @Test
    fun directLocalDelete() = capture("selection_actions_direct_delete", departureLabel = R.string.delete_from_device)

    @Test
    @Config(sdk = [36], qualifiers = "en-w280dp-h700dp-mdpi")
    fun groupDepartureNarrow() =
        capture(
            "selection_actions_departure_narrow",
            single = true,
            departureLabel = R.string.leave_and_delete,
        )

    /** Captures the actual controls with deterministic eligibility and no account content. */
    @Suppress("LongParameterList")
    private fun capture(
        name: String,
        single: Boolean = false,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        large: Boolean = false,
        departureLabel: Int? = null,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (large) 2f else 1f) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        ChatListSelectionControls(
                            count = if (single) 1 else 4,
                            deleteLabel = stringResource(departureLabel ?: R.string.delete),
                            archiveAction = ChatListBulkArchiveAction.Archive,
                            actionsEnabled = true,
                            allVisibleSelected = false,
                            showMarkRead = single,
                            showMarkUnread = false,
                            showMuteToggle = single,
                            muted = false,
                            showPinToggle = single,
                            pinned = false,
                            showMovePinnedUp = false,
                            showMovePinnedDown = false,
                            onArchive = {},
                            onDelete = {},
                            onAddToFolder = {},
                            onMarkRead = {},
                            onMarkUnread = {},
                            onMuteToggle = {},
                            onPinToggle = {},
                            onMovePinned = {},
                            onSelectAll = {},
                            onDeselectAll = {},
                        )
                    }
                }
            }
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val selectAllLabel = context.getString(R.string.chat_list_select_all)
        composeRule.onNodeWithText(selectAllLabel, useUnmergedTree = true).assertIsDisplayed()
        departureLabel?.let { composeRule.onNodeWithContentDescription(context.getString(it)).assertIsDisplayed() }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
