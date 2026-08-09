package dev.ipf.whitenoise.android.media.editor

import android.content.Context
import dev.ipf.whitenoise.android.state.AndroidKeystoreSecretKeyProvider
import dev.ipf.whitenoise.android.state.KeystoreSecureStore

internal interface EditorStringStore {
    fun readAll(): Map<String, String>

    fun replaceAll(values: Map<String, String>): Boolean

    fun clear()
}

internal class KeystoreEditorStringStore(
    context: Context,
    fileName: String,
    keyAlias: String,
) : EditorStringStore {
    private val secureStore =
        KeystoreSecureStore(
            context = context.applicationContext,
            fileName = fileName,
            keyProvider = AndroidKeystoreSecretKeyProvider(keyAlias),
        )

    override fun readAll(): Map<String, String> = secureStore.readAll()

    override fun replaceAll(values: Map<String, String>): Boolean = secureStore.replaceAllDurably(values)

    override fun clear() = secureStore.clear()
}
