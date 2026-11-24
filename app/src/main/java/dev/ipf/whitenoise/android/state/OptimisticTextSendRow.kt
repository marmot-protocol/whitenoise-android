package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi

/** The optimistic row a first text send created, carried into admission, reconciliation and failure handling. */
internal class OptimisticTextSendRow(
    val key: String,
    val tempId: String,
    val record: AppMessageRecordFfi,
    val order: ULong,
    val chatListPreviewReserved: Boolean,
)
