package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AuditLogSettingsFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AuditLogSettingsSerializationTest {
    @Test
    fun concurrentUpdatesArePersistedInCallOrder() =
        runTest {
            val initial = AuditLogSettingsFfi(enabled = true)
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
                        if (!requested.enabled) {
                            firstPersistEntered.complete(Unit)
                            releaseFirstPersist.await()
                        }
                        engine.enabled = requested.enabled
                        requested
                    },
                )
            }

            val disableLogging =
                async {
                    serializedUpdate { it.copy(enabled = false) }
                }
            val enableLogging =
                async {
                    firstPersistEntered.await()
                    serializedUpdate { it.copy(enabled = true) }
                }

            firstPersistEntered.await()
            releaseFirstPersist.complete(Unit)
            disableLogging.await()
            enableLogging.await()

            assertEquals(AuditLogSettingsFfi(enabled = true), cached)
            assertEquals(cached, engine)
        }
}
