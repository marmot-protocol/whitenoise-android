package dev.ipf.whitenoise.android.media.sandbox

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.os.Process

/** All production still-image codec calls enter the permissionless service, never a local fallback. */
@Suppress("TooManyFunctions") // One process-owned facade for the complete platform codec boundary.
internal object IsolatedMediaDecoder {
    @Volatile private var transport: MediaDecodeTransport? = null

    @Volatile private var mediaTransport: MediaDecodeTransport? = null
    private var installedContext: Context? = null
    private var imageOwner: IsolatedMediaTransport? = null

    @Volatile internal var lastIsolation: WorkerIsolation? = null
        private set

    internal fun recordIsolation(result: android.os.Bundle) {
        lastIsolation =
            WorkerIsolation(
                result.getInt("uid"),
                result.getBoolean("isolated"),
                result.getBoolean("internetGranted"),
                result.getBoolean("runtimeInitialized"),
                result.getBoolean("privateCanaryDenied"),
                result.getInt("privateCanaryErrno"),
                result.getInt("inputWriteErrno", -1),
            )
    }

    /**
     * Installs domain-bound still-image worker generations (#3373), owned for the process's lifetime: a worker
     * serves one data-access domain only, and an undeclared input gets a single-use one.
     */
    @Synchronized
    fun install(context: Context) {
        if (!Process.isIsolated()) {
            clear()
            installedContext = context.applicationContext
            val owner =
                imageOwner ?: IsolatedMediaTransport(
                    context.applicationContext,
                    "images",
                    MediaWorkerPool.DOMAIN_SPARES,
                    liveLimit = MediaWorkerPool.MAX_LIVE_GENERATIONS,
                ).also { imageOwner = it }
            owner.activateSession()
            transport = owner
            mediaTransport = PlatformMediaTransport(context.applicationContext)
        }
    }

    /** Explicit test seam; production initialization always installs the real IPC transport. */
    @Synchronized
    internal fun installTestTransport(next: MediaDecodeTransport?) {
        (transport as? ReusableMediaTransport)?.close()
        imageOwner?.invalidateSession()
        transport = next
        mediaTransport = next
    }

    /** After an account change, puts the production image lane in place of a real image transport; fakes stay. */
    @Synchronized
    internal fun resetForAccount() {
        if (transport is IsolatedMediaTransport || transport is ReusableMediaTransport) {
            val currentContext = installedContext ?: return
            clear()
            install(currentContext)
        }
    }

    @Synchronized
    fun clear() {
        IsolatedAnimationSession.retireActive()
        if (transport === imageOwner) {
            imageOwner?.invalidateSession()
        } else {
            (transport as? java.io.Closeable)?.close()
        }
        (mediaTransport as? java.io.Closeable)?.close()
        transport = null
        mediaTransport = null
    }

    /** Retires [domain]'s still-image worker, such as a pick removed from a draft or sent. */
    fun retireDomain(domain: dev.ipf.whitenoise.android.media.MediaTrustDomain) {
        transport?.retireDomain(domain)
    }

    fun decode(
        bytes: ByteArray,
        edge: Int,
        oriented: Boolean = false,
    ): Bitmap? = safely { it.decode(bytes, edge, oriented, MediaDecodePolicy.DECODE) }

    fun probe(
        file: java.io.File,
        edge: Int = 1280,
        poster: Boolean = true,
    ): MediaProbe? = safely { mediaTransport?.probe(file, edge, poster) }

    fun waveform(file: java.io.File): FloatArray? = safely { mediaTransport?.waveform(file) }

    fun probeBytes(
        bytes: ByteArray,
        edge: Int = 1280,
        poster: Boolean = true,
    ): MediaProbe? = safely { mediaTransport?.probeBytes(bytes, edge, poster) }

    fun probeUri(
        resolver: ContentResolver,
        uri: Uri,
        edge: Int,
    ): MediaProbe? = safely { mediaTransport?.probeUri(resolver, uri, edge) }

    internal fun exerciseWatchdogForTest() {
        check(dev.ipf.whitenoise.android.BuildConfig.DEBUG)
        safely { it.decode(byteArrayOf(1), 1, false, MediaDecodePolicy.DEBUG_HANG) }
    }

