@file:Suppress("FunctionNaming") // Unit-returning Compose controls use the established PascalCase convention.

package dev.ipf.whitenoise.android.translation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One finished translation, valid only while the editor still holds exactly [source] and [target] is chosen. */
internal data class ComposerTranslationResult(
    val source: String,
    val target: String,
    val text: String,
)

/**
 * Transient translate-as-you-type state for one mounted account/group/runtime composer. Nothing here is
 * persisted except the chosen outgoing language. Every request detects the source language, waits for typing
 * to settle, replaces the previous request, and is dropped if the editor, language or owner changed meanwhile.
 */
@Suppress("TooManyFunctions")
internal class ComposerTranslationState(
    private val scope: CoroutineScope,
    private val engine: suspend () -> TranslationEngine,
    private val ownerCurrent: () -> Boolean,
    initialTarget: String?,
    private val onTargetSaved: (String) -> Unit = {},
    private val settleMillis: Long = TYPING_SETTLE_MS,
    private val noticeAccepted: () -> Boolean = { true },
    private val onNoticeAccepted: () -> Unit = {},
) {
    /** True while the translation line is shown and Send delivers only its current translation. */
    var active by mutableStateOf(false)
        private set

    /** True while the one-time provider privacy notice waits for the person to accept or decline. */
    var consenting by mutableStateOf(false)
        private set

    /** Identifies the displayed consent request, including a reopen for the same provider. */
    var consentRequestId by mutableLongStateOf(0L)
        private set

    /** The saved outgoing language, or null until the person chooses one from the catalog. */
    var target by mutableStateOf(initialTarget?.takeIf(OfflineTranslatorLanguageCatalog::supportsTarget))
        private set

    /** The latest translation, which Send may use only while it still matches the editor and target. */
    var result by mutableStateOf<ComposerTranslationResult?>(null)
        private set

    /** True from a request's start until its translation, failure or cancellation. */
    var pending by mutableStateOf(false)
        private set

    /** Why the latest request produced no translation, or null. */
    var failure by mutableStateOf<TranslationFailure?>(null)
        private set

    /** Bumped by every external reset so the composer asks for its current text again. */
    var epoch by mutableLongStateOf(0L)
        private set
    private var job: Job? = null
    private var generation = 0L

    /** Turns translate mode on or off; the first turn-on asks for the privacy notice instead of translating. */
    fun toggle() {
        when {
            active -> close()
            noticeAccepted() -> open()
            else -> {
                consentRequestId += 1
                consenting = true
            }
        }
    }

    /** Records the accepted notice once, then opens translate mode. */
    fun acceptNotice(expectedRequest: Long = consentRequestId) {
        if (!consenting || expectedRequest != consentRequestId) return
        consenting = false
        if (!ownerCurrent()) return
        onNoticeAccepted()
        open()
    }

    /** Leaves translate mode off; no text has been sent to the engine. */
    fun declineNotice(expectedRequest: Long = consentRequestId) {
        if (expectedRequest != consentRequestId) return
        consenting = false
    }

    /** Leaves translate mode, for example when editing or dictation takes over the composer. */
    fun close() {
        consenting = false
        if (!active) return
        active = false
        invalidate()
    }

    /** Saves [code] as this chat's outgoing language and retranslates the current text. */
    fun selectTarget(code: String) {
        if (!OfflineTranslatorLanguageCatalog.supportsTarget(code) || code == target) return
        target = code
        onTargetSaved(code)
        invalidate()
    }

    /** Drops pending work and any shown translation; the composer then requests its current text again. */
    fun invalidate() {
        cancel()
        result = null
        epoch += 1
    }

    /** Requests [text] once typing settles, unless the shown translation already matches it. */
    fun update(text: String) {
        // Any newer text replaces the previous request, including typing back to the text already translated.
        cancel()
        val chosen = target
        if (!active || chosen == null || text.isBlank()) {
            result = null
        } else if (result?.let { it.source == text && it.target == chosen } != true) {
            pending = true
            val requestGeneration = generation
            job = scope.launch { perform(text, chosen, requestGeneration) }
        }
    }

    /**
     * What Send delivers for exactly [text]: the text itself outside translate mode, otherwise its current
     * translation, or null while that is missing, pending or stale. Blank text has nothing to translate, so an
     * attachment can still go without a caption.
     */
    fun outgoingFor(text: String): String? =
        if (!active || text.isBlank()) {
            text
        } else {
            result
                ?.takeIf { !pending && failure == null && it.source == text && it.target == target }
                ?.text
        }

    /** Turns translate mode on and asks the composer for its current text. */
    private fun open() {
        active = true
        invalidate()
    }

    /** Stops the current request and forgets its failure; a shown result stays until the caller drops it. */
    private fun cancel() {
        generation += 1
        job?.cancel()
        job = null
        pending = false
        failure = null
    }

    /** True while [requestGeneration] is still the latest request and its chat still owns the composer. */
    private fun current(requestGeneration: Long) = requestGeneration == generation && ownerCurrent()

    /** Waits for typing to settle, translates [text] into [chosen], and keeps the outcome only if still current. */
    private suspend fun perform(
        text: String,
        chosen: String,
        requestGeneration: Long,
    ) {
        try {
            delay(settleMillis)
            if (!current(requestGeneration)) return
            val translated = translateWithPrivacyGate(engine(), text, "", chosen)
            if (current(requestGeneration)) result = ComposerTranslationResult(text, chosen, translated)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: TranslationException) {
            if (current(requestGeneration)) failure = error.failure
        } catch (_: Exception) {
            if (current(requestGeneration)) failure = TranslationFailure.CONNECTION
        } finally {
            if (requestGeneration == generation) pending = false
        }
    }

    private companion object {
        const val TYPING_SETTLE_MS = 400L
    }
}

