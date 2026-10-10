package dev.ipf.whitenoise.android.maestro

import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Presentation-only fault states use production composables, with no installer, signer or public network. */
internal class MaestroPresentationFixture(
    val scenario: String,
    private val expected: List<String>,
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
    private lateinit var clipboard: android.content.ClipboardManager

    fun finish(action: String) {
        calls.add(action)
        complete = true
    }

    fun record(action: String) {
        calls.add(action)
    }

    fun install(activity: MainActivity) {
        appState = (activity.application as MaestroFixtureApplication).fixtureState
        clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
        if (scenario == "surface-onboarding-signup") appState.beginProfileSignUp()
        if (scenario == "surface-animated-avatar") installAnimatedAvatar()
        if (scenario.startsWith("preview-")) {
            val file = File.createTempFile("maestro-preview-", ".png", activity.cacheDir)
            imageFile = file
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            imageUri = Uri.fromFile(file)
        }
        activity.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    if (complete) {
                        Text(
                            "Presentation action completed",
                            Modifier.align(Alignment.Center).testTag("presentation.complete"),
                        )
                    } else {
                        // Dialogs own their Back callbacks. Plain screen fixtures use the same explicit dismissal.
                        BackHandler { finish("dismiss") }
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
        AvatarImageLoader.putCachedAnimated("https://fixture.example.invalid/animated", image, source)
    }

    @Composable
    @Suppress("FunctionNaming")
    private fun Content() {
        when {
            scenario.startsWith("update-") -> MaestroUpdatePresentation(this)
            scenario.startsWith("startup-") -> MaestroStartupPresentation(this)
            scenario.startsWith("text-") -> MaestroTextPresentation(this)
            scenario.startsWith("image-") -> MaestroImagePresentation(this)
            scenario.startsWith("nostr-") -> MaestroNostrPresentation(this)
            scenario.startsWith("dictation-") -> MaestroDictationPresentation(this)
            scenario.startsWith("feedback-") -> MaestroFeedbackPresentation(this)
            scenario.startsWith("setup-") -> MaestroSetupPresentation(this)
            scenario.startsWith("selection-") -> MaestroSelectionPresentation(this)
            scenario.startsWith("preview-") -> MaestroPreviewPresentation(this)
            scenario.startsWith("group-ui-") -> MaestroGroupPresentation(this)
            scenario.startsWith("surface-") -> MaestroSurfacePresentation(this)
            else -> MaestroBoundaryPresentation(this)
        }
    }

    /** Actual callback sequence, never a UI label or controller-supplied success bit. */
    fun verify(): JSONObject {
        check(complete && calls == expected) { "Production presentation callback mismatch: $scenario $calls" }
        if (scenario.startsWith("feedback-") && scenario.endsWith("copyable")) {
            check(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "Synthetic diagnostic report") {
                "Production report Copy did not write the expected synthetic payload"
            }
        }
        if (scenario == "text-dialog-copy") {
            check(
                clipboard.primaryClip?.getItemAt(0)?.text?.toString() ==
                    "Fixture decoded first line\nFixture decoded last line",
            )
        }
        if (scenario == "surface-profile-qr-copy") {
            val account = checkNotNull(appState.activeAccount)
            check(
                clipboard.primaryClip?.getItemAt(0)?.text?.toString() == appState.npubForDisplay(account.accountIdHex),
            )
        }
        return JSONObject().put("scenario", scenario).put("callbacks", JSONArray(calls)).put("verified", true)
    }

    override fun close() {
        if (scenario == "surface-animated-avatar") AvatarImageLoader.clear()
        bitmap.recycle()
        imageFile?.let { check(!it.exists() || it.delete()) { "Generated preview file cleanup failed" } }
    }
}
