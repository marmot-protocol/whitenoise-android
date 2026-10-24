package dev.ipf.whitenoise.android.maestro

import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.translation.OFFLINE_TRANSLATOR_PACKAGE
import dev.ipf.whitenoise.android.translation.OfflineTranslatorRoute
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Presentation-only fault states use production composables, with no installer, signer or public network. */
internal class MaestroPresentationFixture(
    val scenario: String,
    private val expected: List<String>,
    val peerAccountIdHex: String,
    val nativeChat: ChatListItem? = null,
) : AutoCloseable {
    private val calls = mutableListOf<String>()
    private var complete by mutableStateOf(false)
    val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff4285f4.toInt()) }
    val image = bitmap.asImageBitmap()
    lateinit var appState: WhiteNoiseAppState
        private set
    lateinit var imageUri: Uri
        private set
    private var imageFile: File? = null
    var emojiDirectory: File? = null
        private set
    val emojiBytes: ByteArray
        get() =
            java.io.ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
    private var clipboard: MaestroPresentationClipboard? = null
    private var translationAcquisition: JSONObject? = null
    var nativeController: ConversationController? = null
        private set
    val imagePagesLoaded = mutableSetOf<Int>()
    var nostrImageLoads = 0

    fun finish(action: String) {
        calls.add(action)
        complete = true
    }

    fun record(action: String) {
        calls.add(action)
    }

    /** Retains observed native prerequisites after Maestro clears its device-log buffer. */
    fun recordTranslationAcquisition(
        enginePackageAbsent: Boolean,
        acquisitionRoute: OfflineTranslatorRoute,
    ) {
        check(enginePackageAbsent && acquisitionRoute == OfflineTranslatorRoute.GITHUB_RELEASE)
        translationAcquisition =
            JSONObject()
                .put("package", OFFLINE_TRANSLATOR_PACKAGE)
                .put("enginePackageAbsent", enginePackageAbsent)
                .put("acquisitionRoute", acquisitionRoute.name)
    }

    fun install(activity: MainActivity) {
        appState = (activity.application as MaestroFixtureApplication).fixtureState
        clipboard = maestroPresentationClipboard(scenario, activity, appState)
        nativeChat?.let { chat ->
            nativeController =
                ConversationController(
                    appState,
                    chat.group,
                    initialMemberSnapshot = chat.memberSnapshot,
                    initialChatListRow = chat.projection,
                    startOnConstruction = false,
                )
        }
        if (scenario == "surface-onboarding-signup") appState.beginProfileSignUp()
        if (scenario in setOf("surface-animated-avatar", "surface-animated-group-avatar")) installAnimatedAvatar()
        if (scenario.startsWith("preview-")) {
            val file = File.createTempFile("maestro-preview-", ".png", activity.cacheDir)
            imageFile = file
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            imageUri = Uri.fromFile(file)
        }
        if (scenario.startsWith("extra-modal-emoji-wrapper-")) {
            emojiDirectory =
                java.nio.file.Files
                    .createTempDirectory(activity.cacheDir.toPath(), "maestro-emoji-")
                    .toFile()
        }
        activity.setContent {
            WhiteNoiseTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .semantics { testTagsAsResourceId = true }
                        .testTag("presentation.root"),
                ) {
                    if (complete) {
                        Text(
                            "Presentation action completed",
                            Modifier.align(Alignment.Center).testTag("presentation.complete"),
                        )
                    } else {
                        // Every action receipt must originate in the production control or wrapper.
                        Content()
                    }
                }
            }
        }
    }

    /** Cached generated GIF bytes exercise the actual platform decoder without fetching a URL. */
    private fun installAnimatedAvatar() {
        val source =
            Base64.decode(
                "R0lGODlhAQABAIAAAP8AAAAA/yH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAAAQABAAACAkQB" +
                    "ACH5BAAKAAAALAAAAAABAAEAAAICTAEAOw==",
                Base64.DEFAULT,
            )
        val retained =
            if (scenario == "surface-animated-group-avatar") {
                checkNotNull(MediaPipeline.stripOriginalImageMetadata(source))
            } else {
                source
            }
        AvatarImageLoader.putCachedAnimated("https://fixture.example.invalid/animated", image, retained)
    }

    @Composable
    @Suppress("FunctionNaming")
    private fun Content() {
        when {
            scenario.startsWith("modal-") -> MaestroDisclosureModalPresentation(this)
            scenario.startsWith("update-") -> MaestroUpdatePresentation(this)
            scenario.startsWith("startup-") -> MaestroStartupPresentation(this)
            scenario.startsWith("text-") -> MaestroTextPresentation(this)
            scenario.substringBefore('-') in setOf("image", "nostr", "preview") -> MediaContent()
            scenario.startsWith("dictation-") -> MaestroDictationPresentation(this)
            scenario.startsWith("feedback-") -> MaestroFeedbackPresentation(this)
            scenario.startsWith("setup-") -> MaestroSetupPresentation(this)
            scenario.startsWith("selection-") -> MaestroSelectionPresentation(this)
            scenario.startsWith("group-ui-") -> MaestroGroupPresentation(this)
            scenario.startsWith("surface-") -> MaestroSurfacePresentation(this)
            scenario.startsWith("extra-") -> MaestroExtraPresentation(this)
            else -> MaestroBoundaryPresentation(this)
        }
    }

    @Composable
    @Suppress("FunctionNaming")
    private fun MediaContent() {
        when (scenario.substringBefore('-')) {
            "image" -> MaestroImagePresentation(this)
            "nostr" -> MaestroNostrPresentation(this)
            "preview" -> MaestroPreviewPresentation(this)
            else -> error("Unknown media presentation")
        }
    }

    /** Actual callback sequence, never a UI label or controller-supplied success bit. */
    fun verify(): JSONObject {
        val observing = expected.isEmpty()
        if (observing) {
            check(!complete && calls.isEmpty() && expected.isEmpty()) {
                "Observation-only presentation dispatched an action"
            }
        } else {
            check(complete && calls == expected) { "Production presentation callback mismatch: $scenario $calls" }
        }
        clipboard?.verifyAndClear()
        if (scenario == "nostr-image-failure") {
            check(nostrImageLoads == 2) { "Image Retry did not make exactly two loader requests" }
        }
        if (scenario.startsWith("extra-native-viewer-")) {
            val expectedPages = if (scenario.endsWith("gallery")) setOf(0, 1) else setOf(0)
            check(imagePagesLoaded == expectedPages) { "Viewer did not request the selected image pages" }
        }
        if (scenario in translationAcquisitionScenarios) {
            check(translationAcquisition != null) { "Native translation acquisition prerequisites were not observed" }
        }
        return JSONObject()
            .put("scenario", scenario)
            .put("callbacks", JSONArray(calls))
            .put("observationOnly", observing)
            .put("clipboardBaselineCleared", clipboard != null)
            .put("clipboardVerified", clipboard?.verified == true)
            .put("clipboardCleared", clipboard?.cleared == true)
            .put("imagePagesLoaded", JSONArray(imagePagesLoaded.sorted()))
            .put("nostrImageLoads", nostrImageLoads)
            .put("translationAcquisition", translationAcquisition ?: JSONObject.NULL)
            .put("verified", true)
    }

    fun closeClipboard() {
        clipboard?.close()
    }

    override fun close() {
        nativeController?.onCleared()
        if (scenario in setOf("surface-animated-avatar", "surface-animated-group-avatar")) AvatarImageLoader.clear()
        emojiDirectory?.let { check(it.deleteRecursively() && !it.exists()) { "Emoji fixture cleanup failed" } }
        bitmap.recycle()
        imageFile?.let { check(!it.exists() || it.delete()) { "Generated preview file cleanup failed" } }
    }
}

private val translationAcquisitionScenarios =
    setOf(
        "modal-translation-missing",
        "modal-translation-unverified",
        "modal-translation-detection",
        "modal-translation-model",
        "modal-translation-engine",
    )