/**
 * The composer's translation owner for one chat. Like the conversation session, it clears on pause, resume,
 * app lock, runtime or account changes and engine package changes, so no late result reaches another chat.
 */
@Composable
internal fun rememberComposerTranslation(
    appState: WhiteNoiseAppState,
    account: String?,
    group: String?,
): ComposerTranslationState? {
    if (account.isNullOrBlank() || group.isNullOrBlank()) return null
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val preferences = rememberTranslationPreferences()
    val runtime = appState.runtimeGeneration
    val state =
        remember(account, group, runtime) {
            ComposerTranslationState(
                scope = scope,
                engine = { withContext(Dispatchers.IO) { OfflineTranslatorClient(context.applicationContext) } },
                ownerCurrent = { appState.translationOwnerCurrent(account, runtime, lifecycle) },
                initialTarget = preferences.outgoingTarget(account, group),
                onTargetSaved = { preferences.setOutgoingTarget(account, group, it) },
                noticeAccepted = preferences::providerNoticeAccepted,
                onNoticeAccepted = preferences::acceptProviderNotice,
            )
        }
    LaunchedEffect(state, appState.appLockScreenVisible) { state.invalidate() }
    TranslationOwnerResetEffect(state, onReset = state::invalidate, onDispose = state::close)
    return state
}

/** The one-time notice that Offline Translator may log text; nothing is translated until it is accepted. */
@Composable
internal fun ComposerTranslationNotice(state: ComposerTranslationState) {
    if (!state.consenting) return
    val request = state.consentRequestId
    ConfirmDialog(
        title = stringResource(R.string.translation_composer_toggle),
        message = stringResource(R.string.translation_privacy_warning),
        confirmLabel = stringResource(R.string.accept),
        ownerKey = state to request,
        onConfirm = { state.acceptNotice(request) },
        onDismiss = { state.declineNotice(request) },
    )
}

/** Keeps the translation in step with the editor; the composer calls this while translate mode is shown. */
@Composable
internal fun ComposerTranslationSync(
    state: ComposerTranslationState,
    text: String,
) {
    val latest by rememberUpdatedState(text)
    LaunchedEffect(state, text, state.active, state.target, state.epoch) { state.update(latest) }
}

/**
 * The read-only top line: a compact language chip, then the text Send will deliver. It never edits the draft;
 * the person keeps typing in the editor below.
 */
