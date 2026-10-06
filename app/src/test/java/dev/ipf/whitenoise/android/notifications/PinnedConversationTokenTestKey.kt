package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.state.AndroidKeystoreSecretKeyProvider
import javax.crypto.SecretKey

/** Supplies a real AES test key to the process provider; Robolectric cannot create Android Keystore keys. */
internal fun setPinnedConversationTestKey(key: SecretKey?) {
    val provider =
        PinnedConversationTokens::class.java
            .getDeclaredField("keyProvider")
            .apply { isAccessible = true }
            .get(null)
    AndroidKeystoreSecretKeyProvider::class.java
        .getDeclaredField("cached")
        .apply { isAccessible = true }
        .set(provider, key)
}
