package dev.ipf.whitenoise.android.maestro

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
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

    fun finish(action: String) {
        calls.add(action)
        complete = true
    }

    fun record(action: String) {
        calls.add(action)
    }

    fun install(activity: MainActivity) {
        activity.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    if (complete) {
                        Text("Presentation action completed", Modifier.testTag("presentation.complete"))
                    } else {
                        // Dialogs own their Back callbacks. Plain screen fixtures use the same explicit dismissal.
                        BackHandler { finish("dismiss") }
                        Content()
                    }
                }
            }
        }
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
            else -> MaestroBoundaryPresentation(this)
        }
    }

    /** Actual callback sequence, never a UI label or controller-supplied success bit. */
    fun verify(): JSONObject {
        check(complete && calls == expected) { "Production presentation callback mismatch: $scenario $calls" }
        return JSONObject().put("scenario", scenario).put("callbacks", JSONArray(calls)).put("verified", true)
    }

    override fun close() {
        bitmap.recycle()
    }
}