@Composable
internal fun ComposerTranslationPreview(
    state: ComposerTranslationState,
    text: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val choices = rememberTranslationLanguageChoices(allowDetection = false)
    var choosing by remember(state) { mutableStateOf(false) }
    val target = state.target
    LaunchedEffect(state, state.active, target) { if (state.active && target == null) choosing = true }
    val title = stringResource(R.string.translation_target_language)
    ComposerTranslationBar(
        targetLabel = choices.label(target.orEmpty()),
        // A blank draft has nothing to send yet, so the line keeps showing where its translation will appear.
        outgoing = state.outgoingFor(text)?.takeIf(String::isNotBlank),
        pending = state.pending,
        failure = state.failure,
        onChooseLanguage = { choosing = true },
        onRetry = state::invalidate,
        onOpenEngine = { openOfflineTranslator(context) },
        modifier = modifier,
    )
    if (choosing) {
        TranslationLanguagePickerSheet(
            title = title,
            choices = choices,
            selected = target,
            onDismiss = {
                choosing = false
                if (state.target == null) state.close()
            },
            onSelect = {
                state.selectTarget(it)
                choosing = false
            },
        )
    }
}

/** Stateless top line, so screenshots and previews can render every state without an engine. */
@Suppress("LongParameterList")
@Composable
internal fun ComposerTranslationBar(
    targetLabel: String,
    outgoing: String?,
    pending: Boolean,
    failure: TranslationFailure?,
    onChooseLanguage: () -> Unit,
    onRetry: () -> Unit,
    onOpenEngine: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag("composer.translation")) {
        // Top-aligned with no button frame, so the line starts as close to the composer edge as typed text does.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                // Capped so a long language name cannot squeeze out the outgoing text.
                Modifier
                    .widthIn(max = LANGUAGE_SELECTOR_MAX_WIDTH)
                    .clickable(role = Role.Button, onClick = onChooseLanguage)
                    .testTag("composer.translation.language"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    targetLabel,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    painterResource(R.drawable.ic_expand_more),
                    null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            ComposerTranslationLine(outgoing, pending, failure, onRetry, Modifier.weight(1f))
        }
        failure?.takeIf(::needsEngine)?.let { OfflineTranslatorEngineAction(onOpen = onOpenEngine) }
    }
}

/** A "Will send" label over the outgoing text, which scrolls instead of hiding any of it; status lines have none. */
@Composable
private fun ComposerTranslationLine(
    outgoing: String?,
    pending: Boolean,
    failure: TranslationFailure?,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.padding(end = 8.dp).semantics(mergeDescendants = true) {}) {
        // Nothing will be sent while the line shows a placeholder, progress or a failure.
        if (outgoing != null && failure == null) {
            Text(
                stringResource(R.string.translation_composer_will_send),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("composer.translation.label"),
            )
        }
        ComposerTranslationContent(outgoing, pending, failure, onRetry)
    }
}

/** The line body: the translation, a progress or placeholder hint, or the failure, which retries when tapped. */
@Composable
private fun ComposerTranslationContent(
    outgoing: String?,
    pending: Boolean,
    failure: TranslationFailure?,
    onRetry: () -> Unit,
) {
    val lineModifier =
        Modifier
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("composer.translation.text")
    when {
        failure != null ->
            Text(
                stringResource(composerFailureResource(failure)),
                modifier = lineModifier.clickable(onClick = onRetry),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        outgoing != null ->
            SelectionContainer(
                lineModifier
                    .heightIn(max = PREVIEW_MAX_HEIGHT)
                    .fadingVerticalScroll(rememberScrollState()),
            ) {
                Text(
                    outgoing,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        else ->
            Text(
                stringResource(
                    if (pending) R.string.translation_loading else R.string.translation_composer_placeholder,
                ),
                modifier = lineModifier,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
    }
}

/** No source picker exists here, so detection failure asks for more text instead of a source language. */
private fun composerFailureResource(failure: TranslationFailure): Int =
    when (failure) {
        TranslationFailure.DETECTION -> R.string.translation_composer_detection_failed
        else -> translationFailureResource(failure)
    }

/** Failures that opening or updating Offline Translator can fix, unlike a dropped connection or bad text. */
private fun needsEngine(failure: TranslationFailure): Boolean =
    when (failure) {
        TranslationFailure.MISSING_ENGINE,
        TranslationFailure.UNVERIFIED_ENGINE,
        TranslationFailure.INCOMPATIBLE_ENGINE,
        TranslationFailure.SOURCE_MODEL,
        TranslationFailure.ENGINE,
        TranslationFailure.ENGINE_UPDATE_REQUIRED,
        -> true
        else -> false
    }

/** About four body lines; longer translations scroll inside the line. */
private val PREVIEW_MAX_HEIGHT = 96.dp
private val LANGUAGE_SELECTOR_MAX_WIDTH = 160.dp
