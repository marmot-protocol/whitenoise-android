package dev.ipf.darkmatter.ui

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession

/**
 * Privacy hardening for issue #405.
 *
 * When the user enables "Force incognito keyboard" (Settings -> Security &
 * Privacy), every text field in the app asks the active IME to treat its
 * content as incognito by OR-ing [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING]
 * into the field's `imeOptions`. Well-behaved keyboards (Gboard, SwiftKey,
 * Samsung Keyboard, Heliboard/FlorisBoard) then suppress learning,
 * suggestion-bar history, and cloud sync of what was typed. A misbehaving IME
 * can ignore the hint — that's a documented limitation, not something we can
 * detect or enforce from the app side.
 *
 * The flag cannot be expressed through Compose's `KeyboardOptions` /
 * `PlatformImeOptions` (those only carry a `privateImeOptions` String, not the
 * `imeOptions` integer flags), so we install a single
 * [PlatformTextInputInterceptor] near the top of the composition. It mutates
 * the [EditorInfo] of every text-input session for descendant composables,
 * which means the composer, search, settings fields, modal inputs, and the
 * profile editor all inherit the flag without per-field wiring.
 *
 * The interceptor is *always* installed and decides whether to apply the flag
 * lazily, per input session, by reading the live [enabled] state at the moment
 * a text field starts an input session. This keeps the composition structure
 * stable when the setting toggles, so flipping "Force incognito keyboard" never
 * disposes/recreates the app subtree (which previously tore down navigation and
 * dumped the user back on the chat list with a full-screen loading state —
 * fixes #561).
 */
internal class IncognitoKeyboardInterceptor(
    private val enabled: () -> Boolean,
) : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing {
        val wrapped =
            PlatformTextInputMethodRequest { editorInfo ->
                val connection = request.createInputConnection(editorInfo)
                // Read enabled() here so a session that starts later picks up the
                // current setting without recreating the interceptor.
                editorInfo.imeOptions = incognitoImeOptions(editorInfo.imeOptions, enabled())
                connection
            }
        nextHandler.startInputMethod(wrapped)
    }
}

/**
 * Pure: given the current `imeOptions` and whether incognito is enabled, return
 * the `imeOptions` to apply. Split out of [EditorInfo] mutation so it can be
 * exercised on the JVM without Robolectric.
 *
 * [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING] was added in API 26 and the
 * app's `minSdk` is 34, so it is always available — no version guard is needed.
 */
internal fun incognitoImeOptions(
    current: Int,
    enabled: Boolean,
): Int = if (enabled) current or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else current

/**
 * Always mounts [InterceptPlatformTextInput] with a stable, remembered
 * interceptor so the composition structure does not change when [enabled]
 * toggles. The interceptor reads the latest [enabled] value via
 * [rememberUpdatedState] when an input session starts, applying incognito mode
 * conditionally per session. Because [content] is always emitted under the same
 * [InterceptPlatformTextInput] node, toggling the setting never rebuilds the app
 * or pops navigation (fixes #561).
 */
@Composable
fun IncognitoKeyboardScope(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val enabledState = rememberUpdatedState(enabled)
    val interceptor = remember { IncognitoKeyboardInterceptor(enabled = { enabledState.value }) }
    InterceptPlatformTextInput(
        interceptor = interceptor,
        content = content,
    )
}
