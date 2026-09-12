package dev.ipf.whitenoise.android.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.onboarding.setup.setupSnapshot
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual native setup acceptance cleans only its own submitted clipboard key, including late completion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class AddProfileImportClipboardTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val key = "nsec1" + "q".repeat(58)

    /** Closing and reopening while native begin is held must preserve a newly copied different private value. */
    @Test fun lateSuccessfulImportPreservesReplacementClipboard() = acceptedImport(replaceAfterDismissal = true)

    /** Successful native acceptance still removes the submitted key when it remains the single plaintext clip. */
    @Test fun successfulImportClearsStillMatchingSubmittedClipboard() = acceptedImport(replaceAfterDismissal = false)

    /** Returns a genuine setup snapshot through the existing native begin/open route; it never fabricates Ready. */
    @Suppress("LongMethod") // Held native acceptance and clipboard replacement share one lifecycle fixture.
    private fun acceptedImport(replaceAfterDismissal: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val snapshot = setupSnapshot(actions = emptyList())
        val native =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "beginOnboarding" -> {
                        assertEquals(key, args!![0])
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        snapshot
                    }
                    "toString" -> "HeldAcceptedImportFixture"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    // Subscription work is outside this held begin/acceptance regression. Its native error
                    // remains a setup error; no fake progress, publication or activation is returned.
                    else -> throw UnsupportedOperationException("Unused native test call: ${method.name}")
                }
            } as MarmotInterface
        val account = AccountSummaryFfi("alice", "11".repeat(32), true, false, false, true)
        val app =
            WhiteNoiseAppState(
                context,
                DraftStore.forContext(context),
                { null },
                listOf(account),
                "alice",
                initialMarmotRuntime = AppMarmotRuntime(context.cacheDir.resolve("held-import-clipboard").path, native),
            )
        val shown = mutableStateOf(true)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val replacement = "nsec1" + "p".repeat(58)
        try {
            composeRule.setContent {
                WhiteNoiseTheme { if (shown.value) AddIdentitySheet(app) { shown.value = false } }
            }
            composeRule.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("", "  $key  ")) }
            composeRule.onNodeWithTag("onboarding.welcome.sign_in").performClick()
            composeRule.onNodeWithTag("onboarding.sign_in.private_key").performTextInput(key)
            composeRule.onNodeWithTag("onboarding.sign_in.action").performClick()
            composeRule.waitUntil {
                shadowOf(Looper.getMainLooper()).idle()
                entered.count == 0L
            }
            if (replaceAfterDismissal) {
                composeRule.runOnIdle {
                    (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
                }
                composeRule.waitForIdle()
                assertFalse(shown.value)
                composeRule.runOnIdle {
                    shown.value = true
                    clipboard.setPrimaryClip(ClipData.newPlainText("", replacement))
                }
                composeRule.onNodeWithTag("onboarding.welcome.sign_in").assertExists()
            }
            release.countDown()
            composeRule.waitUntil {
                shadowOf(Looper.getMainLooper()).idle()
                app.accountSetup.controller != null
            }
            // Native acceptance triggers state-driven dismissal; settle its composition before asserting it.
            composeRule.waitForIdle()
            assertFalse(shown.value)
            assertEquals("alice", app.activeAccountRef)
            assertTrue(app.accountSetup.controller != null)
            if (replaceAfterDismissal) {
                assertEquals(
                    replacement,
                    clipboard.primaryClip
                        ?.getItemAt(0)
                        ?.text
                        ?.toString(),
                )
            } else {
                assertFalse(clipboard.hasPrimaryClip())
            }
        } finally {
            release.countDown()
            composeRule.runOnIdle {
                shown.value = false
                app.launchMutation { app.accountSetup.close() }
            }
            composeRule.waitForIdle()
        }
    }
}
