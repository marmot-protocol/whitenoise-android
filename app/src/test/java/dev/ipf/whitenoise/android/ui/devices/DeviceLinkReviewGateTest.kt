package dev.ipf.whitenoise.android.ui.devices

import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.marmotkit.DeviceLinkPromptFfi
import dev.ipf.marmotkit.DeviceLinkPromptStateFfi
import dev.ipf.marmotkit.DeviceLinkSnapshotFfi
import dev.ipf.whitenoise.android.state.DeviceLinkRequestWatcher
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Review on another profile's request shows its comparison only after a read made after the switch succeeds. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceLinkReviewGateTest {
    @get:Rule val compose = createComposeRule()

    private val accounts = listOf("a", "b")
    private val withPrompt = DeviceLinkSnapshotFfi(null, listOf(prompt()), emptyList())
    private var nextReadOfB: suspend () -> DeviceLinkSnapshotFfi = { withPrompt }
    private var active by mutableStateOf("a")
    private val watcher =
        DeviceLinkRequestWatcher(readSnapshot = { account ->
            if (account == "b") nextReadOfB() else DeviceLinkSnapshotFfi(null, emptyList(), emptyList())
        })

    /** A read still under way after Review shows the request as being checked, not its cached comparison. */
    @Test
    fun aSlowReadAfterReviewKeepsTheComparisonDown() {
        val held = CompletableDeferred<DeviceLinkSnapshotFfi>()
        showRequestOnB()
        nextReadOfB = { held.await() }

        compose.onNodeWithTag(DEVICE_LINK_REVIEW_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(COMPARISON).assertDoesNotExist()
        compose.onNodeWithTag(DEVICE_LINK_REVIEW_PENDING_TAG).assertExists()

        held.complete(withPrompt)
        compose.waitForIdle()
        compose.onNodeWithText(COMPARISON).assertExists()
    }

    /** A failed read after Review never shows the cached comparison, and the request's own retry recovers it. */
    @Test
    fun aFailedReadAfterReviewRetriesUntilARead() {
        showRequestOnB()
        nextReadOfB = { error("read failed") }

        compose.onNodeWithTag(DEVICE_LINK_REVIEW_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(COMPARISON).assertDoesNotExist()
        compose.onNodeWithTag(DEVICE_LINK_REVIEW_PENDING_TAG).assertExists()

        nextReadOfB = { withPrompt }
        compose.mainClock.advanceTimeBy(DEVICE_LINK_REVIEW_RETRY_MS + 100L)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DEVICE_LINK_REVIEW_RETRY_MS + 100L))
        compose.waitForIdle()
        compose.onNodeWithText(COMPARISON).assertExists()
    }

    /** A request already on the active profile shows its comparison without waiting. */
    @Test
    fun aRequestOnTheActiveProfileShowsAtOnce() {
        active = "b"
        showRequestOnB()
        compose.onNodeWithText(COMPARISON).assertExists()
    }

    /** Reads both profiles, then shows the request profile "b" holds while [active] decides which branch runs. */
    private fun showRequestOnB() {
        runBlocking { watcher.refresh(accounts, blocked = false) }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val request = watcher.queue.presented ?: return@setContent
            WhiteNoiseTheme {
                DeviceLinkRequestPresentation(
                    watcher = watcher,
                    request = request,
                    activeAccountRef = active,
                    blocked = false,
                    profileName = "Work",
                    onReview = {
                        active = "b"
                        scope.launch { watcher.refresh(accounts, blocked = false) }
                    },
                ) { Text(COMPARISON) }
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        const val COMPARISON = "comparison"

        /** An open request prompt on profile "b". */
        fun prompt() =
            DeviceLinkPromptFfi(
                promptId = "p",
                slotIdHex = null,
                requestedAt = 1uL,
                state = DeviceLinkPromptStateFfi.Preparing,
            )
    }
}
