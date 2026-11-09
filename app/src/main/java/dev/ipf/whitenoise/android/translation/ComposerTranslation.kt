@file:Suppress("FunctionNaming") // Unit-returning Compose controls use the established PascalCase convention.

package dev.ipf.whitenoise.android.translation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.ChoiceDialog
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
) {
    var active by mutableStateOf(false)
        private set
    var target by mutableStateOf(initialTarget?.takeIf(OfflineTranslatorLanguageCatalog::supportsTarget))
        private set
    var result by mutableStateOf<ComposerTranslationResult?>(null)
        private set
    var pending by mutableStateOf(false)
        private set
    var failure by mutableStateOf<TranslationFailure?>(null)
        private set

    /** Bumped by every external reset so the composer asks for its current text again. */
    var epoch by mutableLongStateOf(0L)
        private set
    private var job: Job? = null
    private var generation = 0L

    fun toggle() {
        active = !active
        invalidate()
    }

    fun close() {
        if (!active) return
        active = false
        invalidate()
    }

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
        failure = null
        epoch += 1
    }

    /** Requests [text] once typing settles, unless the shown translation already matches it. */
    fun update(text: String) {
        val chosen = target
        if (!active || chosen == null || text.isBlank()) {
            cancel()
            result = null
            failure = null
            return
        }
        if (result?.let { it.source == text && it.target == chosen } == true) return
        cancel()
        failure = null
        pending = true
        val requestGeneration = generation
        job = scope.launch { perform(text, chosen, requestGeneration) }
    }

    /**
     * The translated text Send may use for exactly [text], or null while it is missing, pending or stale.
     * Blank text has nothing to translate, so an attachment can still go without a caption.
     */
    fun outgoingFor(text: String): String? =
        if (text.isBlank()) {
            text
        } else {
            result
                ?.takeIf { active && !pending && failure == null && it.source == text && it.target == target }
                ?.text
        }

    private fun cancel() {
        generation += 1
        job?.cancel()
        job = null
        pending = false
    }

    private fun current(requestGeneration: Long) = requestGeneration == generation && ownerCurrent()

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
                ownerCurrent = {
                    appState.activeAccountRef == account &&
                        appState.runtimeGeneration == runtime &&
                        !appState.appLockScreenVisible &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                },
                initialTarget = preferences.outgoingTarget(account, group),
                onTargetSaved = { preferences.setOutgoingTarget(account, group, it) },
            )
        }
    LaunchedEffect(state, appState.appLockScreenVisible) { state.invalidate() }
    DisposableEffect(state, lifecycle) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_RESUME) state.invalidate()
            }
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    state.invalidate()
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
                addDataSchemeSpecificPart(OFFLINE_TRANSLATOR_PACKAGE, android.os.PatternMatcher.PATTERN_LITERAL)
            }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
            state.close()
        }
    }
    return state
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
        outgoing = state.outgoingFor(text),
        pending = state.pending,
        failure = state.failure,
        onChooseLanguage = { choosing = true },
        onRetry = state::invalidate,
        onOpenEngine = { openOfflineTranslator(context) },
        modifier = modifier,
    )
    if (choosing) {
        ChoiceDialog(
            title = title,
            values = choices.codes,
            selected = target,
            label = choices.label,
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
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = onChooseLanguage,
                modifier = Modifier.testTag("composer.translation.language"),
            ) {
                Text(targetLabel, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(18.dp))
            }
            ComposerTranslationLine(outgoing, pending, failure, onRetry, Modifier.weight(1f))
        }
        failure?.takeIf(::needsEngine)?.let { OfflineTranslatorEngineAction(onOpen = onOpenEngine) }
    }
}

@Composable
private fun ComposerTranslationLine(
    outgoing: String?,
    pending: Boolean,
    failure: TranslationFailure?,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    val lineModifier =
        modifier
            .padding(end = 8.dp)
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
            SelectionContainer(lineModifier) {
                Text(
                    outgoing,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = PREVIEW_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
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

private fun needsEngine(failure: TranslationFailure): Boolean =
    failure in
        setOf(
            TranslationFailure.MISSING_ENGINE,
            TranslationFailure.UNVERIFIED_ENGINE,
            TranslationFailure.INCOMPATIBLE_ENGINE,
            TranslationFailure.SOURCE_MODEL,
            TranslationFailure.ENGINE,
            TranslationFailure.ENGINE_UPDATE_REQUIRED,
        )

private const val PREVIEW_MAX_LINES = 4
