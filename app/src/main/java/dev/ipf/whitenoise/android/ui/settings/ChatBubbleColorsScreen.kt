package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.BubbleSide
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.state.OPAQUE_BLACK_ARGB
import dev.ipf.whitenoise.android.state.OPAQUE_WHITE_ARGB
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.parseOpaqueColorHex
import dev.ipf.whitenoise.android.state.readableTextArgb
import dev.ipf.whitenoise.android.state.tonalBubbleColorPresets
import dev.ipf.whitenoise.android.ui.common.SettingsGroup
import dev.ipf.whitenoise.android.ui.conversation.messages.BubblePresentationTokens
import dev.ipf.whitenoise.android.ui.conversation.messages.colorFromArgb
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleBorder
import dev.ipf.whitenoise.android.ui.conversation.messages.resolveBubblePresentationArgb
import java.util.Locale

private const val BUBBLE_COLOR_RGB_MASK = 0xFFFFFFL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatBubbleColorsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    groupIdHex: String? = null,
) {
    val bubbleTheme = BubbleTheme.resolve(appState.themeMode, isSystemInDarkTheme())
    val accountScope = appState.activeAccountRef?.trim()?.takeIf(String::isNotEmpty) ?: "none"
    val pickerScopeKey =
        groupIdHex?.let { "account:$accountScope:chat:$it" }
            ?: "account:$accountScope:global"
    val scopeSubtitle =
        stringResource(
            if (groupIdHex == null) {
                R.string.chat_bubble_colors_global_subtitle
            } else {
                R.string.chat_bubble_colors_chat_subtitle
            },
        )

    fun selectedColor(side: BubbleSide): Long? =
        if (groupIdHex == null) {
            appState.globalBubbleColorArgb(bubbleTheme, side)
        } else {
            appState.chatBubbleColorArgb(groupIdHex, side)
        }

    fun effectiveColor(side: BubbleSide): Long? =
        if (groupIdHex == null) {
            appState.globalBubbleColorArgb(bubbleTheme, side)
        } else {
            appState.effectiveBubbleColorArgb(bubbleTheme, side, groupIdHex)
        }

    fun updateColor(
        side: BubbleSide,
        argb: Long?,
    ) {
        if (groupIdHex == null) {
            appState.updateGlobalBubbleColor(bubbleTheme, side, argb)
        } else {
            appState.updateChatBubbleColor(groupIdHex, side, argb)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chat_bubble_colors)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(scopeSubtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                SettingsGroup(title = stringResource(R.string.bubble_color_preview)) {
                    item {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                stringResource(R.string.bubble_color_current_theme, stringResource(bubbleTheme.labelRes)),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            BubbleColorPreview(
                                mineOverrideArgb = effectiveColor(BubbleSide.Mine),
                                otherOverrideArgb = effectiveColor(BubbleSide.Other),
                                amoled = bubbleTheme == BubbleTheme.Amoled,
                            )
                        }
                    }
                }
            }
            item {
                SettingsGroup(title = stringResource(R.string.bubble_my_messages)) {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            TonalSwatchPicker(
                                selectedArgb = selectedColor(BubbleSide.Mine),
                                onColorSelected = { updateColor(BubbleSide.Mine, it) },
                                scopeKey = pickerScopeKey,
                                theme = bubbleTheme,
                                slotKey = BubbleSide.Mine.name,
                            )
                        }
                    }
                }
            }
            item {
                SettingsGroup(title = stringResource(R.string.bubble_other_messages)) {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            TonalSwatchPicker(
                                selectedArgb = selectedColor(BubbleSide.Other),
                                onColorSelected = { updateColor(BubbleSide.Other, it) },
                                scopeKey = pickerScopeKey,
                                theme = bubbleTheme,
                                slotKey = BubbleSide.Other.name,
                            )
                        }
                    }
                }
            }
            item {
                TextButton(
                    onClick = {
                        updateColor(BubbleSide.Mine, null)
                        updateColor(BubbleSide.Other, null)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.reset_to_default))
                }
            }
        }
    }
}

private val BubbleTheme.labelRes: Int
    get() =
        when (this) {
            BubbleTheme.Light -> R.string.theme_light
            BubbleTheme.Dark -> R.string.theme_dark
            BubbleTheme.Amoled -> R.string.theme_amoled
        }

@Composable
private fun BubbleColorPreview(
    mineOverrideArgb: Long?,
    otherOverrideArgb: Long?,
    amoled: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val tokens =
        BubblePresentationTokens(
            surfaceBackgroundArgb = scheme.surfaceVariant.toArgb().toLong() and 0xFFFFFFFFL,
            surfaceContentArgb = scheme.onSurfaceVariant.toArgb().toLong() and 0xFFFFFFFFL,
            mineBackgroundArgb = scheme.primaryContainer.toArgb().toLong() and 0xFFFFFFFFL,
            mineContentArgb = scheme.onPrimaryContainer.toArgb().toLong() and 0xFFFFFFFFL,
            mentionAccentArgb = scheme.primary.toArgb().toLong() and 0xFFFFFFFFL,
        )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        PreviewBubble(
            text = stringResource(R.string.bubble_preview_other),
            mine = false,
            presentation = resolveBubblePresentationArgb(false, amoled, false, otherOverrideArgb, tokens),
        )
        PreviewBubble(
            text = stringResource(R.string.bubble_preview_mine),
            mine = true,
            presentation = resolveBubblePresentationArgb(false, amoled, true, mineOverrideArgb, tokens),
        )
    }
}

