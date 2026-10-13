package dev.ipf.whitenoise.android.widget

import androidx.compose.ui.graphics.ImageBitmap

/** Transient rendering data. Only the stable reference is handed back to native-validated configuration. */
internal data class WidgetAccountPresentation(
    val reference: String,
    val seed: String,
    val name: String,
    val pictureUrl: String? = null,
    val active: Boolean = false,
    val picture: ImageBitmap? = null,
)

