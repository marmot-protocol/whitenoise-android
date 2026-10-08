package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Native peer delivery and an empty account/group draft distinguish a sent bubble from unsent input. */
internal suspend fun verifyMaestroSend(
    native: Marmot,
    state: WhiteNoiseAppState,
    peer: String,
    group: String,
) {
    val owner = checkNotNull(state.activeAccountRef)
    withTimeout(30_000L) {
        while (true) {
            val messages =
                native
                    .timelineMessages(peer, TimelineMessageQueryFfi(group, null, null, null, null, null, 100u))
                    .messages
            val received = messages.count { it.plaintext == "Maestro verified send" }
            check(received <= 1) { "Duplicate peer delivery" }
            if (received == 1 && state.draftStore.get(owner, group).isNullOrBlank()) return@withTimeout
            delay(100L)
        }
    }
}
