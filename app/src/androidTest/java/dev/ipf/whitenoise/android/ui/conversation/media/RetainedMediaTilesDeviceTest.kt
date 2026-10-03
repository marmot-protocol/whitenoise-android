package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.FixtureSession
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.MediaLifecycleAttachmentProbe
import dev.ipf.whitenoise.android.media.RestartAttachmentRetentionProbe
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** One restored attachment to render, for the account that owns it. */
private class TileCase(
    val message: String,
    val media: String,
    val index: Int,
    val role: String,
    val request: AttachmentTransferRequest,
    val reference: MediaAttachmentReferenceFfi,
)

/** The tile the single composition currently hosts, with the state and controller of the account that owns it. */
private class ActiveTile(
    val case: TileCase,
    val state: WhiteNoiseAppState,
    val controller: ConversationController,
    val probe: TileProbe,
)

/** Everything the composition needs to open the viewer exactly once and to be sampled for affordances. */
private class TileProbe(
    val opens: MutableList<ConversationMediaViewerOpenRequest> = mutableListOf(),
)

/**
 * Renders the real image and video tiles for media that was genuinely sent and received, in a new process, with every
 * automatic-download cell turned off and acquisition unavailable. Media that MDK retains locally must show its content,
 * never a Download or Retry affordance, and open on a single tap without any transfer.
 */
