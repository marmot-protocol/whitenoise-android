@file:Suppress("FunctionNaming") // Unit-returning Compose controls use the established PascalCase convention.

package dev.ipf.whitenoise.android.translation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

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

/** Width of the toggle slot in the bubble's otherwise empty gutter; the slot is only as tall as the disc. */
internal val MessageTranslationToggleSlotWidth = 48.dp

/** Vertical gap between stacked side discs, such as transcribe above translate. */
internal val MessageSideDiscSpacing = 4.dp
private val MessageSideDiscSize = 28.dp
private val MessageSideDiscIconSize = 18.dp
private val MessageSidePendingRingSize = 18.dp
private val MessageSidePendingRingStroke = 2.dp

/**
 * The side control for a translated message: a progress ring while work is pending, otherwise a translate disc
 * that switches between the translation and the original. It fills the slot its parent measures for it and
 * emits nothing when the message has neither pending work nor a result.
 */
@Composable
internal fun MessageTranslationToggle(
    source: TranslationSource?,
    modifier: Modifier = Modifier,
) {
    val session = LocalConversationTranslation.current
    if (session == null || source == null) return
    val rendition = session.renditions[source]
    if (session.isPending(source)) {
        MessageSidePendingRing(
            description = stringResource(R.string.translation_loading),
            modifier = modifier.testTag(MESSAGE_TRANSLATION_PENDING_TEST_TAG),
        )
    } else if (rendition != null) {
        MessageSideDisc(
            on = rendition.showTranslation,
            description = stringResource(R.string.translation_title),
            onToggle = { session.toggle(source) },
            modifier = modifier.testTag(MESSAGE_TRANSLATION_TOGGLE_TEST_TAG),
        ) {
            MessageSideDiscIcon(R.drawable.ic_translate)
        }
    }
}

/**
 * One gutter switch shared by translation and transcription: a filled disc when on, an outlined disc when off.
 * [stateDescription] lets a disc announce in-flight work while staying tappable to cancel it.
 */
@Composable
internal fun MessageSideDisc(
    on: Boolean,
    description: String,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    stateDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val outline = amoledOutlineBorder()
    val colors = MaterialTheme.colorScheme
    val contentColor = if (on) colors.onPrimary else colors.onSurfaceVariant
    Box(
        modifier =
            modifier
                .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
                .semantics {
                    contentDescription = description
                    stateDescription?.let { this.stateDescription = it }
                },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(MessageSideDiscSize),
            shape = CircleShape,
            color =
                when {
                    on -> colors.primary
                    outline != null -> colors.surface
                    else -> Color.Transparent
                },
            contentColor = if (enabled) contentColor else contentColor.copy(alpha = DISABLED_ALPHA),
            border = if (on) null else outline ?: BorderStroke(1.dp, colors.outline),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
        }
    }
}

@Composable
internal fun MessageSideDiscIcon(icon: Int) {
    Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(MessageSideDiscIconSize))
}

/** The in-disc spinner for work that the same disc can still cancel. */
@Composable
internal fun MessageSideDiscProgress() {
    CircularProgressIndicator(
        modifier = Modifier.size(MessageSidePendingRingSize),
        strokeWidth = MessageSidePendingRingStroke,
        color = LocalContentColor.current,
    )
}

@Composable
private fun MessageSidePendingRing(
    description: String,
    modifier: Modifier,
) {
    Box(
        modifier =
            modifier.semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(MessageSidePendingRingSize),
            strokeWidth = MessageSidePendingRingStroke,
        )
    }
}

private const val DISABLED_ALPHA = 0.38f

internal const val MESSAGE_TRANSLATION_TOGGLE_TEST_TAG = "message-translation-toggle"
internal const val MESSAGE_TRANSLATION_PENDING_TEST_TAG = "message-translation-pending"

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
