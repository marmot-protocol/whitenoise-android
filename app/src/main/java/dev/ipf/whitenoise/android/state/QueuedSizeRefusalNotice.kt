package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.GroupEventKindFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.OversizedArtifactFfi
import dev.ipf.whitenoise.android.R

/** MDK's stable refusal tag for a queued send whose commit or Welcome fits none of its relays. */
internal const val QUEUED_INTENT_REFUSED_TOO_LARGE = "event_too_large"

/** A queued group change MDK dropped for size: whose it was, which group, and what to tell the user. */
internal data class QueuedSizeRefusal(
    val accountRef: String,
    val groupIdHex: String,
    val intentIdHex: String,
    val message: AppText,
)

/**
 * The size refusal [event] reports, or null for any other event.
 *
 * MDK retires such a send once the group settles and never retries it, so the user must be told. A refused Welcome
 * reuses the invitation copy; a refused commit, or one MDK did not classify, says the group change was not made.
 */
internal fun queuedSizeRefusal(event: MarmotEventFfi): QueuedSizeRefusal? {
    val groupEvent = event as? MarmotEventFfi.GroupEvent
    val refusal = groupEvent?.event as? GroupEventKindFfi.QueuedIntentRefused
    return if (groupEvent != null && refusal?.reason == QUEUED_INTENT_REFUSED_TOO_LARGE) {
        QueuedSizeRefusal(
            accountRef = groupEvent.accountLabel,
            groupIdHex = groupEvent.groupIdHex,
            intentIdHex = refusal.intentIdHex,
            message =
                AppText.Resource(
                    if (refusal.artifact == OversizedArtifactFfi.WELCOME) {
                        R.string.error_invite_welcome_too_large
                    } else {
                        R.string.error_group_change_too_large
                    },
                ),
        )
    } else {
        null
    }
}

/**
 * Shows [refusal] while its account is still active, titled with the chat's name when the chat list knows it, so the
 * notice says which chat it is about even when it arrives after the user has moved elsewhere. Another account's
 * refusal shows nothing, because its group is not on screen, and a refusal MDK delivers again (the event stream
 * resubscribes after a failure) is shown once. Must run on the main thread.
 */
internal fun WhiteNoiseAppState.presentQueuedSizeRefusal(refusal: QueuedSizeRefusal) {
    if (activeAccountRef != refusal.accountRef) return
    if (!shownQueuedSizeRefusals.firstShowing(refusal.accountRef, refusal.intentIdHex)) return
    val (title, detail) = queuedSizeRefusalToast(chatTitle(refusal.accountRef, refusal.groupIdHex), refusal.message)
    presentText(title, detail)
}

/**
 * The toast title and detail for a size refusal: the chat's name over [message] when the chat list knows the chat,
 * otherwise [message] alone, whose wording names no particular chat.
 */
internal fun queuedSizeRefusalToast(
    chat: String?,
    message: AppText,
): Pair<AppText, AppText?> = if (chat == null) message to null else AppText.Plain(chat) to message

/**
 * The most recent queued-change refusals already shown, by account and MDK intent ID, which MDK documents as the key
 * for ignoring a repeated delivery. Bounded so a long session cannot grow it without limit; an intent old enough to
 * be evicted is no longer redelivered in practice. Not thread-safe: use it from the main thread.
 */
internal class ShownQueuedRefusals(
    private val capacity: Int = SHOWN_QUEUED_REFUSALS_CAPACITY,
) {
    private val shown = LinkedHashSet<String>()

    /** Records this refusal and returns whether it had not been shown yet. */
    fun firstShowing(
        accountRef: String,
        intentIdHex: String,
    ): Boolean {
        val key = "$accountRef|${intentIdHex.lowercase()}"
        if (!shown.add(key)) return false
        if (shown.size > capacity) shown.remove(shown.first())
        return true
    }
}

private const val SHOWN_QUEUED_REFUSALS_CAPACITY = 64
