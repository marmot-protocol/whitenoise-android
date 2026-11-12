package dev.ipf.whitenoise.android.ui.group

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A group member's key, as subtitle or nameless title, copies the whole key while the row still picks (#3565). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class GroupMemberKeyCopyTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** In the transfer-admin sheet the key copies without picking, and the member's name still picks. */
    @Test
    fun transferAdminKeyCopiesWithoutPickingTheMember() {
        val picked = showTransferAdminSheet(peerName = PEER_NAME)

        composeRule.onNodeWithTag("transfer_admin.key.$PEER", useUnmergedTree = true).performClick()
        assertEquals(PEER_NPUB, clipText())
        assertEquals(emptyList<String>(), picked)
        composeRule.onNode(hasText(PEER_NAME), useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(PEER), picked)
    }

    /**
     * A nameless member is titled by their own short key: that title copies without picking, no duplicate key line is
     * shown, and the rest of the row still picks.
     */
    @Test
    fun transferAdminKeyTitleCopiesWithoutPickingTheMember() {
        val picked = showTransferAdminSheet(peerName = null)

        composeRule
            .onAllNodes(hasText(PEER_NPUB.take(10), substring = true), useUnmergedTree = true)
            .assertCountEquals(1)
        composeRule.onNodeWithTag("transfer_admin.key.$PEER", useUnmergedTree = true).performClick()
        assertEquals(PEER_NPUB, clipText())
        assertEquals(emptyList<String>(), picked)
        composeRule
            .onNode(
                hasClickAction() and hasAnyDescendant(hasTestTag("transfer_admin.key.$PEER")),
                useUnmergedTree = true,
            ).performTouchInput { click(centerLeft + Offset(1f, 0f)) }
        composeRule.waitForIdle()
        assertEquals(listOf(PEER), picked)
    }

    /** Shows the transfer-admin sheet with one peer candidate named [peerName], returning the picks it records. */
    private fun showTransferAdminSheet(peerName: String?): List<String> {
        val state = appState(peerName)
        val controller =
            ConversationController(
                appState = state,
                initialGroup = conversationTimelineTestGroup(),
                initialMemberSnapshot =
                    GroupMemberSnapshot(
                        listOf(
                            AppGroupMemberRecordFfi(SELF, account = SELF_REF, local = true),
                            AppGroupMemberRecordFfi(PEER, account = null, local = false),
                        ),
                    ),
            )
        val picked = mutableListOf<String>()
        composeRule.setContent {
            WhiteNoiseTheme {
                TransferAdminSheet(
                    controller,
                    state,
                    busy = false,
                    onPick = { picked += it.memberIdHex },
                    onDismiss = {},
                )
            }
        }
        composeRule.waitUntil(5_000) {
            state.npubForDisplay(PEER) == PEER_NPUB && (peerName == null || state.displayName(PEER) == peerName)
        }
        composeRule.waitForIdle()
        return picked
    }

    /** The clipboard's current text. */
    private fun clipText(): String? =
        context
            .getSystemService(ClipboardManager::class.java)
            .primaryClip
            ?.getItemAt(0)
            ?.text
            ?.toString()

    /** An app state whose only account is self, with the peer's key and [peerName] resolved through the readers. */
    private fun appState(peerName: String?) =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence),
            accountIdHexResolver = { null },
            accounts = listOf(AccountSummaryFfi(SELF_REF, SELF, true, false, false, true, false)),
            activeAccountRef = SELF_REF,
            profileReader = { null },
            profileDisplayNameReader = { hex -> peerName.takeIf { hex == PEER } },
            npubPresentationReader = { hex -> PEER_NPUB.takeIf { hex == PEER } },
        )

    private companion object {
        val SELF = ConversationTimelineTestIds.ACCOUNT_ID
        val PEER = "9".repeat(64)
        const val SELF_REF = "alice"
        const val PEER_NAME = "Bob"
        const val PEER_NPUB = "npub1qqqqqqqqqqqqqqqqqqqqqppppppppqqqqqqqqqqqqqqqqqqqqqqqkrvh2t"

        object EmptyDraftPersistence : DraftPersistence {
            /** No stored drafts. */
            override fun read(): Map<String, String> = emptyMap()

            /** Discards every draft write. */
            override fun write(
                key: String,
                value: String?,
            ) = Unit
        }
    }
}
