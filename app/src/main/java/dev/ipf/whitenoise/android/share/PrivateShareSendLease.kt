package dev.ipf.whitenoise.android.share

import android.content.Context
import android.net.Uri
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Acquired before optimistic queuing; each queued owner releases once on acceptance or removal. */
internal class PrivateShareSendLease private constructor(
    private val files: PrivateShareFiles,
    private val id: String,
) {
    fun ownerReleases(
        count: Int,
        onFinalRelease: (PrivateShareSendLease) -> Unit,
    ): List<() -> Unit> {
        val owners = AtomicInteger(count)
        return List(count) {
            val released = AtomicBoolean(false)
            val release: () -> Unit = {
                if (released.compareAndSet(false, true) && owners.decrementAndGet() == 0) onFinalRelease(this)
            }
            release
        }
    }

    /** Must run on I/O; release is safe after an earlier acceptance callback. */
    fun release() = files.leases.releaseSend(id)

    companion object {
        /** Must run on I/O, before any queued item can appear or the shelf is cleared. */
        fun acquire(
            context: Context,
            uris: List<Uri>,
            account: String? = null,
        ): PrivateShareSendLease? {
            val files = PrivateShareFiles(context)
            val owned = uris.filter(files::owns)
            return if (owned.isEmpty()) {
                null
            } else {
                val id = UUID.randomUUID().toString()
                files.leases.holdSend(id, owned, account)
                PrivateShareSendLease(files, id)
            }
        }
    }
}
