package dev.ipf.whitenoise.android.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.Lud16Resolver
import dev.ipf.whitenoise.android.core.Nip05Resolver
import dev.ipf.whitenoise.android.core.ProfileFieldValidation
import dev.ipf.whitenoise.android.core.ProfilePseudonymGenerator
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.media.GroupImageDraftProcessor
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.group.ImagePreviewPresentation
import dev.ipf.whitenoise.android.ui.group.ImageSearchSheet
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.ScrimAlpha
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal enum class ProfileImageTarget { Picture, Banner }

internal fun profileImageFailureOperation(
    target: ProfileImageTarget,
    prepared: Boolean,
): String =
    when (target) {
        ProfileImageTarget.Picture -> if (prepared) "PROFILE_IMAGE_UPLOAD" else "PROFILE_IMAGE_PREPARE"
        ProfileImageTarget.Banner -> if (prepared) "PROFILE_BANNER_UPLOAD" else "PROFILE_BANNER_PREPARE"
    }

internal data class ProfileImageDrafts(
    val picture: String = "",
    val banner: String = "",
) {
    fun without(target: ProfileImageTarget): ProfileImageDrafts =
        when (target) {
            ProfileImageTarget.Picture -> copy(picture = "")
            ProfileImageTarget.Banner -> copy(banner = "")
        }

    fun withUploadedImage(
        target: ProfileImageTarget,
        uploadedUrl: String,
        capturedAccountRef: String,
        activeAccountRef: String?,
    ): ProfileImageDrafts {
        if (capturedAccountRef != activeAccountRef) return this
        return when (target) {
            ProfileImageTarget.Picture -> copy(picture = uploadedUrl)
            ProfileImageTarget.Banner -> copy(banner = uploadedUrl)
        }
    }
}

internal const val PROFILE_BANNER_CONTROL_TAG = "profile_banner_control"
internal const val PROFILE_HERO_LOADING_TAG = "profile_hero_loading"
internal const val PROFILE_HEADER_AVATAR_TAG = "profile_header_avatar"
internal const val PROFILE_HEADER_NAME_TAG = "profile_header_name"
private const val PROFILE_BANNER_ASPECT_RATIO = 2f

