package dev.ipf.whitenoise.android.ui.account

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AccountSwitchLocalSnapshotHandoff
import dev.ipf.whitenoise.android.state.NotificationBootstrapTestFixture
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual rendered sheet callbacks cross a held native local-row boundary, never an assigned fake active profile. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ProfileSwitcherNativeDeliveryTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun repeatedTargetSelectsOnceAndDismissesOnlyAfterNativeActivation() = heldSelection(Mode.Activate)

    @Test fun closeDuringNativeReadRejectsLateActivationAndNavigation() = heldSelection(Mode.Close)

    @Test fun wipeDuringNativeReadRejectsLateActivationAndNavigation() = heldSelection(Mode.Wipe)

    @Test fun signOutDuringNativeReadRejectsLateActivationAndNavigation() = heldSelection(Mode.SignOut)

    @Test fun disposedSheetRejectsLateActivationAndNavigation() = heldSelection(Mode.Dispose)

    @Test fun removalDuringNativeReadRejectsLateActivationAndNavigation() = heldSelection(Mode.Remove)

    @Test fun callbackOnlyRecompositionUsesCurrentCompletion() = heldSelection(Mode.Callback)

    @Test fun newerRowSelectionSupersedesHeldNativeTarget() = heldSelection(Mode.Supersede)

    /** Holds the synchronous FFI read on native IO, then waits for the production request's terminal fence. */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun heldSelection(mode: Mode) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val fixture =
            NotificationBootstrapTestFixture(
                context,
                accounts = listOf(A, B, C),
                emitStartupNotification = false,
                onPresentedChatList = { label ->
                    if (label == B.label) {
                        reads.incrementAndGet()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    emptyList()
                },
            )
        try {
            runBlocking { fixture.bootstrap() }
            val app = fixture.appState
            var visible by mutableStateOf(true)
            var latestCallback by mutableStateOf(false)
            var dismissals = 0
            var oldCompletions = 0
            var newCompletions = 0
            composeRule.setContent {
                WhiteNoiseTheme {
                    if (visible) {
                        AccountSelectorSheet(
                            app,
                            onDismiss = {
                                dismissals++
                                visible = false
                            },
                            onAddAccount = {},
                            onAccountSwitched =
                                if (latestCallback) {
                                    { newCompletions++ }
                                } else {
                                    { oldCompletions++ }
                                },
                        )
                    }
                }
            }
            composeRule.onNodeWithTag("profile_switcher.profile.b").performClick()
            composeRule.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idle()
                reads.get() == 1
            }
            composeRule.onNodeWithTag("profile_switcher.profile.b").performClick()
            assertEquals(1, reads.get())
            assertEquals("a", app.activeAccountRef)
            assertEquals(0, oldCompletions)
            when (mode) {
                Mode.Close -> composeRule.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
                Mode.Wipe -> composeRule.runOnIdle { app.wipeInProgress = true }
                Mode.SignOut -> composeRule.runOnIdle { app.signOutInProgress = true }
                Mode.Dispose -> composeRule.runOnIdle { visible = false }
                Mode.Remove ->
                    composeRule.runOnIdle {
                        WhiteNoiseAppState::class.java
                            .getDeclaredMethod("setAccounts", List::class.java)
                            .apply { isAccessible = true }
                            .invoke(app, listOf(A, C))
                    }
                Mode.Callback -> composeRule.runOnIdle { latestCallback = true }
                Mode.Supersede -> {
                    composeRule.onNodeWithTag("profile_switcher.profile.c").performClick()
                    composeRule.waitUntil(5_000) {
                        shadowOf(Looper.getMainLooper()).idle()
                        app.activeAccountRef == "c"
                    }
                }
                Mode.Activate -> Unit
            }
            release.countDown()
            val handoff =
                WhiteNoiseAppState::class.java
                    .getDeclaredField("accountSwitchHandoff")
                    .apply { isAccessible = true }
                    .get(app) as AccountSwitchLocalSnapshotHandoff
            composeRule.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idle()
                handoff.captureForAccount("a") != null
            }
            composeRule.waitForIdle()
            val expected =
                when (mode) {
                    Mode.Activate, Mode.Callback -> "b"
                    Mode.Supersede -> "c"
                    else -> "a"
                }
            assertEquals(expected, app.activeAccountRef)
            assertEquals(if (mode == Mode.Callback) 1 else 0, newCompletions)
            assertEquals(if (mode == Mode.Activate || mode == Mode.Supersede) 1 else 0, oldCompletions)
            assertEquals(if (mode == Mode.Remove || mode == Mode.Dispose) 0 else 1, dismissals)
        } finally {
            release.countDown()
            fixture.close()
        }
    }

    private enum class Mode { Activate, Close, Wipe, SignOut, Dispose, Remove, Callback, Supersede }

    private companion object {
        val A = AccountSummaryFfi("a", "aa".repeat(32), true, false, false, true)
        val B = AccountSummaryFfi("b", "bb".repeat(32), true, false, false, true)
        val C = AccountSummaryFfi("c", "cc".repeat(32), true, false, false, true)
    }
}
