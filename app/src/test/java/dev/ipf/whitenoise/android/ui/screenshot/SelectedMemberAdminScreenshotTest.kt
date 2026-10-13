package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.ConversationTimelineTestDraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.newchat.SelectedMembersReviewScreen
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.Check
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class SelectedMemberAdminScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun mixedRolesLight() = capture("invite_roles_light")

    @Test fun mixedRolesDark() = capture("invite_roles_dark", dark = true)

    @Test fun mixedRolesAmoled() = capture("invite_roles_amoled", dark = true, amoled = true)

    @Test fun pendingRolesDisabled() = capture("invite_roles_busy", dark = true, busy = true)

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h1000dp-mdpi")
    fun mixedRolesLargeRtl() = capture("invite_roles_large_rtl", rtl = true)

    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        busy: Boolean = false,
        rtl: Boolean = false,
    ) {
        val people =
            listOf(
                RecipientSearch.Candidate("aa".repeat(32), "Alexandria Example", "npub1alexandria"),
                RecipientSearch.Candidate("bb".repeat(32), "Benjamin Example", "npub1benjamin"),
            )
        val state =
            WhiteNoiseAppState(
                context = ApplicationProvider.getApplicationContext<android.content.Context>(),
                draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
                accountIdHexResolver = { null },
                accounts = listOf(AccountSummaryFfi("alice", "ee".repeat(32), true, false, false, true)),
                activeAccountRef = "alice",
            )
        composeRule.setContent {
            val layout = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides layout) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 2f else 1f) {
                    SelectedMembersReviewScreen(
                        members = people,
                        appState = state,
                        busy = busy,
                        onBack = {},
                        onRemove = {},
                        onConfirm = {},
                        confirmIcon = Icons.Default.Check,
                        confirmLabel = stringResource(R.string.add_member),
                        initialAdminIds = setOf(people.first().accountIdHex),
                        onInitialAdminChange = { _, _ -> },
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
