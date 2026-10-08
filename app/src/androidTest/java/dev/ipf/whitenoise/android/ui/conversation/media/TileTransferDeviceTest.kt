package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.FixtureMediaAssets
import dev.ipf.whitenoise.android.media.FixtureSession
import dev.ipf.whitenoise.android.media.HeldAttachmentCancellationProbe
import dev.ipf.whitenoise.android.media.MediaLifecycleAttachmentProbe
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.RestartAttachmentRetentionProbe
import dev.ipf.whitenoise.android.media.sendAndroidFixtureMedia
import dev.ipf.whitenoise.android.state.AttachmentOpenDestination
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val IMAGE_BYTES = 3 * 1024 * 1024
private const val VIDEO_BYTES = 9 * 1024 * 1024
private const val JPEG_SEED = 7L
private const val STEP_TIMEOUT_MILLIS = 20_000L
private const val FAILURE_TIMEOUT_MILLIS = 45_000L
private const val COMPLETE_TIMEOUT_MILLIS = 60_000L
private const val QUIET_MILLIS = 1_500L
private const val FRAME_MILLIS = 16L

/** One generated attachment that the receiver has not downloaded, with everything its real tile needs. */
private class TransferTile(
    val media: String,
    val request: AttachmentTransferRequest,
    val reference: MediaAttachmentReferenceFfi,
    val state: WhiteNoiseAppState,
    val controller: ConversationController,
)

/**
 * Drives the real image and video tiles through a genuine transfer in a fresh process: a known-length image that is
 * cancelled and downloaded again, a video whose first attempts fail and which is retried after the server recovers,
 * and an image whose length the server never declares. Every automatic-download cell is off, so each download starts
 * from the reader's tap, and every fact reported is closed: sizes, booleans and times, never names or identifiers.
 */
