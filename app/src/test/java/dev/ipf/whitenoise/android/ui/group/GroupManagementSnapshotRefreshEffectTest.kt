package dev.ipf.whitenoise.android.ui.group

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.ConversationTimelineTestDraftPersistence
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineMemberSnapshot
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.groupConversationSnapshotFixture
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroupManagementSnapshotRefreshEffectTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun settledRosterTriggersOneNewSnapshotThenEqualNativeRecordsStopRefreshing() {
        var reads = 0
        val active = mutableStateOf(true)
        val state =
            WhiteNoiseAppState(
                context = ApplicationProvider.getApplicationContext<Context>(),
                draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
                accountIdHexResolver = { ConversationTimelineTestIds.ACCOUNT_ID },
                accounts = listOf(AccountSummaryFfi("alice", ConversationTimelineTestIds.ACCOUNT_ID, true, false, false, true)),
                activeAccountRef = "alice",
            )
        val controller =
            ConversationController(
                appState = state,
                initialGroup = conversationTimelineTestGroup(),
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                groupSnapshotReader = { _, _ ->
                    reads++
                    // Fresh FFI objects have equal values, just as repeated native reads do.
                    groupConversationSnapshotFixture("Timeline group", admin = true)
                },
            )
        try {
            composeRule.setContent { if (active.value) GroupManagementSnapshotRefreshEffect(controller) }
            composeRule.waitForIdle()
            assertEquals(2, reads)
            composeRule.runOnIdle { active.value = false }
            composeRule.waitForIdle()
            assertEquals(2, reads)
        } finally {
            controller.onCleared()
        }
    }
}
