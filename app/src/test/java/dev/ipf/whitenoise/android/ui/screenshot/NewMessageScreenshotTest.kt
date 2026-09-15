package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageActions
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageContent
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessagePerson
import dev.ipf.whitenoise.android.ui.chats.newchat.RecipientUserSearchState
import dev.ipf.whitenoise.android.ui.chats.newchat.StartChatErrorUiState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Presentation fixtures only; native search/creation acceptance is tested separately and never inferred from these. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h1000dp-mdpi")
class NewMessageScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Light grouping and the target action hierarchy. */
    @Test fun groupedLight() = capture("new_message_grouped_light.png")

    /** Dark segmented person rows and following badge. */
    @Test fun groupedDark() = capture("new_message_grouped_dark.png", dark = true)

    /** AMOLED boundaries remain visible without saved accent-color leakage. */
    @Test fun groupedAmoled() = capture("new_message_grouped_amoled.png", dark = true, amoled = true)

    /** Blank discovery remains a usable action page without simulated contacts. */
    @Test fun blank() = capture("new_message_blank.png", query = "", people = emptyList())

    /** Local people remain visible while native search is pending. */
    @Test fun searchingWithLocalPeople() =
        capture(
            "new_message_searching.png",
            search = RecipientUserSearchState(isSearching = true),
        )

    /** Partial discovery offers explicit retry without losing available people. */
    @Test fun partial() =
        capture(
            "new_message_partial.png",
            search = RecipientUserSearchState(isIncomplete = true),
        )

    /** Failed discovery reports the actual incomplete capability with retry. */
    @Test fun failed() = capture("new_message_failed.png", search = RecipientUserSearchState(failed = true))

    /** Terminal empty search uses the shared empty treatment. */
    @Test fun noMatches() = capture("new_message_no_matches.png", people = emptyList())

    /** Canonical creation recovery exposes Open chat and safe diagnostic copying. */
    @Test fun createdRecovery() =
        capture(
            "new_message_created_recovery.png",
            error =
                StartChatErrorUiState(
                    "npub",
                    "hex",
                    AppText.Plain("The chat was created, but its local view is unavailable."),
                    diagnosticReport = "Synthetic safe diagnostic",
                    retryGroupIdHex = "canonical",
                ),
        )

    /** Large RTL status/actions wrap and remain scrollable in the actual destination. */
    @Test fun largeRtlFailure() =
        capture(
            "new_message_large_rtl_failure.png",
            largeRtl = true,
            search = RecipientUserSearchState(failed = true),
        )

    /** Renders caller-projected display state through the production destination and shared theme. */
    private fun capture(
        file: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        largeRtl: Boolean = false,
        query: String = "Ada",
        people: List<NewMessagePerson> = people(),
        search: RecipientUserSearchState = RecipientUserSearchState(),
        error: StartChatErrorUiState? = null,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (largeRtl) 2f else 1f) {
                    NewMessageContent(
                        TextFieldState(query),
                        people,
                        search,
                        false,
                        false,
                        true,
                        null,
                        error,
                        NewMessageActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}),
                    )
                }
            }
        }
        composeRule.onNodeWithTag("new_message.screen").captureRoboImage("src/test/snapshots/$file")
    }

    /** Synthetic render-only people cover each supported provenance with stable public-key labels. */
    private fun people() =
        listOf(
            NewMessagePerson(
                RecipientSearch.Candidate("ada", "Ada Lovelace", "npub1ada", source = RecipientSearch.Source.InDm),
                "npub1ada…2kz",
                null,
            ),
            NewMessagePerson(
                RecipientSearch.Candidate("grace", "Grace Hopper", "npub1grace", isFollowing = true),
                "npub1grace…9sd",
                null,
            ),
            NewMessagePerson(
                RecipientSearch.Candidate("katherine", "Katherine Johnson", "npub1katherine", searchRadius = 2u),
                "npub1katherine…4np",
                null,
            ),
        )
}
