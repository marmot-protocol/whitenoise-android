package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentAutomaticPermissionFfi
import dev.ipf.marmotkit.MarmotInterface
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class NativeAttachmentPermissionsTest {
    /** Every media type is denied before validated connectivity or during user pause. */
    @Test
    fun permissionRespectsValidationPauseAndMostRestrictiveNetwork() {
        val wifi = setOf(MediaAutoDownloadNetwork.WiFi)
        val matrix = MediaAutoDownloadMatrix.DEFAULT
        val denied = AttachmentAutomaticPermissionFfi(false, false, false, false)
        assertEquals(denied, matrix.nativePermission(wifi, validated = false, paused = false))
        assertEquals(denied, matrix.nativePermission(wifi, validated = true, paused = true))
        assertEquals(denied, matrix.nativePermission(emptySet(), validated = true, paused = false))
        val imagesOnly =
            MediaAutoDownloadMatrix(emptySet()).withToggle(
                MediaAutoDownloadType.Image,
                MediaAutoDownloadNetwork.WiFi,
                true,
            )
        val permission = imagesOnly.nativePermission(wifi, validated = true, paused = false)
        assertTrue(permission.images)
        assertFalse(permission.audio || permission.videos || permission.files)
        assertEquals(denied, imagesOnly.nativePermission(wifi + MediaAutoDownloadNetwork.Metered, true, false))
    }

    /** A newer host event invalidates old evaluation without minting another native generation. */
    @Test
    fun staleEvaluationCannotRestorePermission() =
        runTest {
            val owner = NativeAttachmentPermissions()
            val calls = mutableListOf<String>()
            val engine =
                nativeBoundary { method, _ ->
                    calls += method
                    when (method) {
                        "beginAttachmentPermissionUpdate" -> "generation"
                        else -> error("stale evaluation must not grant: $method")
                    }
                }
            owner.update(owner.invalidate(engine), engine, engine, listOf("account")) {
                owner.invalidate(engine)
                AttachmentAutomaticPermissionFfi(true, true, true, true)
            }
            assertEquals(listOf("beginAttachmentPermissionUpdate"), calls)
        }

    /** A rejected single-use generation is discarded rather than retried by an obsolete callback. */
    @Test
    fun falseGrantIsNotReminted() =
        runTest {
            val owner = NativeAttachmentPermissions()
            val calls = mutableListOf<String>()
            val engine =
                nativeBoundary { method, args ->
                    calls += method
                    when (method) {
                        "beginAttachmentPermissionUpdate" -> "captured"
                        "setAttachmentAutomaticPermission" -> {
                            assertEquals("captured", args[1])
                            false
                        }
                        else -> error(method)
                    }
                }
            owner.update(owner.invalidate(engine), engine, engine, listOf("account", "account")) {
                AttachmentAutomaticPermissionFfi(false, false, false, false)
            }
            assertEquals(listOf("beginAttachmentPermissionUpdate", "setAttachmentAutomaticPermission"), calls)
        }

    /** One unavailable account neither strands healthy accounts revoked nor suppresses a retry. */
    @Test
    fun accountFailureIsIsolatedAndReturnedForRetry() =
        runTest {
            val owner = NativeAttachmentPermissions()
            val calls = mutableListOf<String>()
            var firstFailure = true
            val engine =
                nativeBoundary { method, args ->
                    val account = args.firstOrNull() as? String
                    calls += "$method:$account"
                    when (method) {
                        "beginAttachmentPermissionUpdate" -> {
                            if (account == "broken" && firstFailure) {
                                firstFailure = false
                                throw IllegalStateException("temporarily unavailable")
                            }
                            "generation-$account"
                        }
                        "setAttachmentAutomaticPermission" -> true
                        else -> error(method)
                    }
                }
            val revision = owner.invalidate(engine)

            val failed =
                owner.update(revision, engine, engine, listOf("broken", "healthy")) {
                    AttachmentAutomaticPermissionFfi(true, false, false, false)
                }
            assertEquals(setOf("broken"), failed)
            assertTrue("setAttachmentAutomaticPermission:healthy" in calls)

            val retryFailed =
                owner.update(revision, engine, engine, failed.toList()) {
                    AttachmentAutomaticPermissionFfi(true, false, false, false)
                }
            assertTrue(retryFailed.isEmpty())
            assertTrue("setAttachmentAutomaticPermission:broken" in calls)
        }

    /** A replacement runtime invalidates evaluation even when host inputs retain the same values. */
    @Test
    fun replacementRuntimeCannotReceiveAStaleGrant() =
        runTest {
            val permissions = NativeAttachmentPermissions()
            val calls = mutableListOf<String>()
            val original =
                nativeBoundary { method, _ ->
                    calls += method
                    when (method) {
                        "beginAttachmentPermissionUpdate" -> "old-generation"
                        else -> error("replacement must fence this call: $method")
                    }
                }
            val replacement = nativeBoundary { method, _ -> error("unexpected replacement call: $method") }
            val revision = permissions.invalidate(original)

            permissions.update(revision, original, original, listOf("account")) {
                permissions.invalidate(replacement)
                AttachmentAutomaticPermissionFfi(true, true, true, true)
            }

            assertEquals(listOf("beginAttachmentPermissionUpdate"), calls)
            assertFalse(permissions.isCurrent(revision, original))
        }
}

/** Dispatches only explicitly scripted binding calls, with no native runtime or unsafe fake constructor. */
internal fun nativeBoundary(handler: (String, Array<out Any?>) -> Any?): MarmotInterface {
    val type = MarmotInterface::class.java
    return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
        handler(method.name.substringBefore('-'), args.orEmpty())
    } as MarmotInterface
}
