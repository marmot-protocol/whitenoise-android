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
        val source = controllerSources()

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
            "thumbnailFor" to listOf("cachedMediaThumbnail"),
            "cacheThumbnail" to listOf("cacheMediaThumbnail"),
            "handoffOwnMediaCacheOnReconcile" to listOf("cacheMediaPlaintext", "cacheMediaThumbnail"),
        ).forEach { (functionName, guardedCalls) ->
            val body = source.functionSection(functionName)
            guardedCalls.forEach { guardedCall ->
                assertTrue("$functionName must route through $guardedCall", guardedCall in body)
            }
        }
        assertTrue(
            "downloadAttachment must use the shared guarded AppState boundary",
            "downloadAttachmentPlaintext" in source.functionSection("downloadAttachment"),
        )

        val eviction = source.functionSection("removeMediaMemoryCacheKeys")
        assertTrue("L1 eviction must dispatch before touching the caches", "withContext(dispatcher)" in eviction)
        assertTrue("both L1 tiers must be removed through one guarded boundary", "cacheKeys.forEach(removeEntry)" in eviction)

        assertFalse(
            "cache availability must not retain one sticky entry per attachment",
            "attachmentCacheAvailability" in source,
        )
    }

    @Test
    fun mixedMediaPathsConfineL1ToMainAndKeepSlowWorkOffMain() {
        val source = controllerSources()

        listOf("evictExpiredMediaCaches", "evictCachedAttachment").forEach { functionName ->
            val body = source.functionSection(functionName)
            assertTrue("$functionName must remove L1 entries on Main", "Dispatchers.Main.immediate" in body)
            assertTrue("$functionName must keep L2 removal on IO", "Dispatchers.IO" in body)
        }

        val appState = appStateSource().readText()
        val download = appState.functionSection("downloadAttachmentPlaintext")
        assertTrue(
            "downloadAttachmentPlaintext must dispatch caller-context L1 operations to Main",
            Regex("""withContext\(Dispatchers\.Main\.immediate\)""").findAll(download).count() >= 2,
        )
        assertTrue("downloadAttachmentPlaintext must keep disk access on IO", "withContext(Dispatchers.IO)" in download)
        assertTrue(
            "memoized download publication must run on the main-confined mutation scope",
            "mutationsScope.async" in appState.functionSection("memoizedDownload"),
        )

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
        assertTrue(
            "profile materialization must delegate to the shared persisted-profile reader",
            "loadAccountSwitchProfileSeed" in materialization,
        )
        assertTrue("profile publication must resume on Main", "withContext(Dispatchers.Main.immediate)" in materialization)
        assertTrue(
            "materialization must publish through the account-switch seed boundary",
            "applyAccountSwitchProfileSeed" in materialization,
        )

        val seedPublication = source.functionSection("applyAccountSwitchProfileSeed")
        assertTrue(
            "the account-switch seed boundary must route to the guarded presentation helper",
            "applyProfilePresentation" in seedPublication,
        )

        val persistedProfileRead = source.functionSection("loadAccountSwitchProfileSeed")
        assertFalse("persisted profile reads intentionally run off-main", "assertMainThread" in persistedProfileRead)
        assertTrue("persisted profile reads must stay on marmotIo", "marmotIo" in persistedProfileRead)

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

    /** Extracts a named member or extension function body from Kotlin source text. */
    private fun String.functionSection(functionName: String): String {
        val declaration =
            Regex("""\bfun\s+(?:\w+\.)?${Regex.escape(functionName)}\s*\(""")
                .find(this)
                ?: error("Missing function $functionName")
        val nextFunction =
            Regex(
                """\n\s*(?:(?:private|internal|public|protected)\s+)?""" +
                    """(?:(?:suspend|inline|operator|override)\s+)*fun\s+(?:\w+\.)?\w+\s*\(""",
            )
        val nextDeclaration =
            nextFunction
                .find(this, declaration.range.last + 1)
                ?.range
                ?.first
                ?: length
        return substring(declaration.range.first, nextDeclaration)
    }

    private fun appStateSource(): File = sourceFile("AppState.kt")

    /** Reads every source file that contributes attachment-cache behavior to conversation controllers. */
    private fun controllerSources(): String =
        listOf("Controllers.kt", "AttachmentControllerCache.kt")
            .joinToString(separator = "\n") { sourceFile(it).readText() }

    private fun sourceFile(name: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/$name"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/$name"),
        ).firstOrNull(File::exists)
            ?: error("Missing $name source file")
}
