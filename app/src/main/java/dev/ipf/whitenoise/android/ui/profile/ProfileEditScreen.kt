package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.Lud16Resolver
import dev.ipf.whitenoise.android.core.ProfileFieldValidation
import dev.ipf.whitenoise.android.core.ProfilePseudonymGenerator
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.media.GroupImageDraftProcessor
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.ProfilePublicWarning
import dev.ipf.whitenoise.android.ui.common.SectionCard
import dev.ipf.whitenoise.android.ui.common.StickyFormActionBar
import dev.ipf.whitenoise.android.ui.group.ImagePreviewPresentation
import dev.ipf.whitenoise.android.ui.group.ImageSearchSheet
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.ScrimAlpha
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal enum class ProfileImageTarget { Picture, Banner }

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileEditScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val active = appState.activeAccount
    var displayName by remember(active?.accountIdHex) { mutableStateOf("") }
    var about by remember(active?.accountIdHex) { mutableStateOf("") }
    var imageDrafts by remember(active?.accountIdHex) { mutableStateOf(ProfileImageDrafts()) }
    var profileLoaded by remember(active?.accountIdHex) { mutableStateOf(false) }
    val picture = imageDrafts.picture
    val banner = imageDrafts.banner
    var nip05 by remember(active?.accountIdHex) { mutableStateOf("") }
    var lud16 by remember(active?.accountIdHex) { mutableStateOf("") }
    // In-flight / failed LNURL-pay resolution of the lud16 field (#795). The
    // error is a string resource id so the inline message can distinguish
    // "doesn't resolve" from "no network"; it clears on every edit.
    var lud16Checking by remember { mutableStateOf(false) }
    var lud16ResolveError by remember(active?.accountIdHex) { mutableStateOf<Int?>(null) }
    val lud16FocusRequester = remember { FocusRequester() }
    var busy by remember { mutableStateOf(false) }
    var pictureUploading by remember(active?.accountIdHex) { mutableStateOf(false) }
    var pictureUploadJob by remember(active?.accountIdHex) { mutableStateOf<Job?>(null) }
    var bannerUploading by remember(active?.accountIdHex) { mutableStateOf(false) }
    var bannerUploadJob by remember(active?.accountIdHex) { mutableStateOf<Job?>(null) }
    // Drives the avatar bottom sheet (pick-from-photos / paste-link / remove).
    // The picture URL no longer lives as a standalone editor row; it's edited
    // exclusively through this control so the editor reads like an app screen,
    // not a developer surface. See #286.
    var showPictureSheet by remember { mutableStateOf(false) }
    var showBannerSheet by remember { mutableStateOf(false) }
    var fullPictureOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val safePictureUrl = ProfileSanitizer.imageUrl(picture)
    val safeBannerUrl = ProfileSanitizer.imageUrl(banner)
    val avatarImageAvailable = rememberAvatarImageAvailable(safePictureUrl)
    val pictureValid = ProfileFieldValidation.isAcceptablePictureUrl(picture)
    val bannerValid = ProfileFieldValidation.isAcceptablePictureUrl(banner)
    val nip05Valid = ProfileFieldValidation.isAcceptableNip05(nip05)
    val lud16Valid = ProfileFieldValidation.isAcceptableLud16(lud16)
    val saveEnabled =
        !busy &&
            !pictureUploading &&
            !bannerUploading &&
            active != null &&
            pictureValid &&
            bannerValid &&
            nip05Valid &&
            lud16Valid

    DisposableEffect(active?.accountIdHex) {
        onDispose {
            pictureUploadJob?.cancel()
            bannerUploadJob?.cancel()
        }
    }

    fun saveProfile() {
        if (!saveEnabled) return
        busy = true
        lud16ResolveError = null
        // Snapshot the field values now: the mutation outlives this composition,
        // so reading them inside the lambda would publish whatever is on screen
        // when it runs.
        val metadata =
            UserProfileMetadataFfi(
                name = displayName.trim().ifBlank { null },
                displayName = displayName.trim().ifBlank { null },
                about = about.trim().ifBlank { null },
                picture = picture.trim().ifBlank { null },
                banner = banner.trim().ifBlank { null },
                nip05 = nip05.trim().ifBlank { null },
                lud16 = lud16.trim().ifBlank { null },
            )
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
                        Lud16Resolver.resolve(address)
                    } finally {
                        lud16Checking = false
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
            appState.launchMutation {
                try {
                    appState.publishProfile(metadata)
                } finally {
                    busy = false
                }
            }
        }
    }

    @Suppress("LongMethod")
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
        if (alreadyUploading || busy) return
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
                        ProfileSanitizer.imageUrl(uploaded)
                            ?: throw IllegalStateException("profile image upload returned an unsafe URL")
                    val activeAccountRef = appState.activeAccountRef
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
                } catch (_: Exception) {
                    appState.present(
                        if (prepared) {
                            when (target) {
                                ProfileImageTarget.Picture -> R.string.toast_couldnt_upload_profile_image
                                ProfileImageTarget.Banner -> R.string.toast_couldnt_upload_profile_banner
                            }
                        } else {
                            R.string.toast_couldnt_prepare_image
                        },
                        copyable = true,
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

    LaunchedEffect(active?.accountIdHex) {
        profileLoaded = false
        try {
            val profile = active?.accountIdHex?.let { appState.loadUserProfile(it) }
            if (profile != null) {
                displayName = profile.displayName ?: profile.name ?: ""
                about = profile.about ?: ""
                imageDrafts =
                    ProfileImageDrafts(
                        picture = profile.picture ?: "",
                        banner = profile.banner ?: "",
                    )
                nip05 = profile.nip05 ?: ""
                lud16 = profile.lud16 ?: ""
            }
        } finally {
            profileLoaded = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        bottomBar = {
            if (active != null) {
                StickyFormActionBar {
                    Button(
                        onClick = { saveProfile() },
                        enabled = saveEnabled,
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
            }
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentPadding = PaddingValues(bottom = Dimens.spaceXl),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceXl),
        ) {
            item {
                // Live profile header — the avatar, name, and npub update as the
                // fields below are edited, so the user previews their card inline.
                if (active == null) {
                    Text(
                        stringResource(R.string.no_active_account_period),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(Dimens.spaceLg),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    ProfileHeroHeader(
                        title = displayName.ifBlank { stringResource(R.string.anonymous) },
                        seed = active.accountIdHex,
                        npub = appState.shortNpub(active.accountIdHex),
                        pictureUrl = safePictureUrl,
                        bannerUrl = safeBannerUrl,
                        bannerValid = bannerValid,
                        bannerUploading = bannerUploading,
                        contentReady = profileLoaded,
                        avatarImageAvailable = avatarImageAvailable,
                        pictureInvalid = picture.isNotBlank() && !ProfileFieldValidation.isAcceptablePictureUrl(picture),
                        onEditBanner = { showBannerSheet = true },
                        onOpenPicture = {
                            if (avatarImageAvailable) {
                                fullPictureOpen = true
                            } else {
                                showPictureSheet = true
                            }
                        },
                        onEditPicture = { showPictureSheet = true },
                        onCopyNpub = {
                            clipboard.setText(AnnotatedString(appState.npub(active.accountIdHex)))
                        },
                    )
                }
            }
            if (active != null) {
                item {
                    // Public-profile notice (#380): kind:0 metadata is broadcast
                    // unencrypted to relays, so warn before the editable fields
                    // that everything here is visible to the whole network. Copy
                    // mirrors Whitenoise Flutter (profileIsPublic /
                    // profilePublicDescription) for cross-client parity.
                    Box(Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg)) {
                        ProfilePublicWarning()
                    }
                }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg)) {
                    SectionCard(title = stringResource(R.string.profile)) {
                        // Borderless fields: drop the filled container so each input
                        // reads as a label + underline row on the white panel, leaving
                        // the indicator line to carry focus/error state.
                        val profileFieldColors =
                            TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                errorContainerColor = Color.Transparent,
                            )
                        TextField(
                            colors = profileFieldColors,
                            value = displayName,
                            onValueChange = { displayName = it },
                            label = { Text(stringResource(R.string.display_name)) },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        displayName = ProfilePseudonymGenerator.random(excluding = displayName)
                                    },
                                    enabled = !busy && active != null,
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = stringResource(R.string.regenerate_display_name),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextField(
                            colors = profileFieldColors,
                            value = about,
                            onValueChange = { about = it },
                            label = { Text(stringResource(R.string.about)) },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // Client-side validation: flag a malformed picture URL or
                        // nip-05 and block publish so we don't push junk — or an
                        // SSRF-prone avatar URL — to relays. The picture URL is now
                        // edited via the avatar control above (no inline row), but
                        // the same guard still gates publish in case a bad value was
                        // pasted there. See #69, #286.
                        TextField(
                            colors = profileFieldColors,
                            value = nip05,
                            onValueChange = { nip05 = it },
                            label = { Text(stringResource(R.string.nip_05)) },
                            singleLine = true,
                            isError = !nip05Valid,
                            supportingText = {
                                Text(
                                    stringResource(
                                        if (nip05Valid) R.string.profile_nip05_hint else R.string.profile_nip05_invalid,
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        )
                        TextField(
                            colors = profileFieldColors,
                            value = lud16,
                            onValueChange = {
                                lud16 = it
                                lud16ResolveError = null
                            },
                            label = { Text(stringResource(R.string.lightning)) },
                            singleLine = true,
                            isError = !lud16Valid || lud16ResolveError != null,
                            supportingText = {
                                val resolveError = lud16ResolveError
                                Text(
                                    stringResource(
                                        when {
                                            !lud16Valid -> R.string.profile_lightning_invalid
                                            lud16Checking -> R.string.profile_lightning_checking
                                            resolveError != null -> resolveError
                                            else -> R.string.profile_lightning_hint
                                        },
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth().focusRequester(lud16FocusRequester),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        )
                    }
                }
            }
        }
    }

    if (fullPictureOpen && safePictureUrl != null && avatarImageAvailable) {
        AvatarFullScreenViewer(
            title = displayName.ifBlank { active?.let { appState.shortNpub(it.accountIdHex) }.orEmpty() },
            seed = active?.accountIdHex.orEmpty(),
            pictureUrl = safePictureUrl,
            onDismiss = { fullPictureOpen = false },
            editActionLabel = stringResource(R.string.profile_picture_edit),
            onEditPicture = {
                fullPictureOpen = false
                showPictureSheet = true
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
