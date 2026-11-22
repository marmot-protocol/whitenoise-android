package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.common.isCanonicalNpub

/** Characters per display group of a public key (#3321). An npub holds letters as well as digits. */
internal const val PUBLIC_KEY_GROUP_LENGTH = 5

/**
 * The canonical [npub] split into groups of five characters for display, the last group holding whatever remains.
 * Joining the groups gives back [npub] exactly, so grouping never reaches copy, share or QR.
 */
internal fun publicKeyDisplayGroups(npub: String): List<String> = npub.chunked(PUBLIC_KEY_GROUP_LENGTH)

/**
 * The complete public key in monospace groups of five characters that wrap only between groups, so every character
 * can be read, compared or photographed without scrolling, copying or another app (#3321). Characters stay left to
 * right in a right-to-left layout. The spacing is drawn only: assistive technologies and tests read the canonical
 * contiguous [npub]. With [label], a caption names the value as the public key and is read together with it.
 */
@Suppress("FunctionNaming")
@Composable
internal fun FullPublicKey(
    npub: String,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    label: String? = null,
    textAlign: TextAlign = TextAlign.Center,
) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {},
        horizontalAlignment = if (textAlign == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .let { base -> testTag?.let(base::testTag) ?: base }
                .clearAndSetSemantics { text = AnnotatedString(npub) },
        ) {
            Text(
                text = publicKeyDisplayGroups(npub).joinToString(" "),
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        textDirection = TextDirection.Ltr,
                    ),
                textAlign = textAlign,
            )
        }
    }
}

/** Groups at each end of the key that its short form keeps: `npub1` plus five characters, and the last eight. */
private const val PUBLIC_KEY_SHORT_HEAD_GROUPS = 2
private const val PUBLIC_KEY_SHORT_TAIL_GROUPS = 2

/**
 * The short form of [npub]: its first two and last two display groups around an ellipsis, so the abbreviation is
 * exactly the emphasized ends of the expanded key. A value too short to abbreviate is returned whole.
 */
internal fun publicKeyShortForm(npub: String): String {
    val groups = publicKeyDisplayGroups(npub)
    if (groups.size <= PUBLIC_KEY_SHORT_HEAD_GROUPS + PUBLIC_KEY_SHORT_TAIL_GROUPS) return npub
    return groups.take(PUBLIC_KEY_SHORT_HEAD_GROUPS).joinToString("") + "…" +
        groups.takeLast(PUBLIC_KEY_SHORT_TAIL_GROUPS).joinToString("")
}

/**
 * The grouped [npub] with the groups its short form keeps drawn in [emphasis], so the expanded key visibly contains
 * the abbreviation it replaced.
 */
private fun groupedPublicKeyText(
    npub: String,
    emphasis: SpanStyle,
): AnnotatedString {
    val groups = publicKeyDisplayGroups(npub)
    val abbreviates = groups.size > PUBLIC_KEY_SHORT_HEAD_GROUPS + PUBLIC_KEY_SHORT_TAIL_GROUPS
    return buildAnnotatedString {
        groups.forEachIndexed { index, group ->
            if (index > 0) append(' ')
            val kept = index < PUBLIC_KEY_SHORT_HEAD_GROUPS || index >= groups.size - PUBLIC_KEY_SHORT_TAIL_GROUPS
            if (abbreviates && kept) withStyle(emphasis) { append(group) } else append(group)
        }
    }
}

/**
 * The one public-key presentation of an identity screen: profile, Share & Connect and the profile QR sheet. The key
 * starts short, and a tap copies the complete key and expands it in place into monospace groups of five for
 * comparison (#3321, #3565). It stays expanded while the screen shows this key; later taps copy again. The copy glyph
 * turns into a check while [copied]. Text and glyph are one button, so the key has a single accessible Copy public
 * key action, and its spoken value is always the canonical contiguous [npub]. A reference that is not a complete
 * npub is shown whole as inert text with no copy action, so an unresolved value is never handed out.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun CopyablePublicKey(
    npub: String,
    copied: Boolean,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    targetTestTag: String? = null,
    textTestTag: String? = null,
) {
    val copyable = isCanonicalNpub(npub)
    var expanded by rememberSaveable(npub) { mutableStateOf(false) }
    val showFull = expanded || !copyable
    val copyLabel = stringResource(R.string.copy_public_key)
    val state = stringResource(if (copied) R.string.copied else R.string.not_copied)
    val emphasis = SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
    Row(
        modifier =
            modifier
                .heightIn(min = COPYABLE_PUBLIC_KEY_MIN_HEIGHT)
                .clip(MaterialTheme.shapes.medium)
                .let { base ->
                    if (copyable) {
                        base
                            .explainOnLongPress(
                                Explanation(
                                    copyLabel,
                                    stringResource(R.string.explanation_organize_copy_public_key),
                                ),
                            ).clickable(role = Role.Button, onClickLabel = copyLabel) {
                                onCopy()
                                expanded = true
                            }
                    } else {
                        base
                    }
                }.let { base -> targetTestTag?.let(base::testTag) ?: base }
                .clearAndSetSemantics {
                    text = AnnotatedString(npub)
                    if (copyable) stateDescription = state
                }.animateContentSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (showFull) groupedPublicKeyText(npub, emphasis) else AnnotatedString(publicKeyShortForm(npub)),
            modifier =
                Modifier
                    .weight(1f, fill = false)
                    .let { base -> textTestTag?.let(base::testTag) ?: base },
            style =
                MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    textDirection = TextDirection.Ltr,
                ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (copyable) {
            Icon(
                painter = painterResource(if (copied) R.drawable.ic_check else R.drawable.ic_content_copy),
                contentDescription = null,
                modifier = Modifier.size(COPYABLE_PUBLIC_KEY_ICON_SIZE),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val COPYABLE_PUBLIC_KEY_MIN_HEIGHT = 48.dp
private val COPYABLE_PUBLIC_KEY_ICON_SIZE = 20.dp
