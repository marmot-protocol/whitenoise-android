package dev.ipf.whitenoise.android.state

import org.json.JSONArray
import org.json.JSONObject

private const val MAX_STORED_COMPOSER_SHORTCUTS = 128

// Version 3 adds voice placement on Send; earlier records decode with it off.
private const val COMPOSER_TOOLBAR_RECORD_VERSION = 3

/** Stable presentation IDs only: callbacks, accounts and current capability never enter storage. */
internal enum class ComposerShortcut(
    val id: String,
) {
    SendingIdentity("sending_identity"),
    More("more"),
    Emoji("emoji"),
    Paste("paste"),
    Dictation("dictation"),
    VoiceNote("voice_note"),
    Camera("camera"),
    Gallery("gallery"),
    Files("files"),
    Location("location"),
    Person("person"),
    Contact("contact"),
    Poll("poll"),
}

internal enum class ComposerToolbarLayout(
    val id: String,
) {
    Automatic("automatic"),
    AlwaysExpanded("always_expanded"),
}

internal enum class ComposerShortcutSide { Left, Right }

/** Voice notes either share Send's slot while the composer is empty, sit in the toolbar, or stay hidden. */
internal enum class ComposerVoicePlacement { Send, Left, Right, Off }

internal data class ComposerToolbarSettings(
    val shortcuts: List<ComposerShortcut> = defaultComposerShortcuts,
    val layout: ComposerToolbarLayout = ComposerToolbarLayout.Automatic,
    val rightShortcuts: Set<ComposerShortcut> = defaultRightComposerShortcuts,
    val voiceOnSend: Boolean = true,
) {
    /** Retains the saved overflow position; runtime hides More when its menu would be empty. */
    fun normalized(): ComposerToolbarSettings {
        val unique = shortcuts.distinct()
        return copy(
            shortcuts = if (ComposerShortcut.More in unique) unique else listOf(ComposerShortcut.More) + unique,
            // A pinned voice shortcut always wins over Send placement.
            voiceOnSend = voiceOnSend && ComposerShortcut.VoiceNote !in unique,
        )
    }

    /** Where the voice launcher appears; a pinned voice shortcut always wins. */
    val voicePlacement: ComposerVoicePlacement
        get() =
            when {
                ComposerShortcut.VoiceNote in shortcuts ->
                    if (side(ComposerShortcut.VoiceNote) == ComposerShortcutSide.Right) {
                        ComposerVoicePlacement.Right
                    } else {
                        ComposerVoicePlacement.Left
                    }
                voiceOnSend -> ComposerVoicePlacement.Send
                else -> ComposerVoicePlacement.Off
            }

    /** Moves the voice launcher between Send's slot, either toolbar side, or nowhere. */
    fun withVoicePlacement(placement: ComposerVoicePlacement): ComposerToolbarSettings =
        when (placement) {
            ComposerVoicePlacement.Send -> copy(voiceOnSend = true, shortcuts = shortcuts - ComposerShortcut.VoiceNote)
            ComposerVoicePlacement.Off -> copy(voiceOnSend = false, shortcuts = shortcuts - ComposerShortcut.VoiceNote)
            ComposerVoicePlacement.Left, ComposerVoicePlacement.Right -> {
                val side =
                    if (placement == ComposerVoicePlacement.Left) {
                        ComposerShortcutSide.Left
                    } else {
                        ComposerShortcutSide.Right
                    }
                if (ComposerShortcut.VoiceNote in shortcuts) {
                    withSide(ComposerShortcut.VoiceNote, side)
                } else {
                    insertOnSide(ComposerShortcut.VoiceNote, side, Int.MAX_VALUE)
                }
            }
        }

    /** Removes an optional shortcut; the saved More position remains available for future hidden attachments. */
    fun without(shortcut: ComposerShortcut): ComposerToolbarSettings =
        if (shortcut ==
            ComposerShortcut.More
        ) {
            this
        } else {
            copy(shortcuts = shortcuts - shortcut)
        }

    /** Moves or adds a shortcut at a bounded index without introducing duplicates. */
    fun insert(
        shortcut: ComposerShortcut,
        index: Int,
    ): ComposerToolbarSettings {
        val remaining = shortcuts - shortcut
        return copy(shortcuts = remaining.toMutableList().apply { add(index.coerceIn(0, size), shortcut) }).normalized()
    }

    /** Returns the shortcut's persisted physical side without applying layout direction. */
    fun side(shortcut: ComposerShortcut): ComposerShortcutSide {
        val isRight = shortcut in rightShortcuts
        return if (isRight) ComposerShortcutSide.Right else ComposerShortcutSide.Left
    }

    /** Keeps saved shortcut order while selecting one physical side. */
    fun onSide(side: ComposerShortcutSide): List<ComposerShortcut> = shortcuts.filter { this.side(it) == side }

    /** Changes a shortcut's physical side without changing its saved order. */
    fun withSide(
        shortcut: ComposerShortcut,
        side: ComposerShortcutSide,
    ): ComposerToolbarSettings =
        copy(
            rightShortcuts =
                if (side == ComposerShortcutSide.Right) rightShortcuts + shortcut else rightShortcuts - shortcut,
        )

    /** Inserts a shortcut at a bounded position among its destination-side siblings. */
    fun insertOnSide(
        shortcut: ComposerShortcut,
        side: ComposerShortcutSide,
        index: Int,
    ): ComposerToolbarSettings {
        val remaining = shortcuts - shortcut
        val siblings = remaining.filter { this.side(it) == side }
        val anchor = siblings.getOrNull(index.coerceAtLeast(0))
        val target =
            if (anchor != null) {
                remaining.indexOf(anchor)
            } else {
                siblings.lastOrNull()?.let { remaining.indexOf(it) + 1 } ?: remaining.size
            }
        return insert(shortcut, target).withSide(shortcut, side)
    }

    /** Serializes stable presentation preferences, excluding runtime account and action state. */
    fun encode(): String =
        JSONObject()
            .put("version", COMPOSER_TOOLBAR_RECORD_VERSION)
            .put("shortcuts", JSONArray(normalized().shortcuts.map { it.id }))
            .put("layout", layout.id)
            .put("rightShortcuts", JSONArray(rightShortcuts.map { it.id }))
            .put("voiceOnSend", normalized().voiceOnSend)
            .toString()

    companion object {
        /** Unknown IDs are ignored without scrambling surviving choices; malformed records reset safely. */
        fun decode(encoded: String?): ComposerToolbarSettings {
            if (encoded == null) return ComposerToolbarSettings()
            return runCatching {
                val record = JSONObject(encoded)
                val version = record.get("version")
                require(version is Int)
                require(version in 1..COMPOSER_TOOLBAR_RECORD_VERSION)
                val array = record.getJSONArray("shortcuts")
                require(array.length() <= MAX_STORED_COMPOSER_SHORTCUTS)
                val ids = (0 until array.length()).map { index -> array.get(index).also { require(it is String) } }
                val shortcuts = ids.mapNotNull { id -> ComposerShortcut.entries.firstOrNull { it.id == id } }
                val layout =
                    ComposerToolbarLayout.entries.firstOrNull { it.id == record.optString("layout") }
                        ?: ComposerToolbarLayout.Automatic
                val right =
                    if (version == 1) {
                        // Version 1 did not store sides; retain its original physical assignments.
                        setOf(
                            ComposerShortcut.Emoji,
                            ComposerShortcut.Paste,
                            ComposerShortcut.Dictation,
                            ComposerShortcut.VoiceNote,
                        )
                    } else {
                        val sides = record.getJSONArray("rightShortcuts")
                        require(sides.length() <= MAX_STORED_COMPOSER_SHORTCUTS)
                        (0 until sides.length())
                            .mapNotNull { index ->
                                val id = sides.get(index).also { require(it is String) }
                                ComposerShortcut.entries.firstOrNull { it.id == id }
                            }.toSet()
                    }
                // Earlier versions had no Send placement; keep their pinned or hidden voice choice.
                val voiceOnSend = version >= 3 && record.getBoolean("voiceOnSend")
                ComposerToolbarSettings(shortcuts, layout, right, voiceOnSend).normalized()
            }.getOrElse { ComposerToolbarSettings() }
        }
    }
}

internal val defaultComposerShortcuts =
    listOf(
        ComposerShortcut.SendingIdentity,
        ComposerShortcut.More,
        ComposerShortcut.Emoji,
    )

/** Remembered for a voice launcher moved out of Send's slot; Send placement ignores toolbar sides. */
internal val defaultRightComposerShortcuts = setOf(ComposerShortcut.VoiceNote)
