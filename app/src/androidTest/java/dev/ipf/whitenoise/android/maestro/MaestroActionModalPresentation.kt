package dev.ipf.whitenoise.android.maestro

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerFrame
import dev.ipf.whitenoise.android.ui.group.GroupDetailsLocalDeleteControl
import dev.ipf.whitenoise.android.ui.group.GroupDetailsResetControl
import dev.ipf.whitenoise.android.ui.profile.ProfileImageActions
import dev.ipf.whitenoise.android.ui.profile.ProfileImageTarget
import dev.ipf.whitenoise.android.ui.settings.DeveloperBuildFacts
import dev.ipf.whitenoise.android.ui.settings.DeveloperContent

/** Real menu/confirmation controls with exact bounded callbacks; no image upload, deletion or demo creation. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroActionModalPresentation(fixture: MaestroPresentationFixture) {
    when {
        fixture.scenario.startsWith("modal-image-") -> ProfileImageMenuPresentation(fixture)
        fixture.scenario.startsWith("modal-group-control-") -> GroupControlPresentation(fixture)
        fixture.scenario.startsWith("modal-developer-") -> DeveloperConfirmationPresentation(fixture)
        else -> MediaViewerMenuPresentation(fixture)
    }
}

@Composable
@Suppress("FunctionNaming")
private fun ProfileImageMenuPresentation(fixture: MaestroPresentationFixture) {
    val target = if (fixture.scenario.contains("banner")) ProfileImageTarget.Banner else ProfileImageTarget.Picture
    val busy = fixture.scenario.endsWith("busy")
    val disabled = fixture.scenario.endsWith("disabled")
    Column {
        ProfileImageActions(
            owner = "fixture-image-owner",
            target = target,
            hasImage = fixture.scenario.contains("present"),
            enabled = !busy && !disabled,
            busy = busy,
            onPick = { _, _ -> error("Picker cancellation cannot choose an image or upload") },
            onWeb = { fixture.finish("web-${target.name.lowercase()}") },
            onRemove = {
                check(it == target)
                fixture.finish("remove-${target.name.lowercase()}")
            },
        )
        if (busy || disabled) {
            Button(onClick = { fixture.finish("done") }) { Text("Finish image action check") }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun GroupControlPresentation(fixture: MaestroPresentationFixture) {
    val reset = fixture.scenario.contains("reset")
    val busy = fixture.scenario.endsWith("busy")
    val hidden = fixture.scenario.endsWith("hidden")
    val invitation = fixture.scenario.endsWith("invite")
    val unverified = fixture.scenario.endsWith("unverified")
    Column {
        if (reset) {
            GroupDetailsResetControl(
                isDm = hidden,
                readOnlyInvite = invitation,
                enabled = !busy,
                inProgress = busy,
                onResetConfirmed = { fixture.finish("reset-confirm") },
            )
        } else {
            GroupDetailsLocalDeleteControl(
                isDm = fixture.scenario.contains("direct"),
                readOnlyInvite = invitation,
                isSelfMember = hidden,
                membersVerified = !unverified,
                enabled = !busy,
                inProgress = busy,
                onDeleteConfirmed = { fixture.finish("local-delete-confirm") },
            )
        }
        val unavailable = hidden || invitation || unverified
        if (busy || unavailable) {
            Button(onClick = { fixture.finish("done") }) { Text("Finish group control check") }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun DeveloperConfirmationPresentation(fixture: MaestroPresentationFixture) {
    DeveloperContent(
        developerMode = true,
        streamingDebug = false,
        build = DeveloperBuildFacts("Fixture version", "1", "fixture"),
        onDeveloperModeChange = { error("Confirmation cannot change developer preferences") },
        onStreamingDebugChange = { error("Confirmation cannot change streaming preferences") },
        onBack = { fixture.finish("dismiss") },
        onOpenDiagnostics = { error("Wrong developer action") },
        onOpenKeyPackages = { error("Wrong developer action") },
        demoAvailable = true,
        demoHasSavedSetup = !fixture.scenario.endsWith("create"),
        onStartDemo = { fixture.finish("start-demo") },
        onClearDemo = { fixture.finish("clear-demo") },
    )
}

@Composable
@Suppress("FunctionNaming")
private fun MediaViewerMenuPresentation(fixture: MaestroPresentationFixture) {
    val groupPicture = !fixture.scenario.endsWith("basic")
    MediaViewerFrame(
        senderLabel = "Fixture viewer sender",
        recordedAtLabel = "Fixture viewer date",
        onDismiss = { fixture.finish("dismiss") },
        onSave = { fixture.finish("save-handoff") },
        onShare = { fixture.finish("share-handoff") },
        snackbarHostState = remember { SnackbarHostState() },
        onGoToMessage = { fixture.finish("message-handoff") },
        onSetGroupPicture = if (groupPicture) ({ fixture.finish("picture-handoff") }) else null,
        groupPictureBusy = fixture.scenario.endsWith("busy"),
        body = { Image(fixture.image, contentDescription = "Fixture viewer image") },
    )
}
