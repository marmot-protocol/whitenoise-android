package dev.ipf.whitenoise.android.media.sandbox

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in UI-level measurements for one named lane per run (`-e ui_lane reuse|prewarm2|default`, repeat
 * runs in ABBA order). Rows go to instrumentation status key `media_worker_ui_json`.
 */
@RunWith(AndroidJUnit4::class)
@ManualDeviceFixture
class MediaWorkerUiThroughputDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val lane: String = InstrumentationRegistry.getArguments().getString("ui_lane") ?: "default"
    private val repeats: Int = InstrumentationRegistry.getArguments().getString("ui_repeats")?.toIntOrNull() ?: 5

    /** Restores the production facade and the avatar loader's real fetcher. */
    @After
    fun tearDown() {
        AvatarImageLoader.resetProfileImageFetcherForTests()
        AvatarImageLoader.clear()
    }

    /** One touch scroll of the media grid on the named lane; one pass per run because content is set once. */
    @Test
    fun measureGridScroll() {
        UiLane.install(lane).use { MediaWorkerUiReport.emit(MediaWorkerUiScenarios.gridScroll(rule, it)) }
    }

    /** Repeated notification artwork, idle and behind a burst, then viewer and editor cancellation. */
    @Test
    fun measureNotificationArtworkAndCancellation() {
        AvatarImageLoader.clear()
        val avatar = MediaWorkerUiScenarios.png(AVATAR_EDGE, AVATAR_SEED)
        AvatarImageLoader.attachProfileImageFetcher { _, _ -> avatar }
        UiLane.install(lane).use { installed ->
            repeat(repeats) { attempt ->
                MediaWorkerUiReport.emit(MediaWorkerUiScenarios.notificationArtwork(installed, false, attempt * 2))
                MediaWorkerUiReport.emit(MediaWorkerUiScenarios.notificationArtwork(installed, true, attempt * 2 + 1))
                MediaWorkerUiReport.emit(MediaWorkerUiScenarios.viewerCancellation(installed))
                MediaWorkerUiReport.emit(MediaWorkerUiScenarios.editorCancellation(installed))
            }
        }
    }

    private companion object {
        const val AVATAR_EDGE = 256
        const val AVATAR_SEED = 7
    }
}

/** Sends one UI scenario row as instrumentation status, retained in the raw instrumentation output. */
internal object MediaWorkerUiReport {
    /** Emits [row] under `media_worker_ui_json`, with the device API level. */
    fun emit(row: org.json.JSONObject) {
        row.put("api", android.os.Build.VERSION.SDK_INT)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply { putString("media_worker_ui_json", row.toString()) },
        )
    }
}