    internal fun exerciseMalformedReplyForTest(mode: Int): Bitmap? {
        check(dev.ipf.whitenoise.android.BuildConfig.DEBUG && mode in 1..MediaDecodePolicy.DEBUG_REPLY_KINDS)
        return safely { it.decode(byteArrayOf(1), mode, false, MediaDecodePolicy.DEBUG_BAD_REPLY) }
    }

    fun svg(bytes: ByteArray): Bitmap? =
        safely {
            it.decode(bytes, MediaDecodePolicy.SVG_EDGE, false, MediaDecodePolicy.SVG)
        }

    fun exif(bytes: ByteArray): Int? = safely { it.exif(bytes) }

    fun inspect(bytes: ByteArray): ImageInspection? = safely { it.inspect(bytes) }

    fun region(
        bytes: ByteArray,
        region: MediaImageRegion,
        edge: Int,
    ): Bitmap? = safely { it.region(bytes, region, edge) }

    /** Ordinary callers keep nullable refusal so attachment viewers can use their sampled-still fallback. */
    fun animation(
        bytes: ByteArray,
        edge: Int,
        decorative: Boolean = false,
        waitForDecorativeAdmission: Boolean = false,
    ): android.graphics.drawable.Drawable? = animationAttempt(bytes, edge, decorative, waitForDecorativeAdmission)

    /** Only the existing group playback owner opts into finite typed fault recovery. */
    fun groupAnimation(
        bytes: ByteArray,
        edge: Int,
        demand: AnimationAdmission.GroupDemand?,
    ): android.graphics.drawable.Drawable? =
        safely(preserveStartupFaults = true) { transport ->
            if (transport is IsolatedMediaTransport || transport is ReusableMediaTransport) {
                installedContext?.let { context -> IsolatedAnimationSession.createGroup(context, bytes, edge, demand) }
            } else {
                transport.animation(bytes, edge)
            }
        }

    /**
     * Routes isolated transports to a fresh [IsolatedAnimationSession] and other transports to their own animation
     * decode, collapsing typed startup faults to null while admission-busy faults still escape.
     */
    private fun animationAttempt(
        bytes: ByteArray,
        edge: Int,
        decorative: Boolean,
        waitForDecorativeAdmission: Boolean,
    ): android.graphics.drawable.Drawable? =
        safely { transport ->
            if (transport is IsolatedMediaTransport || transport is ReusableMediaTransport) {
                installedContext?.let { context ->
                    IsolatedAnimationSession.create(context, bytes, edge, decorative, waitForDecorativeAdmission)
                }
            } else {
                transport.animation(bytes, edge)
            }
        }

    /** A persistent, already queued FIFO ticket that a mounted group-thumbnail owner passes to [groupAnimation]. */
    fun groupDemand(): AnimationAdmission.GroupDemand = IsolatedAnimationSession.groupDemand()

    fun decodeUri(
        resolver: ContentResolver,
        uri: Uri,
        edge: Int,
    ): Bitmap? = safely { it.decodeUri(resolver, uri, edge) }

    /**
     * Runs [block] against the installed transport, refusing isolated ones on the main thread and mapping ordinary
     * failures to null. Admission-busy faults always escape so owners can park on availability, startup faults only
     * when [preserveStartupFaults] is set, and an interrupt is restored before returning null.
     */
    private fun <T> safely(
        preserveStartupFaults: Boolean = false,
        block: (MediaDecodeTransport) -> T?,
    ): T? =
        try {
            if ((transport is IsolatedMediaTransport || transport is ReusableMediaTransport) &&
                Looper.myLooper() == Looper.getMainLooper()
            ) {
                null
            } else {
                transport?.let(block)
            }
        } catch (startup: dev.ipf.whitenoise.android.media.AnimationStartupException) {
            if (preserveStartupFaults) throw startup else null
        } catch (busy: dev.ipf.whitenoise.android.media.AnimationAdmissionBusyException) {
            throw busy
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
}

internal data class WorkerIsolation(
    val uid: Int,
    val isolated: Boolean,
    val internetGranted: Boolean,
    val runtimeInitialized: Boolean,
    val privateCanaryDenied: Boolean,
    val privateCanaryErrno: Int,
    val inputWriteErrno: Int = -1,
)
