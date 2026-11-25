package dev.ipf.whitenoise.android.ui.keyboard

import android.content.Context
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.Window

/**
 * Puts [router] in front of [window]'s callback: keys reach the router before the views, and Android's Keyboard
 * Shortcuts Helper gets the available commands. Call after the activity's `super.onCreate`, so the callback
 * wrappers its base classes install there stay in the chain.
 */
internal fun installHardwareShortcuts(
    window: Window,
    router: HardwareShortcutRouter,
) {
    window.callback = HardwareShortcutWindowCallback(window.callback, window.context, router)
}

/**
 * Forwards every [Window.Callback] call to [original], first offering keys to [router]. Kotlin delegation skips
 * Java default methods, so each one is forwarded explicitly, and `HardwareShortcutWindowCallbackTest` fails when a
 * platform update adds another.
 */
internal class HardwareShortcutWindowCallback(
    private val original: Window.Callback,
    private val context: Context,
    val router: HardwareShortcutRouter,
) : Window.Callback by original {
    /** Runs a messaging shortcut before the focused view, so a text field cannot swallow it (#4005). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = router.handle(event) || original.dispatchKeyEvent(event)

    /** Adds the messaging shortcuts available right now after the activity's own groups. */
    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>?,
        menu: Menu?,
        deviceId: Int,
    ) {
        original.onProvideKeyboardShortcuts(data, menu, deviceId)
        hardwareShortcutGroup(context, router.availableCommands())?.let { data?.add(it) }
    }

    /** Forwards the pointer-capture change, a default method delegation would otherwise drop. */
    override fun onPointerCaptureChanged(hasCapture: Boolean) = original.onPointerCaptureChanged(hasCapture)
}
