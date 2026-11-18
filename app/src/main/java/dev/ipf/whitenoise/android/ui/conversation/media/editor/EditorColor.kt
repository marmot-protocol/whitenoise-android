package dev.ipf.whitenoise.android.ui.conversation.media.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R

internal data class EditorColor(
    val argb: Int,
    val name: String,
    val selectedDescription: String,
)

/** The drawing colours the editor offers. */
@Composable
internal fun editorColors(): List<EditorColor> {
    @Composable
    fun color(
        argb: Int,
        nameResource: Int,
    ): EditorColor {
        val name = stringResource(nameResource)
        return EditorColor(argb, name, stringResource(R.string.photo_editor_color_selected, name))
    }
    return listOf(
        color(0xFFFF3B30.toInt(), R.string.photo_editor_color_red),
        color(0xFFFFCC00.toInt(), R.string.photo_editor_color_yellow),
        color(0xFF34C759.toInt(), R.string.photo_editor_color_green),
        color(0xFF007AFF.toInt(), R.string.photo_editor_color_blue),
        color(0xFFFFFFFF.toInt(), R.string.photo_editor_color_white),
    )
}
