package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real key-screen confirmations, lifecycle observer, document launcher and returned-URI callback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1200dp-mdpi")
class AccountKeysExportDeliveryTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var currentNative: NativeExports? = null

    /** Always releases the bounded blocking fake, even when an assertion fails before cancellation. */
    @After
    fun releaseNative() {
        currentNative?.finishRaw()
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Opening the file picker stops the screen, but an authorized raw export still writes on an idle return. */
    @Test
    fun stoppedRawPickerRetainsItsAuthorizedExport() {
        assertPickerReturn(encrypted = false, blockedBy = null)
    }

    /** The encrypted export also survives the ordinary picker stop/start cycle without retaining its passwords. */
    @Test
    fun stoppedEncryptedPickerRetainsItsAuthorizedExport() {
        assertPickerReturn(encrypted = true, blockedBy = null)
    }

    /** A pending raw export must not write during sign-out before the active account reference has changed. */
    @Test
    fun rawPickerReturnDuringSignOutDoesNotOpenTheDestination() {
        assertPickerReturn(encrypted = false, blockedBy = Teardown.SignOut)
    }

    /** The same guard applies to retained encrypted backup content. */
    @Test
    fun encryptedPickerReturnDuringSignOutDoesNotOpenTheDestination() {
        assertPickerReturn(encrypted = true, blockedBy = Teardown.SignOut)
    }

    /** Wipe may still own the original account while native teardown is suspended; raw export is already revoked. */
    @Test
    fun rawPickerReturnDuringWipeDoesNotOpenTheDestination() {
        assertPickerReturn(encrypted = false, blockedBy = Teardown.Wipe)
    }

    /** Wipe blocks file delivery of a pending encrypted backup as well. */
    @Test
    fun encryptedPickerReturnDuringWipeDoesNotOpenTheDestination() {
        assertPickerReturn(encrypted = true, blockedBy = Teardown.Wipe)
    }

    /** Cancelling the real raw confirmation revokes its pending native request before a late result can launch. */
    @Test
    fun cancelledRawConfirmationDoesNotLaunchItsLateNativeResult() {
        val fixture = render(deferRaw = true)
        beginExport(encrypted = false)
        composeRule.waitUntil(TIMEOUT_MILLIS) { fixture.native.rawStarted.get() }
        dialogButton(R.string.cancel).performClick()
        fixture.native.finishRaw()
        composeRule.waitUntil(TIMEOUT_MILLIS) { fixture.native.rawFinished.get() }
        composeRule.waitForIdle()
        assertEquals(0, fixture.picker.launchCount)
        assertEquals(1, fixture.native.rawCalls.get())
    }

    /** Drives the actual returned-URI callback, recording whether the ContentResolver ever opens a destination. */
    private fun assertPickerReturn(
        encrypted: Boolean,
        blockedBy: Teardown?,
    ) {
        val fixture = render()
        val uri = Uri.parse("content://key-export-test/authorized-key")
        val bytes = ByteArrayOutputStream()
        val opens = AtomicInteger()
        shadowOf(context.contentResolver).registerOutputStreamSupplier(uri) {
            opens.incrementAndGet()
            bytes
        }
        beginExport(encrypted)
        composeRule.waitUntil(TIMEOUT_MILLIS) { fixture.picker.launchCount == 1 }
        composeRule.runOnIdle {
            fixture.owner.stop()
            fixture.owner.resume()
            when (blockedBy) {
                Teardown.SignOut -> fixture.appState.signOutInProgress = true
                Teardown.Wipe -> fixture.appState.wipeInProgress = true
                null -> Unit
            }
            // Read the live flag in the callback, even before Compose has rendered the changed state.
            assertEquals(ACCOUNT_REF, fixture.appState.activeAccountRef)
            fixture.picker.deliver(uri)
        }
        composeRule.waitForIdle()
        if (blockedBy == null) {
            assertEquals(1, opens.get())
            assertEquals(if (encrypted) ENCRYPTED_KEY else RAW_KEY, bytes.toString(Charsets.UTF_8.name()))
        } else {
            assertEquals(0, opens.get())
            assertEquals(0, bytes.size())
        }
        assertEquals(if (encrypted) 1 else 0, fixture.native.encryptedCalls.get())
        assertEquals(if (encrypted) 0 else 1, fixture.native.rawCalls.get())
    }

    /** Uses the screen's real menu rows and consequence/password dialogs rather than assigning private UI state. */
    private fun beginExport(encrypted: Boolean) {
        if (encrypted) {
            composeRule
                .onNodeWithText(context.getString(R.string.export_encrypted_private_key))
                .performScrollTo()
                .performClick()
            composeRule.onNodeWithTag("profile_keys.export_password").performTextInput(PASSPHRASE)
            composeRule.onNodeWithTag("profile_keys.export_confirmation").performTextInput(PASSPHRASE)
            dialogButton(R.string.export).performClick()
        } else {
            composeRule.onNodeWithTag("profile_keys.export_raw").performScrollTo().performClick()
            dialogButton(R.string.export_nsec).performClick()
        }
    }

    /** Restricts the match to the dialog because the underlying screen contains the same export label. */
    private fun dialogButton(labelRes: Int) =
        composeRule.onNode(
            hasText(context.getString(labelRes)) and hasClickAction() and hasAnyAncestor(isDialog()),
        )

    /** Injects only synthetic native outputs and a recording registry; no file picker, identity or device is used. */
    private fun render(deferRaw: Boolean = false): Fixture {
        val native = NativeExports(deferRaw).also { currentNative = it }
        val appState =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { null },
                accounts = listOf(account()),
                activeAccountRef = ACCOUNT_REF,
                initialMarmotRuntime = AppMarmotRuntime(rootPath = "test", marmot = native.marmot),
            )
        val picker = RecordingPicker()
        val owner = composeRule.runOnIdle { ScreenLifecycle().apply { resume() } }
        composeRule.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides picker,
                LocalLifecycleOwner provides owner,
            ) {
                WhiteNoiseTheme {
                    AccountKeysScreen(appState = appState, onBack = {})
                }
            }
        }
        return Fixture(appState, picker, owner, native)
    }

    /** Locally signed account metadata only; the synthetic key is supplied by the injected native proxy. */
    private fun account() =
        AccountSummaryFfi(
            label = ACCOUNT_REF,
            accountIdHex = "a".repeat(64),
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private data class Fixture(
        val appState: WhiteNoiseAppState,
        val picker: RecordingPicker,
        val owner: ScreenLifecycle,
        val native: NativeExports,
    )

    private enum class Teardown { SignOut, Wipe }

    /** Records launch and uses ActivityResultRegistry's own dispatch to invoke the registered screen callback. */
    private class RecordingPicker :
        ActivityResultRegistry(),
        ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        var launchCount = 0
            private set
        private var requestCode: Int? = null

        /** Captures the document request without handing anything to an external application. */
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            this.requestCode = requestCode
            launchCount++
        }

        /** Delivers the picked URI through the same registry used by rememberLauncherForActivityResult. */
        fun deliver(uri: Uri) {
            assertTrue(dispatchResult(requireNotNull(requestCode), uri))
        }
    }

    /** Independent lifecycle makes the file-picker stop/start contract deterministic without destroying composition. */
    private class ScreenLifecycle : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        /** Moves through ON_CREATE/ON_START/ON_RESUME when needed. */
        fun resume() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        /** Delivers ON_PAUSE and ON_STOP, exercising the actual screen's sensitive-operation observer. */
        fun stop() {
            registry.currentState = Lifecycle.State.CREATED
        }
    }

    /** Native API proxy models revealNsec as the pinned ABI’s synchronous blocking call on the IO dispatcher. */
    private class NativeExports(
        private val deferRaw: Boolean,
    ) {
        val rawCalls = AtomicInteger()
        val encryptedCalls = AtomicInteger()
        val rawStarted = AtomicBoolean(false)
        private val rawRelease = CountDownLatch(1)
        val rawFinished = AtomicBoolean(false)
        val marmot =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "npub" -> "npub1testpublickey"
                    "revealNsec" -> raw(arguments)
                    "exportEncryptedSecretKey" -> {
                        encryptedCalls.incrementAndGet()
                        check(arguments?.get(0) == ACCOUNT_REF)
                        check(arguments?.get(1) == PASSPHRASE)
                        ENCRYPTED_KEY
                    }
                    "toString" -> "NativeExportsTestProxy"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> error("Unexpected native export call: ${method.name}")
                }
            } as MarmotInterface

        /** Holds the raw result only for the cancellation test; ordinary positive controls complete immediately. */
        private fun raw(arguments: Array<out Any?>?): Any {
            rawCalls.incrementAndGet()
            check(arguments?.get(0) == ACCOUNT_REF)
            if (!deferRaw) return RAW_KEY
            rawStarted.set(true)
            try {
                check(rawRelease.await(15, TimeUnit.SECONDS)) { "Test did not release native key export" }
                return RAW_KEY
            } finally {
                rawFinished.set(true)
            }
        }

        /** Completes the recorded native call even when its coroutine has already been cancelled. */
        fun finishRaw() {
            rawRelease.countDown()
        }
    }

    private companion object {
        const val ACCOUNT_REF = "key-export-test"
        const val RAW_KEY = "synthetic-raw-private-key-for-test"
        const val ENCRYPTED_KEY = "synthetic-encrypted-private-key-for-test"
        const val PASSPHRASE = "Long test passphrase 48!"
        const val TIMEOUT_MILLIS = 5_000L
    }
}
