package dev.ipf.whitenoise.android.ui.navigation

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Delivers apply notifications for snapshot writes made outside composition while [block] runs, the way Compose's
 * own global snapshot manager does on a device.
 *
 * That manager is a process-wide singleton: one consumer coroutine on the main looper and one `sent` flag that stays
 * set until the consumer runs. Under Robolectric every test class in the same SDK sandbox shares it, and when a
 * test ends with the consumer's resume still queued, the looper reset between tests drops that resume. From then on
 * `sent` never clears, so a later test's write outside composition (the reflective account switch, a published voice
 * session) is never applied and the shell never recomposes for it. Which earlier test drops the resume depends on
 * class order and timing, so the shared harness installs its own observer for every mount instead of trusting the
 * singleton. Extra apply notifications are harmless: applying is idempotent.
 */
internal inline fun <T> withGlobalSnapshotApply(block: () -> T): T {
    val handler = Handler(Looper.getMainLooper())
    val pending = AtomicBoolean(false)
    val observer =
        Snapshot.registerGlobalWriteObserver {
            if (pending.compareAndSet(false, true)) {
                handler.post {
                    pending.set(false)
                    Snapshot.sendApplyNotifications()
                }
            }
        }
    try {
        return block()
    } finally {
        observer.dispose()
    }
}
