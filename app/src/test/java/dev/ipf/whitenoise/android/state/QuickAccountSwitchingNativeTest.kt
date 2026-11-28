package dev.ipf.whitenoise.android.state

import android.app.Application
import dev.ipf.marmotkit.AccountSummaryFfi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Avatar taps reach the production setActiveAccount/local-row/preload owner through the native fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QuickAccountSwitchingNativeTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    /** A tap reaches the target's native local snapshot through the production activation owner. */
    @Test fun switchUsesNativeActivation() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context,
                    accounts = listOf(A, B),
                    emitStartupNotification = false,
                )
            try {
                fixture.bootstrap()
                val app = fixture.appState
                app.updateQuickAccountSwitching(true)
                val nativeReads = fixture.directChatListCalls.get()
                var activation: Deferred<Boolean>? = null
                app.requestQuickAccountSwitchTo(B.label) { target ->
                    activation =
                        async {
                            app.setActiveAccount(
                                target,
                                preloadPolicy = AccountSwitchPreloadPolicy.INTERACTIVE_LOCAL_ROWS,
                            )
                        }
                }
                assertTrue(withTimeout(5_000) { checkNotNull(activation).await() })
                assertEquals(B.label, app.activeAccountRef)
                assertEquals(nativeReads + 1, fixture.directChatListCalls.get())
            } finally {
                fixture.close()
            }
        }

    /** Rejected activation leaves the original identity active. */
    @Test fun rejectedNativeActivationKeepsOriginalAccount() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context,
                    accounts = listOf(A, B),
                    emitStartupNotification = false,
                )
            try {
                fixture.bootstrap()
                val app = fixture.appState
                app.updateQuickAccountSwitching(true)
                var activation: Deferred<Boolean>? = null
                app.requestQuickAccountSwitchTo(B.label) { target ->
                    activation = async { app.setActiveAccount(target, shouldActivate = { false }) }
                }
                assertFalse(withTimeout(5_000) { checkNotNull(activation).await() })
                assertEquals(A.label, app.activeAccountRef)
            } finally {
                fixture.close()
            }
        }

    /** The target is re-resolved at the tap, so an account removed since the last frame starts no activation. */
    @Test fun removedTargetIsRejectedBeforeNativeRequest() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context,
                    accounts = listOf(A, B, C),
                    emitStartupNotification = false,
                )
            try {
                fixture.bootstrap()
                val app = fixture.appState
                app.updateQuickAccountSwitching(true)
                assertEquals(listOf(B.label, C.label), app.quickSwitchAvatarAccounts().map { it.label })
                WhiteNoiseAppState::class.java
                    .getDeclaredMethod("setAccounts", List::class.java)
                    .apply { isAccessible = true }
                    .invoke(app, listOf(A, C))

                app.requestQuickAccountSwitchTo(B.label) { error("Removed target must reject") }

                var activation: Deferred<Boolean>? = null
                app.requestQuickAccountSwitchTo(C.label) { target ->
                    assertEquals(C.label, target)
                    activation = async { app.setActiveAccount(target) }
                }
                assertTrue(withTimeout(5_000) { checkNotNull(activation).await() })
                assertEquals(C.label, app.activeAccountRef)
            } finally {
                fixture.close()
            }
        }

    private companion object {
        val A = AccountSummaryFfi("a", "aa".repeat(32), true, false, false, true, false)
        val B = AccountSummaryFfi("b", "bb".repeat(32), true, false, false, true, false)
        val C = AccountSummaryFfi("c", "cc".repeat(32), true, false, false, true, false)
    }
}
