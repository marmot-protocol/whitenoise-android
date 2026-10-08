package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/** Real lookup effects finish failures and reject late results from obsolete view owners. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class GlobalSearchLookupTest {
    @get:Rule val composeRule = createComposeRule()

    /** A failed request completes with a redacted error; a fresh Retry request can publish success. */
    @Test
    fun failedLookupOffersRetryAndFreshRequestCanSucceed() {
        val request = mutableStateOf(Any())
        val calls = AtomicInteger()
        var visible: GlobalSearchLookupResult<String>? = null
        composeRule.setContent {
            val result =
                rememberGlobalSearchLookup(request.value, true, "GLOBAL_BODY_SEARCH") {
                    if (calls.incrementAndGet() == 1) error("private query text")
                    "recovered"
                }
            SideEffect { visible = result }
        }
        advanceLookup()
        composeRule.waitUntil(TIMEOUT_MILLIS) { visible?.error != null }
        composeRule.runOnIdle {
            assertSame(request.value, visible?.request)
            assertNull(visible?.value)
            assertTrue(requireNotNull(visible?.error).retryable)
            assertFalse(requireNotNull(visible?.error).report.contains("private query text"))
            request.value = Any()
        }
        advanceLookup()
        composeRule.waitUntil(TIMEOUT_MILLIS) { visible?.value == "recovered" }
        composeRule.runOnIdle {
            assertEquals(2, calls.get())
            assertNull(visible?.error)
            assertSame(request.value, visible?.request)
        }
    }

    /** Even cancellation-insensitive work cannot publish over the replacement request's result. */
    @Test
    fun obsoleteNonCooperativeLookupCannotOverwriteReplacementResult() {
        val request = mutableStateOf(Any())
        val firstOwner = request.value
        val started = AtomicInteger()
        val finishOld = CompletableDeferred<Unit>()
        var visible: GlobalSearchLookupResult<String>? = null
        composeRule.setContent {
            val owner = request.value
            val result =
                rememberGlobalSearchLookup(owner, true, "GLOBAL_BODY_SEARCH") {
                    if (owner === firstOwner) {
                        started.incrementAndGet()
                        withContext(NonCancellable) { finishOld.await() }
                        "old-account-result"
                    } else {
                        "new-account-result"
                    }
                }
            SideEffect { visible = result }
        }
        advanceLookup()
        composeRule.waitUntil(TIMEOUT_MILLIS) { started.get() == 1 }
        composeRule.runOnIdle { request.value = Any() }
        advanceLookup()
        composeRule.waitUntil(TIMEOUT_MILLIS) { visible?.value == "new-account-result" }
        finishOld.complete(Unit)
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals("new-account-result", visible?.value)
            assertSame(request.value, visible?.request)
        }
    }

    /** Closing a surface drops completed results and prevents its cancelled lookup from publishing. */
    @Test
    fun disablingLookupDropsCompletedResultsAndDoesNotPublishCancelledFailure() {
        val enabled = mutableStateOf(true)
        val finish = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        val request = Any()
        var visible: GlobalSearchLookupResult<String>? = null
        composeRule.setContent {
            val result =
                rememberGlobalSearchLookup<String>(request, enabled.value, "GLOBAL_ATTACHMENT_SEARCH") {
                    started.incrementAndGet()
                    finish.await()
                    "attachment"
                }
            SideEffect { visible = result }
        }
        advanceLookup()
        composeRule.waitUntil(TIMEOUT_MILLIS) { started.get() == 1 }
        composeRule.runOnIdle { enabled.value = false }
        finish.complete(Unit)
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertNull(visible) }
        composeRule.runOnIdle { enabled.value = true }
        advanceLookup()
        composeRule.waitUntil(TIMEOUT_MILLIS) { visible?.value == "attachment" }
        composeRule.runOnIdle { enabled.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertNull(visible) }
    }

    /** An attachment read failure remains distinguishable from a successfully empty library. */
    @Test
    fun attachmentLookupFailureCompletesWithoutFabricatingAnEmptyLibrary() {
        val request = Any()
        var visible: GlobalSearchLookupResult<List<String>>? = null
        composeRule.setContent {
            val result =
                rememberGlobalSearchLookup<List<String>>(request, true, "GLOBAL_ATTACHMENT_SEARCH") {
                    throw IllegalStateException("lookup failed")
                }
            SideEffect { visible = result }
        }
        advanceLookup()
        composeRule.waitUntil(TIMEOUT_MILLIS) { visible?.error != null }
        composeRule.runOnIdle {
            assertSame(request, visible?.request)
            assertNull(visible?.value)
            assertTrue(requireNotNull(visible?.error).retryable)
        }
    }

    /** Starts the replacement effect and advances its real debounce on Compose's virtual clock. */
    private fun advanceLookup() {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(CHAT_LIST_SEARCH_DEBOUNCE_MS)
        composeRule.waitForIdle()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
    }
}
