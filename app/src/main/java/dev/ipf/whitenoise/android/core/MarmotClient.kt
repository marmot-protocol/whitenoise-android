package dev.ipf.whitenoise.android.core

import android.content.Context
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import java.io.File

class MarmotClient(
    context: Context,
    relayUrls: List<String> = bootstrapRelays,
) {
    init {
        MarmotAndroid.initialize(context.applicationContext)
    }

    val rootPath: String =
        File(context.filesDir, "Marmot")
            .apply { mkdirs() }
            .absolutePath

    val marmot: Marmot = Marmot(rootPath, relayUrls)

    companion object {
        val bootstrapRelays =
            listOf(
                "wss://relay.us.whitenoise.chat",
                "wss://relay.eu.whitenoise.chat",
            )
    }
}
