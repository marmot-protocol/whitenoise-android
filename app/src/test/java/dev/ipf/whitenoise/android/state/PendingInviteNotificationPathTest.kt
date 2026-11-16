package dev.ipf.whitenoise.android.state

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A group the account has not accepted rings only as the invitation: MDK still emits its messages and admin changes,
 * and Android withholds them through the production notification path until the invitation is accepted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingInviteNotificationPathTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** Starts every case with notification permission and an empty tray. */
    @Before
    fun clearTray() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
    }

    /** Leaves the tray empty for the next suite. */
    @After
    fun clearTrayAfter() {
        manager.cancelAll()
    }

    /** A message in an unaccepted invite posts nothing, after MDK's group record confirms the row's pending state. */
    @Test
    fun aMessageInAnUnacceptedInviteStaysSilent() =
        runWith(rowPending = true, groupPending = true) { fixture ->
            fixture.appState.processNotificationUpdateForTest(fixture.update)

            assertEquals(0, manager.activeNotifications.size)
            assertTrue(fixture.notificationGroupDetailsCalls.get() > 0)
        }

    /** An admin change in an unaccepted invite, which the read state does not govern, also stays silent. */
    @Test
    fun anAdminChangeInAnUnacceptedInviteStaysSilent() =
        runWith(rowPending = true, groupPending = true) { fixture ->
            val adminChange =
                fixture.update.copy(
                    notificationKey = "made-admin:account-a",
                    trigger = NotificationTriggerFfi.MADE_ADMIN,
                    messageIdHex = null,
                )

            fixture.appState.processNotificationUpdateForTest(adminChange)

            assertEquals(0, manager.activeNotifications.size)
        }

    /** The invitation itself still rings for an unaccepted group. */
    @Test
    fun theInvitationItselfStillRings() =
        runWith(rowPending = true, groupPending = true) { fixture ->
            val invite =
                fixture.update.copy(
                    notificationKey = "invite:account-a",
                    trigger = NotificationTriggerFfi.GROUP_INVITE,
                    messageIdHex = null,
                )

            fixture.appState.processNotificationUpdateForTest(invite)

            assertTrue("the invitation did not post", awaitAnyCard())
        }

    /** A row that still says pending just after Accept is overruled by the group record, so the message rings. */
    @Test
    fun theFirstMessageAfterAcceptStillRings() =
        runWith(rowPending = true, groupPending = false) { fixture ->
            fixture.appState.processNotificationUpdateForTest(fixture.update)

            assertTrue("the first message after accepting was dropped", awaitAnyCard())
        }

    /** Runs [block] against a bootstrapped fixture whose chat row and group record report the given pending states. */
    private fun runWith(
        rowPending: Boolean,
        groupPending: Boolean,
        block: suspend (NotificationBootstrapTestFixture) -> Unit,
    ) = runBlocking {
        lateinit var fixture: NotificationBootstrapTestFixture
        fixture =
            NotificationBootstrapTestFixture(
                context = context,
                emitStartupNotification = false,
                accounts = listOf(account("account-a")),
                onChatListRow = { _, groupIdHex -> pendingRow(groupIdHex, rowPending) },
                onGroupDetails = { _, groupIdHex -> pendingGroup(groupIdHex, groupPending) },
            )
        try {
            fixture.bootstrap()
            fixture.runWithMainLooperPumping { block(fixture) }
        } finally {
            fixture.close()
        }
    }

    /** Waits a bounded time for any card to post. */
    private suspend fun awaitAnyCard(): Boolean =
        withTimeoutOrNull(3_000L) {
            while (manager.activeNotifications.isEmpty()) delay(10L)
            true
        } == true

    /** The chat-list row for [groupIdHex], unread, pending when [pending]. */
    private fun pendingRow(
        groupIdHex: String,
        pending: Boolean,
    ): ChatListRowFfi = notificationChatListRow().copy(groupIdHex = groupIdHex, pendingConfirmation = pending)

    /** MDK's group record for [groupIdHex], pending when [pending]. */
    private fun pendingGroup(
        groupIdHex: String,
        pending: Boolean,
    ): GroupDetailsFfi {
        val details = testGroupMutationResult(listOf(ConversationTimelineTestIds.ACCOUNT_ID), emptyList()).details
        return details.copy(group = details.group.copy(groupIdHex = groupIdHex, pendingConfirmation = pending))
    }

    /** One local signing account. */
    private fun account(label: String) =
        AccountSummaryFfi(
            label = label,
            accountIdHex = label,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
            unlinked = false,
        )
}
