package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.ProfileLink
import dev.ipf.whitenoise.android.share.QrShareCardRenderer
import dev.ipf.whitenoise.android.share.QrShareCardSpec
import dev.ipf.whitenoise.android.share.launchOutboundShare
import dev.ipf.whitenoise.android.share.outboundShareIntent
import dev.ipf.whitenoise.android.share.presentOutboundShareFailure
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.AdaptiveContent
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.LocalWhiteNoiseHeaderScroll
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDropdownMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseScaffold
import dev.ipf.whitenoise.android.ui.common.whiteNoiseVerticalScroll
import dev.ipf.whitenoise.android.ui.profile.ProfileQrGallerySave
import dev.ipf.whitenoise.android.ui.qr.QrScanOutcome
import dev.ipf.whitenoise.android.ui.qr.QrScanResult
import dev.ipf.whitenoise.android.ui.qr.QrScanUseCase
import dev.ipf.whitenoise.android.ui.qr.QrScannerSheet
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Sizes fixed by the prototype's Share & Connect layout. */
private object ShareConnectDefaults {
    const val AVATAR_WIDTH_FRACTION = 0.32f
    val AvatarMinSize = 104.dp
    val AvatarMaxSize = 152.dp
    val ErrorMaxWidth = 440.dp
    val IconSize = 24.dp
    const val COPIED_RESET_MILLIS = 2_000L
}

