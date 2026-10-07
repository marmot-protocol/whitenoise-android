package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.state.SecureStoreKeyProvider
import javax.crypto.SecretKey

/** Supplies a real AES test key to the process provider; Robolectric cannot create Android Keystore keys. */
internal fun setPinnedConversationTestKey(key: SecretKey?) {
    PinnedConversationTokens.installKeyProvider(
        key?.let { testKey ->
            object : SecureStoreKeyProvider {
                /** Returns the fixture key instead of touching the Android Keystore. */
                override fun secretKey(): SecretKey = testKey
            }
        },
    )
}