@Composable
private fun PreviewBubble(
    text: String,
    mine: Boolean,
    presentation: dev.ipf.whitenoise.android.ui.conversation.messages.BubblePresentation,
) {
    Box(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.align(if (mine) Alignment.CenterEnd else Alignment.CenterStart),
            color = colorFromArgb(presentation.backgroundArgb),
            contentColor = colorFromArgb(presentation.contentArgb),
            shape = RoundedCornerShape(18.dp),
            border =
                messageBubbleBorder(
                    highlighted = false,
                    mine = mine,
                    customArgb = presentation.borderOverrideArgb,
                ),
        ) {
            Text(text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

@Composable
internal fun TonalSwatchPicker(
    selectedArgb: Long?,
    onColorSelected: (Long) -> Unit,
    scopeKey: String,
    theme: BubbleTheme,
    slotKey: String,
    @StringRes swatchContentDescriptionRes: Int = R.string.bubble_color_swatch_content_description,
) {
    val scheme = MaterialTheme.colorScheme
    val presets = remember { tonalBubbleColorPresets() }
    var customExpanded by rememberSaveable(scopeKey, theme, slotKey) { mutableStateOf(false) }
    var customHex by rememberSaveable(scopeKey, theme, slotKey, selectedArgb) {
        mutableStateOf(selectedArgb?.let { "#%06X".format(Locale.ROOT, it and BUBBLE_COLOR_RGB_MASK) } ?: "")
    }
    val parsedCustom = parseOpaqueColorHex(customHex)
    val contrastSafeCustom = parsedCustom?.takeIf { readableTextArgb(it) != null }
    val moreColorsDescription = stringResource(R.string.more_colors)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            presets.forEach { argb ->
                val selected = selectedArgb == argb
                val swatchDescription =
                    stringResource(
                        swatchContentDescriptionRes,
                        "#%06X".format(Locale.ROOT, argb and BUBBLE_COLOR_RGB_MASK),
                    )
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = swatchBorderColor(argb, selected, scheme),
                            shape = CircleShape,
                        ).clickable { onColorSelected(argb) }
                        .semantics {
                            role = Role.RadioButton
                            this.selected = selected
                            contentDescription = swatchDescription
                        },
                ) {
                    Surface(
                        color = colorFromArgb(argb),
                        shape = CircleShape,
                        modifier = Modifier.matchParentSize().padding(if (selected) 5.dp else 3.dp),
                    ) {}
                }
            }
            val customSelected = selectedArgb != null && selectedArgb !in presets
            val customContentArgb = selectedArgb?.takeIf { customSelected }?.let(::readableTextArgb)
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .border(
                        width = if (customSelected) 3.dp else 1.dp,
                        color = if (customSelected) scheme.onSurface else scheme.outline,
                        shape = CircleShape,
                    ).clickable { customExpanded = !customExpanded }
                    .semantics {
                        role = Role.Button
                        selected = customSelected
                        contentDescription = moreColorsDescription
                    },
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = selectedArgb?.takeIf { customSelected }?.let { colorFromArgb(it) } ?: scheme.surface,
                    contentColor = customContentArgb?.let { colorFromArgb(it) } ?: scheme.onSurface,
                    shape = CircleShape,
                    modifier = Modifier.matchParentSize().padding(if (customSelected) 5.dp else 3.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Palette, contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
        if (customExpanded) {
            val pickerArgb = parsedCustom ?: selectedArgb ?: presets.firstOrNull() ?: 0xFF06B6D4L
            FullSpectrumColorPicker(
                argb = pickerArgb,
                onColorChanged = { argb ->
                    if (readableTextArgb(argb) != null) {
                        customHex = "#%06X".format(Locale.ROOT, argb and BUBBLE_COLOR_RGB_MASK)
                        onColorSelected(argb)
                    }
                },
            )
            OutlinedTextField(
                value = customHex,
                onValueChange = { customHex = it.take(7) },
                label = { Text(stringResource(R.string.custom_hex_color)) },
                placeholder = { Text("#RRGGBB") },
                singleLine = true,
                isError = customHex.isNotBlank() && contrastSafeCustom == null,
                supportingText = {
                    if (parsedCustom == null && customHex.isNotBlank()) {
                        Text(stringResource(R.string.invalid_hex_color))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { contrastSafeCustom?.let(onColorSelected) },
                enabled = contrastSafeCustom != null,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.apply_color))
            }
        }
    }
}

@Composable
private fun swatchBorderColor(
    argb: Long,
    selected: Boolean,
    scheme: ColorScheme,
): Color {
    if (selected) return scheme.onSurface
    return when (argb) {
        OPAQUE_BLACK_ARGB, OPAQUE_WHITE_ARGB -> scheme.onSurfaceVariant
        else -> scheme.outline
    }
}
