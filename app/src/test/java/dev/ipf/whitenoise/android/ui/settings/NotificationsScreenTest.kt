package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.notifications.NativePushCapability
import dev.ipf.whitenoise.android.state.NotificationDeliveryMode
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en")
class NotificationsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Available push delivery presents exactly one selected mode and the whole row changes it. */
    @Test
    fun availableCapabilityPresentsOneWholeRowSelection() {
        val selections = mutableListOf<NotificationDeliveryMode>()
        render(
            selectedMode = NotificationDeliveryMode.Fcm,
            capability = NativePushCapability.Available,
            onSelect = selections::add,
        )

        composeRule.onNodeWithTag("notification-delivery.fcm").assertIsEnabled().assertIsSelected()
        composeRule
            .onNodeWithTag("notification-delivery.local")
            .assertIsEnabled()
            .assertIsNotSelected()
            .performClick()
        composeRule.runOnIdle { assertEquals(listOf(NotificationDeliveryMode.Local), selections) }
    }

    /** Every unavailable cause omits push from the selector and leaves one truthful local choice. */
    @Test
    fun unavailableCapabilitiesOmitPushChoice() {
        val unavailable = NativePushCapability.entries.filterNot { it.isAvailable }
        val capability = mutableStateOf(unavailable.first())
        composeRule.setContent {
            WhiteNoiseTheme {
                NotificationDeliverySelector(
                    selectedMode = NotificationDeliveryMode.Local,
                    capability = capability.value,
                    enabled = true,
                    onSelect = {},
                )
            }
        }
        unavailable.forEach { unavailableCapability ->
            composeRule.runOnIdle { capability.value = unavailableCapability }

            composeRule.onNodeWithTag("notification-delivery.fcm").assertDoesNotExist()
            composeRule.onNodeWithTag("notification-delivery.local").assertIsSelected()
        }
    }

    /** A pending transition disables both selectable rows without changing the projected selection. */
    @Test
    fun pendingTransitionDisablesTheModeGroup() {
        render(
            selectedMode = NotificationDeliveryMode.Local,
            capability = NativePushCapability.Available,
            enabled = false,
        )

        composeRule.onNodeWithTag("notification-delivery.fcm").assertIsNotEnabled().assertIsNotSelected()
        composeRule.onNodeWithTag("notification-delivery.local").assertIsNotEnabled().assertIsSelected()
    }

    /** Renders the pure production selector without constructing a native runtime. */
    private fun render(
        selectedMode: NotificationDeliveryMode,
        capability: NativePushCapability,
        enabled: Boolean = true,
        onSelect: (NotificationDeliveryMode) -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                NotificationDeliverySelector(
                    selectedMode = selectedMode,
                    capability = capability,
                    enabled = enabled,
                    onSelect = onSelect,
                )
            }
        }
    }
}
