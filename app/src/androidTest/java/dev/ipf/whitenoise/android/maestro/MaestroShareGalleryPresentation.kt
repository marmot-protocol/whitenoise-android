package dev.ipf.whitenoise.android.maestro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.ipf.whitenoise.android.ui.settings.ShareConnectContent
import dev.ipf.whitenoise.android.ui.settings.ShareConnectProfile

/** Real export controls with explicit UI ports; this does not certify a MediaStore write. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroShareGalleryPresentation(
    fixture: MaestroPresentationFixture,
    profile: ShareConnectProfile,
) {
    val unavailable = fixture.scenario.endsWith("unavailable")
    val initiallyBusy = fixture.scenario.endsWith("busy")
    val cycle = fixture.scenario.endsWith("cycle")
    var saving by remember(fixture) { mutableStateOf(initiallyBusy) }
    var settled by remember(fixture) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        ShareConnectContent(
            profile = profile,
            qrContent = if (unavailable) "Unavailable profile link" else profile.npub,
            copied = false,
            scanInvalid = false,
            gallerySaveInProgress = saving,
            canSaveToGallery = !unavailable,
            onSaveToGallery = {
                check(!saving && !unavailable && !settled)
                if (cycle) {
                    saving = true
                    fixture.record("gallery-started")
                } else {
                    fixture.finish("gallery-handoff")
                }
            },
            onBack = { error("Gallery check must retain the parent screen") },
            onShareUrl = { error("Gallery action dispatched URL sharing") },
            onSharePicture = { error("Gallery action dispatched picture sharing") },
            onCopy = { error("Gallery action copied a different field") },
            onOpenScanner = { error("Gallery action launched a camera") },
        )
        if (cycle && saving) {
            Button(onClick = {
                check(!settled)
                saving = false
                settled = true
                fixture.record("gallery-settled")
            }, modifier = Modifier.align(Alignment.BottomEnd)) { Text("Complete controlled gallery save") }
        } else if (initiallyBusy || unavailable || settled) {
            Button(onClick = { fixture.finish("done") }, modifier = Modifier.align(Alignment.BottomEnd)) {
                Text("Finish gallery control check")
            }
        }
    }
}