@ManualDeviceFixture
@RunWith(AndroidJUnit4::class)
class TileTransferDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var blobPort = 0

    /** Sends three generated attachments, then runs the known, failed and unknown-length scenarios on real tiles. */
    @Test
    fun realTilesShowBytesCancelRetryAndComplete() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val arguments = InstrumentationRegistry.getArguments()
            assumeTrue(arguments.getString("allowControlledAttachmentProbe") == "true")
            check(context.packageName == "dev.ipf.whitenoise.android.medialatency")
            blobPort = requireNotNull(arguments.getString("fixtureBlobPort")).toInt()
            val relays = listOf("ws://127.0.0.1:${requireNotNull(arguments.getString("fixtureRelayPort")).toInt()}")
            MarmotAndroid.initialize(context)
            val root = RestartAttachmentRetentionProbe.createRoot(context, null, null)
            val marmot = TileFixtureSupport.openRuntime(root, relays)
            val session = FixtureSession(context, root, marmot, relays, blobPort)
            try {
                marmot.start()
                val tiles = sendTiles(session)
                val driver = TileDriver(composeRule, context)
                knownLengthCancelAndDownloadAgain(driver, tiles.getValue("known"))
                failedThenRetried(driver, tiles.getValue("failed"))
                unknownLength(driver, tiles.getValue("unknown"))
            } finally {
                session.close(preserve = false)
            }
        }

    /** Sends the three generated attachments from the sender and projects each for the receiver, undownloaded. */
    private suspend fun sendTiles(session: FixtureSession): Map<String, TransferTile> {
        val peers = session.createPeers()
        val jpeg = FixtureMediaAssets.jpeg(JPEG_SEED)
        val sends =
            mapOf(
                "known" to FixtureMediaAssets.paddedJpeg(jpeg, IMAGE_BYTES, 1L),
                "unknown" to FixtureMediaAssets.paddedJpeg(jpeg, IMAGE_BYTES, 3L),
                "failed" to FixtureMediaAssets.paddedVideo(FixtureMediaAssets.video(), VIDEO_BYTES, 2L),
            )
        val tiles = mutableMapOf<String, TransferTile>()
        for ((name, bytes) in sends) {
            val isVideo = name == "failed"
            val attachment =
                FixtureMediaAssets.attachment(
                    bytes,
                    if (isVideo) "video/mp4" else "image/jpeg",
                    if (isVideo) "tile-$name.mp4" else "tile-$name.jpg",
                    if (isVideo) "320x240" else "1280x960",
                )
            val sent =
                sendAndroidFixtureMedia(
                    session.context,
                    session.root,
                    session.marmot,
                    peers.sender,
                    peers.group,
                    session.blobPort,
                    listOf(attachment),
                    awaitOwnHostPublication = true,
                )
            session.marmot.catchUpAccounts()
            val request =
                MediaLifecycleAttachmentProbe
                    .projectRequests(session.marmot, peers.receiver, peers.group, sent.references)
                    .single()
            val (state, controller) = TileFixtureSupport.receiverController(session, request)
            // A tap and a Download again only count while this conversation is the foreground destination, exactly as
            // in the shipping shell, which publishes the same three facts when a conversation opens.
            withContext(Dispatchers.Main.immediate) {
                state.setAppInForeground(true)
                state.setActiveConversationFromUi(request.accountRef, request.groupIdHex)
                state.attachmentOpens.setDestination(
                    AttachmentOpenDestination(request.accountRef, request.groupIdHex, navigationGeneration = 1L),
                )
            }
            val media = if (isVideo) "video" else "image"
            tiles[name] = TransferTile(media, request, sent.references.single(), state, controller)
        }
        return tiles
    }

    /** A known length: Download shows real bytes, Cancel is acknowledged, Download again resumes and completes. */
    private suspend fun knownLengthCancelAndDownloadAgain(
        driver: TileDriver,
        tile: TransferTile,
    ) {
        HeldAttachmentCancellationProbe.control(blobPort, "/__hold-resumable-acquisition")
        driver.host(tile)
        val row = scenario("known-cancel-again", tile)
        row.put("idle_download_seen", driver.waitFor { driver.idleDownload(tile) })
        driver.tapDownload(tile)
        val bytes = driver.waitForDescription(" of ")
        row.put("bytes_text", bytes.orEmpty())
        row.put("cancel_offered", driver.cancelControl() != null)
        val cancelled = driver.string(R.string.media_download_cancelled)
        val started = SystemClock.elapsedRealtime()
        driver.cancelControl()?.performClick()
        row.put("cancelled_seen", driver.waitFor { driver.descriptionIs(cancelled) })
        row.put("cancel_ack_ms", SystemClock.elapsedRealtime() - started)
        delay(QUIET_MILLIS)
        row.put("quiet_after_cancel", driver.descriptionIs(cancelled))
        row.put("descriptions_after_quiet", driver.snapshot())
        if (driver.descriptionIs(cancelled)) driver.node(cancelled).performClick() else driver.tapDownload(tile)
        val again = driver.waitForDescription(" of ")
        row.put("restarted", again != null)
        row.put("descriptions_after_restart", driver.snapshot())
        HeldAttachmentCancellationProbe.control(blobPort, "/__release-acquisition")
        row.put("completed", driver.waitFor(COMPLETE_TIMEOUT_MILLIS) { driver.shown(tile) })
        report(row)
    }

    /** A permanent miss surfaces as a failed tile, and once the server recovers one Retry completes it. */
    private suspend fun failedThenRetried(
        driver: TileDriver,
        tile: TransferTile,
    ) {
        HeldAttachmentCancellationProbe.control(blobPort, "/__acquisition-not-found")
        driver.host(tile)
        val row = scenario("failed-then-retried", tile)
        row.put("idle_download_seen", driver.waitFor { driver.idleDownload(tile) })
        driver.tapDownload(tile)
        val retry = driver.string(R.string.media_tap_to_retry)
        val failedSeen = driver.waitFor(FAILURE_TIMEOUT_MILLIS) { driver.descriptionIs(retry) }
        row.put("failed_seen", failedSeen)
        row.put("descriptions_at_failure", driver.snapshot())
        HeldAttachmentCancellationProbe.control(blobPort, "/__restore-acquisition")
        if (failedSeen) driver.node(retry).performClick()
        row.put("completed", driver.waitFor(COMPLETE_TIMEOUT_MILLIS) { driver.shown(tile) })
        row.put("descriptions_after_retry", driver.snapshot())
        report(row)
    }

    /** An undeclared length reports bytes received with no total, then completes once released. */
    private suspend fun unknownLength(
        driver: TileDriver,
        tile: TransferTile,
    ) {
        HeldAttachmentCancellationProbe.control(blobPort, "/__hold-unknown-acquisition")
        driver.host(tile)
        val row = scenario("unknown-length", tile)
        row.put("idle_download_seen", driver.waitFor { driver.idleDownload(tile) })
        driver.tapDownload(tile)
        val received = driver.waitForDescription(" received")
        row.put("bytes_text", received.orEmpty())
        row.put("no_total", received != null && " of " !in received)
        HeldAttachmentCancellationProbe.control(blobPort, "/__release-acquisition")
        row.put("completed", driver.waitFor(COMPLETE_TIMEOUT_MILLIS) { driver.shown(tile) })
        report(row)
    }

    /** One scenario's closed report row. */
    private fun scenario(
        name: String,
        tile: TransferTile,
    ) = JSONObject().put("phase", "tile-transfer").put("scenario", name).put("media", tile.media)

    /** Emits one row to the host without any name, identifier or content. */
    private fun report(row: JSONObject) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("controlled_attachment_json", row.toString()) },
        )
    }
}

