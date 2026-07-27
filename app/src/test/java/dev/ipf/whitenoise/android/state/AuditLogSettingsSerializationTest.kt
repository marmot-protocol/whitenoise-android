package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AuditDataModeFfi
import dev.ipf.marmotkit.AuditLogSettingsFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AuditLogSettingsSerializationTest {
    @Test
    fun serializedConcurrentUpdatesPreserveEnabledAndDataMode() =
        runTest {
            val initial =
                AuditLogSettingsFfi(
                    enabled = true,
                    dataMode = AuditDataModeFfi.FULL_DATA,
                )
            var cached: AuditLogSettingsFfi? = initial
            val engine = initial.copy()
            val mutex = Mutex()
            val firstPersistEntered = CompletableDeferred<Unit>()
            val releaseFirstPersist = CompletableDeferred<Unit>()

            suspend fun serializedUpdate(transform: (AuditLogSettingsFfi) -> AuditLogSettingsFfi) {
                updateAuditLogSettingsSerialized(
                    mutex = mutex,
                    cachedSettings = { cached },
                    storeCachedSettings = { cached = it },
                    loadFromEngine = { engine },
                    transform = transform,
                    persistToEngine = { requested ->
                        if (requested.enabled == false && requested.dataMode == AuditDataModeFfi.FULL_DATA) {
                            firstPersistEntered.complete(Unit)
                            releaseFirstPersist.await()
                        }
                        engine.enabled = requested.enabled
                        engine.dataMode = requested.dataMode
                        requested
                    },
                )
            }

            val disableLogging =
                async {
                    serializedUpdate { AuditLogSettingsPolicy.settingsWithEnabled(it, enabled = false) }
                }
            val enableRedaction =
                async {
                    firstPersistEntered.await()
                    serializedUpdate { AuditLogSettingsPolicy.settingsWithRedaction(it, redact = true) }
                }

            firstPersistEntered.await()
            releaseFirstPersist.complete(Unit)
            disableLogging.await()
            enableRedaction.await()

            assertEquals(
                AuditLogSettingsFfi(
                    enabled = false,
                    dataMode = AuditDataModeFfi.OBFUSCATED_SENSITIVE_DATA,
                ),
                cached,
            )
            assertEquals(cached, engine)
        }
}
