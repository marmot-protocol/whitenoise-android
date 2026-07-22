package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.Lud16Resolver
import dev.ipf.whitenoise.android.core.ProfileFieldValidation
import dev.ipf.whitenoise.android.core.ProfilePseudonymGenerator
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.ProfilePublicWarning
import dev.ipf.whitenoise.android.ui.common.SectionCard
import dev.ipf.whitenoise.android.ui.common.StickyFormActionBar
import dev.ipf.whitenoise.android.ui.group.ImageSearchSheet
import dev.ipf.whitenoise.android.ui.theme.Dimens
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileEditScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val active = appState.activeAccount
    var displayName by remember(active?.accountIdHex) { mutableStateOf("") }
    var about by remember(active?.accountIdHex) { mutableStateOf("") }
    var picture by remember(active?.accountIdHex) { mutableStateOf("") }
    var nip05 by remember(active?.accountIdHex) { mutableStateOf("") }
    var lud16 by remember(active?.accountIdHex) { mutableStateOf("") }
    // In-flight / failed LNURL-pay resolution of the lud16 field (#795). The
    // error is a string resource id so the inline message can distinguish
    // "doesn't resolve" from "no network"; it clears on every edit.
    var lud16Checking by remember { mutableStateOf(false) }
    var lud16ResolveError by remember(active?.accountIdHex) { mutableStateOf<Int?>(null) }
    val lud16FocusRequester = remember { FocusRequester() }
    var busy by remember { mutableStateOf(false) }
    // Drives the avatar bottom sheet (pick-from-photos / paste-link / remove).
    // The picture URL no longer lives as a standalone editor row; it's edited
    // exclusively through this control so the editor reads like an app screen,
    // not a developer surface. See #286.
    var showPictureSheet by remember { mutableStateOf(false) }
    var fullPictureOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val safePictureUrl = ProfileSanitizer.imageUrl(picture)
    val avatarImageAvailable = rememberAvatarImageAvailable(safePictureUrl)
    val pictureValid = ProfileFieldValidation.isAcceptablePictureUrl(picture)
    val nip05Valid = ProfileFieldValidation.isAcceptableNip05(nip05)
    val lud16Valid = ProfileFieldValidation.isAcceptableLud16(lud16)
    val saveEnabled = !busy && active != null && pictureValid && nip05Valid && lud16Valid

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

    LaunchedEffect(active?.accountIdHex) {
        val profile = active?.accountIdHex?.let { appState.loadUserProfile(it) }
        if (profile != null) {
            displayName = profile.displayName ?: profile.name ?: ""
            about = profile.about ?: ""
            picture = profile.picture ?: ""
            nip05 = profile.nip05 ?: ""
            lud16 = profile.lud16 ?: ""
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
            contentPadding = PaddingValues(Dimens.spaceLg),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceXl),
        ) {
            item {
                // Live profile header — the avatar, name, and npub update as the
                // fields below are edited, so the user previews their card inline.
                Column(
                    Modifier.fillMaxWidth().padding(top = Dimens.spaceSm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
                ) {
                    if (active == null) {
                        Text(stringResource(R.string.no_active_account_period), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        // The avatar itself views the current picture. The small
                        // camera badge remains the direct edit affordance (#317),
                        // so viewing and editing no longer compete for the same tap.
                        val editPictureLabel = stringResource(R.string.profile_picture_edit)
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier =
                                    Modifier
                                        .clip(CircleShape)
                                        .clickable(
                                            onClickLabel =
                                                stringResource(
                                                    if (avatarImageAvailable) R.string.profile_view_picture else R.string.profile_picture_edit,
                                                ),
                                            role = Role.Button,
                                        ) {
                                            if (avatarImageAvailable) {
                                                fullPictureOpen = true
                                            } else {
                                                showPictureSheet = true
                                            }
                                        },
                            ) {
                                Avatar(
                                    title = displayName.ifBlank { appState.shortNpub(active.accountIdHex) },
                                    seed = active.accountIdHex,
                                    size = 96.dp,
                                    pictureUrl = safePictureUrl,
                                )
                            }
                            Box(
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .offset(x = 6.dp, y = 6.dp)
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.62f))
                                        .clickable(
                                            onClickLabel = editPictureLabel,
                                            role = Role.Button,
                                        ) { showPictureSheet = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.PhotoCamera,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Text(
                            displayName.ifBlank { stringResource(R.string.anonymous) },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        // Tap to copy the full npub (#287). Same affordance as
                        // the Identity screen npub row and member rows.
                        val copyNpubLabel = stringResource(R.string.copy)
                        Text(
                            appState.shortNpub(active.accountIdHex),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier
                                    .minimumInteractiveComponentSize()
                                    .clickable(
                                        onClickLabel = copyNpubLabel,
                                        role = Role.Button,
                                    ) {
                                        clipboard.setText(AnnotatedString(appState.npub(active.accountIdHex)))
                                    },
                        )
                        // Surface an invalid stored picture URL right on the
                        // avatar control. The inline Picture URL row is gone, so
                        // without this an unsafe/malformed `picture` would
                        // silently disable Publish with no on-screen reason; the
                        // caption tells the user to tap the avatar to fix it.
                        // See #286.
                        if (picture.isNotBlank() && !ProfileFieldValidation.isAcceptablePictureUrl(picture)) {
                            Text(
                                stringResource(R.string.profile_picture_invalid),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            if (active != null) {
                item {
                    // Public-profile notice (#380): kind:0 metadata is broadcast
                    // unencrypted to relays, so warn before the editable fields
                    // that everything here is visible to the whole network. Copy
                    // mirrors Whitenoise Flutter (profileIsPublic /
                    // profilePublicDescription) for cross-client parity.
                    ProfilePublicWarning()
                }
            }
            item {
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
            // so there's no in-flight mutation to gate on here.
            applyInFlight = false,
            onApply = { picked ->
                // picked is the sanitized URL (Apply) or null (Remove); either
                // way it's normalized by the sheet before reaching us.
                picture = picked.orEmpty()
                showPictureSheet = false
            },
            onDismiss = { showPictureSheet = false },
        )
    }
}
