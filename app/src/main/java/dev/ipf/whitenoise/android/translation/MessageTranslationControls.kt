@file:Suppress("FunctionNaming") // Unit-returning Compose controls use the established PascalCase convention.

package dev.ipf.whitenoise.android.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet

/**
 * The message's translation identity for its current text and target, or null when it cannot be translated.
 * An unusable saved target leaves the target empty, so the manual sheet asks for a language and automatic
 * translation waits for an explicit choice instead of substituting one.
 */
@Composable
internal fun rememberMessageTranslationSource(
    messageId: String,
    text: String,
    account: String?,
    group: String,
    automaticEligible: Boolean,
    selectedTarget: String? = null,
    sourceRevision: String = "",
): TranslationSource? {
    val session = LocalConversationTranslation.current
    val preferences = LocalTranslationPreferences.current
    val revision = preferences?.changes?.collectAsState()?.value
    val choice = account?.let { preferences?.effective(it, group) }
    val target = selectedTarget ?: choice?.target
    val source =
        remember(messageId, text, target, sourceRevision, account, group, choice != null) {
            choice
                ?.takeIf { text.isNotBlank() && text.length <= MAX_TRANSLATION_TEXT }
                ?.let { TranslationSource(messageId, text, target.orEmpty(), sourceRevision) }
        }
    // Session epoch, failure and capacity are watched inside the effect rather than keyed in composition: every
    // bubble reads this, and one source's removal would otherwise recompose every visible message.
    LaunchedEffect(source, session, revision, automaticEligible) {
        val enabled = automaticEligible && choice?.enabled == true
        if (source == null || session == null || !enabled) return@LaunchedEffect
        if (!OfflineTranslatorLanguageCatalog.supportsTarget(source.target)) return@LaunchedEffect
        snapshotFlow { Triple(session.epoch, session.failure, session.capacityRevision) }.collect {
            session.request(source, automatic = true)
        }
    }
    DisposableEffect(source, session) { onDispose { source?.let { session?.remove(it) } } }
    return source
}

/** Renders only active translation status or result controls without widening the owning message. */
@Composable
internal fun MessageTranslationRendition(source: TranslationSource?) {
    val session = LocalConversationTranslation.current
    if (session == null || source == null) return
    val rendition = session.renditions[source]
    val pending = session.isPending(source)
    if (!pending && rendition == null) return
    Column(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (pending) {
            Text(stringResource(R.string.translation_loading), style = MaterialTheme.typography.labelSmall)
        }
        if (rendition != null) {
            TextButton(onClick = { session.toggle(source) }) {
                Text(
                    stringResource(
                        if (rendition.showTranslation) {
                            R.string.translation_show_original
                        } else {
                            R.string.translation_show_result
                        },
                    ),
                )
            }
        }
    }
}

/** The manual sheet: submits only a catalog pair, then replaces any earlier work for this message and target. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageTranslationDialog(
    source: TranslationSource?,
    onTargetSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val session = LocalConversationTranslation.current ?: return
    if (!session.isCurrent()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val context = LocalContext.current
    var target by remember(source) { mutableStateOf(source?.target.orEmpty()) }
    var from by remember(source) { mutableStateOf("") }
    WhiteNoiseModalBottomSheet(onDismissRequest = onDismiss) {
        MessageTranslationForm(
            from = from,
            target = target,
            failure = session.failure,
            enabled = source != null && OfflineTranslatorLanguageCatalog.supportsRequest(from, target),
            onFromChange = { from = it },
            onTargetChange = { target = it },
            onTranslate = {
                source?.let {
                    onTargetSelected(target)
                    session.retry()
                    session.remove(it)
                    session.remove(it.copy(target = target))
                    session.request(it.copy(target = target), from)
                }
                onDismiss()
            },
            onDismiss = onDismiss,
            onOpenEngine = { openOfflineTranslator(context) },
        )
    }
}

/** Sanitized, localized recovery copy for a failure; engine messages and languages are never shown. */
internal fun translationFailureResource(failure: TranslationFailure): Int {
    val unverified = R.string.translation_unverified_engine
    return when (failure) {
        TranslationFailure.MISSING_ENGINE -> R.string.translation_missing_engine
        TranslationFailure.UNVERIFIED_ENGINE, TranslationFailure.INCOMPATIBLE_ENGINE -> unverified
        TranslationFailure.DETECTION -> R.string.translation_detection_failed
        TranslationFailure.SOURCE_MODEL -> R.string.translation_source_model_missing
        TranslationFailure.ENGINE_UPDATE_REQUIRED -> R.string.translation_engine_update_required
        else -> R.string.translation_engine_failed
    }
}

/**
 * The conversation notice after the sheet has closed. An engine too old for the chosen language needs an
 * update, which retrying or opening the engine cannot fix, so it names that instead of the generic recovery.
 */
internal fun recoveryNotice(failure: TranslationFailure?): Int =
    when (failure) {
        TranslationFailure.ENGINE_UPDATE_REQUIRED -> R.string.translation_engine_update_required
        else -> R.string.translation_recovery_notice
    }
