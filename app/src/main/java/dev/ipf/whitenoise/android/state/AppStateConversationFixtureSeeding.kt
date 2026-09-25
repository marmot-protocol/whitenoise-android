@file:Suppress("MatchingDeclarationName") // AppState extension file; the progress record is its one small type.

package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import java.util.UUID

/** How far a synthetic-message seed has got: messages MDK accepted, messages it rejected, and the target. */
internal data class ConversationFixtureSeedProgress(
    val sent: Int,
    val failed: Int,
    val total: Int,
) {
    /** Whether every message has been attempted. */
    val finished: Boolean get() = sent + failed >= total
}

/**
 * The body of the [index]th of [total] synthetic messages.
 *
 * A paging fixture is only useful if its pages look like a real conversation, so the bodies cycle
 * through one-liners, sentences, a paragraph, Markdown with emphasis and a list, and a multi-line
 * note. Every body carries its ordinal so a row on screen can be matched to a position in history.
 */
internal fun conversationFixtureMessage(
    index: Int,
    total: Int,
): String {
    val ordinal = "Fixture ${index.toString().padStart(total.toString().length, '0')}/$total"
    return fixtureShapes[(index - 1).mod(fixtureShapes.size)](ordinal)
}

/**
 * Admits [count] synthetic text messages into [groupIdHex] through MDK, one after another, reporting
 * after each so a dialog can show progress. Debug tooling for preparing a paging benchmark fixture:
 * the messages are real, so this belongs in a private test group, never a shared one. A rejected
 * send is counted and the seed continues; cancellation stops it where it is.
 *
 * Each message goes through the same client-token admission the composer uses, under the same
 * per-group locks, so the call returns once MDK owns the send durably rather than after relay
 * publication — the legacy whole-send call waits on relays and a seed of hundreds would never finish.
 */
internal suspend fun WhiteNoiseAppState.seedConversationFixture(
    groupIdHex: String,
    count: Int,
    onProgress: (ConversationFixtureSeedProgress) -> Unit,
): ConversationFixtureSeedProgress {
    var progress = ConversationFixtureSeedProgress(sent = 0, failed = 0, total = count)
    val account = activeAccountRef ?: return progress.copy(failed = count).also(onProgress)
    for (index in 1..count) {
        val body = conversationFixtureMessage(index, count)
        val token = "seed-${UUID.randomUUID()}"
        val attempt =
            runCatching {
                withConversationTextSendOrder(account, groupIdHex) {
                    withGroupCommitLock(account, groupIdHex) {
                        marmotIo { sendTextWithClientToken(account, groupIdHex, body, token) }
                    }
                }
            }
        attempt.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        progress =
            if (attempt.isSuccess) {
                progress.copy(sent = progress.sent + 1)
            } else {
                progress.copy(failed = progress.failed + 1)
            }
        onProgress(progress)
    }
    return progress
}

/** The message shapes a seed cycles through, in order, each given the row's ordinal label. */
private val fixtureShapes: List<(String) -> String> =
    listOf(
        { ordinal -> ordinal },
        { ordinal -> "$ordinal — quick check-in, nothing to add." },
        { ordinal ->
            "$ordinal — a longer note so a page carries some wrapped rows: this message runs to a few lines " +
                "of ordinary prose, the kind a group produces when someone explains what changed and why, " +
                "and it ends without a question."
        },
        { ordinal -> "$ordinal — **bold**, _italic_ and `code`, so Markdown parsing is part of the page." },
        { ordinal -> "$ordinal — a list:\n- first point\n- second point\n- third point" },
        { ordinal -> "$ordinal\nsecond line\nthird line" },
    )
