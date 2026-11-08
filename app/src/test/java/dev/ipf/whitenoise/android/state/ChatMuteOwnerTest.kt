package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatNotificationSettingsFfi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins what each mute reader returns across command, row and projection state. */
class ChatMuteOwnerTest {
    private val key = "account:group"
    private val now = 10_000L

    private data class Case(
        val name: String,
        val committed: ChatNotificationSettingsFfi?,
        val pending: Boolean,
        val expected: ChatMuteDisplay,
        val expectedListMuted: Boolean,
        val expectedListMutedUntilMs: Long?,
    )

    // The loaded chat list says muted until 50_000; a screen's own read says muted until 30_000.
    private val listMuted = true
    private val listMutedUntilMs = 50_000L
    private val screenRead = ChatMuteDisplay(muted = true, mutedUntilMs = 30_000L)

    private val cases =
        listOf(
            Case(
                name = "no command result",
                committed = null,
                pending = false,
                expected = screenRead,
                expectedListMuted = true,
                expectedListMutedUntilMs = listMutedUntilMs,
            ),
            Case(
                name = "pending command without a result yet",
                committed = null,
                pending = true,
                expected = screenRead,
                expectedListMuted = true,
                expectedListMutedUntilMs = listMutedUntilMs,
            ),
            Case(
                name = "settled unmute result beats the screen read and list",
                committed = settings(muted = false),
                pending = false,
                expected = ChatMuteDisplay(muted = false, mutedUntilMs = null),
                expectedListMuted = false,
                expectedListMutedUntilMs = null,
            ),
            Case(
                name = "pending timed mute result",
                committed = settings(muted = true, mutedUntilMs = 20_000L),
                pending = true,
                expected = ChatMuteDisplay(muted = true, mutedUntilMs = 20_000L),
                expectedListMuted = true,
                expectedListMutedUntilMs = 20_000L,
            ),
            Case(
                name = "expired timed mute result reads as unmuted",
                committed = settings(muted = true, mutedUntilMs = now),
                pending = false,
                expected = ChatMuteDisplay(muted = false, mutedUntilMs = null),
                expectedListMuted = false,
                expectedListMutedUntilMs = null,
            ),
        )

    @Test
    fun readersKeepTheirPrecedenceInEveryState() {
        cases.forEach { case ->
            val owner = ChatMuteOwner()
            if (case.committed != null) {
                val command = owner.admit(key)
                runBlocking { command.execute { commit(case.committed) } }
            }
            if (case.pending) owner.admit(key)

            assertEquals(case.name, case.pending, owner.isPending(key))
            assertEquals(case.name, case.expected, owner.display(key, now) { screenRead })
            assertEquals(case.name, case.expectedListMuted, owner.muted(key, now) { listMuted })
            assertEquals(case.name, case.expectedListMutedUntilMs, owner.mutedUntilMs(key, now) { listMutedUntilMs })
            assertEquals(case.name, case.committed != null, owner.committedResult(key, now) != null)
        }
    }

    @Test
    fun displayUsesTheCallersReadsWithoutAnAccount() {
        val owner = ChatMuteOwner()
        val command = owner.admit(key)
        runBlocking { command.execute { commit(settings(muted = false)) } }

        assertEquals(screenRead, owner.display(key = null, nowMillis = now) { screenRead })
    }

    @Test
    fun projectionDropsTheResultOnlyWhenItSupersedesIt() {
        val owner = ChatMuteOwner()
        val command = owner.admit(key)
        runBlocking { command.execute { commit(settings(muted = true, mutedUntilMs = 20_000L)) } }
        val pending = owner.admit(key)

        owner.acceptProjection(key, projectedMuted = false, projectedMutedUntilMs = null)
        assertEquals("a stale projection keeps a pending result", setOf(key), owner.committedKeys)

        owner.acceptProjection(key, projectedMuted = true, projectedMutedUntilMs = 20_000L)
        assertTrue("a matching projection drops it", owner.committedKeys.isEmpty())

        runBlocking { pending.execute { commit(settings(muted = false)) } }
        owner.acceptProjection(key, projectedMuted = true, projectedMutedUntilMs = null)
        assertTrue("any projection drops a settled result", owner.committedKeys.isEmpty())
    }

    @Test
    fun commandCountsAsPendingFromAdmissionUntilItFinishes() {
        val owner = ChatMuteOwner()
        val first = owner.admit(key)
        val second = owner.admit(key)
        assertEquals(setOf(key), owner.pendingKeys)

        runBlocking { first.execute { commit(settings(muted = true)) } }
        assertTrue("another command is still in flight", owner.isPending(key))

        val failure =
            assertThrows(IllegalStateException::class.java) {
                runBlocking { second.execute<Unit> { error("repository failed") } }
            }
        assertEquals("repository failed", failure.message)
        assertFalse("a failed command still releases its count", owner.isPending(key))
        assertTrue(owner.pendingKeys.isEmpty())
        assertEquals(setOf(key), owner.committedKeys)
    }

    @Test
    fun aReleasedCommandCannotCommit() {
        val owner = ChatMuteOwner()
        val command = owner.admit(key)
        runBlocking { command.execute<Unit> { } }

        val failure = assertThrows(IllegalStateException::class.java) { command.commit(settings(muted = true)) }
        assertEquals("Mute command already released", failure.message)
        assertTrue(owner.committedKeys.isEmpty())
    }

    @Test
    fun clearDropsResultsAndPendingCommands() {
        val owner = ChatMuteOwner()
        val command = owner.admit(key)
        runBlocking { command.execute { commit(settings(muted = true)) } }
        owner.admit(key)

        owner.clear()

        assertFalse(owner.isPending(key))
        assertNull(owner.committedResult(key, now))
    }

    private fun settings(
        muted: Boolean,
        mutedUntilMs: Long? = null,
    ) = ChatNotificationSettingsFfi(
        accountRef = "account",
        accountIdHex = "account-id",
        groupIdHex = "group",
        muted = muted,
        mutedUntilMs = mutedUntilMs,
        updatedAtMs = 1,
    )
}
