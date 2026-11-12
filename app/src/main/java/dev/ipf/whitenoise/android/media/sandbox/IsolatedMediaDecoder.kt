package dev.ipf.whitenoise.android.media.sandbox

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.os.Process
import dev.ipf.whitenoise.android.media.MediaTrustDomain

/** All production still-image codec calls enter the permissionless service, never a local fallback. */
@Suppress("TooManyFunctions") // One process-owned facade for the complete platform codec boundary.
internal object IsolatedMediaDecoder {
    @Volatile private var transport: MediaDecodeTransport? = null

    @Volatile private var mediaTransport: MediaDecodeTransport? = null
    private var installedContext: Context? = null
    private var imageOwner: IsolatedMediaTransport? = null
    private var mediaOwner: IsolatedAutomaticAvTransport? = null

    @Volatile internal var lastIsolation: WorkerIsolation? = null
        private set

    @Volatile internal var lastAvIsolation: WorkerIsolation? = null
        private set

    /** The latest still-image receipt alone, which animation and A/V receipts never overwrite. */
    @Volatile internal var lastStillImageIsolation: WorkerIsolation? = null
        private set

    // Observation only (#3722): how many still-image requests reached an isolated worker, and how many replies
    // carried a receipt. A device test reads both around one operation to tie its evidence to that operation.
    private val workerDispatches =
        java.util.concurrent.atomic
            .AtomicLong()
    private val workerReceipts =
        java.util.concurrent.atomic
            .AtomicLong()

    /** Still-image requests sent to an isolated worker so far. */
    internal val dispatchCount: Long get() = workerDispatches.get()

    /**
     * Isolation receipts accepted from still-image workers so far. Animation and A/V receipts are not counted.
     * Concurrent still-image work on another thread would still mix into one operation's deltas.
     */
    internal val receiptCount: Long get() = workerReceipts.get()

    /** Counts one request handed to an isolated worker; never changes routing. */
    internal fun recordDispatch() {
        workerDispatches.incrementAndGet()
    }

    /** Stores the attested automatic A/V isolation receipt without treating it as device qualification. */
    internal fun recordAvIsolation(result: android.os.Bundle) {
        lastAvIsolation = isolationReceipt(result)
    }

    /** Stores the latest accepted isolation receipt for diagnostics without granting a new source capability. */
    internal fun recordIsolation(result: android.os.Bundle) {
        lastIsolation = isolationReceipt(result)
    }

    /** Stores a still-image worker's receipt and counts it for per-operation evidence. */
    internal fun recordStillImageIsolation(result: android.os.Bundle) {
        // One parsed value for both fields, so an animation or A/V receipt cannot land in between.
        val receipt = isolationReceipt(result)
        lastIsolation = receipt
        lastStillImageIsolation = receipt
        workerReceipts.incrementAndGet()
    }

    /** Returns the retained diagnostic receipt rather than probing or parsing another source. */
    private fun isolationReceipt(result: android.os.Bundle): WorkerIsolation =
        WorkerIsolation(
            result.getInt("uid"),
            result.getBoolean("isolated"),
            result.getBoolean("internetGranted"),
            result.getBoolean("runtimeInitialized"),
            result.getBoolean("privateCanaryDenied"),
            result.getInt("privateCanaryErrno"),
            result.getInt("inputWriteErrno", -1),
        )

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
            val automaticOwner =
                mediaOwner ?: IsolatedAutomaticAvTransport(
                    context.applicationContext,
                    "automatic_av",
                ).also { mediaOwner = it }
            automaticOwner.activateSession()
            mediaTransport = automaticOwner
        }
    }

    /** Explicit test seam; production initialization always installs the real IPC transport. */
    @Synchronized
    internal fun installTestTransport(next: MediaDecodeTransport?) {
        (transport as? ReusableMediaTransport)?.close()
        imageOwner?.invalidateSession()
        mediaOwner?.invalidateSession()
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

    /** Revokes the selected transport without inventing a worker-death observation. */
    @Synchronized
    fun clear() {
        IsolatedAnimationSession.retireActive()
        RawAvPlaybackEngine.retireAll()
        if (transport === imageOwner) {
            imageOwner?.invalidateSession()
        } else {
            (transport as? java.io.Closeable)?.close()
        }
        if (mediaTransport === mediaOwner) {
            mediaOwner?.invalidateSession()
        } else {
            (mediaTransport as? java.io.Closeable)?.close()
        }
        transport = null
        mediaTransport = null
    }

    /** Retires [domain]'s still-image worker, such as a draft that was sent or emptied. */
    fun retireDomain(domain: dev.ipf.whitenoise.android.media.MediaTrustDomain) {
        transport?.retireDomain(domain)
    }

    /**
     * The [domains] whose next still-image decode needs no worker to start first. A lane that does not start
     * workers on demand, or no lane at all, has nothing to wait for, so every domain is reported.
     */
    fun servableNow(domains: List<MediaTrustDomain>): Set<MediaTrustDomain> {
        val readiness = transport as? MediaWorkerReadiness ?: return domains.toSet()
        return readiness.servableNow(domains)
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

    /** Blocking compressed-audio work has a separate bounded analysis lane. */
    internal fun openAudioStream(file: java.io.File): IsolatedAvStream {
        val context =
            synchronized(this) { installedContext } ?: throw java.io.IOException("Media decoder is unavailable")
        return IsolatedAvStream.openFile(
            context,
            file,
            wantVideo = false,
            wantAudio = true,
            lane = AvStreamLane.Analysis,
        )
    }

    fun probeUriAt(
        resolver: ContentResolver,
        uri: Uri,
        positionMs: Long,
        edge: Int = 1280,
    ): MediaProbe? =
        safely {
            if (positionMs !in 0..MAX_MEDIA_DURATION_MS) {
                null
            } else {
                mediaTransport?.probeUriAt(resolver, uri, positionMs, edge)
            }
        }

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
    fun svgInspect(bytes: ByteArray): ImageInspection? = safely { it.svgInspect(bytes) }

    fun svgRegion(
        bytes: ByteArray,
        region: ViewerSvgRegion,
        edge: Int,
    ): Bitmap? = safely { it.svgRegion(bytes, region, edge) }

    fun animation(
        bytes: ByteArray,
        edge: Int,
        decorative: Boolean = false,
        waitForDecorativeAdmission: Boolean = false,
    ): android.graphics.drawable.Drawable? = animationAttempt(bytes, edge, decorative, waitForDecorativeAdmission)

    /** Source-sized viewer animation remains in the existing isolated admission and frame stream. */
    fun viewerAnimation(bytes: ByteArray): android.graphics.drawable.Drawable? =
        safely { transport ->
            if (transport is IsolatedMediaTransport || transport is ReusableMediaTransport) {
                installedContext?.let { context -> IsolatedAnimationSession.createViewer(context, bytes) }
            } else {
                transport.animation(bytes, dev.ipf.whitenoise.android.media.MAX_ANIMATION_SOURCE_EDGE_PX)
            }
        }

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
