package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.EventSizeClassFfi
import dev.ipf.marmotkit.GroupChangeNotDeliveredFfi
import dev.ipf.marmotkit.GroupEventKindFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.OversizedArtifactFfi
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * A group change MDK could not make reaches the user once, says what did not happen and why, and clears MDK's record of
 * it once shown. MDK's own upkeep changes and a change MDK sent again stay silent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroupChangeNoticeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** A queued invite whose Welcome fits none of the invitee's inbox relays reuses the invitation copy. */
    @Test
    fun refusedWelcomeSaysTheInvitationIsTooLarge() {
        val notice = groupChangeNotice(refusal(OversizedArtifactFfi.WELCOME))
        assertNull(notice?.headline)
        assertEquals(AppText.Resource(R.string.error_invite_welcome_too_large), notice?.reason)
    }

    /** A queued commit too large for every group relay, or one MDK did not classify, says the change was not made. */
    @Test
    fun refusedCommitSaysTheGroupChangeWasNotMade() {
        val expected = AppText.Resource(R.string.error_group_change_too_large)
        assertEquals(expected, groupChangeNotice(refusal(OversizedArtifactFfi.COMMIT))?.reason)
        assertEquals(expected, groupChangeNotice(refusal(artifact = null))?.reason)
    }

    /** A queued send the settled group refused names the change and why, keyed by its intent for acknowledgement. */
    @Test
    fun queuedRefusalNamesTheChangeAndTheReason() {
        val notice = groupChangeNotice(refusal(artifact = null, reason = "not_group_admin"))
        assertEquals(AppText.Resource(R.string.group_change_invite_not_sent), notice?.headline)
        assertEquals(AppText.Resource(R.string.group_change_reason_not_admin), notice?.reason)
        assertEquals(INTENT, notice?.changeIdHex)
        assertEquals(INTENT, notice?.queuedIntentIdHex)
        assertNull(notice?.commitIdHex)
    }

    /** A commit MDK gave up after its call returned names its record, and each reason tag has its own sentence. */
    @Test
    fun undeliveredChangeNamesItsRecord() {
        val notice = groupChangeNotice(notDelivered("update_group_data", "not_delivered"))
        assertEquals(AppText.Resource(R.string.group_change_details_not_updated), notice?.headline)
        assertEquals(AppText.Resource(R.string.group_change_reason_not_delivered), notice?.reason)
        assertEquals(COMMIT, notice?.commitIdHex)
        assertEquals(COMMIT, notice?.changeIdHex)
        assertEquals(
            AppText.Resource(R.string.group_change_reason_refused),
            groupChangeNotice(notDelivered("remove_members", "refused"))?.reason,
        )
        assertEquals(
            AppText.Resource(R.string.group_change_not_made),
            groupChangeNotice(notDelivered("enable_account_devices", "refused"))?.headline,
        )
        assertEquals(
            AppText.Resource(R.string.group_change_reason_group_changed),
            groupChangeNotice(notDelivered("leave", "admin_depletion"))?.reason,
        )
    }

    /** MDK's own upkeep changes are not the user's, so they raise nothing. */
    @Test
    fun upkeepChangesAreSilent() {
        assertNull(groupChangeNotice(notDelivered("self_update", "not_delivered")))
        assertNull(groupChangeNotice(notDelivered("add_own_account_devices", "refused")))
        assertNull(groupChangeNotice(notDelivered("self_update", QUEUED_INTENT_REFUSED_TOO_LARGE)))
    }

    /** A replaced commit tells the user unless MDK sent it again; one another change already made says so instead. */
    @Test
    fun supersededChangesTellTheUserUnlessReissued() {
        assertNull(groupChangeNotice(superseded("reissued")))
        val satisfied = groupChangeNotice(superseded("already_satisfied"))
        assertNull(satisfied?.headline)
        assertEquals(AppText.Resource(R.string.group_change_already_done), satisfied?.reason)
        assertEquals(
            AppText.Resource(R.string.group_change_reason_not_member),
            groupChangeNotice(superseded("not_member"))?.reason,
        )
        val conflict = groupChangeNotice(superseded("conflict"))
        assertEquals(AppText.Resource(R.string.group_change_member_not_removed), conflict?.headline)
        assertEquals(AppText.Resource(R.string.group_change_reason_superseded), conflict?.reason)
        // The same commit's record, if MDK also gave it up, is cleared by this one notice.
        assertEquals(COMMIT, conflict?.commitIdHex)
    }

    /** Other events are not group-change notices. */
    @Test
    fun otherEventsAreIgnored() {
        assertNull(
            groupChangeNotice(MarmotEventFfi.GroupEvent(ACCOUNT_HEX, ACCOUNT, GROUP, GroupEventKindFfi.GroupCreated)),
        )
        assertNull(groupChangeNotice(MarmotEventFfi.AccountError(ACCOUNT_HEX, ACCOUNT, "x")))
    }

    /** A retained queued send is keyed by its intent, as its live refusal was, and a direct one by its commit. */
    @Test
    fun retainedRecordsKeepTheLiveKey() {
        val queued = retainedGroupChangeNotice(ACCOUNT, record(queuedIntent = INTENT))
        assertEquals(INTENT, queued?.changeIdHex)
        assertEquals(COMMIT, queued?.commitIdHex)
        assertEquals(COMMIT, retainedGroupChangeNotice(ACCOUNT, record())?.changeIdHex)
        assertNull(retainedGroupChangeNotice(ACCOUNT, record(kind = "self_update")))
    }

    /** A known chat titles the toast over what happened; an unknown one leaves the chat-neutral wording alone. */
    @Test
    fun knownChatTitlesTheToast() {
        val named = groupChangeNotice(refusal(artifact = null, reason = "not_group_admin"))!!
        val (title, detail) = groupChangeToast("Team", named)
        assertEquals(AppText.Plain("Team"), title)
        assertEquals(
            "Invitation not sent. You’re no longer an admin of this group.",
            detail?.resolve(context),
        )
        assertEquals(named.headline to named.reason, groupChangeToast(null, named))
        val size = groupChangeNotice(refusal(OversizedArtifactFfi.COMMIT))!!
        assertEquals(AppText.Plain("Team") to size.reason, groupChangeToast("Team", size))
        assertEquals(size.reason to null, groupChangeToast(null, size))
    }

    /** The active account sees the notice until its snackbar retires it; with no chat title it shows what over why. */
    @Test
    fun activeAccountSeesTheNoticeUntilItsSnackbarEnds() =
        runBlocking {
            val state = appState(active = ACCOUNT)
            val notice = groupChangeNotice(notDelivered("invite", "not_delivered"))!!
            val seen = async(start = CoroutineStart.UNDISPATCHED) { state.showGroupChangeNotice(notice) }
            assertEquals(notice.headline, state.toast?.title)
            assertEquals(notice.reason, state.toast?.detail)
            assertFalse(seen.isCompleted)
            retireToast(state)
            assertTrue(seen.await())
        }

    /** A toast that replaces the notice before it is seen is waited out, and the notice shows again after it. */
    @Test
    fun aReplacedNoticeShowsAgainAfterTheToastThatReplacedIt() =
        runBlocking {
            val state = appState(active = ACCOUNT)
            val notice = groupChangeNotice(notDelivered("invite", "refused"))!!
            val seen = async(start = CoroutineStart.UNDISPATCHED) { state.showGroupChangeNotice(notice) }
            state.presentText(AppText.Plain("Other"))
            Snapshot.sendApplyNotifications()
            yield()
            assertEquals(AppText.Plain("Other"), state.toast?.title)
            retireToast(state)
            assertEquals(notice.headline, state.toast?.title)
            assertFalse(seen.isCompleted)
            retireToast(state)
            assertTrue(seen.await())
        }

    /**
     * A change MDK reports again while its notice is showing is left to that notice; once seen, it returns at once so a
     * failed acknowledgement can be retried, without showing again. A different change still shows.
     */
    @Test
    fun aChangeIsShownOnceAndItsAcknowledgementCanBeRetried() =
        runBlocking {
            val state = appState(active = ACCOUNT)
            val notice = groupChangeNotice(refusal(OversizedArtifactFfi.COMMIT))!!
            val seen = async(start = CoroutineStart.UNDISPATCHED) { state.showGroupChangeNotice(notice) }
            assertFalse(state.showGroupChangeNotice(groupChangeNotice(refusal(OversizedArtifactFfi.COMMIT))!!))
            retireToast(state)
            assertTrue(seen.await())
            assertTrue(state.showGroupChangeNotice(notice))
            assertNull(state.toast)
            val other =
                async(start = CoroutineStart.UNDISPATCHED) {
                    state.showGroupChangeNotice(notice.copy(changeIdHex = "12".repeat(32)))
                }
            assertEquals(notice.reason, state.toast?.title)
            retireToast(state)
            assertTrue(other.await())
        }

    /** The shown set stays bounded, so the oldest change can be shown again once evicted. */
    @Test
    fun shownNoticesAreBounded() {
        val shown = ShownQueuedRefusals(capacity = 2)
        assertTrue(shown.firstShowing(ACCOUNT, "a"))
        assertFalse(shown.firstShowing(ACCOUNT, "A"))
        assertTrue(shown.firstShowing("other", "a"))
        assertTrue(shown.firstShowing(ACCOUNT, "b"))
        assertTrue(shown.firstShowing(ACCOUNT, "a"))
    }

    /** Another account's change shows nothing, so its record is left for when that account is active. */
    @Test
    fun anotherAccountsChangeShowsNothing() =
        runBlocking {
            val state = appState(active = "other")
            assertFalse(state.showGroupChangeNotice(groupChangeNotice(notDelivered("invite", "refused"))!!))
            assertNull(state.toast)
        }

    /** Retires the current toast as its snackbar does when it ends, then lets the waiting notice run. */
    private suspend fun retireToast(state: WhiteNoiseAppState) {
        state.clearToast()
        Snapshot.sendApplyNotifications()
        repeat(SETTLE_YIELDS) { yield() }
    }

    /** A given-up commit is acknowledged by its own ID, without reading the group's records. */
    @Test
    fun acknowledgingAnUndeliveredChangeDeletesItsRecord() =
        runBlocking {
            val marmot = RecordingMarmot()
            marmot.proxy.acknowledgeGroupChange(groupChangeNotice(notDelivered("invite", "refused"))!!)
            assertEquals(listOf("ack $GROUP $COMMIT"), marmot.calls)
        }

    /** A queued send's record is found by its intent; other records in the group stay, and none at all is fine. */
    @Test
    fun acknowledgingAQueuedRefusalDeletesOnlyItsRecord() =
        runBlocking {
            val other = record(commit = "99".repeat(32), queuedIntent = "88".repeat(32))
            val marmot = RecordingMarmot(records = mapOf(GROUP to listOf(other, record(queuedIntent = INTENT))))
            val notice = groupChangeNotice(refusal(artifact = null, reason = "not_group_admin"))!!
            marmot.proxy.acknowledgeGroupChange(notice)
            assertEquals(listOf("read $GROUP", "ack $GROUP $COMMIT"), marmot.calls)

            val empty = RecordingMarmot()
            empty.proxy.acknowledgeGroupChange(notice)
            assertEquals(listOf("read $GROUP"), empty.calls)
        }

    /**
     * The stream-open read covers listed and archived chats and raises the newest change of the user's, skipping MDK's
     * upkeep and any chat whose read fails.
     */
    @Test
    fun newestRetainedChangeComesFromEveryChat() =
        runBlocking {
            val archived = "aa".repeat(32)
            val failing = "bb".repeat(32)
            val marmot =
                RecordingMarmot(
                    ids =
                        mapOf(
                            ChatListViewFfi.CHATS to listOf(GROUP, failing),
                            ChatListViewFfi.ARCHIVED to listOf(archived),
                        ),
                    records =
                        mapOf(
                            GROUP to listOf(record(at = 10uL)),
                            archived to listOf(record(group = archived, commit = "77".repeat(32), at = 20uL)),
                        ),
                    upkeepNewest = record(kind = "self_update", commit = "66".repeat(32), at = 30uL),
                    failingGroup = failing,
                )
            val newest = marmot.proxy.newestRetainedGroupChange(ACCOUNT)
            assertEquals(archived, newest?.groupIdHex)
            assertEquals("77".repeat(32), newest?.commitIdHex)
            assertNull(RecordingMarmot().proxy.newestRetainedGroupChange(ACCOUNT))
        }

    /**
     * A [MarmotInterface] answering only the calls these notices make and recording them. [ids] are each view's chats,
     * [records] each group's retained changes, [upkeepNewest] an extra record in the first listed chat, and
     * [failingGroup] a chat whose read throws.
     */
    private class RecordingMarmot(
        private val ids: Map<ChatListViewFfi, List<String>> = emptyMap(),
        private val records: Map<String, List<GroupChangeNotDeliveredFfi>> = emptyMap(),
        private val upkeepNewest: GroupChangeNotDeliveredFfi? = null,
        private val failingGroup: String? = null,
    ) {
        val calls = mutableListOf<String>()

        val proxy: MarmotInterface =
            Proxy.newProxyInstance(MarmotInterface::class.java.classLoader, arrayOf(MarmotInterface::class.java)) {
                _,
                method,
                arguments,
                ->
                val args = arguments.orEmpty()
                when (method.name) {
                    "chatListIds" -> ids[args[1]].orEmpty()
                    "groupChangesNotDelivered" -> groupRecords(args[1] as String)
                    "acknowledgeChangeNotDelivered" -> {
                        calls += "ack ${args[1]} ${args[2]}"
                        true
                    }
                    else -> error("unexpected call ${method.name}")
                }
            } as MarmotInterface

        /** Records the read of [group] and answers its records, throwing for [failingGroup]. */
        private fun groupRecords(group: String): List<GroupChangeNotDeliveredFfi> {
            calls += "read $group"
            check(group != failingGroup) { "read failed" }
            val first = ids[ChatListViewFfi.CHATS]?.firstOrNull()
            return records[group].orEmpty() + listOfNotNull(upkeepNewest?.takeIf { group == first })
        }
    }

    /** An app state whose active account is [active] and which has no chat list yet. */
    private fun appState(active: String) =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = active,
        )

    /** A `QueuedIntentRefused` group event for an invite from [ACCOUNT] with the given [reason] and [artifact]. */
    private fun refusal(
        artifact: OversizedArtifactFfi? = OversizedArtifactFfi.COMMIT,
        reason: String = QUEUED_INTENT_REFUSED_TOO_LARGE,
    ) = MarmotEventFfi.GroupEvent(
        accountIdHex = ACCOUNT_HEX,
        accountLabel = ACCOUNT,
        groupIdHex = GROUP,
        event =
            GroupEventKindFfi.QueuedIntentRefused(
                intentIdHex = INTENT,
                intentKind = "invite",
                reason = reason,
                artifact = artifact,
                sizeClass = artifact?.let { EventSizeClassFfi.FROM64_TO128_KIB },
            ),
    )

    /** A `GroupChangeNotDelivered` group event for [ACCOUNT]'s change of [kind] with MDK's [reason] tag. */
    private fun notDelivered(
        kind: String,
        reason: String,
    ) = MarmotEventFfi.GroupEvent(
        accountIdHex = ACCOUNT_HEX,
        accountLabel = ACCOUNT,
        groupIdHex = GROUP,
        event = GroupEventKindFfi.GroupChangeNotDelivered(commitIdHex = COMMIT, intentKind = kind, reason = reason),
    )

    /** A member removal of [ACCOUNT]'s that lost a same-epoch race, with MDK's [outcome]. */
    private fun superseded(outcome: String) =
        MarmotEventFfi.GroupChangeSuperseded(
            accountIdHex = ACCOUNT_HEX,
            accountLabel = ACCOUNT,
            groupIdHex = GROUP,
            commitIdHex = COMMIT,
            kind = "remove_members",
            outcome = outcome,
            reason = "test",
        )

    /** MDK's retained record of a given-up change. */
    private fun record(
        group: String = GROUP,
        commit: String = COMMIT,
        queuedIntent: String? = null,
        kind: String = "invite",
        at: ULong = 1uL,
    ) = GroupChangeNotDeliveredFfi(
        groupIdHex = group,
        commitIdHex = commit,
        queuedIntentIdHex = queuedIntent,
        intentKind = kind,
        reason = "not_delivered",
        at = at,
    )

    private companion object {
        const val ACCOUNT = "account-a"
        val ACCOUNT_HEX = "ab".repeat(32)
        val GROUP = "ef".repeat(32)
        val INTENT = "cd".repeat(32)
        val COMMIT = "c0".repeat(32)

        /** Enough turns of the test's event loop for a waiting notice to see a toast change and act on it. */
        const val SETTLE_YIELDS = 3
    }
}
