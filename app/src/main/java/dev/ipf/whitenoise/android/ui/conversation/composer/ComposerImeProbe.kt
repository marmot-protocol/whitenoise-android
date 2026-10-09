@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.ipf.whitenoise.android.ui.conversation.composer

import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.BuildConfig
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** Temporary preview-only, explicitly armed synthetic-fixture probe. Remove before merging. */
internal class ComposerImeProbe {
    private var armed = false
    private var records = 0
    private var value = TextFieldValue()

    fun synchronize(next: TextFieldValue) {
        if (next != value) record("accepted", next)
        value = next
    }

    fun proposed(next: TextFieldValue) {
        value = next
        record("proposed", next)
    }

    fun wrap(request: PlatformTextInputMethodRequest): PlatformTextInputMethodRequest =
        object : PlatformTextInputMethodRequest {
            override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                val target = request.createInputConnection(outAttributes)
                if (BuildConfig.WHITENOISE_DEPLOYMENT_ENVIRONMENT != "preview") return target
                armed = armed || value.text == "WN-IME-PROBE"
                if (!armed) return target
                record("connection", value)
                return Proxy.newProxyInstance(
                    InputConnection::class.java.classLoader,
                    arrayOf(InputConnection::class.java),
                ) { _, method, arguments ->
                    val args = arguments ?: emptyArray()
                    val description = args.joinToString { argument ->
                        when (argument) {
                            is Number, is Boolean -> argument.toString()
                            is CharSequence -> "length=${argument.length}"
                            null -> "null"
                            else -> argument.javaClass.simpleName
                        }
                    }
                    record("before ${method.name}($description)", value)
                    val result =
                        try {
                            method.invoke(target, *args)
                        } catch (failure: InvocationTargetException) {
                            throw checkNotNull(failure.cause)
                        }
                    val returned = if (result is CharSequence) " length=${result.length}" else ""
                    record("after ${method.name}$returned", value)
                    result
                } as InputConnection
            }
        }

    private fun record(stage: String, next: TextFieldValue) {
        if (!armed || records++ >= 1000) return
        Log.d(
            "WN_IME_PROBE",
            "$stage textLength=${next.text.length} spaces=${next.text.count { it == ' ' }} " +
                "selection=${next.selection.start},${next.selection.end} composition=${next.composition}",
        )
    }
}