@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun ProfileBannerControl(
    bannerUrl: String?,
    isValid: Boolean,
    isUploading: Boolean,
    isProfileLoaded: Boolean = true,
    showValidationError: Boolean = true,
    imageLoader: suspend (String) -> ImageBitmap? = { AvatarImageLoader.load(it) },
    onClick: () -> Unit,
) {
    var bannerImage by remember(bannerUrl) { mutableStateOf(AvatarImageLoader.peek(bannerUrl)) }
    LaunchedEffect(bannerUrl) {
        if (bannerImage == null && bannerUrl != null) bannerImage = imageLoader(bannerUrl)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(PROFILE_BANNER_ASPECT_RATIO)
                    .clip(RectangleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(
                        enabled = !isUploading && isProfileLoaded,
                        onClickLabel = stringResource(R.string.profile_banner_edit),
                        role = Role.Button,
                        onClick = onClick,
                    ).testTag(PROFILE_BANNER_CONTROL_TAG),
            contentAlignment = Alignment.Center,
        ) {
            val image = bannerImage
            val imageAlpha by
                animateFloatAsState(
                    targetValue = if (image != null) 1f else 0f,
                    animationSpec = tween(durationMillis = 220),
                    label = "profile banner image",
                )
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(imageAlpha),
                )
            } else if (!isProfileLoaded || bannerUrl != null) {
                Spacer(Modifier.fillMaxSize())
            } else if (!isUploading) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
                ) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(stringResource(R.string.profile_banner_placeholder))
                }
            }
            if (isUploading) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = ScrimAlpha.HEAVY)),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                        Text(
                            stringResource(R.string.profile_banner_uploading),
                            color = Color.White,
                        )
                    }
                }
            } else {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(Dimens.spaceMd)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PhotoCamera,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        if (!isValid && showValidationError) {
            Text(
                stringResource(R.string.profile_banner_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun ProfileHeroHeader(
    title: String,
    seed: String,
    npub: String,
    pictureUrl: String?,
    bannerUrl: String?,
    bannerValid: Boolean,
    bannerUploading: Boolean,
    contentReady: Boolean = true,
    avatarImageAvailable: Boolean,
    pictureInvalid: Boolean,
    onEditBanner: () -> Unit,
    onOpenPicture: () -> Unit,
    onEditPicture: () -> Unit,
    onCopyNpub: () -> Unit,
) {
    var avatarImage by remember(pictureUrl) { mutableStateOf(AvatarImageLoader.peek(pictureUrl)) }
    var avatarLoadFinished by
        remember(pictureUrl) {
            mutableStateOf(pictureUrl == null || avatarImage != null)
        }
    LaunchedEffect(pictureUrl, contentReady) {
        if (contentReady && avatarImage == null && pictureUrl != null) {
            avatarImage = AvatarImageLoader.load(pictureUrl)
            avatarLoadFinished = true
        }
    }
    val avatarImageAlpha by
        animateFloatAsState(
            targetValue = if (avatarImage != null) 1f else 0f,
            animationSpec = tween(durationMillis = 220),
            label = "profile avatar image",
        )
    val contentAlpha by
        animateFloatAsState(
            targetValue = if (contentReady) 1f else 0f,
            animationSpec = tween(durationMillis = 180),
            label = "profile hero reveal",
        )
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().alpha(contentAlpha),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                ProfileBannerControl(
                    bannerUrl = bannerUrl,
                    isValid = bannerValid,
                    isUploading = bannerUploading,
                    isProfileLoaded = contentReady,
                    showValidationError = false,
                    onClick = onEditBanner,
                )
                val editPictureLabel = stringResource(R.string.profile_picture_edit)
                Box(
                    modifier =
                        Modifier
                            .offset(y = 58.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 4.dp,
                        modifier =
                            Modifier
                                .clip(CircleShape)
                                .testTag(PROFILE_HEADER_AVATAR_TAG)
                                .clickable(
                                    enabled = contentReady,
                                    onClickLabel =
                                        stringResource(
                                            if (avatarImageAvailable) {
                                                R.string.profile_view_picture
                                            } else {
                                                R.string.profile_picture_edit
                                            },
                                        ),
                                    role = Role.Button,
                                    onClick = onOpenPicture,
                                ),
                    ) {
                        Box(Modifier.padding(4.dp)) {
                            Box {
                                Avatar(
                                    title = if (avatarLoadFinished && avatarImage == null) title else "",
                                    seed = seed,
                                    size = 108.dp,
                                )
                                val image = avatarImage
                                if (image != null) {
                                    Image(
                                        bitmap = image,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier =
                                            Modifier
                                                .size(108.dp)
                                                .clip(CircleShape)
                                                .alpha(avatarImageAlpha),
                                    )
                                }
                            }
                        }
                    }
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 4.dp, y = 4.dp)
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.Transparent)
                                .clickable(
                                    enabled = contentReady,
                                    onClickLabel = editPictureLabel,
                                    role = Role.Button,
                                    onClick = onEditPicture,
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.PhotoCamera,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.size(66.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(PROFILE_HEADER_NAME_TAG),
            )
            Text(
                npub,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .minimumInteractiveComponentSize()
                        .clickable(
                            enabled = contentReady,
                            onClickLabel = stringResource(R.string.copy),
                            role = Role.Button,
                            onClick = onCopyNpub,
                        ).padding(horizontal = Dimens.spaceLg),
                maxLines = 1,
            )
            if (pictureInvalid) {
                Text(
                    stringResource(R.string.profile_picture_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Dimens.spaceLg),
                )
            }
            if (!bannerValid) {
                Text(
                    stringResource(R.string.profile_banner_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Dimens.spaceLg),
                )
            }
        }
        if (!contentReady) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(PROFILE_BANNER_ASPECT_RATIO)
                        .testTag(PROFILE_HERO_LOADING_TAG),
            ) {}
        }
    }
}

@Suppress("FunctionNaming")
@Composable
internal fun ProfileSaveButton(
    enabled: Boolean,
    busy: Boolean,
    onSave: () -> Unit,
) {
    Button(
        onClick = onSave,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        // Profile edit's primary action is conceptually "save" to the
        // user; the relay-publish mechanics are an implementation detail
        // that only the failure surface needs to name (#834).
        Text(stringResource(R.string.save))
    }
}

