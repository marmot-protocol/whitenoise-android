package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatNotificationSettingsFfi
import dev.ipf.marmotkit.MarmotInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface ChatMuteGateway {
    fun read(
        accountRef: String,
        groupIdHex: String,
    ): ChatNotificationSettingsFfi

    fun mute(
        accountRef: String,
        groupIdHex: String,
        mutedUntilMs: Long?,
    ): ChatNotificationSettingsFfi

    fun unmute(
        accountRef: String,
        groupIdHex: String,
    ): ChatNotificationSettingsFfi
}

internal class MarmotChatMuteGateway(
    private val marmot: () -> MarmotInterface,
) : ChatMuteGateway {
    override fun read(
        accountRef: String,
        groupIdHex: String,
    ): ChatNotificationSettingsFfi = marmot().chatNotificationSettings(accountRef, groupIdHex)

    override fun mute(
        accountRef: String,
        groupIdHex: String,
        mutedUntilMs: Long?,
    ): ChatNotificationSettingsFfi = marmot().setChatMuted(accountRef, groupIdHex, mutedUntilMs)

    override fun unmute(
        accountRef: String,
        groupIdHex: String,
    ): ChatNotificationSettingsFfi = marmot().clearChatMuted(accountRef, groupIdHex)
}

/** Serialized, off-main command boundary for MDK-owned chat mute state. */
internal class ChatMuteRepository(
    private val gateway: ChatMuteGateway,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lockTableGuard = Any()
    private val lockTable = mutableMapOf<Key, Entry>()
    private val commandQueueGuard = Any()
    private val commandTails = mutableMapOf<Key, CompletableDeferred<Unit>>()

    suspend fun settings(
        accountRef: String,
        groupIdHex: String,
    ): Result<ChatNotificationSettingsFfi> = call(accountRef, groupIdHex) { gateway.read(accountRef, groupIdHex) }

    suspend fun setMuted(
        accountRef: String,
        groupIdHex: String,
        mutedUntilMs: Long?,
    ): Result<ChatNotificationSettingsFfi> =
        command(accountRef, groupIdHex) {
            resolveAmbiguous(
                accountRef = accountRef,
                groupIdHex = groupIdHex,
                matches = { it.muted && it.mutedUntilMs == mutedUntilMs },
            ) { gateway.mute(accountRef, groupIdHex, mutedUntilMs) }
        }

    suspend fun clearMuted(
        accountRef: String,
        groupIdHex: String,
    ): Result<ChatNotificationSettingsFfi> =
        command(accountRef, groupIdHex) {
            resolveAmbiguous(
                accountRef = accountRef,
                groupIdHex = groupIdHex,
                matches = { !it.muted },
            ) { gateway.unmute(accountRef, groupIdHex) }
        }

    private suspend fun command(
        accountRef: String,
        groupIdHex: String,
        block: () -> ChatNotificationSettingsFfi,
    ): Result<ChatNotificationSettingsFfi> {
        val key = Key(accountRef, groupIdHex)
        val completion = CompletableDeferred<Unit>()
        val predecessor =
            synchronized(commandQueueGuard) {
                commandTails.put(key, completion)
            }
        try {
            predecessor?.await()
            return call(accountRef, groupIdHex, block = block)
        } finally {
            completion.complete(Unit)
            synchronized(commandQueueGuard) {
                if (commandTails[key] === completion) commandTails.remove(key)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun resolveAmbiguous(
        accountRef: String,
        groupIdHex: String,
        matches: (ChatNotificationSettingsFfi) -> Boolean,
        command: () -> ChatNotificationSettingsFfi,
    ): ChatNotificationSettingsFfi =
        try {
            command()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cause: Exception) {
            val authoritative = runCatching { gateway.read(accountRef, groupIdHex) }.getOrNull()
            if (authoritative != null && matches(authoritative)) authoritative else throw cause
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun call(
        accountRef: String,
        groupIdHex: String,
        block: () -> ChatNotificationSettingsFfi,
    ): Result<ChatNotificationSettingsFfi> =
        withContext(ioDispatcher) {
            val key = Key(accountRef, groupIdHex)
            val entry = synchronized(lockTableGuard) { lockTable.getOrPut(key) { Entry() }.also { it.users++ } }
            try {
                entry.mutex.withLock {
                    try {
                        Result.success(block())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (cause: Exception) {
                        Result.failure(cause)
                    }
                }
            } finally {
                synchronized(lockTableGuard) {
                    entry.users--
                    if (entry.users == 0 && lockTable[key] === entry) lockTable.remove(key)
                }
            }
        }

    private data class Key(
        val accountRef: String,
        val groupIdHex: String,
    )

    private class Entry(
        val mutex: Mutex = Mutex(),
        var users: Int = 0,
    )
}
