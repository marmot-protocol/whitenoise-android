package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * When the default notification permission may be asked for (#4278): once a reply is expected, because the person
 * opened a conversation or shared an invite or their profile in this process, or on any process start after the one in
 * which this install first reached a ready account. Asking earlier would interrupt a new account before notifications
 * mean anything to it.
 */
internal class NotificationAskMoment(
    private val preferences: SharedPreferences,
) {
    private val readySeenBeforeThisProcess = preferences.getBoolean(FIRST_READY_SEEN_KEY, false)
    private var replyExpected by mutableStateOf(false)

    /** Whether this is a moment to ask; observable, so a waiting effect re-runs when a reply becomes expected. */
    val reached: Boolean
        get() = replyExpected || readySeenBeforeThisProcess

    fun noteReplyExpected() {
        replyExpected = true
    }

    /** Records that this install reached a ready account, so the next process start is a moment to ask. */
    fun noteReady() {
        if (preferences.getBoolean(FIRST_READY_SEEN_KEY, false)) return
        preferences.edit().putBoolean(FIRST_READY_SEEN_KEY, true).commit()
    }

    companion object {
        /** Device fixtures set this to start as a returning install, where the explanation opens at readiness. */
        const val FIRST_READY_SEEN_KEY = "notification_ask_first_ready_seen"
    }
}
