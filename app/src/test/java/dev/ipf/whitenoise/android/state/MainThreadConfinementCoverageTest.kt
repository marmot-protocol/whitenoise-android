package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainThreadConfinementCoverageTest {
    @Test
    fun mediaLruAccessIsPrivateAndGuardedAtTheNarrowBoundary() {
        val source = appStateSource().readText()

        assertTrue("the non-thread-safe plaintext LRU must not be exposed", "private val mediaPlaintextCache" in source)
        assertTrue("the non-thread-safe thumbnail LRU must not be exposed", "private val mediaThumbnailCache" in source)

        mapOf(
            "cachedMediaPlaintext" to "mediaPlaintextCache.get",
            "cacheMediaPlaintext" to "mediaPlaintextCache.put",
            "cachedMediaThumbnail" to "mediaThumbnailCache.get",
            "cacheMediaThumbnail" to "mediaThumbnailCache.put",
            "removeMediaMemoryCacheEntry" to "mediaPlaintextCache.remove",
            "clearInMemoryMediaCaches" to "mediaPlaintextCache.clear",
        ).forEach { (functionName, mutation) ->
            assertGuardPrecedesMutation(source.functionSection(functionName), functionName, mutation)
        }
        assertGuardPrecedesMutation(
            source.functionSection("removeMediaMemoryCacheEntry"),
            "removeMediaMemoryCacheEntry",
            "mediaThumbnailCache.remove",
        )
        assertGuardPrecedesMutation(
            source.functionSection("clearInMemoryMediaCaches"),
            "clearInMemoryMediaCaches",
            "mediaThumbnailCache.clear",
        )
    }

    @Test
    fun controllerMediaPathsUseTheGuardedAppStateBoundary() {
        val source = controllersSource().readText()

        assertFalse(
            "controllers must not bypass the guarded plaintext LRU boundary",
            Regex("""appState\.mediaPlaintextCache\b""").containsMatchIn(source),
        )
        assertFalse(
            "controllers must not bypass the guarded thumbnail LRU boundary",
            Regex("""appState\.mediaThumbnailCache\b""").containsMatchIn(source),
        )

        mapOf(
            "performMediaUpload" to listOf("cacheMediaPlaintext", "cacheMediaThumbnail"),
            "evictExpiredMediaCaches" to listOf("removeMediaMemoryCacheEntry"),
            "hasCachedAttachment" to listOf("cachedMediaPlaintext"),
            "evictCachedAttachment" to listOf("removeMediaMemoryCacheEntry"),
            "downloadAttachment" to listOf("cachedMediaPlaintext", "cacheMediaPlaintext"),
            "thumbnailFor" to listOf("cachedMediaThumbnail"),
            "cacheThumbnail" to listOf("cacheMediaThumbnail"),
            "handoffOwnMediaCacheOnReconcile" to listOf("cacheMediaPlaintext", "cacheMediaThumbnail"),
        ).forEach { (functionName, guardedCalls) ->
            val body = source.functionSection(functionName)
            guardedCalls.forEach { guardedCall ->
                assertTrue("$functionName must route through $guardedCall", guardedCall in body)
            }
        }

        val eviction = source.functionSection("removeMediaMemoryCacheKeys")
        assertTrue("L1 eviction must dispatch before touching the caches", "withContext(dispatcher)" in eviction)
        assertTrue("both L1 tiers must be removed through one guarded boundary", "cacheKeys.forEach(removeEntry)" in eviction)
    }

    @Test
    fun mixedMediaPathsConfineL1ToMainAndKeepSlowWorkOffMain() {
        val source = controllersSource().readText()

        listOf("evictExpiredMediaCaches", "evictCachedAttachment").forEach { functionName ->
            val body = source.functionSection(functionName)
            assertTrue("$functionName must remove L1 entries on Main", "Dispatchers.Main.immediate" in body)
            assertTrue("$functionName must keep L2 removal on IO", "Dispatchers.IO" in body)
        }

        val download = source.functionSection("downloadAttachment")
        assertTrue(
            "downloadAttachment must dispatch both caller-context L1 operations to Main",
            Regex("""withContext\(Dispatchers\.Main\.immediate\)""").findAll(download).count() >= 2,
        )
        assertTrue("downloadAttachment must keep disk access on IO", "withContext(Dispatchers.IO)" in download)

        val decode = source.functionSection("decodeMediaThumbnailOffMain")
        assertTrue("thumbnail decoding must stay on Default", "withContext(Dispatchers.Default)" in decode)
    }

    @Test
    fun profilePublicationAndCacheClearsAssertBeforeComposeMutation() {
        val source = appStateSource().readText()

        mapOf(
            "applyProfilePresentation" to "profilePresentations.put",
            "notifyProfilesChanged" to "profilePresentations.clear",
            "clearCrossAccountCaches" to "accountScopedCaches.clearAll",
        ).forEach { (functionName, mutation) ->
            assertGuardPrecedesMutation(source.functionSection(functionName), functionName, mutation)
        }
        assertGuardPrecedesMutation(
            source.functionSection("applyProfilePresentation"),
            "applyProfilePresentation",
            "userProfiles.put",
        )
    }

    @Test
    fun intentionallyOffMainReadsRemainOutsideTheAssertionBoundary() {
        val source = appStateSource().readText()
        val networkRegistration = source.functionSection("registerActiveNetworkListener")
        assertFalse("ConnectivityManager Binder work intentionally runs off-main", "assertMainThread" in networkRegistration)

        val materialization = source.functionSection("materializeProfileLocally")
        assertFalse("profile FFI reads intentionally run off-main", "assertMainThread" in materialization)
        assertTrue("profile reads must stay on marmotIo", "marmotIo" in materialization)
        assertTrue("profile publication must resume on Main", "withContext(Dispatchers.Main.immediate)" in materialization)
        assertTrue("only the publication helper owns the main-thread assertion", "applyProfilePresentation" in materialization)

        val refresh = source.functionSection("refreshProfile")
        assertTrue("profile refresh reads must stay on marmotIo", "marmotIo" in refresh)
        assertTrue("profile refresh publication must resume on Main", "withContext(Dispatchers.Main.immediate)" in refresh)
    }

    private fun assertGuardPrecedesMutation(
        body: String,
        functionName: String,
        mutation: String,
    ) {
        val guardIndex = body.indexOf("assertMainThread")
        val mutationIndex = body.indexOf(mutation)
        assertTrue("$functionName must contain an assertMainThread tripwire", guardIndex >= 0)
        assertTrue("$functionName must contain $mutation", mutationIndex >= 0)
        assertTrue("$functionName must assert before $mutation", guardIndex < mutationIndex)
    }

    private fun String.functionSection(functionName: String): String {
        val declaration =
            Regex("""\bfun\s+${Regex.escape(functionName)}\s*\(""")
                .find(this)
                ?: error("Missing function $functionName")
        val nextDeclaration =
            Regex("""\n\s*(?:(?:private|internal|public|protected)\s+)?(?:(?:suspend|inline|operator|override)\s+)*fun\s+\w+\s*\(""")
                .find(this, declaration.range.last + 1)
                ?.range
                ?.first
                ?: length
        return substring(declaration.range.first, nextDeclaration)
    }

    private fun appStateSource(): File = sourceFile("AppState.kt")

    private fun controllersSource(): File = sourceFile("Controllers.kt")

    private fun sourceFile(name: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/$name"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/$name"),
        ).firstOrNull(File::exists)
            ?: error("Missing $name source file")
}
