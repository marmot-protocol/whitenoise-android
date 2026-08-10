package dev.ipf.whitenoise.android.media.editor

import android.content.Context
import dev.ipf.whitenoise.android.state.AndroidKeystoreSecretKeyProvider
import dev.ipf.whitenoise.android.state.KeystoreSecureStore
import java.security.GeneralSecurityException

internal interface EditorStringStore {
    /** Returns null when the encrypted store cannot currently be read. */
    fun readAll(): Map<String, String>?

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

    override fun readAll(): Map<String, String>? =
        try {
            secureStore.readAll()
        } catch (_: GeneralSecurityException) {
            null
        }

    override fun replaceAll(values: Map<String, String>): Boolean =
        try {
            secureStore.replaceAllDurably(values)
        } catch (_: GeneralSecurityException) {
            false
        }

    override fun clear() = secureStore.clear()
}
