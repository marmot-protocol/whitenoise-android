package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.state.SecureStoreKeyProvider
import org.junit.rules.ExternalResource
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Exercise real encrypted push preferences without a platform Keystore in JVM fixtures. */
class PushTokenKeyRule : ExternalResource() {
    /**
     * The provider this rule installed. A test that observes key requests wraps this one, because the
     * application's startup work may already have sealed the push state with its key.
     */
    internal lateinit var provider: SecureStoreKeyProvider
        private set

    /** Installs one AES key for the whole test. */
    override fun before() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        provider =
            object : SecureStoreKeyProvider {
                /** Hands out the test's one key. */
                override fun secretKey(): SecretKey = key
            }
        PushTokenStore.installKeyProvider(provider)
    }

    /** Restores the production Keystore boundary. */
    override fun after() {
        PushTokenStore.installKeyProvider(null)
    }
}
