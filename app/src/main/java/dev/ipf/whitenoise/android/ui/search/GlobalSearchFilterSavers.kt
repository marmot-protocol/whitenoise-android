package dev.ipf.whitenoise.android.ui.search

import androidx.compose.runtime.saveable.Saver
import dev.ipf.whitenoise.android.search.GlobalSearchContentFilterSelection
import dev.ipf.whitenoise.android.search.GlobalSearchDateFilterSelection
import dev.ipf.whitenoise.android.search.decodeGlobalSearchContentFilter
import dev.ipf.whitenoise.android.search.decodeGlobalSearchDateFilter
import dev.ipf.whitenoise.android.search.encodeGlobalSearchContentFilter
import dev.ipf.whitenoise.android.search.encodeGlobalSearchDateFilter

internal val GlobalSearchDateFilterSelectionSaver: Saver<GlobalSearchDateFilterSelection, String> =
    Saver(
        save = { selection -> encodeGlobalSearchDateFilter(selection) },
        restore = { encoded -> decodeGlobalSearchDateFilter(encoded) },
    )

internal val GlobalSearchContentFilterSelectionSaver: Saver<GlobalSearchContentFilterSelection, String> =
    Saver(
        save = { selection -> encodeGlobalSearchContentFilter(selection) },
        restore = { encoded -> decodeGlobalSearchContentFilter(encoded) },
    )
