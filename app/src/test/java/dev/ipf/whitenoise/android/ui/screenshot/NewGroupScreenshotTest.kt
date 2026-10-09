package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.ui.chats.newchat.GroupCreationPerson
import dev.ipf.whitenoise.android.ui.chats.newchat.NewGroupDraft
import dev.ipf.whitenoise.android.ui.chats.newchat.NewGroupRecipientActions
import dev.ipf.whitenoise.android.ui.chats.newchat.NewGroupRecipientContent
import dev.ipf.whitenoise.android.ui.chats.newchat.NewGroupSetupActions
import dev.ipf.whitenoise.android.ui.chats.newchat.NewGroupSetupContent
import dev.ipf.whitenoise.android.ui.chats.newchat.NewGroupSetupPresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Presentation fixtures only; no screenshot establishes native group creation, relays or encrypted-image readiness. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h1000dp-mdpi")
class NewGroupScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Prototype unselected list and pinned solo action. */
    @Test fun peopleLight() = picker("new_group_people_light.png")

    /** Selected chips, connected surfaces and bottom Continue in dark mode. */
    @Test fun selectedDark() = picker("new_group_selected_dark.png", selected = true, dark = true)

    /** AMOLED action and row boundaries remain visible. */
    @Test fun selectedAmoled() = picker("new_group_selected_amoled.png", selected = true, dark = true, amoled = true)

    /** Real empty discovery retains solo-group creation. */
    @Test fun empty() = picker("new_group_empty.png", empty = true)

    /** Partial discovery keeps people and exposes recovery. */
    @Test fun partial() = picker("new_group_partial.png", partial = true)

    @Test fun addressFailedLight() = picker("new_group_address_failed_light.png", addressFailed = true)

    @Test fun addressFailedDark() = picker("new_group_address_failed_dark.png", addressFailed = true, dark = true)

    @Test fun addressFailedAmoled() =
        picker(
            "new_group_address_failed_amoled.png",
            addressFailed = true,
            dark = true,
            amoled = true,
        )

    @Test fun addressFailedLargeRtl() =
        picker(
            "new_group_address_failed_large_rtl.png",
            addressFailed = true,
            largeRtl = true,
        )

    /** Large RTL chips and field retain scrolling and native directionality. */
    @Test fun selectedLargeRtl() = picker("new_group_selected_large_rtl.png", selected = true, largeRtl = true)

    /** Prototype centered avatar and two-field setup. */
    @Test fun setupLight() = setup("group_setup_light.png")

    /** Native read-only membership styling in dark setup. */
    @Test fun setupDark() = setup("group_setup_dark.png", dark = true)

    /** AMOLED setup and primary action. */
    @Test fun setupAmoled() = setup("group_setup_amoled.png", dark = true, amoled = true)

    /** Native image work gets feedback and disabled competing controls. */
    @Test fun setupPreparing() = setup("group_setup_preparing.png", preparing = true)

    /** Restored prepared photo is truthfully unavailable until reselected. */
    @Test fun setupRestoredPhoto() = setup("group_setup_reselect_photo.png", reselect = true)

    /** Accepted canonical ID is distinct from a failed create. */
    @Test fun setupCanonicalRecovery() = setup("group_setup_canonical_recovery.png", canonical = true)

    /** Large RTL setup uses the same root font scale as dialogs and other destinations. */
    @Test fun setupLargeRtl() = setup("group_setup_large_rtl.png", largeRtl = true)

    /** Recipient and actionable recovery remain readable beside the retained draft. */
    @Test fun setupRecipientRecoveryLight() = setup("group_setup_recipient_recovery_light.png", recovery = true)

    /** Dark recovery uses the same named error and deliberate actions. */
    @Test fun setupRecipientRecoveryDark() =
        setup(
            "group_setup_recipient_recovery_dark.png",
            recovery = true,
            dark = true,
        )

    /** Long duplicate names and public-key disambiguation wrap without obscuring the actions. */
    @Test fun setupRecipientRecoveryLargeRtl() =
        setup(
            "group_setup_recipient_recovery_large_rtl.png",
            recovery = true,
            largeRtl = true,
        )

    /** A keyboard-compressed visible viewport keeps recovery scrollable and Retry pinned. */
    @Test
    @Config(qualifiers = "en-w360dp-h400dp-mdpi")
    fun setupRecipientRecoveryShortViewport() = setup("group_setup_recipient_recovery_short.png", recovery = true)

    /** Unknown identity offers manual editing without an invented recipient removal. */
    @Test fun setupUnattributedRecovery() = setup("group_setup_unattributed_recovery.png", unknown = true)

    /** The real picker composable renders controlled display states, never a fake search provider. */
    private fun picker(
        file: String,
        selected: Boolean = false,
        dark: Boolean = false,
        amoled: Boolean = false,
        empty: Boolean = false,
        partial: Boolean = false,
        largeRtl: Boolean = false,
        addressFailed: Boolean = false,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (largeRtl) 2f else 1f) {
                    NewGroupRecipientContent(
                        TextFieldState(if (addressFailed) "ada@example.com" else ""),
                        when {
                            empty -> emptyList()
                            addressFailed -> people().take(1).map { it.copy(subtitle = "Address not verified") }
                            else -> people()
                        },
                        if (selected) people().take(2) else emptyList(),
                        false,
                        false,
                        partial,
                        NewGroupRecipientActions({}, {}, {}, {}, {}, {}, {}, {}),
                        isValidNpub = { true },
                        addressLookupFailed = addressFailed,
                    )
                }
            }
        }
        composeRule.onNodeWithTag("new_group.screen").captureRoboImage("src/test/snapshots/$file")
    }

    /** Production setup renderer with native-operation display fixtures and no simulated success transitions. */
    private fun setup(
        file: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        largeRtl: Boolean = false,
        preparing: Boolean = false,
        reselect: Boolean = false,
        canonical: Boolean = false,
        recovery: Boolean = false,
        unknown: Boolean = false,
    ) {
        val draft =
            NewGroupDraft(
                TextFieldState("Weekend plans"),
                TextFieldState("A place to coordinate our next trip."),
                retryGroupIdHex = if (canonical) "canonical" else null,
                imageNeedsReselection = reselect,
            )
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (largeRtl) 2f else 1f) {
                    NewGroupSetupContent(
                        draft,
                        NewGroupSetupPresentation(
                            people(),
                            null,
                            preparing,
                            false,
                            !preparing && !canonical,
                            !preparing && !reselect,
                            false,
                            null,
                            when {
                                canonical -> "The local group view is unavailable. Try opening again."
                                recovery -> "This person has not published their messaging setup."
                                unknown -> "Could not create the group. Please try again."
                                else -> null
                            },
                            "Off",
                            false,
                            recoveryRecipient =
                                if (recovery) "Alexandra Margaret Hamilton · npub1abcd…wxyz" else null,
                            hasUnacceptedFailure = recovery || unknown,
                        ),
                        NewGroupSetupActions({}, {}, {}, {}, {}),
                    )
                }
            }
        }
        composeRule.onNodeWithTag("group_setup.screen").captureRoboImage("src/test/snapshots/$file")
    }

    /** Synthetic display identities stay inside test code only. */
    private fun people() =
        listOf("Ada Lovelace", "Grace Hopper", "Margaret Hamilton").mapIndexed { i, name ->
            GroupCreationPerson(RecipientSearch.Candidate("${i + 1}".repeat(64), name, "npub$i"), "npub1…$i", null)
        }
}
