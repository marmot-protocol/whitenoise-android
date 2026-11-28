package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import dev.ipf.whitenoise.android.ui.navigation.ShellRoute
import dev.ipf.whitenoise.android.ui.navigation.decodeSettingsChain
import dev.ipf.whitenoise.android.ui.navigation.encodeSettingsChain
import dev.ipf.whitenoise.android.ui.navigation.settingsChain

/**
 * Saves a Settings page stack, home first, the way the shell saves it, for tests that drive [SettingsScreen]
 * directly and keep its stack across a state restoration.
 */
internal val SettingsTestStackSaver: Saver<List<ShellRoute.Settings>, Any> =
    listSaver(
        save = { encodeSettingsChain(it).orEmpty() },
        restore = { saved ->
            val chain = decodeSettingsChain(saved).orEmpty()
            chain.lastOrNull()?.let { top -> settingsChain(top.detail, top.arg, chain.dropLast(1)) }
                ?: settingsChain(null)
        },
    )
