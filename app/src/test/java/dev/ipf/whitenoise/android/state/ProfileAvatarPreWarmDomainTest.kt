package dev.ipf.whitenoise.android.state

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.MediaDecodeTransport
import dev.ipf.whitenoise.android.media.sandbox.MediaTrustScope
import dev.ipf.whitenoise.android.media.sandbox.TestMediaDecodeTransport
import dev.ipf.whitenoise.android.media.sandbox.privateField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** A profile picture warmed for one account decodes in that account's public-profile worker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileAvatarPreWarmDomainTest {
    /**
     * A warm a chat list names for its own account decodes in that account's public profiles, even while another
     * account is active, as after an account switch a replaced controller may still recompute.
     */
    @Test
    fun aWarmNamedForItsOwnAccountDecodesInThatAccountsWorker() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = WhiteNoiseAppState(context, DraftStore(NoPreWarmDrafts), { null }, emptyList(), ACTIVE_ACCOUNT)

        @Suppress("UNCHECKED_CAST")
        val presentations = privateField(app, "profilePresentations") as ScopedCache<String, ProfilePresentation>
        presentations.put(PROFILE_ID, ProfilePresentation(displayName = null, avatarUrl = PICTURE_URL))
        val decoded = CopyOnWriteArrayList<MediaTrustDomain>()
        IsolatedMediaDecoder.installTestTransport(
            object : MediaDecodeTransport by TestMediaDecodeTransport {
                /** Records the domain each decode declares, then decodes in process. */
                override fun decode(
                    bytes: ByteArray,
                    edge: Int,
                    oriented: Boolean,
                    operation: Int,
                ): Bitmap? {
                    decoded += MediaTrustScope.current()
                    return TestMediaDecodeTransport.decode(bytes, edge, oriented, operation)
                }
            },
        )
        AvatarImageLoader.attachProfileImageFetcher { _, _ -> Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64) }
        try {
            val owner = MediaTrustDomain.publicProfiles(OWNER_ACCOUNT)
            app.preWarmProfileAvatar(PROFILE_ID, owner)
            val until = System.nanoTime() + WAIT_NANOS
            while (decoded.isEmpty() && System.nanoTime() < until) Thread.sleep(POLL_MS)
            assertTrue("the warm decoded the picture", decoded.isNotEmpty())
            assertEquals(setOf(owner), decoded.toSet())
        } finally {
            AvatarImageLoader.resetProfileImageFetcherForTests()
            IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
        }
    }

    private companion object {
        const val ACTIVE_ACCOUNT = "account-b"
        const val OWNER_ACCOUNT = "account-a"
        const val PROFILE_ID = "c3c3c3"
        const val PICTURE_URL = "https://profiles.example/prewarm-domain.png"
        const val WAIT_NANOS = 5_000_000_000L
        const val POLL_MS = 10L
        const val ONE_PIXEL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}

/** Keeps unrelated composer text persistence in memory. */
private object NoPreWarmDrafts : DraftPersistence {
    /** Nothing stored. */
    override fun read(): Map<String, String> = emptyMap()

    /** Drops every write. */
    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