/** Owns the account-scoped draft and existing load, image-upload and publication transactions. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")
@Composable
internal fun ProfileEditScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    cachedProfile: (String) -> UserProfileMetadataFfi? = appState::userProfileCached,
    loadProfile: suspend (String) -> UserProfileMetadataFfi? = appState::loadUserProfile,
    publishProfile: suspend (UserProfileMetadataFfi) -> Boolean = appState::publishProfile,
    resolveAddress: suspend (String) -> String? = { Nip05Resolver.resolve(it) },
    resolveLightning: suspend (String) -> Boolean = { Lud16Resolver.resolve(it) },
) {
    val active = appState.activeAccount
    val activeAccountId = active?.accountIdHex
    val cachedDraft =
        remember(appState, activeAccountId) {
            activeAccountId?.let(cachedProfile)?.let(::profileEditDraft)
        }
    val initialDraft = cachedDraft ?: ProfileEditDraft()
    val saveState =
        remember(appState, activeAccountId) {
            ProfileEditSaveState().apply {
                beginLoad(activeAccountId)
                if (activeAccountId != null && cachedDraft != null) {
                    completeLoad(activeAccountId, cachedDraft.metadata())
                }
            }
        }
    val fields = remember(appState, activeAccountId) { ProfileEditFields(initialDraft) }
    val displayName = fields.name.text.toString()
    val about = fields.about.text.toString()
    var baselineDraft by remember(appState, activeAccountId) { mutableStateOf(initialDraft) }
    var isEditing by remember(appState, activeAccountId) { mutableStateOf(false) }
    var editRevision by remember(appState, activeAccountId) { mutableIntStateOf(0) }
    var acceptedSaveRevision by remember(appState, activeAccountId) { mutableIntStateOf(0) }
    var imageDrafts by
        remember(appState, activeAccountId) {
            mutableStateOf(ProfileImageDrafts(picture = initialDraft.picture, banner = initialDraft.banner))
        }
    val picture = imageDrafts.picture
    val banner = imageDrafts.banner
    val nip05 = fields.address.text.toString()
    val lud16 = fields.lightning.text.toString()
    // In-flight / failed LNURL-pay resolution of the lud16 field (#795). The
    // error is a string resource id so the inline message can distinguish
    // "doesn't resolve" from "no network"; it clears on every edit.
    var lud16Checking by remember(activeAccountId) { mutableStateOf(false) }
    var lud16ResolveError by remember(activeAccountId) { mutableStateOf<Int?>(null) }
    val lud16FocusRequester = remember { FocusRequester() }
    var busy by remember(activeAccountId) { mutableStateOf(false) }
    var saveFailed by remember(activeAccountId) { mutableStateOf(false) }
    var pictureUploading by remember(activeAccountId) { mutableStateOf(false) }
    var pictureUploadJob by remember(activeAccountId) { mutableStateOf<Job?>(null) }
    var bannerUploading by remember(activeAccountId) { mutableStateOf(false) }
    var bannerUploadJob by remember(activeAccountId) { mutableStateOf<Job?>(null) }
    var profileContentReady by remember(appState, activeAccountId) { mutableStateOf(cachedDraft != null) }
    // Drives the avatar bottom sheet (pick-from-photos / paste-link / remove).
    // The picture URL no longer lives as a standalone editor row; it's edited
    // exclusively through this control so the editor reads like an app screen,
    // not a developer surface. See #286.
    var showPictureSheet by remember(activeAccountId) { mutableStateOf(false) }
    var showBannerSheet by remember(activeAccountId) { mutableStateOf(false) }
    var fullPictureOpen by remember(activeAccountId) { mutableStateOf(false) }
    var fullBannerOpen by remember(activeAccountId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val safePictureUrl = ProfileSanitizer.protocolImageUrl(picture)
    val safeBannerUrl = ProfileSanitizer.protocolImageUrl(banner)
    val avatarImageAvailable = rememberAvatarImageAvailable(safePictureUrl)
    val pictureValid = ProfileFieldValidation.isAcceptablePictureUrl(picture)
    val bannerValid = ProfileFieldValidation.isAcceptablePictureUrl(banner)
    val nip05Valid = ProfileFieldValidation.isAcceptableNip05(nip05)
    val lud16Valid = ProfileFieldValidation.isAcceptableLud16(lud16)
    var resolvedAddressHex by remember(activeAccountId, baselineDraft.nip05) { mutableStateOf<String?>(null) }
    LaunchedEffect(activeAccountId, baselineDraft.nip05) {
        resolvedAddressHex = baselineDraft.nip05.takeIf { it.isNotBlank() }?.let { resolveAddress(it) }
    }
    LaunchedEffect(lud16) { lud16ResolveError = null }
    val addressVerified =
        nip05 == baselineDraft.nip05 &&
            resolvedAddressHex != null &&
            resolvedAddressHex.equals(
                activeAccountId,
                ignoreCase = true,
            )
    val currentMetadata = profileEditMetadata(displayName, about, picture, banner, nip05, lud16)
    val saveEnabled =
        isEditing &&
            !busy &&
            !pictureUploading &&
            !bannerUploading &&
            active != null &&
            pictureValid &&
            bannerValid &&
            nip05Valid &&
            lud16Valid &&
            saveState.canSave(activeAccountId, currentMetadata)

    DisposableEffect(active?.accountIdHex) {
        onDispose {
            editRevision++
            pictureUploadJob?.cancel()
            bannerUploadJob?.cancel()
        }
    }

    fun resetDraft() {
        saveFailed = false
        fields.restore(baselineDraft)
        imageDrafts = ProfileImageDrafts(baselineDraft.picture, baselineDraft.banner)
        lud16ResolveError = null
    }

    fun beginEditing() {
        if (busy || activeAccountId == null || !profileContentReady) return
        editRevision++
        resetDraft()
        isEditing = true
    }

    fun handleBack() {
        if (!isEditing) {
            onBack()
            return
        }
        editRevision++
        pictureUploadJob?.cancel()
        bannerUploadJob?.cancel()
        showPictureSheet = false
        showBannerSheet = false
        resetDraft()
        isEditing = false
    }

    val imageOverlayOpen = fullPictureOpen || fullBannerOpen || showPictureSheet || showBannerSheet
    BackHandler(enabled = isEditing && !imageOverlayOpen) { handleBack() }

    fun saveProfile() {
        if (!saveEnabled) return
        val accountId = activeAccountId ?: return
        busy = true
        saveFailed = false
        lud16ResolveError = null
        // Snapshot the field values now: the mutation outlives this composition,
        // so reading them inside the lambda would publish whatever is on screen
        // when it runs.
        val metadata = currentMetadata
        val submittedRevision = editRevision
        scope.launch {
            // A non-blank Lightning address must resolve to a live LNURL-pay
            // endpoint before it is published (#795); a blank field means "no
            // address" and skips the check. On failure nothing is saved — the
            // error surfaces inline and focus returns to the field.
            val address = metadata.lud16
            if (address != null) {
                lud16Checking = true
                val resolves =
                    try {
                        resolveLightning(address)
                    } finally {
                        lud16Checking = false
                    }
                if (submittedRevision != editRevision || !isEditing) {
                    busy = false
                    return@launch
                }
                if (!resolves) {
                    lud16ResolveError =
                        if (appState.hasActiveNetwork()) {
                            R.string.profile_lightning_unresolved
                        } else {
                            R.string.profile_lightning_no_network
                        }
                    busy = false
                    runCatching { lud16FocusRequester.requestFocus() }
                    return@launch
                }
            }
            if (submittedRevision != editRevision || !isEditing) {
                busy = false
                return@launch
            }
            appState.launchMutation {
                try {
                    if (appState.activeAccount?.accountIdHex != accountId) return@launchMutation
                    val succeeded = publishProfile(metadata)
                    if (!succeeded && submittedRevision == editRevision) saveFailed = true
                    if (saveState.completeSave(accountId, metadata, succeeded)) {
                        acceptedSaveRevision++
                        baselineDraft = profileEditDraft(metadata)
                        if (submittedRevision == editRevision) isEditing = false
                        if (!isEditing) resetDraft()
                    }
                } finally {
                    busy = false
                }
            }
        }
    }

    @Suppress("LongMethod", "TooGenericExceptionCaught") // Preparation and upload have different failure types.
    fun uploadProfileDraft(
        target: ProfileImageTarget,
        prepare: suspend () -> dev.ipf.whitenoise.android.media.ImageUploadDraft,
    ) {
        val accountRef = appState.activeAccountRef ?: return
        val alreadyUploading =
            when (target) {
                ProfileImageTarget.Picture -> pictureUploading
                ProfileImageTarget.Banner -> bannerUploading
            }
        if (alreadyUploading || busy || !isEditing) return
        val capturedRevision = editRevision
        when (target) {
            ProfileImageTarget.Picture -> pictureUploading = true
            ProfileImageTarget.Banner -> bannerUploading = true
        }
        val uploadJob =
            scope.launch {
                var prepared = false
                try {
                    val draft = prepare()
                    prepared = true
                    val uploaded =
                        appState.marmotIo {
                            uploadProfileImage(
                                accountRef,
                                draft.plaintext,
                                draft.mediaType,
                                null,
                            )
                        }
                    val safeUploaded =
                        ProfileSanitizer.androidOwnedHttpsImageUrl(uploaded)
                            ?: throw IllegalStateException("profile image upload returned an unsafe URL")
                    val activeAccountRef = appState.activeAccountRef
                    if (capturedRevision != editRevision || !isEditing) return@launch
                    imageDrafts =
                        imageDrafts.withUploadedImage(
                            target = target,
                            uploadedUrl = safeUploaded,
                            capturedAccountRef = accountRef,
                            activeAccountRef = activeAccountRef,
                        )
                    if (activeAccountRef != accountRef) return@launch
                    when (target) {
                        ProfileImageTarget.Picture -> showPictureSheet = false
                        ProfileImageTarget.Banner -> showBannerSheet = false
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    appState.presentFailure(
                        titleRes =
                            if (prepared) {
                                when (target) {
                                    ProfileImageTarget.Picture -> R.string.toast_couldnt_upload_profile_image
                                    ProfileImageTarget.Banner -> R.string.toast_couldnt_upload_profile_banner
                                }
                            } else {
                                R.string.toast_couldnt_prepare_image
                            },
                        operationCode = profileImageFailureOperation(target, prepared),
                        throwable = error,
                    )
                } finally {
                    when (target) {
                        ProfileImageTarget.Picture -> {
                            pictureUploading = false
                            pictureUploadJob = null
                        }

                        ProfileImageTarget.Banner -> {
                            bannerUploading = false
                            bannerUploadJob = null
                        }
                    }
                }
            }
        when (target) {
            ProfileImageTarget.Picture -> pictureUploadJob = uploadJob
            ProfileImageTarget.Banner -> bannerUploadJob = uploadJob
        }
    }

    @Suppress("TooGenericExceptionCaught") // The injected or FFI profile reader can throw any non-cancellation failure.
    LaunchedEffect(activeAccountId) {
        val accountId = activeAccountId ?: return@LaunchedEffect
        val loadStartedWith = ProfileEditDraft(displayName, about, picture, banner, nip05, lud16)
        val loadSaveRevision = acceptedSaveRevision
        try {
            val profile = loadProfile(accountId)
            if (appState.activeAccount?.accountIdHex != accountId) return@LaunchedEffect
            // A refresh started before an accepted publication cannot replace its new read/edit baseline.
            if (loadSaveRevision != acceptedSaveRevision) return@LaunchedEffect
            if (profile == null && cachedDraft != null) return@LaunchedEffect

            val refreshed = profileEditDraft(profile)
            val current =
                ProfileEditDraft(
                    fields.name.text.toString(),
                    fields.about.text.toString(),
                    imageDrafts.picture,
                    imageDrafts.banner,
                    fields.address.text.toString(),
                    fields.lightning.text.toString(),
                )
            val merged = current.mergeUntouchedFields(loadStartedWith, refreshed)
            if (!saveState.completeLoad(accountId, refreshed.metadata())) return@LaunchedEffect
            baselineDraft = refreshed
            fields.restore(merged)
            imageDrafts = ProfileImageDrafts(picture = merged.picture, banner = merged.banner)
            profileContentReady = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (appState.activeAccount?.accountIdHex == accountId) {
                profileContentReady = true
                appState.presentFailure(
                    R.string.toast_couldnt_load_profile,
                    "PROFILE_EDIT_LOAD",
                    error,
                )
            }
        }
    }

    ProfileEditContent(
        fields = fields,
        seed = activeAccountId.orEmpty(),
        hasAccount = active != null,
        editing = isEditing,
        ready = profileContentReady,
        busy = busy,
        pictureUrl = safePictureUrl,
        bannerUrl = safeBannerUrl,
        pictureUploading = pictureUploading,
        bannerUploading = bannerUploading,
        picturePresent = picture.isNotBlank(),
        bannerPresent = banner.isNotBlank(),
        pictureValid = pictureValid,
        bannerValid = bannerValid,
        addressVerified = addressVerified,
        addressValid = nip05Valid,
        lightningValid = lud16Valid,
        lightningChecking = lud16Checking,
        lightningError = lud16ResolveError,
        saveEnabled = saveEnabled,
        saveFailed = saveFailed,
        lightningFocusRequester = lud16FocusRequester,
        onBack = ::handleBack,
        onEdit = ::beginEditing,
        onSave = ::saveProfile,
        onSuggestName = {
            fields.name.setTextAndPlaceCursorAtEnd(ProfilePseudonymGenerator.random(excluding = displayName))
        },
        onOpenPicture = { if (avatarImageAvailable) fullPictureOpen = true },
        onEditPicture = { showPictureSheet = true },
        onEditBanner = { showBannerSheet = true },
        onOpenBanner = { fullBannerOpen = true },
        onPickImage = { target, uri ->
            uploadProfileDraft(target) { GroupImageDraftProcessor.fromContentUri(context.contentResolver, uri) }
        },
        onRemoveImage = { target -> imageDrafts = imageDrafts.without(target) },
    )

    if (fullPictureOpen && safePictureUrl != null && avatarImageAvailable) {
        AvatarFullScreenViewer(
            title = displayName.ifBlank { active?.let { appState.shortNpub(it.accountIdHex) }.orEmpty() },
            seed = active?.accountIdHex.orEmpty(),
            pictureUrl = safePictureUrl,
            onDismiss = { fullPictureOpen = false },
            editActionLabel = stringResource(R.string.profile_picture_edit),
            onEditPicture =
                if (busy) {
                    null
                } else {
                    (
                        {
                            fullPictureOpen = false
                            if (!isEditing) beginEditing()
                            showPictureSheet = true
                        }
                    )
                },
        )
    }

    if (fullBannerOpen && safeBannerUrl != null) {
        AvatarFullScreenViewer(
            title = stringResource(R.string.profile_banner),
            seed = activeAccountId.orEmpty(),
            pictureUrl = safeBannerUrl,
            onDismiss = { fullBannerOpen = false },
            editActionLabel = stringResource(R.string.profile_banner_edit),
            onEditPicture =
                if (busy) {
                    null
                } else {
                    (
                        {
                            fullBannerOpen = false
                            if (!isEditing) beginEditing()
                            showBannerSheet = true
                        }
                    )
                },
        )
    }

    if (showPictureSheet) {
        ImageSearchSheet(
            initialUrl = picture,
            header = stringResource(R.string.profile_picture_sheet_title),
            title = displayName.ifBlank { active?.let { appState.shortNpub(it.accountIdHex) }.orEmpty() },
            seed = active?.accountIdHex.orEmpty(),
            urlLabel = stringResource(R.string.profile_picture_hint),
            // The profile editor stages edits locally and persists on Publish,
            // so the returned Blossom URL remains staged until Save.
            applyInFlight = pictureUploading,
            onApply = { picked ->
                if (picked == null) {
                    imageDrafts = imageDrafts.without(ProfileImageTarget.Picture)
                    showPictureSheet = false
                } else {
                    uploadProfileDraft(target = ProfileImageTarget.Picture) {
                        GroupImageDraftProcessor.fromRemoteUrl(picked)
                    }
                }
            },
            onPickPhoto = { uri ->
                uploadProfileDraft(target = ProfileImageTarget.Picture) {
                    GroupImageDraftProcessor.fromContentUri(context.contentResolver, uri)
                }
            },
            onDismiss = { if (!pictureUploading) showPictureSheet = false },
        )
    }

    if (showBannerSheet) {
        ImageSearchSheet(
            initialUrl = banner,
            hasCurrentImage = banner.isNotBlank(),
            header = stringResource(R.string.profile_banner_sheet_title),
            title = displayName.ifBlank { active?.let { appState.shortNpub(it.accountIdHex) }.orEmpty() },
            seed = active?.accountIdHex.orEmpty(),
            urlLabel = stringResource(R.string.profile_banner_url_label),
            applyInFlight = bannerUploading,
            onApply = { picked ->
                if (picked == null) {
                    imageDrafts = imageDrafts.without(ProfileImageTarget.Banner)
                    showBannerSheet = false
                } else {
                    uploadProfileDraft(target = ProfileImageTarget.Banner) {
                        GroupImageDraftProcessor.fromRemoteUrl(picked)
                    }
                }
            },
            onPickPhoto = { uri ->
                uploadProfileDraft(target = ProfileImageTarget.Banner) {
                    GroupImageDraftProcessor.fromContentUri(context.contentResolver, uri)
                }
            },
            onDismiss = { if (!bannerUploading) showBannerSheet = false },
            previewPresentation = ImagePreviewPresentation.Banner,
            choosePhotoLabel = stringResource(R.string.profile_banner_choose_photo),
            removeImageLabel = stringResource(R.string.profile_banner_remove),
            applyImageLabel = stringResource(R.string.profile_banner_apply),
        )
    }
}