/**
 * Share & Connect for the active profile: identity, copyable public key, QR code and the scanner that opens a scanned
 * profile through the production profile presentation.
 */
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod") // One account-owned route and scanner receipt.
@Composable
internal fun ShareConnectScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    isCurrent: () -> Boolean = { true },
    onScannedProfile: ((QrScanOutcome) -> Unit)? = null,
    scannerContent: @Composable (() -> Unit, (String) -> Unit) -> Unit = { dismiss, scan ->
        QrScannerSheet(onDismiss = dismiss, onScan = scan)
    },
) {
    val account = appState.activeAccount ?: return
    val accountIdHex = account.accountIdHex
    val runtime = appState.runtimeGeneration
    var routeActive by remember(accountIdHex, runtime) { mutableStateOf(true) }
    DisposableEffect(accountIdHex, runtime) { onDispose { routeActive = false } }

    /** True while the screen still shows the active account and no teardown runs. */
    fun ownsScreen(): Boolean {
        val sameOwner = appState.activeAccountRef == account.label && appState.runtimeGeneration == runtime
        val blocked = appState.signOutInProgress || appState.wipeInProgress
        return routeActive && sameOwner && !blocked && isCurrent()
    }
    val npub = appState.npubForDisplay(accountIdHex)
    val profile =
        ShareConnectProfile(
            name = appState.displayName(accountIdHex),
            npub = npub,
            seed = accountIdHex,
            pictureUrl = appState.avatarUrl(accountIdHex),
            nostrAddress = appState.userProfileCached(accountIdHex)?.nip05.orEmpty(),
        )
    val link = remember(npub) { ProfileLink.parse(npub) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val shareProfileTitle = stringResource(R.string.share_profile)
    var copied by rememberSaveable(accountIdHex) { mutableStateOf(false) }
    var scannerSession by remember(accountIdHex, runtime) { mutableStateOf<Long?>(null) }
    var nextScannerSession by remember(accountIdHex, runtime) { mutableStateOf(0L) }
    var scanInvalid by rememberSaveable(accountIdHex) { mutableStateOf(false) }
    var pictureShareInProgress by remember(accountIdHex, runtime) { mutableStateOf(false) }
    val gallerySave = remember(accountIdHex, runtime) { ProfileQrGallerySave() }
    val saveScope = rememberCoroutineScope()
    val profileCardHeadline = stringResource(R.string.profile_share_card_headline)

    LaunchedEffect(copied) {
        if (copied) {
            delay(ShareConnectDefaults.COPIED_RESET_MILLIS)
            copied = false
        }
    }

    ShareConnectContent(
        profile = profile,
        qrContent = link?.qrUri ?: npub,
        copied = copied,
        scanInvalid = scanInvalid,
        pictureShareInProgress = pictureShareInProgress,
        gallerySaveInProgress = gallerySave.inProgress,
        canSaveToGallery = link != null,
        onSaveToGallery = save@{
            val exportInProgress = pictureShareInProgress || gallerySave.inProgress
            if (!ownsScreen() || exportInProgress || link == null) return@save
            val spec =
                QrShareCardSpec(
                    headline = profileCardHeadline,
                    qrPayload = link.qrUri,
                    displayName = profile.name,
                    avatar = AvatarImageLoader.peekBitmap(profile.pictureUrl),
                )
            saveScope.launch(start = CoroutineStart.UNDISPATCHED) {
                runCatchingCancellable { gallerySave.save(context, spec, ::ownsScreen) }
                    .onSuccess { saved ->
                        if (ownsScreen() && saved != null) {
                            if (saved) {
                                appState.presentTransient(R.string.media_saved)
                            } else {
                                appState.present(R.string.media_save_failed)
                            }
                        }
                    }.onFailure { if (ownsScreen()) appState.present(R.string.media_save_failed) }
            }
        },
        onBack = {
            if (ownsScreen()) {
                routeActive = false
                onBack()
            }
        },
        onShareUrl = share@{
            if (!ownsScreen()) return@share
            launchOutboundShare(
                context,
                outboundShareIntent(link?.uri ?: npub, emptyList()),
                shareProfileTitle,
            ).onFailure { appState.presentOutboundShareFailure("PROFILE_URL_SHARE", it) }
        },
        onSharePicture = picture@{
            val exportInProgress = pictureShareInProgress || gallerySave.inProgress
            if (!ownsScreen() || exportInProgress || link == null) return@picture
            pictureShareInProgress = true
            appState.launchMutation {
                runCatchingCancellable {
                    val staged =
                        QrShareCardRenderer.stage(
                            context,
                            QrShareCardSpec(
                                headline = profileCardHeadline,
                                qrPayload = link.qrUri,
                                displayName = profile.name,
                                avatar = AvatarImageLoader.peekBitmap(profile.pictureUrl),
                            ),
                        )
                    if (ownsScreen()) {
                        launchOutboundShare(
                            context,
                            outboundShareIntent(link.uri, listOf(staged.stream)),
                            shareProfileTitle,
                        ).getOrThrow()
                    }
                }.onFailure {
                    if (ownsScreen()) appState.presentOutboundShareFailure("PROFILE_PICTURE_SHARE", it)
                }
                if (ownsScreen()) pictureShareInProgress = false
            }
        },
        onCopy = copy@{
            if (!ownsScreen()) return@copy
            clipboard.setText(AnnotatedString(npub))
            copied = true
        },
        onOpenScanner = {
            if (ownsScreen() && scannerSession == null) {
                scanInvalid = false
                nextScannerSession++
                scannerSession = nextScannerSession
            }
        },
    )

    val scanSession = scannerSession
    if (scanSession != null && ownsScreen()) {
        scannerContent(
            { if (ownsScreen() && scannerSession == scanSession) scannerSession = null },
            scan@{ raw ->
                if (!ownsScreen() || scannerSession != scanSession) return@scan
                scannerSession = null
                val accountIdHex = appState::accountIdHexForMention
                when (val outcome = QrScanResult.resolve(raw, QrScanUseCase.ViewProfile, accountIdHex)) {
                    is QrScanOutcome.OpenProfileNpub ->
                        if (onScannedProfile != null) {
                            onScannedProfile(outcome)
                        } else {
                            appState.presentProfile(outcome.npub)
                        }
                    is QrScanOutcome.OpenProfileNprofile ->
                        if (onScannedProfile != null) {
                            onScannedProfile(outcome)
                        } else {
                            appState.presentNostrProfile(outcome.nprofile)
                        }
                    QrScanOutcome.Invalid, is QrScanOutcome.FillRecipientQuery -> scanInvalid = true
                }
            },
        )
    }
}

/** The active profile as Share & Connect presents it. */
internal data class ShareConnectProfile(
    val name: String,
    val npub: String,
    val seed: String,
    val pictureUrl: String?,
    val nostrAddress: String,
)

/** Share & Connect as the prototype lays it out: centered top bar with share, identity column, pinned scan button. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongMethod", "LongParameterList") // Single visual surface with one anchored share menu.
@Composable
internal fun ShareConnectContent(
    profile: ShareConnectProfile,
    qrContent: String,
    copied: Boolean,
    scanInvalid: Boolean,
    pictureShareInProgress: Boolean = false,
    gallerySaveInProgress: Boolean = false,
    canSaveToGallery: Boolean = qrContent.isNotBlank(),
    onSaveToGallery: () -> Unit = {},
    onBack: () -> Unit,
    onShareUrl: () -> Unit,
    onSharePicture: () -> Unit,
    onCopy: () -> Unit,
    onOpenScanner: () -> Unit,
) {
    WhiteNoiseScaffold(
        modifier = Modifier.fillMaxSize().testTag("share_connect.screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.share_and_connect)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    ShareConnectShareAction(
                        pictureShareInProgress = pictureShareInProgress,
                        gallerySaveInProgress = gallerySaveInProgress,
                        canSaveToGallery = canSaveToGallery,
                        onShareUrl = onShareUrl,
                        onSharePicture = onSharePicture,
                        onSaveToGallery = onSaveToGallery,
                    )
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                scrollBehavior = LocalWhiteNoiseHeaderScroll.current,
            )
        },
        bottomBar = { ShareConnectBottomAction(onClick = onOpenScanner) },
    ) { innerPadding ->
        AdaptiveContent(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            ShareProfileContent(profile, qrContent, copied, scanInvalid, onCopy)
        }
    }
}

/**
 * Top-bar Share action: the trigger and its anchored menu with Share profile URL, Share profile card and Save to
 * Gallery. Any export disables the trigger and every export entry, and a save announces Saving to TalkBack.
 */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList") // One trigger, its busy semantics and its menu rows.
@Composable
private fun ShareConnectShareAction(
    pictureShareInProgress: Boolean,
    gallerySaveInProgress: Boolean,
    canSaveToGallery: Boolean,
    onShareUrl: () -> Unit,
    onSharePicture: () -> Unit,
    onSaveToGallery: () -> Unit,
) {
    val shareLabel = stringResource(R.string.share_profile)
    val savingLabel = stringResource(R.string.photo_editor_saving_action)
    val exporting = pictureShareInProgress || gallerySaveInProgress
    var menuExpanded by remember { mutableStateOf(false) }

    // An export that starts while the menu is open closes it, and the menu must not reopen when the export ends.
    LaunchedEffect(exporting) { if (exporting) menuExpanded = false }

    Box {
        IconButton(
            onClick = { menuExpanded = true },
            enabled = !exporting,
            modifier =
                Modifier.testTag("share_connect.share").semantics {
                    if (exporting) contentDescription = shareLabel
                    if (gallerySaveInProgress) stateDescription = savingLabel
                },
        ) {
            if (exporting) {
                CircularProgressIndicator(
                    Modifier.size(ShareConnectDefaults.IconSize),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_share),
                    contentDescription = shareLabel,
                )
            }
        }
        WhiteNoiseDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            items =
                listOf(
                    WhiteNoiseMenuItem(
                        stringResource(R.string.share_profile_url),
                        onShareUrl,
                        R.drawable.ic_link,
                    ),
                    WhiteNoiseMenuItem(
                        stringResource(R.string.share_profile_picture),
                        onSharePicture,
                        R.drawable.ic_image,
                        enabled = !exporting,
                    ),
                    WhiteNoiseMenuItem(
                        stringResource(R.string.share_connect_save_to_gallery),
                        onSaveToGallery,
                        R.drawable.ic_download,
                        enabled = canSaveToGallery && !exporting,
                        modifier = Modifier.testTag("share_connect.save_to_gallery"),
                    ),
                ),
        )
    }
}

