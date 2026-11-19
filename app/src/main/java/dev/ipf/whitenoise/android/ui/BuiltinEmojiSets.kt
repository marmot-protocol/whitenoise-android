package dev.ipf.whitenoise.android.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Emoji sets shipped in the APK. Each one has its own picker section, and the user can remove it from
 * the picker. Its artwork stays bundled, so a removed set's emoji still render when someone sends them.
 */
internal enum class BuiltinEmojiSet(
    override val id: String,
    @StringRes val titleRes: Int,
    val shortcodes: List<String>,
) : EmojiPickerTab {
    WhiteNoise("set.whitenoise", R.string.white_noise, listOf(":wn:")),
    Marmot("set.marmot", R.string.emoji_name_marmot, EmojiShortcodes.builtins - ":wn:"),
    ;

    /** The emoji that stands for the set on its picker tab. */
    val iconEmoji: String get() = shortcodes.first()

    companion object {
        /** The set that ships [shortcode], or null for any other emoji. */
        fun forShortcode(shortcode: String): BuiltinEmojiSet? = entries.firstOrNull { shortcode in it.shortcodes }
    }
}

/** Pre-installed sets the user removed from the picker on this device. */
internal val LocalHiddenBuiltinEmojiSets = compositionLocalOf { emptySet<BuiltinEmojiSet>() }

/** Whether [emoji] belongs to a pre-installed set the user removed. */
internal fun Set<BuiltinEmojiSet>.hides(emoji: String): Boolean = any { emoji in it.shortcodes }

/** Device-wide choice of which pre-installed sets the picker leaves out. */
internal class BuiltinEmojiSetChoices(
    private val context: Context,
) {
    private var loaded = false

    var hidden: Set<BuiltinEmojiSet> by mutableStateOf(emptySet())
        private set

    /** Reads the stored choice once per process. */
    suspend fun load() {
        if (loaded) {
            return
        }
        val stored = withContext(Dispatchers.IO) { readHidden(context) }
        if (!loaded) {
            hidden = stored
            loaded = true
        }
    }

    /** Removes [set] from the picker, or restores it. */
    fun setHidden(
        set: BuiltinEmojiSet,
        hide: Boolean,
    ) {
        hidden = if (hide) hidden + set else hidden - set
        loaded = true
        context
            .preferences()
            .edit()
            .putString(HiddenKey, hidden.joinToString(Separator) { it.id })
            .apply()
    }

    private companion object {
        const val PreferencesName = "whitenoise_ui"
        const val HiddenKey = "hidden_builtin_emoji_sets"
        const val Separator = "\n"

        fun readHidden(context: Context): Set<BuiltinEmojiSet> {
            val ids =
                context
                    .preferences()
                    .getString(HiddenKey, null)
                    ?.split(Separator)
                    .orEmpty()
                    .toSet()
            return BuiltinEmojiSet.entries.filterTo(mutableSetOf()) { it.id in ids }
        }

        fun Context.preferences() = applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
    }
}

/** The process-wide hidden set choice, loaded off the main thread. */
@Composable
internal fun rememberBuiltinEmojiSetChoices(context: Context = LocalContext.current): BuiltinEmojiSetChoices {
    val application = context.applicationContext as WhiteNoiseApplication
    val choices = remember(application) { application.builtinEmojiSetChoices }
    LaunchedEffect(choices) {
        choices.load()
    }
    return choices
}
