@file:Suppress("FunctionNaming") // Composable functions use framework naming.

package dev.ipf.whitenoise.android.ui.onboarding

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseScaffold
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTopBar
import dev.ipf.whitenoise.android.ui.common.reserveSnackbarSpace
import dev.ipf.whitenoise.android.ui.common.whiteNoiseVerticalScroll
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Renders an app-owned signup attempt; only explicit submission enters native creation. */
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod") // Declarative stage presentation.
@Composable
internal fun SignUpScreen(
    controller: SignUpController,
    hasValidatedInternet: () -> Boolean,
    onBack: () -> Unit,
) {
    val name = remember(controller) { TextFieldState(controller.draft?.name.orEmpty()) }
    val about = remember(controller) { TextFieldState(controller.draft?.about.orEmpty()) }
    var photo by remember(controller) { mutableStateOf(controller.draft?.photo) }
    var preparingPhoto by remember(controller) { mutableStateOf(false) }
    var offline by remember(controller) { mutableStateOf(false) }
    var leaveConfirmation by remember(controller) { mutableStateOf(false) }
    var preview by remember(photo) { mutableStateOf<ImageBitmap?>(null) }
    val stage = controller.stage
    val editable = controller.acceptedIdentity == null && !stage.busy && stage != SignUpStage.OwnerChanged
    LaunchedEffect(name, about, photo) {
        snapshotFlow { SignUpProfileDraft(name.text.toString(), about.text.toString(), photo) }
            .collect(controller::stageDraft)
    }
    LaunchedEffect(photo) {
        val bytes = photo?.plaintext
        preview =
            if (bytes == null) {
                null
            } else {
                withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }
    }

    fun submit() {
        if (preparingPhoto || stage.busy) return
        if (!hasValidatedInternet()) {
            offline = true
            return
        }
        offline = false
        if (stage == SignUpStage.PhotoFailed || stage == SignUpStage.PublishFailed) {
            controller.retry()
        } else {
            controller.submit(SignUpProfileDraft(name.text.toString(), about.text.toString(), photo))
        }
    }

    fun back() {
        if (stage.busy) return
        if (controller.acceptedIdentity != null && stage != SignUpStage.OwnerChanged) {
            leaveConfirmation = true
        } else {
            onBack()
        }
    }
    BackHandler(onBack = ::back)
    SignUpContent(
        name = name,
        about = about,
        photo = preview,
        stage = stage,
        editable = editable,
        preparingPhoto = preparingPhoto,
        offline = offline,
        onSubmit = ::submit,
        onContinueWithoutProfile = controller::continueWithoutProfile,
        onBack = ::back,
        photoControls = {
            SignUpPhotoControls(
                owner = controller,
                enabled = editable,
                photo = photo,
                onPhoto = { photo = it },
                onPreparing = { preparingPhoto = it },
            )
        },
    )
    if (leaveConfirmation) {
        WhiteNoiseAlertDialog(
            onDismissRequest = { leaveConfirmation = false },
            title = { Text(stringResource(R.string.signup_profile_incomplete_title)) },
            text = { Text(stringResource(R.string.signup_leave_profile_detail)) },
            confirmButton = {
                TextButton(onClick = {
                    leaveConfirmation = false
                    controller.continueWithoutProfile()
                }) {
                    Text(stringResource(R.string.signup_continue_without_profile))
                }
            },
            dismissButton = {
                TextButton(onClick = { leaveConfirmation = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Prototype 120 dp avatar, compact photo action, 24 dp sections, 16 dp field gaps and IME-aware 520 dp form. */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")
@Composable
internal fun SignUpContent(
    name: TextFieldState,
    about: TextFieldState,
    photo: ImageBitmap?,
    stage: SignUpStage,
    editable: Boolean,
    preparingPhoto: Boolean,
    offline: Boolean,
    onSubmit: () -> Unit,
    onContinueWithoutProfile: () -> Unit,
    onBack: () -> Unit,
    photoControls: @Composable () -> Unit,
) {
    val retry =
        stage == SignUpStage.PhotoFailed || stage == SignUpStage.PublishFailed || stage == SignUpStage.CreateFailed
    val stale = stage == SignUpStage.OwnerChanged
    val loadingLabel =
        stringResource(
            when (stage) {
                SignUpStage.UploadingPhoto -> R.string.media_uploading
                SignUpStage.Publishing -> R.string.signup_publishing_profile
                else -> R.string.creating_identity_title
            },
        )
    WhiteNoiseScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            WhiteNoiseTopBar(stringResource(R.string.onboarding_sign_up), onBack = { if (!stage.busy) onBack() })
        },
        bottomBar = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .reserveSnackbarSpace()
                    .padding(WhiteNoiseSpacing.PinnedActionInset),
                contentAlignment = Alignment.Center,
            ) {
                WhiteNoiseButton(
                    onClick = if (stale) onBack else onSubmit,
                    enabled = !preparingPhoto && !stage.busy && stage != SignUpStage.Complete,
                    loading = stage.busy,
                    loadingLabel = loadingLabel,
                    modifier =
                        Modifier
                            .widthIn(max = OnboardingMaxContentWidth)
                            .fillMaxWidth()
                            .testTag("onboarding.sign_up.action"),
                ) {
                    Text(
                        stringResource(
                            if (stale) {
                                R.string.close
                            } else if (retry) {
                                R.string.retry
                            } else {
                                R.string.onboarding_sign_up
                            },
                        ),
                    )
                }
            }
        },
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
                    .padding(WhiteNoiseSpacing.CompactScreenMargin),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Section),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
                ) {
                    SignUpAvatar(name.text.toString(), photo)
                    photoControls()
                }
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
                ) {
                    WhiteNoiseTextField(
                        name,
                        modifier = Modifier.fillMaxWidth().testTag("onboarding.sign_up.name"),
                        enabled = editable,
                        label = { Text(stringResource(R.string.name)) },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words,
                                imeAction = ImeAction.Next,
                            ),
                    )
                    WhiteNoiseTextField(
                        about,
                        modifier = Modifier.fillMaxWidth().testTag("onboarding.sign_up.about"),
                        enabled = editable,
                        label = { Text(stringResource(R.string.about)) },
                        placeholder = { Text(stringResource(R.string.signup_about_prompt)) },
                        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 6),
                    )
                }
                if (offline) OnboardingOfflineNotice(onSubmit)
                val error =
                    when (stage) {
                        SignUpStage.CreateFailed -> R.string.signup_create_failed
                        SignUpStage.PhotoFailed -> R.string.signup_photo_failed
                        SignUpStage.PublishFailed -> R.string.signup_publish_failed
                        SignUpStage.OwnerChanged -> R.string.signup_owner_changed
                        else -> null
                    }
                error?.let {
                    Text(
                        stringResource(it),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (stage == SignUpStage.PhotoFailed || stage == SignUpStage.PublishFailed) {
                    TextButton(
                        onClick = onContinueWithoutProfile,
                        modifier = Modifier.testTag("onboarding.sign_up.continue"),
                    ) {
                        Text(stringResource(R.string.signup_continue_without_profile))
                    }
                }
            }
        }
    }
}

/** The prototype's neutral circular monogram or the actual locally prepared pixels; no fixture avatar assets. */
@Composable
internal fun SignUpAvatar(
    name: String,
    photo: ImageBitmap?,
) {
    val description = stringResource(R.string.profile_picture_sheet_title)
    val modifier = Modifier.size(120.dp).clip(CircleShape).semantics { contentDescription = description }
    if (photo != null) {
        Image(photo, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text(name.trim().firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.headlineMedium)
        }
    }
}
