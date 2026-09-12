package dev.ipf.whitenoise.android.ui.settings

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
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
        currentNative?.finishEncrypted()
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

    /** Password drafts are gone and inputs disabled before the synchronous native encryption returns. */
    @Test
    fun blockedEncryptionClearsAndDisablesBothPasswordsBeforeReturning() {
        val fixture = render(deferEncrypted = true)
        beginExport(encrypted = true)
        composeRule.waitUntil(TIMEOUT_MILLIS) { fixture.native.encryptedStarted.get() }
        assertClearedPasswordDrafts()
        assertEquals(0, fixture.picker.launchCount)
        fixture.native.finishEncrypted()
        composeRule.waitUntil(TIMEOUT_MILLIS) { fixture.picker.launchCount == 1 }
        assertEquals(1, fixture.native.encryptedCalls.get())
    }

    /** Canceling View backup discards late native output without opening a destination or preview. */
    @Test
    fun cancelledEncryptedPreviewDiscardsItsBlockedNativeResult() {
        val fixture = render(deferEncrypted = true)
        beginExport(encrypted = true, encryptedAction = R.string.key_export_view_backup)
        composeRule.waitUntil(TIMEOUT_MILLIS) { fixture.native.encryptedStarted.get() }
        assertClearedPasswordDrafts()
        dialogButton(R.string.cancel).performClick()
        fixture.native.finishEncrypted()
        composeRule.waitUntil(TIMEOUT_MILLIS) { fixture.native.encryptedFinished.get() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("profile_keys.encrypted_backup").assertDoesNotExist()
        assertEquals(0, fixture.picker.launchCount)
        assertEquals(1, fixture.native.encryptedCalls.get())
    }

    /** Copy preserves the exact sensitive native value; Hide discards it without opening a file picker. */
    @Test
    fun encryptedPreviewCopiesExactSensitiveValueAndHidesWithoutPickingAFile() {
        val fixture = render()
        openBackupPreview()
        assertEquals(0, fixture.picker.launchCount)
        dialogButton(R.string.copy).performClick()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        composeRule.runOnIdle {
            val clip = requireNotNull(clipboard.primaryClip)
            assertEquals(ENCRYPTED_KEY, clip.getItemAt(0).text.toString())
            assertEquals(context.getString(R.string.encrypted_backup_result_title), clip.description.label.toString())
            assertTrue(clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)
        }
        dialogButton(R.string.hide).performClick()
        composeRule.onNodeWithTag("profile_keys.encrypted_backup").assertDoesNotExist()
        assertEquals(0, fixture.picker.launchCount)
    }

    /** The optional file action reuses the confirmed preview bytes, without requesting native encryption again. */
    @Test
    fun exportingThePreviewUsesTheSameEncryptedValue() {
        val fixture = render()
        val uri = Uri.parse("content://key-export-test/preview-key")
        val bytes = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStreamSupplier(uri) { bytes }
        openBackupPreview()
        dialogButton(R.string.export).performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { fixture.picker.launchCount == 1 }
        composeRule.runOnIdle { fixture.picker.deliver(uri) }
        assertEquals(ENCRYPTED_KEY, bytes.toString(Charsets.UTF_8.name()))
        assertEquals(1, fixture.native.encryptedCalls.get())
        composeRule.onNodeWithTag("profile_keys.encrypted_backup").assertDoesNotExist()
    }

    /** Foreground departure clears an encrypted preview and resume does not reveal it again. */
    @Test
    fun stoppedEncryptedPreviewStaysHiddenAfterResume() {
        val fixture = render()
        openBackupPreview()
        composeRule.runOnIdle {
            fixture.owner.stop()
            fixture.owner.resume()
        }
        composeRule.onNodeWithTag("profile_keys.encrypted_backup").assertDoesNotExist()
        assertEquals(0, fixture.picker.launchCount)
    }

    /** Native teardown revokes the visible backup even while the same account reference is still active. */
    @Test
    fun wipeClearsEncryptedPreviewBeforeAccountRemoval() {
        val fixture = render()
        openBackupPreview()
        composeRule.runOnIdle { fixture.appState.wipeInProgress = true }
        composeRule.onNodeWithTag("profile_keys.encrypted_backup").assertDoesNotExist()
        assertEquals(ACCOUNT_REF, fixture.appState.activeAccountRef)
        assertEquals(0, fixture.picker.launchCount)
    }

    /** The result's 30-second timer clears the preview without requiring a second user gesture. */
    @Test
    fun encryptedPreviewExpiresAfterThirtySeconds() {
        render()
        openBackupPreview()
        composeRule.mainClock.advanceTimeBy(30_001L)
        composeRule.onNodeWithTag("profile_keys.encrypted_backup").assertDoesNotExist()
    }

    /** The raw confirmation preserves the legacy explicit sensitive share transport without opening a file picker. */
    @Test
    fun rawShareMarksBothIntentAndPayloadSensitive() {
        val fixture = render()
        composeRule.onNodeWithTag("profile_keys.export_raw").performScrollTo().performClick()
        dialogButton(R.string.share).performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { shadowOf(composeRule.activity).peekNextStartedActivity() != null }
        val chooser = shadowOf(composeRule.activity).nextStartedActivity
        assertNotNull(chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val target = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, target.action)
        assertEquals("text/plain", target.type)
        assertEquals(RAW_KEY, target.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(target.getBooleanExtra(ClipDescription.EXTRA_IS_SENSITIVE, false))
        val clip = requireNotNull(target.clipData)
        assertEquals(RAW_KEY, clip.getItemAt(0).text.toString())
        assertTrue(clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)
        assertEquals(0, fixture.picker.launchCount)
    }

    /** The added share action follows the same generation guard as file export after cancellation. */
    @Test
    fun cancelledRawShareDoesNotDispatchALateNativeSecret() {
        val fixture = render(deferRaw = true)
        composeRule.onNodeWithTag("profile_keys.export_raw").performScrollTo().performClick()
        dialogButton(R.string.share).performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { fixture.native.rawStarted.get() }
        dialogButton(R.string.cancel).performClick()
        fixture.native.finishRaw()
        composeRule.waitUntil(TIMEOUT_MILLIS) { fixture.native.rawFinished.get() }
        composeRule.waitForIdle()
        assertNull(shadowOf(composeRule.activity).nextStartedActivity)
        assertEquals(0, fixture.picker.launchCount)
    }

    /** The preview retains its original deadline when handed to a picker instead of receiving another 30 seconds. */
    @Test
    fun previewFileReturnAfterOriginalDeadlineDoesNotWrite() {
        val fixture = render()
        val uri = Uri.parse("content://key-export-test/expired-preview")
        val opens = AtomicInteger()
        shadowOf(context.contentResolver).registerOutputStreamSupplier(uri) {
            opens.incrementAndGet()
            ByteArrayOutputStream()
        }
        openBackupPreview()
        composeRule.runOnIdle { ShadowSystemClock.advanceBy(20, TimeUnit.SECONDS) }
        dialogButton(R.string.export).performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) { fixture.picker.launchCount == 1 }
        composeRule.runOnIdle {
            ShadowSystemClock.advanceBy(11, TimeUnit.SECONDS)
            fixture.picker.deliver(uri)
        }
        assertEquals(0, opens.get())
        assertEquals(1, fixture.native.encryptedCalls.get())
    }

    /** A stale rendered Copy action reads the live teardown flag before touching the clipboard. */
    @Test
    fun encryptedCopyDuringSignOutDoesNotReplaceTheClipboard() {
        val fixture = render()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        composeRule.runOnIdle { clipboard.clearPrimaryClip() }
        openBackupPreview()
        val copy =
            requireNotNull(dialogButton(R.string.copy).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        composeRule.runOnIdle {
            fixture.appState.signOutInProgress = true
            assertTrue(copy())
            assertNull(clipboard.primaryClip)
        }
        composeRule.onNodeWithTag("profile_keys.encrypted_backup").assertDoesNotExist()
    }

    /** A native encryption failure is identified accurately and allows retry only with newly entered passwords. */
    @Test
    fun failedEncryptedPreviewAllowsExplicitRetryWithFreshPassphrase() {
        val fixture = render(deferEncrypted = true, failFirstEncryption = true)
        beginExport(encrypted = true, encryptedAction = R.string.key_export_view_backup)
        composeRule.waitUntil(TIMEOUT_MILLIS) { fixture.native.encryptedStarted.get() }
        assertClearedPasswordDrafts()
        fixture.native.finishEncrypted()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule
                .onAllNodes(hasText(context.getString(R.string.toast_couldnt_create_encrypted_backup)))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText(context.getString(R.string.couldnt_save_file)).assertDoesNotExist()
        assertEquals(0, fixture.picker.launchCount)
        dialogButton(R.string.ok).performClick()
        dialogButton(R.string.key_export_view_backup).assertIsNotEnabled()
        composeRule.onNodeWithTag("profile_keys.export_password").assertIsEnabled().performTextInput(PASSPHRASE)
        composeRule.onNodeWithTag("profile_keys.export_confirmation").assertIsEnabled().performTextInput(PASSPHRASE)
        dialogButton(R.string.key_export_view_backup).performClick()
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule
                .onAllNodes(hasText(context.getString(R.string.encrypted_backup_result_title)))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("profile_keys.encrypted_backup").assertExists()
        assertEquals(2, fixture.native.encryptedCalls.get())
        assertEquals(0, fixture.picker.launchCount)
    }

    /** Checks the actual secure input semantics while the native worker is still blocked. */
    private fun assertClearedPasswordDrafts() {
        listOf("profile_keys.export_password", "profile_keys.export_confirmation").forEach { tag ->
            val field = composeRule.onNodeWithTag(tag).assertIsNotEnabled()
            assertEquals("", field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        }
    }

    /** Opens the real optional encrypted result; its content is synthetic native output only. */
    private fun openBackupPreview() {
        beginExport(encrypted = true, encryptedAction = R.string.key_export_view_backup)
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule
                .onAllNodes(hasText(context.getString(R.string.encrypted_backup_result_title)))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("profile_keys.encrypted_backup").assertExists()
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
    private fun beginExport(
        encrypted: Boolean,
        encryptedAction: Int = R.string.export,
    ) {
        if (encrypted) {
            composeRule
                .onNodeWithText(context.getString(R.string.export_encrypted_private_key))
                .performScrollTo()
                .performClick()
            composeRule.onNodeWithTag("profile_keys.export_password").performTextInput(PASSPHRASE)
            composeRule.onNodeWithTag("profile_keys.export_confirmation").performTextInput(PASSPHRASE)
            dialogButton(encryptedAction).performClick()
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
    private fun render(
        deferRaw: Boolean = false,
        deferEncrypted: Boolean = false,
        failFirstEncryption: Boolean = false,
    ): Fixture {
        val native = NativeExports(deferRaw, deferEncrypted, failFirstEncryption).also { currentNative = it }
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
        private val deferEncrypted: Boolean,
        failFirstEncryption: Boolean,
    ) {
        val rawCalls = AtomicInteger()
        val encryptedCalls = AtomicInteger()
        val rawStarted = AtomicBoolean(false)
        private val rawRelease = CountDownLatch(1)
        val rawFinished = AtomicBoolean(false)
        private val encryptionFailurePending = AtomicBoolean(failFirstEncryption)
        val encryptedStarted = AtomicBoolean(false)
        private val encryptedRelease = CountDownLatch(1)
        val encryptedFinished = AtomicBoolean(false)
        val marmot =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "npub" -> "npub1testpublickey"
                    "revealNsec" -> raw(arguments)
                    "exportEncryptedSecretKey" -> encrypted(arguments)
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

        /** The pinned encrypted export ABI is also synchronous; the IO worker remains blocked until released. */
        private fun encrypted(arguments: Array<out Any?>?): String {
            encryptedCalls.incrementAndGet()
            check(arguments?.get(0) == ACCOUNT_REF)
            check(arguments?.get(1) == PASSPHRASE)
            if (!deferEncrypted) return ENCRYPTED_KEY
            encryptedStarted.set(true)
            try {
                check(encryptedRelease.await(15, TimeUnit.SECONDS)) { "Test did not release native encrypted export" }
                check(!encryptionFailurePending.getAndSet(false)) { "Synthetic native encryption failure" }
                return ENCRYPTED_KEY
            } finally {
                encryptedFinished.set(true)
            }
        }

        /** Releases encrypted native work after assertions or in unconditional test cleanup. */
        fun finishEncrypted() {
            encryptedRelease.countDown()
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
