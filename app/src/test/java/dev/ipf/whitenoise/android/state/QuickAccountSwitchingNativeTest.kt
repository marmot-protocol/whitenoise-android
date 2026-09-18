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

    /** A tap publishes the target's native local snapshot before its one destination confirmation. */
    @Test fun switchUsesNativeActivationAndConfirmsOnlyTheActualAccount() =
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
                val titleAtTap = app.accountDisplayNameCached(B.accountIdHex)
                val notices = mutableListOf<String>()
                var activation: Deferred<Boolean>? = null
                var accountAtNotice: String? = null
                app.requestQuickAccountSwitchTo(
                    targetLabel = B.label,
                    requestSwitch = { target, activated ->
                        activation =
                            async {
                                app.setActiveAccount(
                                    target,
                                    preloadPolicy = AccountSwitchPreloadPolicy.INTERACTIVE_LOCAL_ROWS,
                                    onActivated = activated,
                                )
                            }
                    },
                    onSwitched = { title ->
                        accountAtNotice = app.activeAccountRef
                        notices += title
                    },
                )
                assertTrue(withTimeout(5_000) { checkNotNull(activation).await() })
                assertEquals(B.label, app.activeAccountRef)
                assertEquals(B.label, accountAtNotice)
                assertEquals(listOf(titleAtTap), notices)
                assertEquals(nativeReads + 1, fixture.directChatListCalls.get())
            } finally {
                fixture.close()
            }
        }

    /** Rejected activation returns no confirmation and leaves the original identity active. */
    @Test fun rejectedNativeActivationDoesNotClaimSwitchSuccess() =
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
                val notices = mutableListOf<String>()
                app.requestQuickAccountSwitchTo(
                    targetLabel = B.label,
                    requestSwitch = { target, activated ->
                        activation =
                            async {
                                app.setActiveAccount(target, shouldActivate = { false }, onActivated = activated)
                            }
                    },
                    onSwitched = { notices += it },
                )
                assertFalse(withTimeout(5_000) { checkNotNull(activation).await() })
                assertEquals(A.label, app.activeAccountRef)
                assertTrue(notices.isEmpty())
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

                app.requestQuickAccountSwitchTo(B.label, { _, _ -> error("Removed target must reject") }, {})

                var activation: Deferred<Boolean>? = null
                app.requestQuickAccountSwitchTo(
                    targetLabel = C.label,
                    requestSwitch = { target, activated ->
                        assertEquals(C.label, target)
                        activation = async { app.setActiveAccount(target, onActivated = activated) }
                    },
                    onSwitched = {},
                )
                assertTrue(withTimeout(5_000) { checkNotNull(activation).await() })
                assertEquals(C.label, app.activeAccountRef)
            } finally {
                fixture.close()
            }
        }

    private companion object {
        val A = AccountSummaryFfi("a", "aa".repeat(32), true, false, false, true)
        val B = AccountSummaryFfi("b", "bb".repeat(32), true, false, false, true)
        val C = AccountSummaryFfi("c", "cc".repeat(32), true, false, false, true)
    }
}
