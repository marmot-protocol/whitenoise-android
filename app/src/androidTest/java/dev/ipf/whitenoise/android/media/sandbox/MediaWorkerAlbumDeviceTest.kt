package dev.ipf.whitenoise.android.media.sandbox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.media.editor.PhotoEditRecipe
import dev.ipf.whitenoise.android.media.editor.PhotoEditorRenderResult
import dev.ipf.whitenoise.android.media.editor.PhotoEditorRenderer
import dev.ipf.whitenoise.android.state.MediaQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random

/**
 * Opt-in album measurements (#3373, `BUDGET-v3-amend-picks`): ten synthetic camera-sized photos picked into one
 * conversation, prepared and sent through the composer's own decode entry points on one named lane per run
 * (`-e album_lane master|conversation|pick`, `-e album_repeats N`, repeat runs in ABBA order). Rows go to the
 * instrumentation status key `media_worker_album_json`. Synthetic files only, under the app's own cache.
 */
@RunWith(AndroidJUnit4::class)
@ManualDeviceFixture
class MediaWorkerAlbumDeviceTest {
    private val lane: String = InstrumentationRegistry.getArguments().getString("album_lane") ?: "pick"
    private val repeats: Int = InstrumentationRegistry.getArguments().getString("album_repeats")?.toIntOrNull() ?: 5

    /** Prepares and sends the album [repeats] times on the named lane, emitting one row per phase. */
    @Test
    fun measureAlbumPreparationAndSend() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val album = SyntheticAlbum.write(target)
        try {
            UiLane.install(if (lane == "master") "reuse" else "domain").use { installed ->
                repeat(repeats) { attempt ->
                    val picks = album.picks(lane, attempt)
                    emit(AlbumScenarios.prepare(target, installed, picks).put("lane", lane))
                    emit(AlbumScenarios.send(target, installed, picks, retire = lane == "pick").put("lane", lane))
                }
            }
        } finally {
            album.delete()
        }
    }

    /** Sends [row] under `media_worker_album_json`, with the device API level. */
    private fun emit(row: JSONObject) {
        val bundle = Bundle().apply { putString(KEY, row.put("api", Build.VERSION.SDK_INT).toString()) }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, bundle)
    }

    private companion object {
        const val KEY = "media_worker_album_json"
    }
}

/** One picked photo: its content URI and the outgoing domain the lane decodes it in. */
internal class AlbumPick(
    val uri: Uri,
    val domain: MediaTrustDomain,
)

/** Ten synthetic camera-sized JPEGs in the app cache, served through the app's own FileProvider. */
internal class SyntheticAlbum private constructor(
    private val directory: File,
    private val uris: List<Uri>,
) {
    /**
     * This attempt's picks on [lane]: one outgoing domain per pick for `pick`, one for every pick of the
     * conversation for `conversation` (the previous head's mapping), and either for `master`, which ignores domains.
     */
    fun picks(
        lane: String,
        attempt: Int,
    ): List<AlbumPick> =
        uris.mapIndexed { index, uri ->
            val pick = if (lane == "conversation") "conversation" else "attempt-$attempt-pick-$index"
            AlbumPick(uri, MediaTrustDomain.Outgoing(ACCOUNT, CONVERSATION, pick))
        }

    /** Removes the album's files. */
    fun delete() {
        directory.deleteRecursively()
    }

    companion object {
        private const val ACCOUNT = "album-account"
        private const val CONVERSATION = "album"
        private const val PHOTOS = 10
        private const val WIDTH = 4032
        private const val HEIGHT = 3024
        private const val JPEG_QUALITY = 90
        private const val SHAPES = 60

        /** Writes the album, one photo at a time so only one full-size bitmap is held. */
        fun write(context: Context): SyntheticAlbum {
            val directory = File(context.cacheDir, "composer_paste/album-measure").apply { mkdirs() }
            val authority = "${context.packageName}.fileprovider"
            val uris =
                (0 until PHOTOS).map { index ->
                    val file = File(directory, "photo-$index.jpg")
                    val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
                    try {
                        paint(bitmap, index)
                        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                    } finally {
                        bitmap.recycle()
                    }
                    FileProvider.getUriForFile(context, authority, file)
                }
            return SyntheticAlbum(directory, uris)
        }

        /** A distinct gradient with random shapes per photo, so no two photos or decodes share bytes. */
        private fun paint(
            bitmap: Bitmap,
            seed: Int,
        ) {
            val canvas = Canvas(bitmap)
            val random = Random(seed.toLong())
            val gradient = Paint()
            gradient.shader =
                LinearGradient(
                    0f,
                    0f,
                    WIDTH.toFloat(),
                    HEIGHT.toFloat(),
                    Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)),
                    Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)),
                    Shader.TileMode.CLAMP,
                )
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), gradient)
            val shape = Paint(Paint.ANTI_ALIAS_FLAG)
            repeat(SHAPES) {
                shape.color = Color.argb(160, random.nextInt(256), random.nextInt(256), random.nextInt(256))
                canvas.drawCircle(
                    random.nextInt(WIDTH).toFloat(),
                    random.nextInt(HEIGHT).toFloat(),
                    (40 + random.nextInt(400)).toFloat(),
                    shape,
                )
            }
        }
    }
}

