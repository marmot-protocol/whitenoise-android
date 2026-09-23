package dev.ipf.whitenoise.android.ui.settings

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionPreferences
import dev.ipf.whitenoise.android.notifications.NativePushCapability
import dev.ipf.whitenoise.android.state.NotificationBootstrapTestFixture
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Revocation exercises the real screen and AppState mutations against the established fake native boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class NotificationRevocationTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val permissionRegistry = PermissionRegistry()
    private var fixture: NotificationBootstrapTestFixture? = null

    /** Start with denied Android permission and no inherited app preferences. */
    @Before
    fun prepareDeniedPermission() {
        app
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Release the fixture's event channels without touching device/runtime state. */
    @After
    fun closeFixture() {
        fixture?.close()
    }

    /** Permission loss preserves an existing local-delivery choice without issuing native writes. */
    @Test
    fun deniedPermissionPreservesLocalDelivery() {
        val owner = show(local = true, push = false, background = true)

        composeRule.onNodeWithTag("notification-delivery.local").assertIsSelected().assertIsNotEnabled()
        assertTrue(owner.notificationSettings(ACCOUNT).localNotificationsEnabled)
        assertTrue(owner.appState.backgroundConnectionEnabled)
        assertTrue(BackgroundConnectionPreferences.isEnabled(app))
        assertTrue(owner.nativePushSettingWrites.isEmpty())
        assertFalse(owner.appState.localNotificationPermissionGranted)
    }

    /** Permission loss preserves an existing native-push choice and its registration. */
    @Test
    fun deniedPermissionPreservesNativePushDelivery() {
        val owner = show(local = true, push = true, background = false)

        composeRule.onNodeWithTag("notification-delivery.fcm").assertIsSelected().assertIsNotEnabled()
        composeRule.onNodeWithTag("notification-delivery.local").assertIsNotSelected().assertIsNotEnabled()
        assertTrue(owner.notificationSettings(ACCOUNT).nativePushEnabled)
        assertTrue(owner.clearedPushRegistrations.isEmpty())
        assertTrue(owner.nativePushSettingWrites.isEmpty())
        assertFalse(owner.appState.localNotificationPermissionGranted)
    }

    /** A legacy all-off state stays unchanged while the permission gate prevents choosing a mode. */
    @Test
    fun deniedPermissionDoesNotRepairLegacyAllOffState() {
        val owner = show(local = false, push = false, background = false)

        composeRule.onNodeWithTag("notification-delivery.local").assertIsSelected().assertIsNotEnabled()
        assertFalse(owner.notificationSettings(ACCOUNT).localNotificationsEnabled)
        assertFalse(owner.notificationSettings(ACCOUNT).nativePushEnabled)
        assertFalse(BackgroundConnectionPreferences.isEnabled(app))
        assertTrue(owner.nativePushSettingWrites.isEmpty())
        assertFalse(owner.appState.localNotificationPermissionGranted)
    }

    /** Both delivery choices remain disabled until Android permission is available. */
    @Test
    fun deniedPermissionDisablesEveryAvailableDeliveryChoice() {
        show(local = true, push = false, background = true)

        composeRule.onNodeWithTag("notification-delivery.fcm").assertIsNotSelected().assertIsNotEnabled()
        composeRule.onNodeWithTag("notification-delivery.local").assertIsSelected().assertIsNotEnabled()
    }

    /** A grant through the screen cannot turn an account's disabled notification rendering back on. */
    @Test
    fun permissionGrantPreservesRenderingOptOut() {
        val owner = show(local = false, push = false, background = false)

        composeRule.onNodeWithText(app.getString(R.string.allow_notifications)).performClick()
        composeRule.runOnIdle {
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            permissionRegistry.deliver(granted = true)
        }
        composeRule.waitUntil(5_000) { owner.appState.localNotificationPermissionGranted }
        composeRule.waitForIdle()

        assertFalse(owner.notificationSettings(ACCOUNT).localNotificationsEnabled)
        assertFalse(owner.notificationSettings(ACCOUNT).nativePushEnabled)
        assertTrue(owner.nativePushSettingWrites.isEmpty())
        composeRule.onNodeWithTag("notification-delivery.local").assertIsSelected()
    }

    /** Persisted fixture state enters the real owner through notificationSettings, not direct UI assignment. */
    private fun show(
        local: Boolean,
        push: Boolean,
        background: Boolean,
    ): NotificationBootstrapTestFixture {
        BackgroundConnectionPreferences.setEnabled(app, background)
        val owner =
            NotificationBootstrapTestFixture(
                context = app,
                accounts = listOf(AccountSummaryFfi(ACCOUNT, "a".repeat(64), true, false, false, true)),
                initialNotificationSettings = NotificationSettingsFfi(ACCOUNT, "a".repeat(64), local, push),
                emitStartupNotification = false,
                nativePushCapabilityResolver = { NativePushCapability.Available },
            )
        fixture = owner
        runBlocking { owner.bootstrap() }
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides permissionRegistry) {
                WhiteNoiseTheme { NotificationsScreen(owner.appState, onBack = {}) }
            }
        }
        composeRule.waitUntil(5_000) { owner.appState.localNotificationSettings != null }
        assertFalse(owner.appState.localNotificationPermissionGranted)
        return owner
    }

    private companion object {
        const val ACCOUNT = "notification-revocation"
    }

    private class PermissionRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        private var requestCode: Int? = null

        /** Capture the permission request without opening the Android permission dialog. */
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            this.requestCode = requestCode
        }

        /** Deliver the permission result to the screen's registered callback. */
        fun deliver(granted: Boolean) {
            assertTrue(dispatchResult(requireNotNull(requestCode), granted))
        }
    }
}
