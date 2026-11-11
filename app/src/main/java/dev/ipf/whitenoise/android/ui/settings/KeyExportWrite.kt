package dev.ipf.whitenoise.android.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * Writes a key export into the document the user picked; false when the provider cannot open or accept it.
 *
 * Opening, writing, flushing and closing a document can block on a slow or remote provider, so all of it runs on IO
 * and the main thread only receives the result. Cancellation before the provider is opened writes nothing.
 */
internal suspend fun writeKeyExport(
    content: String,
    openOutput: () -> OutputStream?,
): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            checkNotNull(openOutput()).bufferedWriter().use { it.write(content) }
        }.isSuccess
    }
