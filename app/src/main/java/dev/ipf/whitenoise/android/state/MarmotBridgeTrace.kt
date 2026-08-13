package dev.ipf.whitenoise.android.state

import androidx.tracing.Trace
import java.util.concurrent.atomic.AtomicInteger

internal object MarmotTraceSection {
    const val CREATE_GROUP = "WhiteNoise.marmot.createGroup"
    const val ACCEPT_GROUP_INVITE = "WhiteNoise.marmot.acceptGroupInvite"
    const val REFRESH_GROUP_ROSTER = "WhiteNoise.marmot.refreshMembers.roster"
    const val INVITE_MEMBERS = "WhiteNoise.marmot.inviteMembers"
    const val REMOVE_MEMBERS = "WhiteNoise.marmot.removeMembers"
    const val PROMOTE_ADMIN = "WhiteNoise.marmot.promoteAdmin"
    const val DEMOTE_ADMIN = "WhiteNoise.marmot.demoteAdmin"
    const val SELF_DEMOTE_ADMIN = "WhiteNoise.marmot.selfDemoteAdmin"
}

internal interface AsyncTraceBackend {
    fun isEnabled(): Boolean

    fun beginAsyncSection(
        sectionName: String,
        cookie: Int,
    )

    fun endAsyncSection(
        sectionName: String,
        cookie: Int,
    )
}

private object AndroidxAsyncTraceBackend : AsyncTraceBackend {
    override fun isEnabled(): Boolean = Trace.isEnabled()

    override fun beginAsyncSection(
        sectionName: String,
        cookie: Int,
    ) = Trace.beginAsyncSection(sectionName, cookie)

    override fun endAsyncSection(
        sectionName: String,
        cookie: Int,
    ) = Trace.endAsyncSection(sectionName, cookie)
}

/**
 * Emits process-wide async slices around suspending Marmot bridge calls.
 *
 * Async sections are intentional: a coroutine can resume on another IO worker,
 * while synchronous trace sections must begin and end on the same thread.
 */
internal class MarmotBridgeTracer(
    private val backend: AsyncTraceBackend = AndroidxAsyncTraceBackend,
) {
    private val nextCookie = AtomicInteger()

    suspend fun <T> trace(
        sectionName: String,
        block: suspend () -> T,
    ): T {
        if (!backend.isEnabled()) return block()

        val cookie = nextCookie.updateAndGet { current -> if (current == Int.MAX_VALUE) 1 else current + 1 }
        backend.beginAsyncSection(sectionName, cookie)
        return try {
            block()
        } finally {
            backend.endAsyncSection(sectionName, cookie)
        }
    }
}
