package dev.ipf.whitenoise.android.maestro

import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.core.ProfileLink
import dev.ipf.whitenoise.android.ui.profile.ProfileQrSheet
import dev.ipf.whitenoise.android.ui.settings.ShareConnectContent
import dev.ipf.whitenoise.android.ui.settings.ShareConnectProfile

/** Real public-profile share menus; bounded callbacks/intents never launch an external app. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroShareMenuPresentation(fixture: MaestroPresentationFixture) {
    if (fixture.scenario.startsWith("modal-share-qr-")) {
        ProfileQrMenuPresentation(fixture)
        return
    }
    val busy = fixture.scenario.endsWith("busy")
    val profile =
        remember(fixture) {
            val npub = fixture.appState.npubForDisplay(fixture.peerAccountIdHex)
            ShareConnectProfile(
                "Fixture public profile",
                npub,
                fixture.peerAccountIdHex,
                null,
                "Fixture public address",
            )
        }
    if (fixture.scenario.startsWith("modal-share-connect-gallery")) {
        MaestroShareGalleryPresentation(fixture, profile)
        return
    }
    Box(Modifier.fillMaxSize()) {
        ShareConnectContent(
            profile = profile,
            qrContent = profile.npub,
            copied = false,
            scanInvalid = false,
            pictureShareInProgress = busy,
            onBack = { fixture.finish("dismiss") },
            onShareUrl = { fixture.finish("share-url") },
            onSharePicture = { fixture.finish("share-picture") },
            onCopy = { error("Share menu cannot copy a different field") },
            onOpenScanner = { error("Share menu cannot launch a camera") },
        )
        if (busy) {
            Button(onClick = { fixture.finish("done") }, modifier = Modifier.align(Alignment.BottomEnd)) {
                Text("Finish profile share check")
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun ProfileQrMenuPresentation(fixture: MaestroPresentationFixture) {
    val base = LocalContext.current
    val account = checkNotNull(fixture.appState.activeAccount).accountIdHex
    val npub = fixture.appState.npubForDisplay(account)
    val expectedUrl = remember(npub) { ProfileLink.parse(npub)?.uri ?: npub }
    val picture = fixture.scenario.endsWith("picture")
    val context =
        remember(base, fixture, expectedUrl, picture) {
            object : ContextWrapper(base) {
                override fun startActivity(intent: Intent) {
                    check(intent.action == Intent.ACTION_CHOOSER)
                    val content = checkNotNull(intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
                    check(content.action == Intent.ACTION_SEND)
                    if (picture) {
                        check(content.type == "image/png")
                        check(content.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java) != null)
                        fixture.finish("qr-picture-handoff")
                    } else {
                        check(content.type == "text/plain" && content.getStringExtra(Intent.EXTRA_TEXT) == expectedUrl)
                        fixture.finish("qr-url-handoff")
                    }
                }
            }
        }
    CompositionLocalProvider(LocalContext provides context) {
        ProfileQrSheet(fixture.appState, account, onDismiss = { fixture.finish("dismiss") }, showScan = false)
    }
}
