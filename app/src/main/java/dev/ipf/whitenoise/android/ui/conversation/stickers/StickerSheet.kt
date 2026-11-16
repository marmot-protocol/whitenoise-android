package dev.ipf.whitenoise.android.ui.conversation.stickers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.StickerFfi
import dev.ipf.marmotkit.StickerPackFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.state.rethrowIfCancellation
import dev.ipf.whitenoise.android.state.stickerFailureDetail
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseOutlinedButton
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor

private const val SHEET_HEIGHT_SHARE = 0.88f

/** Shows a failed sticker or pack operation: its title, its diagnostic code and the cause. */
internal typealias StickerFailureReporter = (title: Int, code: String, throwable: Throwable) -> Unit

private sealed interface StickerSheetPage {
    data object Picker : StickerSheetPage

    data object Browser : StickerSheetPage

    data class Detail(
        val pack: StickerPackFfi,
    ) : StickerSheetPage
}

/** Sticker picker with the pack browser and pack detail as pages of the same sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
internal fun StickerSheet(
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    onSend: (StickerFfi) -> Unit,
    onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf<StickerSheetPage>(StickerSheetPage.Picker) }
    val back = { page = if (page is StickerSheetPage.Detail) StickerSheetPage.Browser else StickerSheetPage.Picker }
    BackHandler(enabled = page != StickerSheetPage.Picker, onBack = back)
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val maxHeight = with(LocalDensity.current) { (windowHeight * SHEET_HEIGHT_SHARE).toDp() }
    val library = controller.stickerLibrary
    val cache = controller.stickerAssets
    val reportFailure: StickerFailureReporter = { title, code, throwable ->
        appState.presentFailure(title, code, throwable, stickerFailureDetail(throwable))
    }
    WhiteNoiseModalBottomSheet(onDismissRequest = onDismiss, containerColor = amoledSheetContainerColor()) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).testTag("sticker.sheet")) {
            when (val current = page) {
                StickerSheetPage.Picker ->
                    StickerPickerPage(
                        library = library,
                        cache = cache,
                        onBrowse = { page = StickerSheetPage.Browser },
                        onSend = onSend,
                        onClose = onDismiss,
                    )
                StickerSheetPage.Browser ->
                    StickerBrowserPage(
                        library = library,
                        onFailure = reportFailure,
                        onOpen = { page = StickerSheetPage.Detail(it) },
                        onBack = back,
                        onClose = onDismiss,
                    )
                is StickerSheetPage.Detail ->
                    StickerPackDetailPage(
                        pack = current.pack,
                        library = library,
                        cache = cache,
                        authorName = appState.networkDisplayName(current.pack.authorPubkeyHex),
                        onOpenAuthor = {
                            val npub = appState.npubForDisplay(current.pack.authorPubkeyHex)
                            if (npub.isNotBlank()) {
                                onDismiss()
                                appState.presentProfile(npub)
                            }
                        },
                        onSend = onSend,
                        onFailure = reportFailure,
                        onBack = back,
                        onClose = onDismiss,
                    )
            }
        }
    }
}

/** The sheet's title bar with optional back, a page [action] and close. */
@Suppress("FunctionNaming")
@Composable
internal fun StickerSheetTopBar(
    title: String,
    onBack: (() -> Unit)?,
    onClose: () -> Unit,
    action: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = if (onBack == null) WhiteNoiseSpacing.Section else WhiteNoiseSpacing.Related,
                end = WhiteNoiseSpacing.Related,
                bottom = WhiteNoiseSpacing.Related,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        action()
        IconButton(onClick = onClose) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close))
        }
    }
}

/** The pack's display title, or a generic label when it has none. */
@Composable
internal fun stickerPackTitle(pack: StickerPackFfi): String {
    val fallback = stringResource(R.string.sticker_pack)
    return StickerPresentation.title(pack) ?: fallback
}

/** A heading inside a sticker page. */
@Suppress("FunctionNaming")
@Composable
internal fun StickerSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = WhiteNoiseSpacing.Related).semantics { heading() },
    )
}

/** Secondary explanatory text inside a sticker page. */
@Suppress("FunctionNaming")
@Composable
internal fun StickerDisclosure(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A centered spinner while sticker data loads. */
@Suppress("FunctionNaming")
@Composable
internal fun StickerProgress() {
    Box(Modifier.fillMaxWidth().padding(WhiteNoiseSpacing.Section), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
    }
}

/** Sticker packs couldn't be read; [onRetry] tries again. */
@Suppress("FunctionNaming")
@Composable
internal fun StickerLoadFailed(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(WhiteNoiseSpacing.Section).testTag("sticker.load.failed"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        StickerDisclosure(stringResource(R.string.sticker_packs_load_failed))
        WhiteNoiseOutlinedButton(onClick = onRetry) { Text(stringResource(R.string.sticker_packs_try_again)) }
    }
}

/** Runs [block], returning its failure; cancellation still propagates. */
@Suppress("TooGenericExceptionCaught")
internal suspend fun attemptSticker(block: suspend () -> Unit): Throwable? =
    try {
        block()
        null
    } catch (throwable: Throwable) {
        rethrowIfCancellation(throwable)
        throwable
    }
