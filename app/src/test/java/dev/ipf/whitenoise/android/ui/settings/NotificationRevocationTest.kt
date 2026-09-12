package dev.ipf.whitenoise.android.ui.settings

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionPreferences
import dev.ipf.whitenoise.android.state.NotificationBootstrapTestFixture
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** Local notification revocation reaches MDK and also stops the saved background policy. */
    @Test
    fun deniedPermissionStillAllowsLocalNotificationsOff() {
        val owner = show(local = true, push = false, background = true)
        composeRule
            .onNodeWithText(app.getString(R.string.local_notifications))
            .performScrollTo()
            .assertIsOn()
            .assertIsEnabled()
            .performClick()
        awaitMutation { owner.appState.localNotificationSettings?.localNotificationsEnabled == false }
        assertFalse(owner.notificationSettings(ACCOUNT).localNotificationsEnabled)
        assertFalse(owner.appState.backgroundConnectionEnabled)
        assertFalse(BackgroundConnectionPreferences.isEnabled(app))
        composeRule.onNodeWithText(app.getString(R.string.local_notifications)).assertIsOff().assertIsNotEnabled()
        assertFalse(owner.appState.localNotificationPermissionGranted)
    }

    /** Stopping an enabled persistent connection requires no new notification grant. */
    @Test
    fun deniedPermissionStillAllowsKeepConnectedOff() {
        val owner = show(local = true, push = false, background = true)
        composeRule
            .onNodeWithText(app.getString(R.string.keep_connected_in_background))
            .performScrollTo()
            .assertIsOn()
            .assertIsEnabled()
            .performClick()
        awaitMutation { !owner.appState.backgroundConnectionEnabled }
        assertFalse(BackgroundConnectionPreferences.isEnabled(app))
        composeRule
            .onNodeWithText(app.getString(R.string.keep_connected_in_background))
            .assertIsOff()
            .assertIsNotEnabled()
        assertFalse(owner.appState.localNotificationPermissionGranted)
    }

    /** A previously enabled push policy can be disabled after local delivery and permission are both off. */
    @Test
    fun deniedPermissionAndLocalOffStillAllowNativePushRevocation() {
        val owner = show(local = false, push = true, background = false)
        composeRule
            .onNodeWithText(app.getString(R.string.native_push_title))
            .performScrollTo()
            .assertIsOn()
            .assertIsEnabled()
            .performClick()
        awaitMutation { owner.clearedPushRegistrations.contains(ACCOUNT) }
        assertFalse(owner.notificationSettings(ACCOUNT).nativePushEnabled)
        assertEquals(listOf(ACCOUNT to false), owner.nativePushSettingWrites.toList())
        composeRule.onNodeWithText(app.getString(R.string.native_push_title)).assertIsOff().assertIsNotEnabled()
        assertFalse(owner.appState.localNotificationPermissionGranted)
    }

    /** Off policies retain the prototype permission gate and cannot be enabled through their disabled rows. */
    @Test
    fun deniedPermissionDoesNotEnableOffPolicies() {
        show(local = false, push = false, background = false)
        val policies =
            listOf(
                R.string.local_notifications,
                R.string.keep_connected_in_background,
                R.string.native_push_title,
            )
        policies.forEach { id ->
            composeRule
                .onNodeWithText(app.getString(id))
                .performScrollTo()
                .assertIsOff()
                .assertIsNotEnabled()
        }
    }

    /** Process-owned Main mutations resume outside the Compose test scheduler after native IO returns. */
    private fun awaitMutation(condition: () -> Boolean) {
        composeRule.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
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
            )
        fixture = owner
        runBlocking { owner.bootstrap() }
        composeRule.setContent { WhiteNoiseTheme { NotificationsScreen(owner.appState, onBack = {}) } }
        composeRule.waitUntil(5_000) { owner.appState.localNotificationSettings != null }
        assertFalse(owner.appState.localNotificationPermissionGranted)
        return owner
    }

    private companion object {
        const val ACCOUNT = "notification-revocation"
    }
}
