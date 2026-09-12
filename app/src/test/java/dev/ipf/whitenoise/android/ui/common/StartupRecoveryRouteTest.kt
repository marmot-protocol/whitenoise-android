package dev.ipf.whitenoise.android.ui.common

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.BootstrapAttemptCoordinator
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.WhiteNoiseApp
import dev.ipf.whitenoise.android.ui.navigation.MainShellStateHolder
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drives actual app phase routing and bootstrap retry against an existing process-owned attempt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class StartupRecoveryRouteTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pendingAttempt = CompletableDeferred<Unit>()
    private val visible = mutableStateOf(true)
    private var timeout = 10L
    private var nativeConstructions = 0
    private lateinit var app: WhiteNoiseAppState
    private lateinit var shell: MainShellStateHolder
    private lateinit var attempts: BootstrapAttemptCoordinator

    /** An actual actionable timeout exposes recovery; only its explicit Retry restores the loading tag. */
    @Test fun retryReawaitsTheExistingAttemptWithoutCreatingAnotherRuntime() {
        show()
        try {
            waitForFailure()
            composeRule.onNodeWithTag(STARTUP_FAILURE_TEST_TAG).assertExists()
            composeRule.onNodeWithTag(WARM_RESUME_USEFUL_SURFACE_TEST_TAG).assertExists()
            composeRule.runOnIdle { timeout = 10_000L }
            composeRule.onNodeWithTag(STARTUP_RETRY_TEST_TAG).performClick()
            composeRule.waitUntil { app.phase == AppPhase.Bootstrapping }
            composeRule.onNodeWithTag(STARTUP_LOADING_TEST_TAG).assertExists()
            composeRule.onNodeWithTag(WARM_RESUME_USEFUL_SURFACE_TEST_TAG).assertDoesNotExist()
            assertSame(pendingAttempt, runBlocking { attempts.currentOrStart { error("duplicate bootstrap") } })
            assertEquals(0, nativeConstructions)
        } finally {
            close()
        }
    }

    /** Recreating the real root reattaches bootstrap but cannot hide an actionable failure before Retry. */
    @Test fun compositionReentryKeepsFailureInteractiveWhileItsNativeAttemptIsPending() {
        show()
        try {
            waitForFailure()
            composeRule.runOnIdle {
                timeout = 10_000L
                visible.value = false
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle { visible.value = true }
            composeRule.onNodeWithTag(STARTUP_FAILURE_TEST_TAG).assertExists()
            composeRule.onNodeWithTag(STARTUP_LOADING_TEST_TAG).assertDoesNotExist()
            composeRule.onNodeWithTag(WARM_RESUME_USEFUL_SURFACE_TEST_TAG).assertExists()
            assertEquals(0, nativeConstructions)
        } finally {
            close()
        }
    }

    /** Seeds only the process attempt boundary; AppState itself produces the timeout failure and handles Retry. */
    private fun show() {
        app =
            WhiteNoiseAppState(
                context = context,
                draftStore =
                    DraftStore(
                        object : DraftPersistence {
                            override fun read(): Map<String, String> = emptyMap()

                            override fun write(
                                key: String,
                                value: String?,
                            ) = Unit
                        },
                    ),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = "",
                bootstrapActionableTimeoutMillis = { timeout },
                marmotRuntimeFactory = {
                    nativeConstructions++
                    error("native bootstrap must reuse the seeded attempt")
                },
            )
        app.markDefaultNotificationsEnableAttempted()
        attempts =
            WhiteNoiseAppState::class.java.getDeclaredField("bootstrapAttempts").let {
                it.isAccessible = true
                it.get(app) as BootstrapAttemptCoordinator
            }
        runBlocking { attempts.currentOrStart { pendingAttempt } }
        shell = MainShellStateHolder(app, SavedStateHandle())
        composeRule.setContent {
            WhiteNoiseTheme {
                if (visible.value) WhiteNoiseApp(app, shell, 0, 0)
            }
        }
    }

    /** Waits on actual process state, never replaces the route with a test-only state machine. */
    private fun waitForFailure() {
        composeRule.waitUntil(timeoutMillis = 5_000L) { app.phase is AppPhase.Failed }
        composeRule.waitForIdle()
    }

    /** Releases the seeded work and actual shell so no pending bootstrap collector survives the fixture. */
    private fun close() {
        pendingAttempt.complete(Unit)
        composeRule.runOnIdle {
            visible.value = false
            shell.release()
        }
        composeRule.waitForIdle()
    }
}
