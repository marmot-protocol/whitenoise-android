package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.Intent

/** Keeps the existing invite intent entry point while its transport remains with the shared platform owner. */
internal fun inviteShareIntent(message: String): Intent =
    dev.ipf.whitenoise.android.share
        .inviteShareIntent(message)
