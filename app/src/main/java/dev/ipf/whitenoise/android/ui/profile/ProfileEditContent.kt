@file:Suppress("MatchingDeclarationName") // The screen owns its small presentation state declaration.

package dev.ipf.whitenoise.android.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseCallout
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDropdownMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.common.rememberRecoverableAvatar
import dev.ipf.whitenoise.android.ui.common.whiteNoiseVerticalScroll
import dev.ipf.whitenoise.android.ui.settings.SettingsBottomAction
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

/** Native text states stay with the account-scoped owner across read/edit presentation changes. */
@Stable
internal class ProfileEditFields(
    initial: ProfileEditDraft,
) {
    val name = TextFieldState(initial.displayName)
    val address = TextFieldState(initial.nip05)
    val lightning = TextFieldState(initial.lud16)
    val about = TextFieldState(initial.about)

    /** Applies an explicit load, cancellation or accepted-save baseline; typing never passes through this method. */
    fun restore(draft: ProfileEditDraft) {
        if (name.text.toString() != draft.displayName) name.setTextAndPlaceCursorAtEnd(draft.displayName)
        if (address.text.toString() != draft.nip05) address.setTextAndPlaceCursorAtEnd(draft.nip05)
        if (lightning.text.toString() != draft.lud16) lightning.setTextAndPlaceCursorAtEnd(draft.lud16)
        if (about.text.toString() != draft.about) about.setTextAndPlaceCursorAtEnd(draft.about)
    }
}

