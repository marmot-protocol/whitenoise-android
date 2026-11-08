package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.ManageStorageUiState
import dev.ipf.whitenoise.android.state.StorageUsage
import dev.ipf.whitenoise.android.state.StoredAttachment
import dev.ipf.whitenoise.android.state.StoredAttachmentsUiState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What TalkBack hears on Manage Storage: the usage sentence, row selection, select-all and list retry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ManageStorageSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The usage panel reads as one sentence naming the total and every category with its size. */
    @Test fun usagePanelReadsOneSentenceWithEveryCategory() {
        val usage = StorageUsage(2_000_000, 3, 500_000, 0, 100_000, 50_000)
        composeRule.setContent {
            WhiteNoiseTheme { StorageUsagePanel(usage) }
        }
        val description =
            composeRule
                .onNodeWithTag("storage.usage")
                .fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription]
                .single()
        val size = { bytes: Long -> formatStorageBytes(context, bytes) }
        listOf(
            "Storage used: ${size(usage.totalBytes)}",
            "Attachments ${size(usage.attachmentBytes)}",
            "Messages and chat data ${size(usage.chatDataBytes)}",
            "Temporary files ${size(usage.temporaryBytes)}",
        ).forEach { assert(description.contains(it)) { "$it missing from $description" } }
        assert(!description.contains("Unused database space")) { "Empty unused space must be left out" }
    }

    /** A selected attachment row reports its selected state, and select-all names its effect. */
    @Test fun attachmentRowsReportSelectionAndSelectAllNamesItsEffect() {
        val rows = listOf(attachment("a", 9_000_000), attachment("b", 4_000_000))
        renderList(StoredAttachmentsUiState(items = rows, complete = true, selected = setOf(rows.first().key)))
        val nodes = composeRule.onAllNodesWithTag("storage.attachment", useUnmergedTree = true)
        nodes[0].assertIsSelected()
        nodes[1].assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        composeRule.onNodeWithContentDescription("Select all").assertExists()
    }

    /** A failed page offers Try again, which resumes the list. */
    @Test fun failedListOffersRetry() {
        var retries = 0
        renderList(StoredAttachmentsUiState(items = listOf(attachment("a", 1)), failed = true), onRetry = { retries++ })
        composeRule.onNodeWithTag("storage.list.retry").performClick()
        assertEquals(1, retries)
    }

    /** Renders the account-wide attachment list for [list]. */
    private fun renderList(
        list: StoredAttachmentsUiState,
        onRetry: () -> Unit = {},
    ) {
        val appState = appState()
        composeRule.setContent {
            WhiteNoiseTheme {
                StoredAttachmentsContent(
                    appState = appState,
                    storage = ManageStorageUiState(),
                    list = list,
                    groupIdHex = null,
                    onBack = {},
                    actions = StoredAttachmentsActions({}, {}, {}, onRetry, {}),
                )
            }
        }
    }

    /** One signed-in account on cleared preferences. */
    private fun appState(): WhiteNoiseAppState {
        val preferences = context.getSharedPreferences("manage-storage-semantics", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = "account-a",
                        accountIdHex = "ab".repeat(32),
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                        unlinked = false,
                    ),
                ),
            activeAccountRef = "account-a",
            preferences = preferences,
        )
    }

    /** A photo attachment of [bytes] in one chat. */
    private fun attachment(
        message: String,
        bytes: Long,
    ) = StoredAttachment("g".repeat(64), message, message, 0, bytes, "image/jpeg", 0)
}
