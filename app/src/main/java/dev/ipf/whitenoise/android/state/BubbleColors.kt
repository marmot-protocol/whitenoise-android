package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

internal const val WCAG_AA_NORMAL_TEXT_CONTRAST = 4.5
internal const val OPAQUE_BLACK_ARGB = 0xFF000000L
internal const val OPAQUE_WHITE_ARGB = 0xFFFFFFFFL

internal enum class BubbleTheme {
    Light,
    Dark,
    Amoled,
    ;

    companion object {
        fun resolve(
            mode: AppThemeMode,
            systemDarkTheme: Boolean,
        ): BubbleTheme =
            when (mode) {
                AppThemeMode.Light -> Light
                AppThemeMode.Dark -> Dark
                AppThemeMode.Amoled -> Amoled
                AppThemeMode.System -> if (systemDarkTheme) Dark else Light
            }
    }
}

internal enum class BubbleSide {
    Mine,
    Other,
}

/** Local-only appearance preferences. Chat keys are account-scoped so two local
 * identities can style the same Marmot group independently. */
internal object BubbleColorPreferences {
    private const val GLOBAL_PREFIX = "bubble_color_global:"
    private const val CHAT_PREFIX = "bubble_color_chat:"

    fun readGlobalColor(
        preferences: SharedPreferences,
        theme: BubbleTheme,
        side: BubbleSide,
    ): Long? = readOpaqueArgb(preferences, globalKey(theme, side))

    fun writeGlobalColor(
        preferences: SharedPreferences,
        theme: BubbleTheme,
        side: BubbleSide,
        argb: Long?,
    ) {
        writeOpaqueArgb(preferences, globalKey(theme, side), argb)
    }

    fun readChatColor(
        preferences: SharedPreferences,
        accountRef: String?,
        groupIdHex: String,
        side: BubbleSide,
    ): Long? = chatKey(accountRef, groupIdHex, side)?.let { readOpaqueArgb(preferences, it) }

    fun writeChatColor(
        preferences: SharedPreferences,
        accountRef: String?,
        groupIdHex: String,
        side: BubbleSide,
        argb: Long?,
    ) {
        val key = chatKey(accountRef, groupIdHex, side) ?: return
        writeOpaqueArgb(preferences, key, argb)
    }

    internal fun chatKey(
        accountRef: String?,
        groupIdHex: String,
        side: BubbleSide,
    ): String? {
        val account = accountRef?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val group =
            groupIdHex
                .trim()
                .lowercase(Locale.ROOT)
                .takeIf { it.isNotEmpty() } ?: return null
        return "$CHAT_PREFIX$account:$group:${side.preferenceSuffix}"
    }

    private fun globalKey(
        theme: BubbleTheme,
        side: BubbleSide,
    ): String = "$GLOBAL_PREFIX${theme.preferenceSuffix}:${side.preferenceSuffix}"

    private fun readOpaqueArgb(
        preferences: SharedPreferences,
        key: String,
    ): Long? =
        if (preferences.contains(key)) {
            normalizeOpaqueArgb(preferences.getLong(key, 0L))
        } else {
            null
        }

    private fun writeOpaqueArgb(
        preferences: SharedPreferences,
        key: String,
        argb: Long?,
    ) {
        val normalized = normalizeOpaqueArgb(argb)
        preferences
            .edit()
            .apply {
                if (normalized == null) remove(key) else putLong(key, normalized)
            }.apply()
    }

    private val BubbleTheme.preferenceSuffix: String
        get() = name.lowercase(Locale.ROOT)

    private val BubbleSide.preferenceSuffix: String
        get() = name.lowercase(Locale.ROOT)
}

internal fun resolveBubbleColorArgb(
    chatOverrideArgb: Long?,
    globalOverrideArgb: Long?,
    defaultArgb: Long,
): Long = chatOverrideArgb ?: globalOverrideArgb ?: defaultArgb