/** Hosts one real tile at a time in the rule's single composition and reads what a reader would see and hear. */
private class TileDriver(
    private val rule: AndroidComposeTestRule<*, ComponentActivity>,
    private val context: Context,
) {
    private val hosted = mutableStateOf<TransferTile?>(null)
    private var composed = false

    /** Replaces the hosted tile; the first call installs the content, later calls only swap the state. */
    fun host(tile: TransferTile) {
        if (!composed) {
            composed = true
            rule.setContent { WhiteNoiseTheme(darkTheme = false) { hosted.value?.let { RealTile(it) } } }
        }
        rule.runOnUiThread { hosted.value = tile }
        rule.waitForIdle()
    }

    /** A localized string from the app under test. */
    fun string(id: Int): String = context.getString(id)

    /** Polls every frame until [condition] holds or [timeoutMillis] passes. */
    suspend fun waitFor(
        timeoutMillis: Long = STEP_TIMEOUT_MILLIS,
        condition: () -> Boolean,
    ): Boolean {
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < timeoutMillis) {
            rule.waitForIdle()
            if (condition()) return true
            delay(FRAME_MILLIS)
        }
        return false
    }

    /** The first description that contains [fragment] and reports real bytes, once one appears. */
    suspend fun waitForDescription(fragment: String): String? {
        var found: String? = null
        waitFor {
            found = descriptions().firstOrNull { fragment in it && !it.startsWith("0 B") }
            found != null
        }
        return found
    }

    /** Every description in the merged tree, for the report when a step is not where it was expected. */
    fun snapshot(): String = descriptions().joinToString(" | ")

    /** True when some node is described exactly as [text]. */
    fun descriptionIs(text: String): Boolean = text in descriptions()

    /** The node a reader hears as [text]. */
    fun node(text: String) = rule.onNode(hasContentDescription(text))

    /** The idle Download action: a labelled node that is not itself clickable, in the unmerged tree. */
    fun idleDownload(tile: TransferTile): Boolean {
        val label = string(if (tile.media == "video") R.string.media_open else R.string.media_tap_to_download)
        return rule
            .onAllNodes(hasContentDescription(label) and !hasClickAction(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
    }

    /** One tap on the tile's idle Download action, once the tile has actually presented it to touch. */
    suspend fun tapDownload(tile: TransferTile) {
        val label = string(if (tile.media == "video") R.string.media_open else R.string.media_tap_to_download)
        val present = waitFor { rule.onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty() }
        check(present) { "no Download action to tap; the tile shows: ${snapshot()}" }
        if (tile.media == "video") {
            rule
                .onNodeWithTag(videoAttachmentOpenTestTag(tile.request.messageIdHex, tile.request.attachmentIndex))
                .performClick()
        } else {
            node(label).performClick()
        }
    }

    /** The control whose click label is Cancel while a transfer is active, or null. */
    fun cancelControl() =
        rule
            .onAllNodes(cancelMatcher())
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.let { found -> rule.onNode(SemanticsMatcher("node ${found.id}") { it.id == found.id }) }

    /** True once the tile shows its media. */
    fun shown(tile: TransferTile): Boolean =
        if (tile.media == "video") {
            descriptionIs(string(R.string.reply_media_video))
        } else {
            descriptionIs(MediaPipeline.safeDisplayName(tile.reference.fileName))
        }

    /** Matches a node whose click is labelled Cancel. */
    private fun cancelMatcher() =
        SemanticsMatcher("cancel control") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == string(R.string.media_cancel_download)
        }

    /** Every content description currently in the merged tree. */
    private fun descriptions(): List<String> =
        rule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
            .fetchSemanticsNodes()
            .flatMap { it.config[SemanticsProperties.ContentDescription] }
}

/** Hosts exactly the production tile for the attachment, with the viewer hand-off ignored. */
@Composable
private fun RealTile(tile: TransferTile) {
    val item = TileFixtureSupport.timelineMessage(tile.request, tile.reference, sent = false)
    Column {
        if (tile.media == "video") {
            MediaVideoBubble(
                item = item,
                attachmentIndex = tile.request.attachmentIndex,
                reference = tile.reference,
                controller = tile.controller,
                appState = tile.state,
                onOpenConversationMedia = {},
                mine = false,
            )
        } else {
            MediaImageBubble(
                item = item,
                reference = tile.reference,
                attachmentIndex = tile.request.attachmentIndex,
                controller = tile.controller,
                appState = tile.state,
                onOpenConversationMedia = {},
                mine = false,
            )
        }
    }
}
