package dev.ipf.whitenoise.android.benchmark

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

/** Removes fixture FCM ingress for the all-disabled floor without adding a product delivery mode. */
internal class BackgroundPushReceiverControl(
    private val device: UiDevice,
) {
    private val manager = InstrumentationRegistry.getInstrumentation().context.packageManager
    private val component = ComponentName(BenchmarkConfig.TARGET_PACKAGE, RECEIVER_CLASS)

    /** Restores the exact per-user component override, even if a benchmark assertion fails. */
    fun withReceiverDisabled(block: () -> Unit) {
        val user = BenchmarkConfig.requireQualificationUser(device.executeShellCommand("am get-current-user"))
        manager.getReceiverInfo(
            component,
            PackageManager.ComponentInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()),
        )
        val original = manager.getComponentEnabledSetting(component)
        val restoreAction = componentAction(original)
        try {
            setState(user, "disable", PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
            block()
        } finally {
            setState(user, restoreAction, original)
        }
    }

    /** Uses a fixed declared component and a validated user; arbitrary shell input is never accepted. */
    private fun setState(
        user: Int,
        action: String,
        expected: Int,
    ) {
        device.executeShellCommand("pm $action --user $user ${component.flattenToString()}")
        check(manager.getComponentEnabledSetting(component) == expected) {
            "The fixture push receiver state was not acknowledged or restored."
        }
    }

    /** Maps Android's distinct override states rather than restoring every receiver as enabled. */
    private fun componentAction(state: Int): String =
        when (state) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> "default-state"
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "enable"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "disable"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> "disable-user"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> "disable-until-used"
            else -> error("Unknown fixture push receiver override.")
        }

    private companion object {
        const val RECEIVER_CLASS = "com.google.firebase.iid.FirebaseInstanceIdReceiver"
    }
}
