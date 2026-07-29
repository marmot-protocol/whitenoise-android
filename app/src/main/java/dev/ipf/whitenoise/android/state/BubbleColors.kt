package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

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

internal object ActionColorPreferences {
    private const val PREFIX = "action_color:"

    fun readColor(
        preferences: SharedPreferences,
        accountRef: String?,
        theme: BubbleTheme,
    ): Long? {
        val key = key(accountRef, theme) ?: return null
        return if (preferences.contains(key)) {
            normalizeOpaqueArgb(preferences.getLong(key, 0L))
        } else {
            null
        }
    }

    fun writeColor(
        preferences: SharedPreferences,
        accountRef: String?,
        theme: BubbleTheme,
        argb: Long?,
    ) {
        val key = key(accountRef, theme) ?: return
        val normalized = normalizeOpaqueArgb(argb)
        preferences
            .edit()
            .apply {
                if (normalized == null) remove(key) else putLong(key, normalized)
            }.apply()
    }

    private fun key(
        accountRef: String?,
        theme: BubbleTheme,
    ): String? {
        val account = accountRef?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return "$PREFIX$account:${theme.name.lowercase(Locale.ROOT)}"
    }
}

internal data class ActionColorArgb(
    val container: Long,
    val content: Long,
)

internal fun resolveActionColorArgb(
    customArgb: Long?,
    defaultContainerArgb: Long,
    defaultContentArgb: Long,
): ActionColorArgb =
    customArgb
        ?.let { custom -> readableTextArgb(custom)?.let { content -> ActionColorArgb(custom, content) } }
        ?: ActionColorArgb(defaultContainerArgb, defaultContentArgb)

/** Local-only appearance preferences. Chat keys are account-scoped so two local
 * identities can style the same Marmot group independently. */
internal object BubbleColorPreferences {
    private const val GLOBAL_PREFIX = "bubble_color_global:"
    private const val CHAT_PREFIX = "bubble_color_chat:"

    fun readLegacyGlobalColor(
        preferences: SharedPreferences,
        theme: BubbleTheme,
        side: BubbleSide,
    ): Long? = readOpaqueArgb(preferences, legacyGlobalKey(theme, side))

    fun writeLegacyGlobalColor(
        preferences: SharedPreferences,
        theme: BubbleTheme,
        side: BubbleSide,
        argb: Long?,
    ) {
        writeOpaqueArgb(preferences, legacyGlobalKey(theme, side), argb)
    }

    fun readGlobalColor(
        preferences: SharedPreferences,
        accountRef: String?,
        theme: BubbleTheme,
        side: BubbleSide,
    ): Long? = globalKey(accountRef, theme, side)?.let { readOpaqueArgb(preferences, it) }

    fun writeGlobalColor(
        preferences: SharedPreferences,
        accountRef: String?,
        theme: BubbleTheme,
        side: BubbleSide,
        argb: Long?,
    ) {
        val key = globalKey(accountRef, theme, side) ?: return
        writeOpaqueArgb(preferences, key, argb)
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

    internal fun legacyGlobalKey(
        theme: BubbleTheme,
        side: BubbleSide,
    ): String = "$GLOBAL_PREFIX${theme.preferenceSuffix}:${side.preferenceSuffix}"

    internal fun globalKey(
        accountRef: String?,
        theme: BubbleTheme,
        side: BubbleSide,
    ): String? {
        val account = accountRef?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return "$GLOBAL_PREFIX$account:${theme.preferenceSuffix}:${side.preferenceSuffix}"
    }

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

internal object LegacyBubbleColorMigration {
    fun migrate(
        preferences: SharedPreferences,
        accountRefs: Collection<String>,
    ): Boolean {
        val accounts =
            accountRefs
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
        val editor = preferences.edit()
        var hasLegacyValues = false

        for (theme in BubbleTheme.entries) {
            for (side in BubbleSide.entries) {
                val legacyKey = BubbleColorPreferences.legacyGlobalKey(theme, side)
                if (!preferences.contains(legacyKey)) continue
                hasLegacyValues = true
                BubbleColorPreferences
                    .readLegacyGlobalColor(preferences, theme, side)
                    ?.let { copyToAccounts(preferences, editor, accounts, theme, side, it) }
                editor.remove(legacyKey)
            }
        }
        return hasLegacyValues && editor.commit()
    }

    private fun copyToAccounts(
        preferences: SharedPreferences,
        editor: SharedPreferences.Editor,
        accounts: List<String>,
        theme: BubbleTheme,
        side: BubbleSide,
        legacyColor: Long,
    ) {
        for (account in accounts) {
            val key = checkNotNull(BubbleColorPreferences.globalKey(account, theme, side))
            if (!preferences.contains(key)) {
                editor.putLong(key, legacyColor)
            }
        }
    }
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
 * Fixed quick-swatch palette spanning distinct hue and luminance bands. Black
 * and white anchors are always included; every entry passes [readableTextArgb].
 * Theme Material roles are intentionally not blended here — surface-container
 * roles collapse on AMOLED and produced near-duplicate swatches (#1699).
 */
private val GOLDEN_BUBBLE_COLOR_PRESETS =
    listOf(
        OPAQUE_BLACK_ARGB,
        OPAQUE_WHITE_ARGB,
        0xFFB91C1CL,
        0xFFC2410CL,
        0xFFA16207L,
        0xFF15803DL,
        0xFF0E7490L,
        0xFF1D4ED8L,
        0xFF6D28D9L,
        0xFFBE185DL,
    )

internal fun tonalBubbleColorPresets(): List<Long> = GOLDEN_BUBBLE_COLOR_PRESETS

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
