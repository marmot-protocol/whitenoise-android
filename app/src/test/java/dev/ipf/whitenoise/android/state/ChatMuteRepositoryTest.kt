package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatNotificationSettingsFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatMuteRepositoryTest {
    @Test
    fun rapidCommandsAreSerializedInInvocationOrder() =
        runTest {
            val gateway = FakeChatMuteGateway()
            val repository = ChatMuteRepository(gateway, UnconfinedTestDispatcher(testScheduler))

            val mute = async { repository.setMuted("account", "group", 42L) }
            val clear = async { repository.clearMuted("account", "group") }

            assertTrue(mute.await().isSuccess)
            assertTrue(clear.await().isSuccess)
            assertEquals(listOf("mute:42", "clear"), gateway.commands)
            assertFalse(gateway.current.muted)
        }

    @Test
    fun failedCommandDoesNotInventContradictoryState() =
        runTest {
            val gateway = FakeChatMuteGateway().apply { failBeforeCommand = true }
            val repository = ChatMuteRepository(gateway, UnconfinedTestDispatcher(testScheduler))

            val result = repository.setMuted("account", "group", null)

            assertTrue(result.isFailure)
            assertFalse(gateway.current.muted)
        }

    @Test
    fun ambiguousFailureReturnsConfirmedMdkResult() =
        runTest {
            val gateway = FakeChatMuteGateway().apply { failAfterCommand = true }
            val repository = ChatMuteRepository(gateway, UnconfinedTestDispatcher(testScheduler))

            val result = repository.setMuted("account", "group", 99L)

            assertTrue(result.isSuccess)
            assertEquals(99L, result.getOrThrow().mutedUntilMs)
        }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotConvertedToFailure() =
        runTest {
            val gateway = FakeChatMuteGateway().apply { cancellation = CancellationException("cancel") }
            ChatMuteRepository(gateway, UnconfinedTestDispatcher(testScheduler))
                .setMuted("account", "group", null)
        }
}

private class FakeChatMuteGateway : ChatMuteGateway {
    val commands = mutableListOf<String>()
    var current = settings(muted = false)
    var failBeforeCommand = false
    var failAfterCommand = false
    var cancellation: CancellationException? = null

    override fun read(
        accountRef: String,
        groupIdHex: String,
    ): ChatNotificationSettingsFfi = current

    override fun mute(
        accountRef: String,
        groupIdHex: String,
        mutedUntilMs: Long?,
    ): ChatNotificationSettingsFfi {
        cancellation?.let { throw it }
        if (failBeforeCommand) error("before")
        commands += "mute:$mutedUntilMs"
        current = settings(muted = true, mutedUntilMs = mutedUntilMs)
        if (failAfterCommand) error("after")
        return current
    }

    override fun unmute(
        accountRef: String,
        groupIdHex: String,
    ): ChatNotificationSettingsFfi {
        commands += "clear"
        current = settings(muted = false)
        return current
    }
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
