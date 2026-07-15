package dev.ipf.whitenoise.android.ui.settings

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
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.parseOpaqueColorHex
import dev.ipf.whitenoise.android.state.readableTextArgb
import dev.ipf.whitenoise.android.state.tonalBubbleColorPresets
import dev.ipf.whitenoise.android.ui.common.SectionCard
import dev.ipf.whitenoise.android.ui.conversation.messages.BubblePresentationTokens
import dev.ipf.whitenoise.android.ui.conversation.messages.colorFromArgb
import dev.ipf.whitenoise.android.ui.conversation.messages.resolveBubblePresentationArgb
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatBubbleColorsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    groupIdHex: String? = null,
) {
    val bubbleTheme = BubbleTheme.resolve(appState.themeMode, isSystemInDarkTheme())
    val pickerScopeKey = groupIdHex?.let { "chat:$it" } ?: "global"
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
                SectionCard(title = stringResource(R.string.bubble_color_preview)) {
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
            item {
                SectionCard(title = stringResource(R.string.bubble_my_messages)) {
                    TonalSwatchPicker(
                        selectedArgb = selectedColor(BubbleSide.Mine),
                        onColorSelected = { updateColor(BubbleSide.Mine, it) },
                        scopeKey = pickerScopeKey,
                        theme = bubbleTheme,
                        side = BubbleSide.Mine,
                    )
                }
            }
            item {
                SectionCard(title = stringResource(R.string.bubble_other_messages)) {
                    TonalSwatchPicker(
                        selectedArgb = selectedColor(BubbleSide.Other),
                        onColorSelected = { updateColor(BubbleSide.Other, it) },
                        scopeKey = pickerScopeKey,
                        theme = bubbleTheme,
                        side = BubbleSide.Other,
                    )
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
            errorBackgroundArgb = scheme.errorContainer.toArgb().toLong() and 0xFFFFFFFFL,
            errorContentArgb = scheme.onErrorContainer.toArgb().toLong() and 0xFFFFFFFFL,
            surfaceBackgroundArgb = scheme.surfaceVariant.toArgb().toLong() and 0xFFFFFFFFL,
            surfaceContentArgb = scheme.onSurfaceVariant.toArgb().toLong() and 0xFFFFFFFFL,
            mineBackgroundArgb = scheme.primaryContainer.toArgb().toLong() and 0xFFFFFFFFL,
            mineContentArgb = scheme.onPrimaryContainer.toArgb().toLong() and 0xFFFFFFFFL,
        )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        PreviewBubble(
            text = stringResource(R.string.bubble_preview_other),
            mine = false,
            presentation = resolveBubblePresentationArgb(false, false, amoled, false, otherOverrideArgb, tokens),
        )
        PreviewBubble(
            text = stringResource(R.string.bubble_preview_mine),
            mine = true,
            presentation = resolveBubblePresentationArgb(false, false, amoled, true, mineOverrideArgb, tokens),
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
    side: BubbleSide,
) {
    val scheme = MaterialTheme.colorScheme
    val presets =
        remember(scheme) {
            tonalBubbleColorPresets(
                primaryContainerArgb = scheme.primaryContainer.toArgb().toLong() and 0xFFFFFFFFL,
                secondaryContainerArgb = scheme.secondaryContainer.toArgb().toLong() and 0xFFFFFFFFL,
                tertiaryContainerArgb = scheme.tertiaryContainer.toArgb().toLong() and 0xFFFFFFFFL,
                errorContainerArgb = scheme.errorContainer.toArgb().toLong() and 0xFFFFFFFFL,
                inversePrimaryArgb = scheme.inversePrimary.toArgb().toLong() and 0xFFFFFFFFL,
                surfaceArgb = scheme.surface.toArgb().toLong() and 0xFFFFFFFFL,
            )
        }
    var customExpanded by rememberSaveable(scopeKey, theme, side) { mutableStateOf(false) }
    var customHex by rememberSaveable(scopeKey, theme, side, selectedArgb) {
        mutableStateOf(selectedArgb?.let { "#%06X".format(Locale.ROOT, it and 0xFFFFFFL) } ?: "")
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
                        R.string.bubble_color_swatch_content_description,
                        "#%06X".format(Locale.ROOT, argb and 0xFFFFFFL),
                    )
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
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
            val customContentArgb = selectedArgb?.let(::readableTextArgb)
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
