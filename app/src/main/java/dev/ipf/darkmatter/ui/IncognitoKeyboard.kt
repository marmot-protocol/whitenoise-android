package dev.ipf.darkmatter.ui

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
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
 */
internal object IncognitoKeyboardInterceptor : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing {
        val wrapped =
            PlatformTextInputMethodRequest { editorInfo ->
                val connection = request.createInputConnection(editorInfo)
                editorInfo.applyNoPersonalizedLearning()
                connection
            }
        nextHandler.startInputMethod(wrapped)
    }
}

/**
 * OR in [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING] so the IME treats this
 * field as incognito. The flag was added in API 26 and the app's `minSdk` is
 * 34, so it is always available — no version guard is needed.
 */
private fun EditorInfo.applyNoPersonalizedLearning() {
    imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
}

/**
 * Conditionally wraps [content] so that, when [enabled] is true, every
 * descendant text field requests incognito mode from the IME. When disabled the
 * content is emitted unchanged so there is no behavioral or recomposition cost.
 */
@Composable
fun IncognitoKeyboardScope(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (enabled) {
        InterceptPlatformTextInput(
            interceptor = IncognitoKeyboardInterceptor,
            content = content,
        )
    } else {
        content()
    }
}