/** Avatar, name, address, copy capsule, QR card and caption, centered in a scrolling column. */
@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun ShareProfileContent(
    profile: ShareConnectProfile,
    qrContent: String,
    copied: Boolean,
    scanInvalid: Boolean,
    onCopy: () -> Unit,
) {
    val copyState = stringResource(if (copied) R.string.copied else R.string.not_copied)
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().whiteNoiseVerticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        val avatarSize =
            (maxWidth * ShareConnectDefaults.AVATAR_WIDTH_FRACTION)
                .coerceIn(ShareConnectDefaults.AvatarMinSize, ShareConnectDefaults.AvatarMaxSize)
        val availableWidth = maxWidth
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = WhiteNoiseSpacing.CompactScreenMargin,
                        end = WhiteNoiseSpacing.CompactScreenMargin,
                        top = WhiteNoiseSpacing.Section,
                        bottom = WhiteNoiseSpacing.Section,
                    ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(
                title = profile.name,
                seed = profile.seed,
                size = avatarSize,
                pictureUrl = profile.pictureUrl,
                pictureDomain = AvatarImageLoader.publicProfiles(),
            )
            EmojiLabel(
                text = profile.name,
                modifier = Modifier.padding(top = WhiteNoiseSpacing.FormField),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            if (profile.nostrAddress.isNotBlank()) {
                Text(
                    text = profile.nostrAddress,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IdentifierCopyCapsule(
                value = profile.npub,
                copied = copied,
                onCopy = onCopy,
                copyContentDescription = stringResource(R.string.copy_public_key),
                copiedContentDescription = stringResource(R.string.copied),
                notCopiedStateDescription = copyState,
                copiedStateDescription = copyState,
                targetTestTag = "share_connect.copy_public_key",
                visualTestTag = "share_connect.copy_public_key.visual",
                modifier = Modifier.padding(top = 4.dp),
            )
            // The capsule abbreviates; the whole key stays readable for comparison (#3321).
            if (profile.npub.isNotBlank()) {
                FullPublicKey(
                    npub = profile.npub,
                    modifier = Modifier.padding(top = 8.dp),
                    testTag = "share_connect.full_public_key",
                    label = stringResource(R.string.public_key),
                )
            }
            IdentityQrCodeSurface(
                value = qrContent,
                availableWidth = availableWidth,
                contentDescription = stringResource(R.string.profile_qr_code),
                testTag = "share_connect.qr_surface",
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = stringResource(R.string.scan_to_connect),
                modifier = Modifier.padding(top = 1.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (scanInvalid) {
                Text(
                    text = stringResource(R.string.error_not_white_noise_profile_qr),
                    modifier =
                        Modifier
                            .padding(top = WhiteNoiseSpacing.Related)
                            .widthIn(max = ShareConnectDefaults.ErrorMaxWidth),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Pinned full-width "Scan QR Code" task button above the navigation bar. */
@Suppress("FunctionNaming")
@Composable
private fun ShareConnectBottomAction(onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        AdaptiveContent {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(WhiteNoiseSpacing.PinnedActionInset),
                contentAlignment = Alignment.Center,
            ) {
                WhiteNoiseButton(
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth().testTag("share_connect.open_scanner"),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_qr_code_scanner),
                        contentDescription = null,
                        modifier = Modifier.size(ShareConnectDefaults.IconSize),
                    )
                    Spacer(Modifier.width(WhiteNoiseSpacing.Related))
                    Text(stringResource(R.string.scan_qr_code))
                }
            }
        }
    }
}