/** The album's two measured phases, through the composer's and sender's own decode entry points. */
internal object AlbumScenarios {
    private const val PREVIEW_EDGE = 256
    private val quality = MediaQuality.Standard

    /**
     * Pick -> previews shown: every pick at once, each staged as PhotoDraftStager stages it (read, inspect, render
     * the Original recipe at standard quality) while its shelf tile decodes its preview.
     */
    fun prepare(
        context: Context,
        lane: UiLane,
        picks: List<AlbumPick>,
    ): JSONObject =
        runBlocking {
            val renderer = PhotoEditorRenderer()
            val started = SystemClock.elapsedRealtimeNanos()
            val results =
                coroutineScope {
                    picks
                        .map { pick ->
                            async(Dispatchers.Default) {
                                val domain = lane.domain(pick.domain)
                                val begin = SystemClock.elapsedRealtimeNanos()
                                val staged =
                                    async(Dispatchers.Default) {
                                        val bytes =
                                            withContext(Dispatchers.IO) {
                                                context.contentResolver
                                                    .openInputStream(pick.uri)
                                                    ?.use { it.readBytes() }
                                            } ?: return@async false
                                        renderer.inspect(bytes, domain)
                                        val rendered = renderer.render(bytes, PhotoEditRecipe.Original, quality, domain)
                                        (rendered as? PhotoEditorRenderResult.Success) != null
                                    }
                                val previewed =
                                    async(Dispatchers.IO) {
                                        val resolver = context.contentResolver
                                        MediaPipeline
                                            .decodeSampledFromUri(resolver, pick.uri, PREVIEW_EDGE, domain)
                                            ?.also { it.recycle() } != null
                                    }
                                val ok = staged.await() && previewed.await()
                                (SystemClock.elapsedRealtimeNanos() - begin) to ok
                            }
                        }.awaitAll()
                }
            row("album_prepare", started, results)
        }

    /**
     * Send tap -> attachments encoded for upload, then just-sent posters: the picks in slot order, as an unprepared
     * slot is read at send time, then each sent attachment's poster. With [retire], as on the pick lane, each pick's
     * worker is retired at acceptance and again after its poster, as sending a pick does.
     */
    fun send(
        context: Context,
        lane: UiLane,
        picks: List<AlbumPick>,
        retire: Boolean,
    ): JSONObject {
        val started = SystemClock.elapsedRealtimeNanos()
        val results = mutableListOf<Pair<Long, Boolean>>()
        val encoded =
            picks.map { pick ->
                val begin = SystemClock.elapsedRealtimeNanos()
                val jpeg =
                    MediaPipeline.readDownscaledJpeg(
                        context.contentResolver,
                        pick.uri,
                        maxEdgePx = quality.imageMaxEdgePx,
                        quality = quality.imageJpegQuality,
                        domain = lane.domain(pick.domain),
                    )
                results += (SystemClock.elapsedRealtimeNanos() - begin) to (jpeg != null)
                jpeg?.bytes
            }
        if (retire) picks.forEach { IsolatedMediaDecoder.retireDomain(it.domain) }
        picks.zip(encoded).forEachIndexed { index, (pick, bytes) ->
            val begin = SystemClock.elapsedRealtimeNanos()
            val poster =
                bytes?.let {
                    val domain = lane.domain(pick.domain)
                    MediaPipeline.decodeSampledBitmap(it, MediaPipeline.THUMBNAIL_MAX_EDGE_PX, domain = domain)
                }
            poster?.recycle()
            if (retire) IsolatedMediaDecoder.retireDomain(pick.domain)
            val (spent, ok) = results[index]
            results[index] = (spent + SystemClock.elapsedRealtimeNanos() - begin) to (ok && poster != null)
        }
        return row("album_send", started, results)
    }

    /** One phase's row: whole-album time, each pick's time and how many picks failed. */
    private fun row(
        scenario: String,
        started: Long,
        results: List<Pair<Long, Boolean>>,
    ): JSONObject =
        JSONObject()
            .put("scenario", scenario)
            .put("album_ns", SystemClock.elapsedRealtimeNanos() - started)
            .put("pick_ns", JSONArray(results.map { it.first }))
            .put("picks", results.size)
            .put("failures", results.count { !it.second })
}
