package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.MissingRelayListKindFfi
import dev.ipf.marmotkit.RelayListFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.RelayListKind
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class RelayListSettingsContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun configuredRelayAppearsOnceWithCompleteStatus() {
        val relay = "wss://post.example.com"

        render(relayLists(nip65 = listOf(relay), inbox = listOf("wss://inbox.example.com")))

        composeRule.onAllNodesWithText(relay).assertCountEquals(1)
        assertEquals("My relays", app.getString(R.string.account_relay_lists))
        composeRule.onNodeWithText(app.getString(R.string.all_relay_lists_published)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.relay_posting_help)).assertIsDisplayed()
    }

    @Test
    fun missingStatusNamesDirectionAndDirectionHelpChanges() {
        val lists =
            relayLists(
                nip65 = listOf("wss://post.example.com"),
                inbox = emptyList(),
                missing = listOf(MissingRelayListKindFfi.INBOX),
            )

        render(lists)

        composeRule
            .onNodeWithText(app.getString(R.string.missing_relay_lists, app.getString(R.string.inbox)))
            .assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.inbox)).performClick()
        composeRule.onNodeWithText(app.getString(R.string.relay_inbox_help)).assertIsDisplayed()
    }

    @Test
    fun relayRemovalIsDisabledWhenEditingIsUnavailable() {
        val lists = relayLists(nip65 = listOf("wss://one.example.com", "wss://two.example.com"), inbox = emptyList())

        render(lists, canEdit = false)

        composeRule
            .onAllNodesWithContentDescription(app.getString(R.string.remove_relay))[0]
            .assertIsNotEnabled()
    }

    private fun render(
        lists: AccountRelayListsFfi,
        canEdit: Boolean = true,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    Column(Modifier.padding(16.dp)) {
                        var selectedKind by remember { mutableStateOf(RelayListKind.Nip65) }
                        RelayListSettingsContent(
                            lists = lists,
                            selectedKind = selectedKind,
                            onSelectKind = { selectedKind = it },
                            pendingUrl = "",
                            onPendingUrlChange = {},
                            saving = false,
                            canEdit = canEdit,
                            onUpdateRelays = { _, _ -> },
                        )
                    }
                }
            }
        }
    }
}

internal fun relayLists(
    nip65: List<String>,
    inbox: List<String>,
    missing: List<MissingRelayListKindFfi> = emptyList(),
): AccountRelayListsFfi =
    AccountRelayListsFfi(
        complete = missing.isEmpty(),
        missing = missing,
        defaultRelays = emptyList(),
        bootstrapRelays = emptyList(),
        nip65 = RelayListFfi(kind = 10_002uL, relays = nip65),
        inbox = RelayListFfi(kind = 10_050uL, relays = inbox),
    )
