package dev.ipf.whitenoise.android.ui.qr

import android.Manifest
import android.app.Application
import androidx.activity.ComponentDialog
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Exercise the actual sheet's permission registry, lifecycle resumption and callback ownership. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QrScannerRouteTest {
    @get:Rule val composeRule = createComposeRule()
    private val visible = mutableStateOf(true)
    private val registry = PermissionRegistry()
    private lateinit var lifecycle: ScreenLifecycle
    private var mounts = 0
    private var disposals = 0
    private var scans = 0
    private var scan: ((String) -> Unit)? = null
    private var fail: ((String) -> Unit)? = null

    /** A denied Android result never starts camera work; a subsequent actual grant does. */
    @Test fun permissionRegistryControlsCameraMount() {
        show(granted = false)
        composeRule.onNodeWithTag("qr_scanner.permission_pending").assertExists()
        composeRule.runOnIdle {
            assertEquals(1, registry.launches)
            registry.deliver(false)
        }
        composeRule.onNodeWithTag("qr_scanner.recovery").assertExists()
        composeRule.runOnIdle { assertEquals(0, mounts) }
        composeRule.runOnIdle {
            permission(true)
            lifecycle.resumeAgain()
        }
        composeRule.onNodeWithTag("real_camera_boundary").assertExists()
        composeRule.runOnIdle { assertEquals(1, mounts) }
    }

    /** Revoking permission in Android settings removes the camera when this sheet resumes. */
    @Test fun permissionRevocationOnResumeDisposesCamera() {
        show(granted = true)
        composeRule.onNodeWithTag("real_camera_boundary").assertExists()
        composeRule.runOnIdle {
            permission(false)
            lifecycle.resumeAgain()
        }
        composeRule.onNodeWithTag("real_camera_boundary").assertDoesNotExist()
        composeRule.onNodeWithTag("qr_scanner.recovery").assertExists()
        composeRule.runOnIdle {
            assertEquals(1, disposals)
            assertEquals(0, registry.launches)
        }
    }

    /** Native failure tears down the failed camera; Retry starts one fresh binding. */
    @Test fun cameraFailureHasRealRetryAndNoPretendTarget() {
        show(granted = true)
        composeRule.runOnIdle { requireNotNull(fail)("Camera is unavailable.") }
        composeRule.onNodeWithTag("qr_scanner.target").assertDoesNotExist()
        composeRule.onNodeWithTag("qr_scanner.retry").performClick()
        composeRule.onNodeWithTag("real_camera_boundary").assertExists()
        composeRule.runOnIdle {
            assertEquals(2, mounts)
            assertEquals(1, disposals)
        }
    }

    /** The error dialog's Close action cancels the scanner without remounting or delivering a result. */
    @Test fun cameraErrorCloseDismissesWithoutRetry() {
        show(granted = true)
        composeRule.runOnIdle { requireNotNull(fail)("Camera is unavailable.") }
        composeRule.onNodeWithTag("qr_scanner.error_close").performClick()
        composeRule.onNodeWithTag(QR_SCANNER_SHEET_CONTENT_TAG).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, mounts)
            assertEquals(1, disposals)
            assertEquals(0, scans)
        }
    }

    /** Android Back reaches the error dialog's real dismissal callback and closes the scanner. */
    @Test fun cameraErrorBackDismissesWithoutRetry() {
        show(granted = true)
        composeRule.runOnIdle { requireNotNull(fail)("Camera is unavailable.") }
        composeRule.onNodeWithTag("qr_scanner.error_dialog").assertExists()
        composeRule.runOnIdle {
            (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.onNodeWithTag(QR_SCANNER_SHEET_CONTENT_TAG).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, mounts)
            assertEquals(1, disposals)
            assertEquals(0, scans)
        }
    }

    /** A result after composition disposal cannot reach the production caller. */
    @Test fun disposedSheetRejectsLateScanCallback() {
        show(granted = true)
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            requireNotNull(scan)("late test payload")
            assertEquals(0, scans)
        }
    }

    /** Close uses the caller's dismissal and disposes the scanner without delivering a scan. */
    @Test fun closeDisposesCameraWithoutResult() {
        show(granted = true)
        composeRule.onNodeWithTag("qr_scanner.close").performClick()
        composeRule.runOnIdle {
            assertEquals(1, disposals)
            assertEquals(0, scans)
        }
    }

    /** The camera slot replaces hardware only; all sheet permission/state/disposal code runs unchanged. */
    private fun show(granted: Boolean) {
        permission(granted)
        lifecycle = composeRule.runOnIdle { ScreenLifecycle().apply { resumeAgain() } }
        composeRule.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides registry,
                LocalLifecycleOwner provides lifecycle,
            ) {
                WhiteNoiseTheme {
                    if (visible.value) {
                        QrScannerSheet(
                            onDismiss = { visible.value = false },
                            onScan = { scans++ },
                            cameraContent = { onScan, onError, _ ->
                                DisposableEffect(Unit) {
                                    mounts++
                                    scan = onScan
                                    fail = onError
                                    onDispose { disposals++ }
                                }
                                Box(Modifier.fillMaxSize().testTag("real_camera_boundary"))
                            },
                        )
                    }
                }
            }
        }
    }

    /** Change the actual permission queried by ContextCompat, without assigning presentation state. */
    private fun permission(granted: Boolean) {
        val app = shadowOf(ApplicationProvider.getApplicationContext<Application>())
        if (granted) {
            app.grantPermissions(Manifest.permission.CAMERA)
        } else {
            app.denyPermissions(Manifest.permission.CAMERA)
        }
    }

    private class ScreenLifecycle : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        /** Return from settings through the same resume event observed by the real screen. */
        fun resumeAgain() {
            registry.currentState = Lifecycle.State.STARTED
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    private class PermissionRegistry :
        ActivityResultRegistry(),
        ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        var launches = 0
        private var request: Int? = null

        /** Record the real contract launch without opening any external permission UI. */
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            request = requestCode
            launches++
        }

        /** Dispatch through the registered ActivityResult callback. */
        fun deliver(granted: Boolean) {
            assertTrue(dispatchResult(requireNotNull(request), granted))
        }
    }
}
