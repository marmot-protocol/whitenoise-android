package dev.ipf.whitenoise.android.ui.settings

import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import dev.ipf.marmotkit.MemberRefFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ProfileLink
import dev.ipf.whitenoise.android.ui.common.SystemPasteIconButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.common.rememberClipboardCanOfferPaste
import dev.ipf.whitenoise.android.ui.common.withPrimaryClipForPaste
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Checks the public reference locally and shows its complete key before opening the normal profile flow. */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun AgentKeyReviewScreen(
    ownAccountId: String,
    normalizeReference: (String) -> MemberRefFfi?,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    val input = remember { TextFieldState() }
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val raw = input.text.toString().trim()
    var checkedInput by remember { mutableStateOf<String?>(null) }
    val result =
        if (checkedInput == raw) {
            remember(raw, ownAccountId) { checkAgentKey(raw, ownAccountId, normalizeReference) }
        } else {
            null
        }
    val check = {
        checkedInput = input.text.toString().trim()
        focus.clearFocus()
    }
    val error =
        when (result) {
            AgentKeyCheck.Invalid -> stringResource(R.string.ai_agent_key_invalid)
            AgentKeyCheck.OwnKey -> stringResource(R.string.ai_agent_key_self)
            else -> null
        }
    val canonicalKey = (result as? AgentKeyCheck.Valid)?.npub
    SettingsScaffold(
        title = stringResource(R.string.ai_agent_review_key),
        onBack = onBack,
        bottomBar = {
            SettingsBottomAction {
                WhiteNoiseButton(
                    onClick = {
                        if (canonicalKey == null || input.text.toString().trim() != raw) check() else onOpenProfile(canonicalKey)
                    },
                    modifier = Modifier.fillMaxWidth().testTag("ai_agents.key_action"),
                    enabled = raw.isNotEmpty(),
                ) {
                    val label = if (canonicalKey == null) R.string.ai_agent_check_key else R.string.ai_agent_open_profile
                    Text(stringResource(label))
                }
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().fadingVerticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
        ) {
            SettingsExplainer(stringResource(R.string.ai_agent_key_intro))
            WhiteNoiseTextField(
                state = input,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin)
                        .testTag("ai_agents.key_input"),
                label = { Text(stringResource(R.string.ai_agent_key_label)) },
                trailingIcon =
                    if (rememberClipboardCanOfferPaste(clipboard)) {
                        {
                            PasteAgentKey(clipboard) { text ->
                                input.setTextAndPlaceCursorAtEnd(text)
                                check()
                            }
                        }
                    } else {
                        null
                    },
                supportingText = error?.let { message -> { Text(message) } },
                errorMessage = error,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                onKeyboardAction = { if (raw.isNotEmpty()) check() },
                lineLimits = TextFieldLineLimits.SingleLine,
                emojiAction = false,
            )
            if (canonicalKey != null) {
                SettingsExplainer(stringResource(R.string.ai_agent_key_compare))
                FullPublicKey(
                    npub = canonicalKey,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
                    testTag = "ai_agents.checked_key",
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

/** Clipboard contents are read only when the person explicitly taps Paste. */
@Suppress("FunctionNaming")
@Composable
private fun PasteAgentKey(clipboard: ClipboardManager?, onPaste: (String) -> Unit) {
    SystemPasteIconButton(
        onPaste = {
            clipboard.withPrimaryClipForPaste { clip ->
                if (clip.itemCount > 0) clip.getItemAt(0).text?.toString()?.let(onPaste)
            }
        },
    ) {
        Icon(painterResource(R.drawable.ic_content_paste), stringResource(R.string.paste))
    }
}

internal sealed interface AgentKeyCheck {
    data class Valid(val npub: String) : AgentKeyCheck

    data object Invalid : AgentKeyCheck

    data object OwnKey : AgentKeyCheck
}

/** Normalizes public references through MDK. The parser checks identity syntax, not control of the key. */
internal fun checkAgentKey(
    raw: String,
    ownAccountId: String,
    normalizeReference: (String) -> MemberRefFfi?,
): AgentKeyCheck {
    val text = raw.trim()
    val reference = ProfileLink.parse(text)?.npub ?: text.withoutNostrScheme()
    val payload = reference.removePrefix("marmot://profile/")
    if (!payload.startsWith("npub1", ignoreCase = true) && !payload.startsWith("nprofile1", ignoreCase = true)) {
        return AgentKeyCheck.Invalid
    }
    val member = normalizeReference(reference)
    return when {
        member == null -> AgentKeyCheck.Invalid
        member.accountIdHex.equals(ownAccountId, ignoreCase = true) -> AgentKeyCheck.OwnKey
        else -> AgentKeyCheck.Valid(member.npub)
    }
}

private fun String.withoutNostrScheme(): String {
    val prefix = "nostr:"
    return if (startsWith(prefix, ignoreCase = true)) drop(prefix.length) else this
}
