package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi

/**
 * Whether this installation sent the message. Only such a row has a local delivery state: MDK projects it before it
 * publishes and fills in its source event on delivery. A row this account wrote on another device, or one that
 * arrived in imported history, is received here and was never a local send, even without a source event.
 * [MessageProjector.isMine] answers authorship instead, which also covers the account's other devices.
 */
internal fun AppMessageRecordFfi.isSentFromThisDevice(): Boolean = direction == "sent"
