package dev.ipf.whitenoise.android.share

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class InboundShareImportState {
    var store by mutableStateOf<SerializedPendingShareRequestStore?>(null)
    var progress by mutableStateOf<ShareImportProgress?>(null)
    var creationFailed by mutableStateOf(false)
}
