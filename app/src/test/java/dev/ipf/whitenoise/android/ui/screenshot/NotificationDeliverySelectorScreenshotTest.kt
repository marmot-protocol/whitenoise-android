package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.notifications.NativePushCapability
import dev.ipf.whitenoise.android.notifications.PushStorageRecovery
import dev.ipf.whitenoise.android.state.NotificationDeliveryMode
import dev.ipf.whitenoise.android.ui.settings.NotificationDeliverySelector
import dev.ipf.whitenoise.android.ui.settings.PushStorageRecoveryControl
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class NotificationDeliverySelectorScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Records the two-choice mode selector with push delivery selected. */
    @Test
    fun availablePushLight() {
        render(NotificationDeliveryMode.Fcm, NativePushCapability.Available)
        capture("notification_delivery_push_light.png")
    }

    /** Records the single local choice when push capability is unavailable. */
    @Test
    fun unavailablePushDarkLargeRtl() {
        render(
            selectedMode = NotificationDeliveryMode.Local,
            capability = NativePushCapability.GooglePlayServicesUnavailable,
            darkTheme = true,
            rtl = true,
            fontScale = 1.4f,
        )
        capture("notification_delivery_local_unavailable_dark_large_rtl.png")
    }

    /** Keeps transient storage failure readable without exposing sensitive saved values. */
    @Test
    fun retainedPushStorageUnavailableLight() {
        renderRecovery(PushStorageRecovery.StorageUnavailable)
        capture("notification_push_storage_unavailable_light.png")
    }

    /** Shows historical cleanup uncertainty separately under large-font RTL and dark theme. */
    @Test
    fun retainedPushCleanupDarkLargeRtl() {
        renderRecovery(
            PushStorageRecovery.CleanupReconciliationRequired,
            darkTheme = true,
            rtl = true,
            fontScale = 1.4f,
        )
        capture("notification_push_cleanup_unknown_dark_large_rtl.png")
    }

    /** Keeps retry disabled while an original legacy source is being checked again. */
    @Test
    fun retainedPushLegacyRetryBusy() {
        renderRecovery(PushStorageRecovery.LegacyImportPending, busy = true)
        capture("notification_push_import_pending_busy.png")
    }

    /** Renders the real settings action without running storage or native calls from composition. */
    private fun renderRecovery(
        state: PushStorageRecovery,
        busy: Boolean = false,
        darkTheme: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    Surface(modifier = Modifier.width(360.dp).testTag(TAG)) {
                        PushStorageRecoveryControl(state, busy, onRetry = {})
                    }
                }
            }
        }
    }

    /** Renders the production mode selector under the requested accessibility conditions. */
    private fun render(
        selectedMode: NotificationDeliveryMode,
        capability: NativePushCapability,
        darkTheme: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    Surface(modifier = Modifier.width(360.dp).testTag(TAG)) {
                        NotificationDeliverySelector(
                            selectedMode = selectedMode,
                            capability = capability,
                            enabled = true,
                            onSelect = {},
                        )
                    }
                }
            }
        }
    }

    /** Captures the tagged production selector into its tracked baseline. */
    private fun capture(fileName: String) {
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/$fileName")
    }

    private companion object {
        const val TAG = "notification-delivery-selector"
    }
}
