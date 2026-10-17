package dev.ipf.whitenoise.android.ui.group

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.ui.chats.newchat.GroupMemberReviewPair
import dev.ipf.whitenoise.android.ui.chats.newchat.MemberReviewGroup
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class GroupActionIdentityScreenshotTest {
    @get:Rule val rule = createComposeRule()

    /** A member action must identify its recipient, never substitute the acting account. */
    @Test fun memberActionsUseRecipientAndRejectMissingTargets() {
        listOf(
            GroupDetailsConfirmationKind.RemoveMember,
            GroupDetailsConfirmationKind.TransferAdmin,
            GroupDetailsConfirmationKind.TransferThenLeave,
        ).forEach { kind ->
            assertEquals("recipient", groupActionMemberHex(kind, "actor", "recipient"))
            assertEquals(null, groupActionMemberHex(kind, "actor", null))
            assertEquals(null, groupActionMemberHex(kind, "actor", ""))
        }
    }

    /** Unknown bound identity cannot submit a destructive self-action. */
    @Test fun unresolvedSelfIdentityCannotConfirm() {
        var submitted = 0
        rule.setContent {
            WhiteNoiseTheme {
                GroupDetailsConfirmation(
                    model = GroupDetailsConfirmationState.Leave("Garden club") { submitted += 1 },
                    onDismiss = {},
                    confirmEnabled = false,
                )
            }
        }
        rule.onNodeWithTag("confirmation.confirm").assertIsNotEnabled().performClick()
        assertEquals(0, submitted)
    }

    /** Captures the real warning and affected member/group in light mode. */
    @Test fun transferIdentityLight() = capture("light")

    /** Captures the same context in dark mode. */
    @Test fun transferIdentityDark() = capture("dark", dark = true)

    /** Keeps warning, identity and buttons reachable with large RTL text. */
    @Test fun transferIdentityLargeRtl() = capture("large_rtl", rtl = true)

    /** Renders a recipient with a canonical matching key and npub, retaining the existing consequence copy. */
    private fun capture(
        name: String,
        dark: Boolean = false,
        rtl: Boolean = false,
    ) {
        val state = emptyAppState()
        val hex = "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
        val npub = "npub1yqsjygeyy5nzw2pf9g4jctfw9ucrzv3nxs6nvdec8yark0pa8clst3m4tg"
        var submitted = 0
        rule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = if (rtl) 2f else 1f) {
                    GroupDetailsConfirmation(
                        model = GroupDetailsConfirmationState.TransferAdmin("Private alias") { submitted += 1 },
                        onDismiss = {},
                        identity = {
                            GroupMemberReviewPair(
                                appState = state,
                                group = MemberReviewGroup("garden", "Garden club"),
                                member =
                                    RecipientSearch.Candidate(
                                        hex,
                                        "Private alias",
                                        npub,
                                        searchProfile =
                                            UserProfileMetadataFfi("Public Alice", null, null, null, null, null, null),
                                    ),
                            )
                        },
                    )
                }
            }
        }
        rule.onNodeWithText("Public Alice").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Garden club").assertIsDisplayed()
        rule.onNodeWithText("Private alias").assertDoesNotExist()
        rule.onAllNodesWithText(npub).assertCountEquals(0)
        rule.onAllNodesWithText(IdentityFormatter.short(npub, prefix = 10, suffix = 8)).assertCountEquals(1)
        rule.onNodeWithTag("confirmation.confirm").assertIsDisplayed()
        rule.onNode(isDialog()).captureRoboImage("src/test/snapshots/group_action_identity_$name.png")
        rule.onNodeWithTag("confirmation.confirm").performClick()
        assertEquals(1, submitted)
    }
}
