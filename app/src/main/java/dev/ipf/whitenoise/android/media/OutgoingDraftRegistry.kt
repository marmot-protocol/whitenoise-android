package dev.ipf.whitenoise.android.media

import java.util.concurrent.ConcurrentHashMap

/**
 * One [OutgoingDrafts] per account and conversation for the process's lifetime, the one place an outgoing domain is
 * made (#3373). Keeping it out of any controller or screen means a draft, and a collection send that outlives the
 * screen that started it, keeps its picks' drafts when the controller, the sender or the activity is replaced.
 */
internal class OutgoingDraftRegistry(
    private val retire: (MediaTrustDomain) -> Unit = {
        dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
            .retireDomain(it)
    },
) {
    private val conversations = ConcurrentHashMap<Pair<String, String>, OutgoingDrafts>()

    /** The domain [pick] of [account] in [conversation] decodes in, or single-use when either is unknown. */
    fun domain(
        account: String?,
        conversation: String,
        pick: String?,
    ): MediaTrustDomain =
        if (account.isNullOrEmpty() || pick.isNullOrEmpty()) {
            MediaTrustDomain.Unique
        } else {
            MediaTrustDomain.outgoing(account, conversation, drafts(account, conversation).draftOf(pick))
        }

    /**
     * Starts a send of [picks] in [conversation]. With [ownDraft], picks that belong to no draft yet, such as a
     * collection resumed after the process restarted, join a draft of their own instead of the composer's.
     */
    fun beginSend(
        account: String?,
        conversation: String,
        picks: Collection<String>,
        ownDraft: Boolean = false,
    ): OutgoingSend =
        if (account.isNullOrEmpty()) {
            OutgoingSend(null, emptySet())
        } else {
            OutgoingSend(drafts(account, conversation), drafts(account, conversation).beginSend(picks, ownDraft))
        }

    /** Ends a send [beginSend] started, retiring each of its drafts that nothing can still decode in. */
    fun endSend(send: OutgoingSend) {
        send.owner?.endSend(send.drafts)
    }

    /** Records the [picks] now in [conversation]'s composer. */
    fun retain(
        account: String?,
        conversation: String,
        picks: Set<String>,
    ) {
        if (!account.isNullOrEmpty()) drafts(account, conversation).retain(picks)
    }

    /** [conversation]'s drafts for [account], made on first use with retirement into that conversation's domain. */
    private fun drafts(
        account: String,
        conversation: String,
    ): OutgoingDrafts =
        conversations.computeIfAbsent(account to conversation) {
            OutgoingDrafts { draft -> retire(MediaTrustDomain.outgoing(account, conversation, draft)) }
        }
}

/** A send in progress: the drafts it decodes in, which stay open until it is ended. */
internal class OutgoingSend(
    internal val owner: OutgoingDrafts?,
    internal val drafts: Set<String>,
)
