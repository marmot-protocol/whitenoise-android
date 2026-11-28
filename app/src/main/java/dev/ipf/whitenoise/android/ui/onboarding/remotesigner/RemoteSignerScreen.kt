package dev.ipf.whitenoise.android.ui.onboarding.remotesigner

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.StateContent
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseCallout
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseOutlinedButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseScaffold
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTopBar
import dev.ipf.whitenoise.android.ui.common.WindowSecureFlag
import dev.ipf.whitenoise.android.ui.common.whiteNoiseVerticalScroll
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingMaxContentWidth
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/**
 * The remote-signer pairing flow: method choice, exact permission review, pairing and verified sign-in. The
 * screen is secure because links and connection codes carry pairing secrets. [onClose] leaves the flow.
 */
@Suppress("FunctionNaming")
@Composable
internal fun RemoteSignerScreen(
    pairing: RemoteSignerPairing,
    onClose: () -> Unit,
    onOpenApproval: (String) -> Boolean,
) {
    WindowSecureFlag()
    val step = pairing.step
    val reviewing = step is RemoteSignerStep.Review
    val title = if (reviewing) R.string.remote_signer_review_title else R.string.remote_signer_title
    BackHandler { if (!pairing.back()) onClose() }
    WhiteNoiseScaffold(
        modifier = Modifier.fillMaxSize().testTag("remote_signer.screen"),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = { WhiteNoiseTopBar(stringResource(title), onBack = { if (!pairing.back()) onClose() }) },
        bottomBar = { RemoteSignerActions(pairing, onOpenApproval) },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = OnboardingMaxContentWidth)
                    .fillMaxSize()
                    .whiteNoiseVerticalScroll(rememberScrollState())
                    .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin, vertical = WhiteNoiseSpacing.Section),
                verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
            ) {
                RemoteSignerBody(pairing, step)
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ColumnScope.RemoteSignerBody(
    pairing: RemoteSignerPairing,
    step: RemoteSignerStep,
) {
    when (step) {
        RemoteSignerStep.Choose ->
            RemoteSignerMethodChoice(
                onLink = { pairing.choose(RemoteSignerMethod.BunkerLink) },
                onCode = { pairing.choose(RemoteSignerMethod.ConnectionCode) },
            )
        RemoteSignerStep.EnterLink -> RemoteSignerLinkField(pairing)
        is RemoteSignerStep.Review -> RemoteSignerPermissionReview(step.permissions)
        RemoteSignerStep.Connecting ->
            RemoteSignerProgress(
                stringResource(R.string.remote_signer_connecting),
                stringResource(R.string.remote_signer_connecting_detail),
            )
        is RemoteSignerStep.WaitingForSigner -> RemoteSignerConnectionCode(step.offer)
        is RemoteSignerStep.Approval -> {
            Text(stringResource(R.string.remote_signer_approval_title), style = MaterialTheme.typography.titleMedium)
            RemoteSignerReviewSupporting(stringResource(R.string.remote_signer_approval_body, step.host))
        }
        RemoteSignerStep.SigningIn, RemoteSignerStep.Done ->
            RemoteSignerProgress(stringResource(R.string.remote_signer_signing_in), null)
        is RemoteSignerStep.Failed ->
            WhiteNoiseCallout(
                text = stringResource(step.failure.messageRes),
                modifier = Modifier.testTag("remote_signer.error").semantics { liveRegion = LiveRegionMode.Polite },
                icon = R.drawable.ic_error,
                isError = true,
            )
    }
}

/** The pinned actions for the current step; the method choice and sign-in progress have none. */
@Suppress("FunctionNaming")
@Composable
private fun RemoteSignerActions(
    pairing: RemoteSignerPairing,
    onOpenApproval: (String) -> Boolean,
) {
    val step = pairing.step
    if (step == RemoteSignerStep.Choose || step == RemoteSignerStep.SigningIn || step == RemoteSignerStep.Done) return
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(WhiteNoiseSpacing.PinnedActionInset),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = OnboardingMaxContentWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        ) {
            when (step) {
                RemoteSignerStep.EnterLink ->
                    RemoteSignerPrimary(R.string.remote_signer_continue, "remote_signer.link.continue") {
                        pairing.submitLink()
                    }
                is RemoteSignerStep.Review ->
                    RemoteSignerPrimary(R.string.remote_signer_review_approve, "remote_signer.review.approve") {
                        pairing.approve()
                    }
                is RemoteSignerStep.Approval -> RemoteSignerApprovalActions(step, pairing, onOpenApproval)
                is RemoteSignerStep.Failed -> {
                    val startOver = step.failure.startOver
                    val label = if (startOver) R.string.remote_signer_start_over else R.string.remote_signer_try_again
                    RemoteSignerPrimary(label, "remote_signer.retry", pairing::retry)
                }
                else -> Unit
            }
            if (step !is RemoteSignerStep.EnterLink) {
                RemoteSignerSecondary(R.string.cancel, "remote_signer.cancel") { pairing.back() }
            }
        }
    }
}

/** I've finished appears only after the user opened the page, so the check is never acknowledged unseen. */
@Suppress("FunctionNaming")
@Composable
private fun RemoteSignerApprovalActions(
    step: RemoteSignerStep.Approval,
    pairing: RemoteSignerPairing,
    onOpenApproval: (String) -> Boolean,
) {
    var opened by remember(step.requestId, step.authUrl) { mutableStateOf<Boolean?>(null) }
    if (opened == true) {
        RemoteSignerPrimary(R.string.remote_signer_approval_done, "remote_signer.approval.done") {
            pairing.approvalFinished()
        }
    }
    if (opened == false) {
        Text(
            stringResource(R.string.remote_signer_approval_open_failed),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error,
        )
    }
    RemoteSignerSecondary(R.string.remote_signer_approval_open, "remote_signer.approval.open") {
        opened = onOpenApproval(step.authUrl)
    }
}

@Suppress("FunctionNaming")
@Composable
private fun RemoteSignerPrimary(
    label: Int,
    tag: String,
    onClick: () -> Unit,
) {
    WhiteNoiseButton(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) { Text(stringResource(label)) }
}

@Suppress("FunctionNaming")
@Composable
private fun RemoteSignerSecondary(
    label: Int,
    tag: String,
    onClick: () -> Unit,
) {
    WhiteNoiseOutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Text(stringResource(label))
    }
}

/** Inline progress for a signer step: [title] with optional [detail], announced as it changes. */
@Suppress("FunctionNaming")
@Composable
private fun RemoteSignerProgress(
    title: String,
    detail: String?,
) {
    StateContent(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("remote_signer.progress")
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        title = title,
        message = detail,
        progress = true,
    )
}