/** Profile form uses the prototype's read-first layout; application state owns publication, validation and images. */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")
@Composable
internal fun ProfileEditContent(
    fields: ProfileEditFields,
    seed: String,
    hasAccount: Boolean,
    editing: Boolean,
    ready: Boolean,
    busy: Boolean,
    pictureUrl: String?,
    bannerUrl: String?,
    pictureUploading: Boolean,
    bannerUploading: Boolean,
    picturePresent: Boolean,
    bannerPresent: Boolean,
    pictureValid: Boolean,
    bannerValid: Boolean,
    addressVerified: Boolean,
    addressValid: Boolean,
    lightningValid: Boolean,
    lightningChecking: Boolean,
    lightningError: Int?,
    saveEnabled: Boolean,
    saveFailed: Boolean,
    lightningFocusRequester: FocusRequester,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onSuggestName: () -> Unit,
    onOpenPicture: () -> Unit,
    onEditPicture: () -> Unit,
    onEditBanner: () -> Unit,
    onOpenBanner: () -> Unit,
    onPickImage: (ProfileImageTarget, Uri) -> Unit,
    onRemoveImage: (ProfileImageTarget) -> Unit,
) {
    SettingsScaffold(
        title = stringResource(R.string.profile),
        onBack = onBack,
        topBarActions = {
            if (!editing && hasAccount) {
                TextButton(onClick = onEdit, enabled = ready && !busy, modifier = Modifier.testTag("profile.edit")) {
                    Text(stringResource(R.string.edit))
                }
            }
        },
        bottomBar = {
            if (editing && hasAccount) {
                SettingsBottomAction(tonalElevation = 0.dp) {
                    WhiteNoiseButton(
                        onClick = onSave,
                        enabled = saveEnabled,
                        loading = busy,
                        loadingLabel =
                            stringResource(
                                if (lightningChecking) R.string.profile_lightning_checking else R.string.profile_saving,
                            ),
                        modifier = Modifier.fillMaxWidth().testTag("profile.save"),
                    ) { Text(stringResource(R.string.save)) }
                }
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .whiteNoiseVerticalScroll(rememberScrollState())
                .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin, vertical = WhiteNoiseSpacing.Section)
                .testTag("profile.form"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
        ) {
            if (!hasAccount) {
                Text(stringResource(R.string.no_active_account_period))
                return@Column
            }
            if (bannerUrl != null) {
                ProfileBanner(bannerUrl, onOpen = onOpenBanner, enabled = ready && !busy)
            }
            if (editing) {
                ProfileImageActions(
                    seed,
                    ProfileImageTarget.Banner,
                    bannerPresent,
                    !busy && !bannerUploading,
                    bannerUploading,
                    onPickImage,
                    onEditBanner,
                    onRemoveImage,
                )
                if (!bannerValid) {
                    Text(
                        stringResource(R.string.profile_banner_invalid),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Box(
                Modifier
                    .size(120.dp)
                    .then(
                        if (pictureUrl != null) {
                            Modifier.clickable(
                                role = Role.Button,
                                onClick = onOpenPicture,
                            )
                        } else {
                            Modifier
                        },
                    ).testTag("profile.avatar"),
            ) {
                if (ready) {
                    ProfileAvatar(fields.name.text.toString(), seed, pictureUrl)
                } else {
                    CircularProgressIndicator(Modifier.align(Alignment.Center).testTag(PROFILE_HERO_LOADING_TAG))
                }
            }
            if (editing) {
                ProfileImageActions(
                    seed,
                    ProfileImageTarget.Picture,
                    picturePresent,
                    !busy && !pictureUploading,
                    pictureUploading,
                    onPickImage,
                    onEditPicture,
                    onRemoveImage,
                )
                if (!pictureValid) {
                    Text(
                        stringResource(R.string.profile_picture_invalid),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                WhiteNoiseCallout(
                    title = stringResource(R.string.profile_is_public),
                    text = stringResource(R.string.profile_public_description),
                )
            }
            WhiteNoiseTextField(
                state = fields.name,
                modifier = Modifier.fillMaxWidth().testTag("profile.name_field"),
                enabled = !busy,
                readOnly = !editing,
                label = { Text(stringResource(R.string.name)) },
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next,
                    ),
            )
            if (editing) {
                TextButton(
                    onClick = onSuggestName,
                    enabled = !busy,
                    modifier = Modifier.testTag("profile.suggest_name"),
                ) {
                    Text(stringResource(R.string.profile_suggest_name))
                }
            }
            WhiteNoiseTextField(
                state = fields.address,
                modifier = Modifier.fillMaxWidth().testTag("profile.address_field"),
                enabled = !busy,
                readOnly = !editing,
                label = { Text(stringResource(R.string.nip_05)) },
                trailingIcon =
                    if (addressVerified) {
                        (
                            {
                                Icon(
                                    painterResource(R.drawable.ic_verified_filled),
                                    stringResource(R.string.profile_nip05_verified),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        )
                    } else {
                        null
                    },
                errorMessage = if (addressValid) null else stringResource(R.string.profile_nip05_invalid),
                supportingText = {
                    val hint = if (addressValid) R.string.profile_nip05_hint else R.string.profile_nip05_invalid
                    Text(stringResource(hint))
                },
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                        autoCorrectEnabled = false,
                    ),
            )
            val lightningMessage =
                when {
                    !lightningValid -> R.string.profile_lightning_invalid
                    lightningChecking -> R.string.profile_lightning_checking
                    lightningError != null -> lightningError
                    else -> R.string.profile_lightning_hint
                }
            WhiteNoiseTextField(
                state = fields.lightning,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(lightningFocusRequester)
                        .testTag("profile.lightning_field"),
                enabled = !busy,
                readOnly = !editing,
                label = { Text(stringResource(R.string.profile_lightning_address)) },
                errorMessage =
                    if (!lightningValid || lightningError != null) {
                        stringResource(lightningMessage)
                    } else {
                        null
                    },
                supportingText = { Text(stringResource(lightningMessage)) },
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                        autoCorrectEnabled = false,
                    ),
            )
            WhiteNoiseTextField(
                state = fields.about,
                modifier = Modifier.fillMaxWidth().testTag("profile.about_field"),
                enabled = !busy,
                readOnly = !editing,
                label = { Text(stringResource(R.string.about)) },
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 6),
            )
            if (saveFailed) {
                Text(
                    stringResource(R.string.profile_publish_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Prototype monogram/image geometry with production identity-scoped cache and reconnect recovery. */
@Suppress("FunctionNaming")
@Composable
internal fun ProfileAvatar(
    name: String,
    owner: String,
    url: String?,
) {
    val bitmap by key(owner, url) {
        rememberRecoverableAvatar(initialImage = AvatarImageLoader.peek(url), enabled = url != null) {
            AvatarImageLoader.load(checkNotNull(url))
        }
    }
    Box(
        Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                image,
                stringResource(R.string.profile_view_picture),
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(name.trim().firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

/** Banner uses the prototype's 2:1 image crop and large rounded shape with cached pixels on first composition. */
@Suppress("FunctionNaming")
@Composable
internal fun ProfileBanner(
    url: String,
    onOpen: () -> Unit,
    enabled: Boolean,
) {
    var bitmap by remember(url) { mutableStateOf(AvatarImageLoader.peek(url)) }
    var loaded by remember(url) { mutableStateOf(bitmap != null) }
    LaunchedEffect(url) {
        if (bitmap == null) bitmap = AvatarImageLoader.load(url)
        loaded = true
    }
    Surface(
        onClick = onOpen,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().aspectRatio(2f).testTag("profile.banner"),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val image = bitmap
            if (image != null) {
                Image(
                    image,
                    stringResource(R.string.profile_banner),
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else if (!loaded) {
                CircularProgressIndicator()
            } else {
                Text(stringResource(R.string.profile_image_failed))
            }
        }
    }
}

/** Device selections carry the account identity that opened them; stale activity results never start an upload. */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")
@Composable
internal fun ProfileImageActions(
    owner: String,
    target: ProfileImageTarget,
    hasImage: Boolean,
    enabled: Boolean,
    busy: Boolean,
    onPick: (ProfileImageTarget, Uri) -> Unit,
    onWeb: () -> Unit,
    onRemove: (ProfileImageTarget) -> Unit,
) {
    var expanded by remember(owner) { mutableStateOf(false) }
    var pickerOwner by remember(owner) { mutableStateOf<String?>(null) }
    var launchFailed by remember(owner) { mutableStateOf(false) }
    val photos =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val valid = pickerOwner == owner
            pickerOwner = null
            if (valid && uri != null) onPick(target, uri)
        }
    val files =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val valid = pickerOwner == owner
            pickerOwner = null
            if (valid && uri != null) onPick(target, uri)
        }
    val actionTag = if (target == ProfileImageTarget.Banner) "profile.banner_actions" else "profile.photo_actions"
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            FilledTonalButton(
                onClick = { expanded = true },
                enabled = enabled,
                border = amoledOutlineBorder(enabled),
                modifier = Modifier.testTag(actionTag),
            ) {
                Text(
                    stringResource(
                        when {
                            target == ProfileImageTarget.Banner && hasImage -> R.string.profile_change_banner
                            target == ProfileImageTarget.Banner -> R.string.profile_add_banner
                            hasImage -> R.string.change_photo
                            else -> R.string.add_photo
                        },
                    ),
                )
            }
            WhiteNoiseDropdownMenu(
                expanded,
                onDismissRequest = { expanded = false },
                items =
                    buildList {
                        add(
                            WhiteNoiseMenuItem(stringResource(R.string.profile_choose_photos), onClick = {
                                pickerOwner = owner
                                launchFailed =
                                    runCatching {
                                        photos.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                        )
                                    }.isFailure
                                if (launchFailed) pickerOwner = null
                            }, icon = R.drawable.ic_image),
                        )
                        add(
                            WhiteNoiseMenuItem(stringResource(R.string.profile_choose_files), onClick = {
                                pickerOwner = owner
                                launchFailed = runCatching { files.launch(arrayOf("image/*")) }.isFailure
                                if (launchFailed) pickerOwner = null
                            }, icon = R.drawable.ic_description),
                        )
                        add(
                            WhiteNoiseMenuItem(
                                stringResource(R.string.find_web_image),
                                onClick = onWeb,
                                icon = R.drawable.ic_search,
                            ),
                        )
                        if (hasImage) {
                            add(
                                WhiteNoiseMenuItem(
                                    stringResource(
                                        if (target ==
                                            ProfileImageTarget.Banner
                                        ) {
                                            R.string.profile_remove_banner
                                        } else {
                                            R.string.remove_photo
                                        },
                                    ),
                                    onClick = { onRemove(target) },
                                    icon = R.drawable.ic_delete,
                                    destructive = true,
                                ),
                            )
                        }
                    },
            )
        }
        if (busy) Text(stringResource(R.string.media_uploading))
        if (launchFailed) {
            Text(
                stringResource(R.string.toast_couldnt_prepare_image),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
