package dev.ipf.whitenoise.android.share

import android.content.ContentResolver
import android.content.Context
import android.net.Uri

/**
 * Gate for URIs another app hands us (share intents, keyboard and clipboard paste).
 *
 * We open these URIs with our own permissions. A `file:` URI or a URI naming one of our own providers would make
 * us read app-private plaintext, such as decrypted attachments in the FileProvider cache, and attach it to an
 * outgoing message. Only `content:` URIs served by another app pass.
 */
internal object ForeignContentSource {
    /**
     * The URI ContentResolver will actually route to, or null when [uri] is not a `content:` URI with an authority.
     * ContentResolver drops Android's `user@` prefix before choosing a provider, so the gate does too. Callers keep
     * the original URI for the read so the sender's grant still applies.
     */
    fun providerUri(uri: Uri): Uri? =
        uri.authority
            ?.takeIf { uri.scheme == ContentResolver.SCHEME_CONTENT }
            ?.substringAfterLast('@')
            ?.takeIf { it.isNotBlank() }
            ?.let { authority -> uri.buildUpon().authority(authority).build() }

    /** True when [providerUri] resolves to a provider running under this app's uid. */
    fun isAppOwned(
        context: Context,
        providerUri: Uri,
    ): Boolean =
        context.packageManager
            .resolveContentProvider(providerUri.authority.orEmpty(), 0)
            ?.applicationInfo
            ?.uid == context.applicationInfo.uid

    /** True when [uri] is a `content:` URI served by another app. */
    fun isForeign(
        uri: Uri,
        isAppOwned: (Uri) -> Boolean,
    ): Boolean {
        val provider = providerUri(uri) ?: return false
        return !isAppOwned(provider)
    }
}
