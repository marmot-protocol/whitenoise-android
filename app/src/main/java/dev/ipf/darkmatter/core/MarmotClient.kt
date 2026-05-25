package dev.ipf.darkmatter.core

import android.content.Context
import io.crates.keyring.Keyring
import java.io.File
import dev.ipf.marmotkit.Marmot

class MarmotClient(
    context: Context,
    relayUrls: List<String> = bootstrapRelays,
) {
    init {
        Keyring.initializeNdkContext(context.applicationContext)
    }

    val rootPath: String = File(context.filesDir, "Marmot")
        .apply { mkdirs() }
        .absolutePath

    val marmot: Marmot = Marmot(rootPath, relayUrls)

    companion object {
        val bootstrapRelays = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
        )
    }
}
