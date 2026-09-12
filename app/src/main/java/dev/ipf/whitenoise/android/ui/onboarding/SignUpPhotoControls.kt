package dev.ipf.whitenoise.android.ui.onboarding

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.GroupImageDraftProcessor
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDropdownMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem
import dev.ipf.whitenoise.android.ui.group.ImageSearchSheet
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Real platform/file/web selection prepares a local bounded image; no upload occurs before Sign Up. */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")
@Composable
internal fun SignUpPhotoControls(
    owner: Any,
    enabled: Boolean,
    photo: ImageUploadDraft?,
    onPhoto: (ImageUploadDraft?) -> Unit,
    onPreparing: (Boolean) -> Unit,
    prepareUri: (suspend (Uri) -> ImageUploadDraft)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember(owner) { mutableStateOf(false) }
    var webSession by remember(owner) { mutableStateOf<Long?>(null) }
    var pickerGeneration by remember(owner) { mutableStateOf<Long?>(null) }
    var generation by remember(owner) { mutableStateOf(0L) }
    var active by remember(owner) { mutableStateOf(true) }
    var preparing by remember(owner) { mutableStateOf(false) }
    var error by remember(owner) { mutableStateOf(false) }
    var job by remember(owner) { mutableStateOf<Job?>(null) }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentPhoto by rememberUpdatedState(onPhoto)
    val currentPreparing by rememberUpdatedState(onPreparing)

    fun invalidate() {
        generation++
        pickerGeneration = null
        webSession = null
        menu = false
        job?.cancel()
        preparing = false
        currentPreparing(false)
    }
    DisposableEffect(owner) {
        onDispose {
            active = false
            invalidate()
        }
    }
    LaunchedEffect(enabled) { if (!enabled) invalidate() }

    fun prepare(action: suspend () -> ImageUploadDraft) {
        if (!currentEnabled || !active) return
        val token = ++generation
        job?.cancel()
        preparing = true
        currentPreparing(true)
        error = false
        job =
            scope.launch {
                try {
                    val result = action()
                    if (token == generation && active && currentEnabled) {
                        currentPhoto(result)
                        webSession = null
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (token == generation && active && currentEnabled) error = true
                } finally {
                    if (token == generation && active) {
                        preparing = false
                        currentPreparing(false)
                    }
                }
            }
    }

    fun selected(uri: Uri?) {
        val token = pickerGeneration
        pickerGeneration = null
        val ownsPicker = token != null && token == generation && currentEnabled
        if (uri != null && ownsPicker && active) {
            prepare { prepareUri?.invoke(uri) ?: GroupImageDraftProcessor.fromContentUri(context.contentResolver, uri) }
        }
    }
    val photos = rememberLauncherForActivityResult(PickVisualMedia(), ::selected)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), ::selected)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        Box {
            FilledTonalButton(
                onClick = { if (currentEnabled) menu = true },
                enabled = enabled && !preparing,
                border = amoledOutlineBorder(enabled && !preparing),
                modifier = Modifier.testTag("onboarding.sign_up.photo"),
            ) {
                Text(stringResource(if (photo == null) R.string.add_photo else R.string.change_photo))
            }
            WhiteNoiseDropdownMenu(
                menu,
                onDismissRequest = { menu = false },
                items =
                    buildList {
                        add(
                            WhiteNoiseMenuItem(
                                stringResource(R.string.profile_choose_photos),
                                icon = R.drawable.ic_image,
                                onClick = {
                                    if (currentEnabled && active) {
                                        pickerGeneration = ++generation
                                        error =
                                            runCatching {
                                                photos.launch(
                                                    PickVisualMediaRequest(PickVisualMedia.ImageOnly),
                                                )
                                            }.isFailure
                                        if (error) pickerGeneration = null
                                    }
                                },
                            ),
                        )
                        add(
                            WhiteNoiseMenuItem(
                                stringResource(R.string.profile_choose_files),
                                icon = R.drawable.ic_description,
                                onClick = {
                                    if (currentEnabled && active) {
                                        pickerGeneration = ++generation
                                        error = runCatching { files.launch(arrayOf("image/*")) }.isFailure
                                        if (error) pickerGeneration = null
                                    }
                                },
                            ),
                        )
                        add(
                            WhiteNoiseMenuItem(
                                stringResource(R.string.find_web_image),
                                icon = R.drawable.ic_search,
                                onClick = {
                                    if (currentEnabled && active) webSession = ++generation
                                },
                            ),
                        )
                        if (photo != null) {
                            add(
                                WhiteNoiseMenuItem(
                                    stringResource(R.string.remove_photo),
                                    icon = R.drawable.ic_delete,
                                    destructive = true,
                                    onClick = {
                                        if (currentEnabled && active) {
                                            invalidate()
                                            currentPhoto(null)
                                            error = false
                                        }
                                    },
                                ),
                            )
                        }
                    },
            )
        }
        if (preparing) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.signup_preparing_photo))
            }
        }
        if (error) Text(stringResource(R.string.toast_couldnt_prepare_image), color = MaterialTheme.colorScheme.error)
    }
    val session = webSession
    if (session != null && enabled) {
        ImageSearchSheet(
            initialUrl = photo?.sourceUrl.orEmpty(),
            hasCurrentImage = photo != null,
            header = stringResource(R.string.profile_picture_sheet_title),
            title = "",
            seed = "signup-draft",
            urlLabel = stringResource(R.string.profile_picture_hint),
            applyInFlight = preparing,
            onApply = { url ->
                if (webSession == session && currentEnabled && active) {
                    if (url == null) {
                        invalidate()
                        currentPhoto(null)
                    } else {
                        prepare { GroupImageDraftProcessor.fromRemoteUrl(url) }
                    }
                }
            },
            onDismiss = { if (webSession == session && !preparing) webSession = null },
        )
    }
}
