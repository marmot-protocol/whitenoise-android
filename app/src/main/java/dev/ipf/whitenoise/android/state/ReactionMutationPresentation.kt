package dev.ipf.whitenoise.android.state

import android.util.Log
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R

/** Logs and presents a terminal reaction failure after the latest intent stops retrying. */
internal fun WhiteNoiseAppState.presentReactionMutationFailure(throwable: Throwable) {
    if (BuildConfig.DEBUG) {
        Log.w("DMConversation", "reaction mutation failed", throwable)
    } else {
        Log.w("DMConversation", "reaction mutation failed: ${throwable.javaClass.simpleName}")
    }
    presentFailure(R.string.toast_reaction_failed, "MESSAGE_REACTION", throwable)
}
