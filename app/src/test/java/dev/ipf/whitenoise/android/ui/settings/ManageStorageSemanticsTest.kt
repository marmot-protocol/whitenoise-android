package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.ManageStorageUiState
import dev.ipf.whitenoise.android.state.StorageUsage
import dev.ipf.whitenoise.android.state.StoredAttachment
import dev.ipf.whitenoise.android.state.StoredAttachmentsUiState
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.lifecycleOwner
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What TalkBack hears on Manage Storage: the usage sentence, row selection, select-all and list retry. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ManageStorageSemanticsTest {
    /** A request accepted on one profile cannot clear caches for its replacement. */
    @Test fun replacingStorageOwnerCancelsAcceptedClear() {
        var owner by mutableStateOf<Any>(Any())
        lateinit var dialogs: StorageDialogs
        val actions = mutableListOf<dev.ipf.whitenoise.android.state.StorageAction>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            WhiteNoiseTheme {
                dialogs = rememberStorageDialogs(owner)
                StorageOverviewDialogs(ManageStorageUiState(), dialogs, { actions.add(it) })
            }
        }
        composeRule.runOnIdle { dialogs.clearCache.value = true }
        advanceTimeBy(400)
        composeRule.onNodeWithTag("confirmation.confirm").performClick()
        composeRule.runOnIdle { owner = Any() }
        advanceTimeBy(800)
        composeRule.runOnIdle {
            assertEquals(emptyList<dev.ipf.whitenoise.android.state.StorageAction>(), actions)
            assertEquals(false, dialogs.clearCache.value)
        }
    }

    /** An accepted removal cannot silently switch to another attachment selection during its exit. */
    @Test fun replacingSelectionCancelsAcceptedRemovalAndNewRequestStillWorks() {
        val rows = listOf(attachment("a", 9_000_000), attachment("b", 4_000_000))
        var list by mutableStateOf(
            StoredAttachmentsUiState(items = rows, complete = true, selected = setOf(rows[0].key)),
        )
        val appState = appState()
        val actions = mutableListOf<dev.ipf.whitenoise.android.state.StorageAction>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            WhiteNoiseTheme {
                StoredAttachmentsContent(
                    appState = appState,
                    storage = ManageStorageUiState(),
                    list = list,
                    groupIdHex = null,
                    onBack = {},
                    actions = StoredAttachmentsActions({}, {}, {}, {}, { actions.add(it) }),
                )
            }
        }
        advanceTimeBy(400)
        composeRule.onNodeWithTag("storage.selection.remove").performClick()
        advanceTimeBy(400)
        composeRule.onNodeWithTag("confirmation.confirm").performClick()
        composeRule.runOnIdle { list = list.copy(selected = setOf(rows[1].key)) }
        advanceTimeBy(800)
        composeRule.runOnIdle { assertEquals(0, actions.size) }
        composeRule.onNodeWithTag("confirmation.confirm").performClick()
        advanceTimeBy(16)
        composeRule.onNodeWithTag("confirmation.confirm").assertDoesNotExist()
        advanceTimeBy(400)
        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    dev.ipf.whitenoise.android.state.StorageAction
                        .RemoveAttachments(listOf(rows[1])),
                ),
                actions,
            )
        }
    }

    /** Publish paused-clock state writes before the frame that consumes them. */
    private fun advanceTimeBy(millis: Long) {
        composeRule.runOnUiThread { Snapshot.sendApplyNotifications() }
        repeat(((millis + 15L) / 16L).toInt()) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
    }

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * The usage header reads this profile's total, and each legend row reads its category and size as its own
     * sentence. Empty unused space is left out.
     */
    @Test fun usagePanelReadsTotalAndEachCategory() {
        val usage = StorageUsage(2_000_000, 3, 500_000, 0, 100_000, 50_000)
        composeRule.setContent {
            WhiteNoiseTheme { StorageUsagePanel(usage) }
        }
        val size = { bytes: Long -> formatStorageBytes(context, bytes) }
        val header = description(composeRule.onNodeWithTag("storage.usage"))
        assert(header.contains("Storage used: ${size(usage.profileBytes)}.")) { header }
        val rows = composeRule.onAllNodesWithTag("storage.legend")
        val spoken = (0 until rows.fetchSemanticsNodes().size).map { description(rows[it]) }
        listOf(
            "Attachments, ${size(usage.attachmentBytes)}, 77%, 3 attachments.",
            "Messages and chat data, ${size(usage.chatDataBytes)}, 19%.",
            "Temporary files, ${size(usage.temporaryBytes)}, Shared by all profiles on this device.",
        ).forEach { expected -> assert(expected in spoken) { "$expected missing from $spoken" } }
        assert(spoken.none { it.contains("Unused database space") }) { "Empty unused space must be left out" }
    }

    /** Tapping a legend row highlights it and the header announces its share, a second tap clears it. */
    @Test fun legendRowTogglesHighlight() {
        val usage = StorageUsage(2_000_000, 3, 500_000, 0, 100_000, 50_000)
        composeRule.setContent {
            WhiteNoiseTheme { StorageUsagePanel(usage) }
        }
        val size = { bytes: Long -> formatStorageBytes(context, bytes) }
        val attachments = composeRule.onAllNodesWithTag("storage.legend")[0]
        attachments.assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        attachments.performClick()
        attachments.assertIsSelected()
        val header = description(composeRule.onNodeWithTag("storage.usage"))
        assert(header.contains(size(usage.attachmentBytes)) && header.contains("Attachments · 77% of")) { header }
        attachments.performClick()
        attachments.assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        assert(description(composeRule.onNodeWithTag("storage.usage")).startsWith("Storage used:"))
    }

    /** On a profile with nothing stored yet, a highlighted profile category reads 0%, never as shared. */
    @Test fun emptyProfileHighlightIsNotShared() {
        composeRule.setContent {
            WhiteNoiseTheme { StorageUsagePanel(StorageUsage(0, 0, 0, 0, 0, 0)) }
        }
        composeRule.onAllNodesWithTag("storage.legend")[0].performClick()
        val header = description(composeRule.onNodeWithTag("storage.usage"))
        assert(header.contains("Attachments · 0% of") && !header.contains("shared")) { header }
    }

    /** A highlighted category that a refresh leaves out stays cleared when it comes back. */
    @Test fun highlightDoesNotReturnAfterItsCategoryLeaves() {
        var usage by mutableStateOf(StorageUsage(2_000_000, 3, 500_000, 40_000, 100_000, 50_000))
        composeRule.setContent {
            WhiteNoiseTheme { StorageUsagePanel(usage) }
        }
        composeRule.onAllNodesWithTag("storage.legend")[2].performClick()
        assert(description(composeRule.onNodeWithTag("storage.usage")).contains("Unused database space"))
        usage = usage.copy(unusedDatabaseBytes = 0)
        composeRule.waitForIdle()
        usage = usage.copy(unusedDatabaseBytes = 40_000)
        composeRule.waitForIdle()
        assert(description(composeRule.onNodeWithTag("storage.usage")).startsWith("Storage used:"))
    }

    /** The single content description a node reads. */
    private fun description(node: SemanticsNodeInteraction): String {
        val config = node.fetchSemanticsNode().config
        return config[SemanticsProperties.ContentDescription].single()
    }

    /** A chosen attachment row reports itself checked, and select-all names its effect. */
    @Test fun attachmentRowsReportSelectionAndSelectAllNamesItsEffect() {
        val rows = listOf(attachment("a", 9_000_000), attachment("b", 4_000_000))
        renderList(StoredAttachmentsUiState(items = rows, complete = true, selected = setOf(rows.first().key)))
        val nodes = composeRule.onAllNodesWithTag("storage.attachment.select")
        nodes[0].assertIsOn()
        nodes[1].assertIsOff()
        composeRule.onNodeWithContentDescription("Select all").assertExists()
    }

    /** System Back first clears a selection and keeps the list open, then leaves the list. */
    @Test fun backClearsSelectionBeforeLeaving() {
        val rows = listOf(attachment("a", 9_000_000), attachment("b", 4_000_000))
        var list by mutableStateOf(
            StoredAttachmentsUiState(items = rows, complete = true, selected = setOf(rows[0].key)),
        )
        var left = 0
        val appState = appState()
        lateinit var activity: ComponentActivity
        composeRule.setContent {
            activity = LocalView.current.context.lifecycleOwner() as ComponentActivity
            BackHandler { left++ }
            WhiteNoiseTheme {
                StoredAttachmentsContent(
                    appState = appState,
                    storage = ManageStorageUiState(),
                    list = list,
                    groupIdHex = null,
                    onBack = {},
                    actions =
                        StoredAttachmentsActions(
                            onToggle = {},
                            onSelectAll = { all -> if (!all) list = list.copy(selected = emptySet()) },
                            onLoadMore = {},
                            onRetry = {},
                            onAction = {},
                        ),
                )
            }
        }
        composeRule.onNodeWithTag("storage.selection").assertExists()
        composeRule.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, left)
        composeRule.onNodeWithTag("storage.selection").assertDoesNotExist()
        composeRule.onAllNodesWithTag("storage.attachment.select")[0].assertIsOff()
        composeRule.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(1, left)
    }

    /** Tapping a row opens its attachment without selecting it; its check mark selects it without opening. */
    @Test fun rowOpensAndCheckMarkSelects() {
        val rows = listOf(attachment("a", 9_000_000))
        val opened = mutableListOf<String>()
        val toggled = mutableListOf<String>()
        val appState = appState()
        composeRule.setContent {
            WhiteNoiseTheme {
                StoredAttachmentsContent(
                    appState = appState,
                    storage = ManageStorageUiState(),
                    list = StoredAttachmentsUiState(items = rows, complete = true),
                    groupIdHex = null,
                    onBack = {},
                    actions =
                        StoredAttachmentsActions(
                            onToggle = { toggled += it },
                            onSelectAll = {},
                            onLoadMore = {},
                            onRetry = {},
                            onAction = {},
                            onOpen = { opened += it.key },
                        ),
                )
            }
        }
        composeRule.onNodeWithTag("storage.attachment").performClick()
        composeRule.runOnIdle { assertEquals(listOf(rows[0].key) to emptyList<String>(), opened to toggled.toList()) }
        composeRule.onNodeWithTag("storage.attachment.select").performClick()
        composeRule.runOnIdle { assertEquals(listOf(rows[0].key) to listOf(rows[0].key), opened to toggled.toList()) }
        composeRule.onNodeWithContentDescription("Select Photo").assertExists()
        composeRule.onNodeWithTag("storage.attachment").performTouchInput { longClick() }
        composeRule.runOnIdle { assertEquals(listOf(rows[0].key, rows[0].key), toggled.toList()) }
        assertEquals(1, opened.size)
        val labels =
            composeRule
                .onNodeWithTag("storage.attachment")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .map { it.label }
        assert("Explain" in labels) { labels }
    }

    /** Before the last page loads, select-all says it selects only the rows shown. */
    @Test fun selectAllNamesItsScopeWhileMoreRowsRemain() {
        val rows = listOf(attachment("a", 9_000_000), attachment("b", 4_000_000))
        renderList(StoredAttachmentsUiState(items = rows, complete = false, selected = setOf(rows.first().key)))
        composeRule.onNodeWithContentDescription("Select all shown").assertExists()
    }

    /** A selection confirmation whose selection empties, as after recreation, closes instead of removing nothing. */
    @Test fun emptiedSelectionClosesItsConfirmation() {
        val rows = listOf(attachment("a", 9_000_000))
        val selected = StoredAttachmentsUiState(items = rows, complete = true, selected = setOf(rows.first().key))
        var list by mutableStateOf(selected)
        val appState = appState()
        composeRule.setContent {
            WhiteNoiseTheme {
                StoredAttachmentsContent(
                    appState = appState,
                    storage = ManageStorageUiState(),
                    list = list,
                    groupIdHex = null,
                    onBack = {},
                    actions = StoredAttachmentsActions({}, {}, {}, {}, {}),
                )
            }
        }
        composeRule.onNodeWithTag("storage.selection.remove").performClick()
        composeRule.onNodeWithText("Remove 1 attachment?").assertExists()
        list = list.copy(selected = emptySet())
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Remove 1 attachment?").assertCountEquals(0)
        composeRule.onAllNodesWithText("Remove 0 attachments?").assertCountEquals(0)
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