internal fun parseOpaqueColorHex(input: String): Long? {
    val rgb = input.trim().removePrefix("#")
    if (rgb.length != 6 || rgb.any { it.digitToIntOrNull(16) == null }) return null
    return OPAQUE_BLACK_ARGB or rgb.toLong(16)
}

/**
 * Builds a compact palette from Material semantic roles without depending on
 * surface-container roles, which all collapse to black in the AMOLED theme.
 * Interleaving the surface blends keeps every role represented before the cap.
 */
internal fun tonalBubbleColorPresets(
    primaryContainerArgb: Long,
    secondaryContainerArgb: Long,
    tertiaryContainerArgb: Long,
    errorContainerArgb: Long,
    inversePrimaryArgb: Long,
    surfaceArgb: Long,
): List<Long> {
    val surface = normalizeOpaqueArgb(surfaceArgb) ?: return emptyList()
    val roles =
        listOf(
            primaryContainerArgb,
            secondaryContainerArgb,
            tertiaryContainerArgb,
            errorContainerArgb,
            inversePrimaryArgb,
        ).mapNotNull(::normalizeOpaqueArgb)

    return TONAL_PRESET_SURFACE_FRACTIONS
        .flatMap { surfaceFraction ->
            roles.map { role -> blendOpaqueArgb(role, surface, surfaceFraction) }
        }.distinct()
        .filter { readableTextArgb(it) != null }
        .take(MAX_TONAL_BUBBLE_PRESETS)
}

private val TONAL_PRESET_SURFACE_FRACTIONS = listOf(0.30, 0.48, 0.64)
private const val MAX_TONAL_BUBBLE_PRESETS = 12

private fun blendOpaqueArgb(
    fromArgb: Long,
    towardArgb: Long,
    towardFraction: Double,
): Long {
    fun blendedChannel(shift: Int): Long {
        val from = (fromArgb shr shift) and 0xFF
        val toward = (towardArgb shr shift) and 0xFF
        return (from + (toward - from) * towardFraction).roundToInt().toLong()
    }

    return OPAQUE_BLACK_ARGB or
        (blendedChannel(16) shl 16) or
        (blendedChannel(8) shl 8) or
        blendedChannel(0)
}

internal fun readableTextArgb(backgroundArgb: Long): Long? {
    val background = normalizeOpaqueArgb(backgroundArgb) ?: return null
    val blackContrast = contrastRatio(OPAQUE_BLACK_ARGB, background)
    val whiteContrast = contrastRatio(OPAQUE_WHITE_ARGB, background)
    return when {
        blackContrast >= whiteContrast && blackContrast >= WCAG_AA_NORMAL_TEXT_CONTRAST -> OPAQUE_BLACK_ARGB
        whiteContrast >= WCAG_AA_NORMAL_TEXT_CONTRAST -> OPAQUE_WHITE_ARGB
        blackContrast >= WCAG_AA_NORMAL_TEXT_CONTRAST -> OPAQUE_BLACK_ARGB
        else -> null
    }
}

internal fun contrastRatio(
    foregroundArgb: Long,
    backgroundArgb: Long,
): Double {
    val foreground = normalizeOpaqueArgb(foregroundArgb) ?: return 0.0
    val background = normalizeOpaqueArgb(backgroundArgb) ?: return 0.0
    val lighter = max(relativeLuminance(foreground), relativeLuminance(background))
    val darker = min(relativeLuminance(foreground), relativeLuminance(background))
    return (lighter + 0.05) / (darker + 0.05)
}

private fun normalizeOpaqueArgb(argb: Long?): Long? =
    argb?.takeIf {
        it in 0L..0xFFFFFFFFL && (it and 0xFF000000L) == 0xFF000000L
    }

private fun relativeLuminance(argb: Long): Double {
    fun channel(shift: Int): Double {
        val srgb = ((argb shr shift) and 0xFF).toDouble() / 255.0
        return if (srgb <= 0.04045) srgb / 12.92 else ((srgb + 0.055) / 1.055).pow(2.4)
    }

    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}
