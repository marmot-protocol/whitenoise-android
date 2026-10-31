package dev.ipf.whitenoise.android.media.editor

import android.graphics.Bitmap
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.media.sandbox.ImageInspection
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.MediaDecodeTransport
import dev.ipf.whitenoise.android.media.sandbox.MediaImageRegion
import dev.ipf.whitenoise.android.media.sandbox.MediaTrustScope
import dev.ipf.whitenoise.android.media.sandbox.TestMediaDecodeTransport
import java.util.Collections

/**
 * Installs the in-process test codec behind a recorder of the worker domain each still-image request declares, so a
 * test can check which pick's worker a decode would reach. [close] restores the plain test codec.
 */
internal class EditorDomainRecorder : AutoCloseable {
    private val seen = Collections.synchronizedList(mutableListOf<MediaTrustDomain>())

    init {
        IsolatedMediaDecoder.installTestTransport(
            object : MediaDecodeTransport by TestMediaDecodeTransport {
                override fun inspect(bytes: ByteArray): ImageInspection? {
                    seen += MediaTrustScope.current()
                    return TestMediaDecodeTransport.inspect(bytes)
                }

                override fun decode(
                    bytes: ByteArray,
                    edge: Int,
                    oriented: Boolean,
                    operation: Int,
                ): Bitmap? {
                    seen += MediaTrustScope.current()
                    return TestMediaDecodeTransport.decode(bytes, edge, oriented, operation)
                }

                override fun region(
                    bytes: ByteArray,
                    region: MediaImageRegion,
                    edge: Int,
                ): Bitmap? {
                    seen += MediaTrustScope.current()
                    return TestMediaDecodeTransport.region(bytes, region, edge)
                }
            },
        )
    }

    /** The distinct domains requests declared since the last [clear]. */
    fun domains(): Set<MediaTrustDomain> = synchronized(seen) { seen.toSet() }

    /** Forgets every recorded request. */
    fun clear() = seen.clear()

    /** Restores the plain in-process test codec. */
    override fun close() = IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
}