@ManualDeviceFixture
@RunWith(AndroidJUnit4::class)
class RetainedMediaTilesDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Samples every restored tile for a Download or Retry affordance, then taps it once and counts viewer opens. */
    @Test
    fun retainedMediaRendersLocallyWithAutomaticDownloadsOff() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val arguments = InstrumentationRegistry.getArguments()
            assumeTrue(arguments.getString("allowControlledAttachmentProbe") == "true")
            check(context.packageName == "dev.ipf.whitenoise.android.medialatency")
            val relayPort = requireNotNull(arguments.getString("fixtureRelayPort")).toInt()
            val blobPort = requireNotNull(arguments.getString("fixtureBlobPort")).toInt()
            MarmotAndroid.initialize(context)
            val root =
                RestartAttachmentRetentionProbe.createRoot(
                    context,
                    "read",
                    arguments.getString("fixtureRestartSession"),
                )
            val relays = listOf("ws://127.0.0.1:$relayPort")
            val marmot = TileFixtureSupport.openRuntime(root, relays)
            val session = FixtureSession(context, root, marmot, relays, blobPort)
            try {
                marmot.start()
                renderAndTap(session)
            } finally {
                session.close(preserve = false)
            }
        }

    /** Restores each attachment from the private receipt and the native history, never from the original bytes. */
    private suspend fun cases(session: FixtureSession): List<TileCase> {
        val manifest = JSONObject(File(session.root, MediaLifecycleAttachmentProbe.MANIFEST).readText())
        val items = manifest.getJSONArray("items")
        val cases = mutableListOf<TileCase>()
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            for (role in listOf("sender", "receiver")) {
                val request = request(item.getJSONObject(role))
                session.accounts.takeIf { request.accountRef !in it }?.add(request.accountRef)
                val reference = MediaLifecycleAttachmentProbe.publishedReference(session.marmot, request)
                cases +=
                    TileCase(
                        item.getString("message"),
                        item.getString("media"),
                        item.getInt("index"),
                        if (role == "sender") "sent" else "received",
                        request,
                        reference,
                    )
            }
        }
        return cases
    }

    /** Renders each tile in turn inside the rule's single content, samples it until it settles, then taps it once. */
    private suspend fun renderAndTap(session: FixtureSession) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val downloadLabel = context.getString(R.string.media_tap_to_download)
        val retryLabel = context.getString(R.string.media_tap_to_retry)
        // Only MDK's retention may remain: drop every playback file and decoded thumbnail the earlier stages produced.
        File(context.cacheDir, MediaCacheDirs.VIDEO).deleteRecursively()
        val controllers = mutableMapOf<String, Pair<WhiteNoiseAppState, ConversationController>>()
        val active = mutableStateOf<ActiveTile?>(null)
        composeRule.setContent {
            active.value?.let { tile ->
                key(tile.case.request.messageIdHex, tile.case.index, tile.case.role) {
                    Tile(tile.case, tile.state, tile.controller, tile.probe)
                }
            }
        }
        for (case in cases(session)) {
            val req = case.request
            val receiver = controllers.getOrPut(req.accountRef) { TileFixtureSupport.receiverController(session, req) }
            val (state, controller) = receiver
            val probe = TileProbe()
            composeRule.runOnUiThread { active.value = ActiveTile(case, state, controller, probe) }
            val started = SystemClock.elapsedRealtime()
            var downloadSeen = false
            var retrySeen = false
            val trace = mutableListOf<Pair<Char, Long>>()
            var settledMillis = -1L
            while (SystemClock.elapsedRealtime() - started < SETTLE_TIMEOUT_MILLIS && settledMillis < 0) {
                composeRule.waitForIdle()
                // One consistent sample per frame, so a tile changing between two reads cannot read as two states.
                val idleDownload = idleDownloadAffordance(downloadLabel)
                val displayed = shown(case, context)
                downloadSeen = downloadSeen || idleDownload
                retrySeen = retrySeen || labelled(retryLabel)
                val frame =
                    when {
                        idleDownload -> 'D'
                        displayed -> 'I'
                        labelled(downloadLabel) -> 'S'
                        else -> '-'
                    }
                if (trace.lastOrNull()?.first != frame) trace += frame to (SystemClock.elapsedRealtime() - started)
                if (displayed) settledMillis = SystemClock.elapsedRealtime() - started
                kotlinx.coroutines.delay(FRAME_MILLIS)
            }
            // A tile that never showed its media is reported, not tapped: there is nothing readable to open.
            val opened = settledMillis >= 0 && tapOnce(case, probe)
            val states = trace.joinToString(" ") { "${it.first}@${it.second}" }
            report(case, downloadSeen, retrySeen, settledMillis, opened, states)
            composeRule.runOnUiThread { active.value = null }
            composeRule.waitForIdle()
        }
    }

    /** Hosts exactly the production tile for the attachment, with the viewer hand-off recorded. */
    @Composable
    private fun Tile(
        case: TileCase,
        state: WhiteNoiseAppState,
        controller: ConversationController,
        probe: TileProbe,
    ) {
        val item = TileFixtureSupport.timelineMessage(case.request, case.reference, sent = case.role == "sent")
        WhiteNoiseTheme(darkTheme = false) {
            Column {
                if (case.media == "video") {
                    MediaVideoBubble(
                        item = item,
                        attachmentIndex = case.index,
                        reference = case.reference,
                        controller = controller,
                        appState = state,
                        onOpenConversationMedia = { probe.opens += it },
                        mine = case.role == "sent",
                    )
                } else {
                    MediaImageBubble(
                        item = item,
                        reference = case.reference,
                        attachmentIndex = case.index,
                        controller = controller,
                        appState = state,
                        onOpenConversationMedia = { probe.opens += it },
                        mine = case.role == "sent",
                    )
                }
            }
        }
    }

    /** True when the tile shows its media: a decoded image, or a video tile with its play affordance. */
    private fun shown(
        case: TileCase,
        context: Context,
    ): Boolean =
        if (case.media == "video") {
            labelled(context.getString(R.string.reply_media_video))
        } else {
            labelled(
                dev.ipf.whitenoise.android.media.MediaPipeline
                    .safeDisplayName(case.reference.fileName),
            )
        }

    /**
     * The idle Download action: a node carrying [label] that is not itself clickable, in the unmerged tree. The idle
     * action's label sits on its icon, inside a clickable surface, while the loading spinner reuses the label on a node
     * that is clickable, so reading retained bytes locally never counts as a prompt to download.
     */
    private fun idleDownloadAffordance(label: String): Boolean =
        composeRule
            .onAllNodes(hasContentDescription(label) and !hasClickAction(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    /** Whether any node currently carries [label] as its content description. */
    private fun labelled(label: String): Boolean =
        composeRule
            .onAllNodesWithContentDescription(label)
            .fetchSemanticsNodes()
            .isNotEmpty()

    /** One tap on the tile; the viewer hand-off must happen exactly once and carry the tapped attachment. */
    private fun tapOnce(
        case: TileCase,
        probe: TileProbe,
    ): Boolean {
        if (case.media == "video") {
            composeRule.onNodeWithTag(videoAttachmentOpenTestTag(case.request.messageIdHex, case.index)).performClick()
        } else {
            val name =
                dev.ipf.whitenoise.android.media.MediaPipeline
                    .safeDisplayName(case.reference.fileName)
            composeRule.onAllNodesWithContentDescription(name).assertCountEquals(1)
            composeRule.onAllNodesWithContentDescription(name)[0].performClick()
        }
        composeRule.waitForIdle()
        return probe.opens.size == 1 &&
            probe.opens.single().messageIdHex == case.request.messageIdHex &&
            probe.opens.single().tappedAttachmentIndex == case.index
    }

    /** Emits only closed facts about one tile; no file name, identifier or content leaves the device. */
    private fun report(
        case: TileCase,
        downloadSeen: Boolean,
        retrySeen: Boolean,
        settledMillis: Long,
        opened: Boolean,
        trace: String,
    ) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply {
                putString(
                    "controlled_attachment_json",
                    JSONObject()
                        .put("phase", "media-tile")
                        .put("message", case.message)
                        .put("index", case.index)
                        .put("role", case.role)
                        .put("media", case.media)
                        .put("shown", settledMillis >= 0)
                        .put("settle_ms", settledMillis)
                        .put("download_affordance_seen", downloadSeen)
                        .put("retry_affordance_seen", retrySeen)
                        .put("state_trace", trace)
                        .put("one_tap_opened", opened)
                        .toString(),
                )
            },
        )
    }

    /** Restores one request from the private receipt. */
    private fun request(value: JSONObject) =
        AttachmentTransferRequest(
            value.getString("account"),
            value.getString("group"),
            value.getString("message"),
            value.getInt("index"),
            value.getString("source"),
        )

    private companion object {
        const val SETTLE_TIMEOUT_MILLIS = 10_000L
        const val FRAME_MILLIS = 16L
    }
}
