package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatNotificationSettingsFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatMuteActionAdmissionTest {
    @Test fun queuedMuteRejectsRetiredPresentationBeforeNativeCall() = runTest { verifyRetirement(mute = true) }

    @Test fun queuedUnmuteRejectsRetiredPresentationBeforeNativeCall() = runTest { verifyRetirement(mute = false) }

    private suspend fun TestScope.verifyRetirement(mute: Boolean) {
        val gateway = AdmissionMuteGateway()
        val repository = ChatMuteRepository(gateway, StandardTestDispatcher(testScheduler))
        // The first command owns the FIFO slot but its I/O dispatch has not run yet.
        val predecessor = async(start = CoroutineStart.UNDISPATCHED) { repository.setMuted("account", "group", null) }
        var current = true
        val queued =
            async(start = CoroutineStart.UNDISPATCHED) {
                if (mute) {
                    repository.setMuted("account", "group", null) { current }
                } else {
                    repository.clearMuted("account", "group") { current }
                }
            }
        assertTrue(gateway.commands.isEmpty())
        current = false
        runCurrent()
        assertTrue(predecessor.await().isSuccess)
        try {
            queued.await()
            throw AssertionError("A retired presentation must cancel before the gateway")
        } catch (_: CancellationException) {
            assertEquals(listOf("mute"), gateway.commands)
        }
        // Retiring the queued action must release its FIFO slot for a subsequent valid command.
        val successor = async { repository.clearMuted("account", "group") }
        runCurrent()
        assertTrue(successor.await().isSuccess)
        assertEquals(listOf("mute", "clear"), gateway.commands)
    }
}

private class AdmissionMuteGateway : ChatMuteGateway {
    val commands = mutableListOf<String>()
    private var current = settings(false)

    override fun read(
        accountRef: String,
        groupIdHex: String,
    ): ChatNotificationSettingsFfi = current

    override fun mute(
        accountRef: String,
        groupIdHex: String,
        mutedUntilMs: Long?,
    ): ChatNotificationSettingsFfi {
        commands += "mute"
        current = settings(true)
        return current
    }

    override fun unmute(
        accountRef: String,
        groupIdHex: String,
    ): ChatNotificationSettingsFfi {
        commands += "clear"
        current = settings(false)
        return current
    }

    private fun settings(muted: Boolean) =
        ChatNotificationSettingsFfi(
            accountRef = "account",
            accountIdHex = "account-id",
            groupIdHex = "group",
            muted = muted,
            mutedUntilMs = null,
            updatedAtMs = 1L,
        )
}
