package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.ui.test.junit4.ComposeTestRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reacquires the process-wide viewer image lane while the test's main looper still runs.
 *
 * Lane work started by a composition resumes on that looper. If a test ends while a decode still holds the
 * lane, the resume is dropped when the looper is reset and the lane stays held for every later test in the
 * same JVM. Joining it here keeps any timeout on the test that left the work behind.
 */
internal fun ComposeTestRule.awaitViewerImageWorkIdle() {
    val acquired = AtomicBoolean()
    val probeOwner = Job()
    val probe =
        CoroutineScope(Dispatchers.Default + probeOwner).launch {
            withViewerImageWork { acquired.set(true) }
        }
    try {
        waitUntil(10_000) { acquired.get() && probe.isCompleted }
    } finally {
        probeOwner.cancel()
        waitUntil(10_000) { probeOwner.isCompleted }
    }
}
