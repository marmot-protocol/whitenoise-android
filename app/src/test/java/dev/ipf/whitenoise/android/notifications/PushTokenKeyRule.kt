package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.state.SecureStoreKeyProvider
import org.junit.rules.ExternalResource
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Exercise real encrypted push preferences without a platform Keystore in JVM fixtures. */
class PushTokenKeyRule : ExternalResource() {
    override fun before() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        PushTokenStore.installKeyProvider(
            object : SecureStoreKeyProvider {
                override fun secretKey(): SecretKey = key
            },
        )
    }

    override fun after() {
        PushTokenStore.installKeyProvider(null)
    }
}
