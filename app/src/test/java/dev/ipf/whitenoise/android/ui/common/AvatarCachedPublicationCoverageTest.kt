package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.kotlinBlockFrom
import dev.ipf.whitenoise.android.ui.profile.ProfileAvatar
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Invariant gate for #3557: a mounted placeholder whose own load failed adopts pixels another consumer later
 * publishes for the same key, with no fetch of its own, no recovery event and no identity change, and every
 * URL-backed recoverable avatar call site takes part in that adoption.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AvatarCachedPublicationCoverageTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val online = AtomicBoolean(false)
    private val fetches = AtomicInteger()

    /** Starts every case with an empty cache and a fetcher that fails until the test brings it online. */
    @Before
    fun attachFetcher() {
        AvatarImageLoader.clear()
        AvatarImageLoader.attachProfileImageFetcher { _, _ ->
            fetches.incrementAndGet()
            check(online.get()) { "synthetic transient failure" }
            Base64.getDecoder().decode(PNG)
        }
    }

    /** Retires detached loader work and the fetcher after each composition. */
    @After
    fun resetLoader() {
        AvatarImageLoader.resetProfileImageFetcherForTests()
        AvatarImageLoader.clear()
    }

    /**
     * Consumer A (the profile header) fails and stays visible; after the cooldown a newly mounted consumer B
     * (a list avatar) loads the same URL. Both end up showing it, and only B's request fetched successfully.
     */
    @Test
    fun visibleFailedConsumerAdoptsAnotherConsumersSuccess() {
        val secondMounted = mutableStateOf(false)
        composeRule.setContent {
            Column {
                Box(Modifier.size(64.dp)) { ProfileAvatar(name = "Alice", owner = "alice", url = URL) }
                if (secondMounted.value) Avatar(title = "Bob", seed = "bob", size = 48.dp, pictureUrl = URL)
            }
        }
        composeRule.waitUntil(5_000) { fetches.get() == 1 }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("A").assertExists()
        // Joins A's request, so its failure is recorded before the cooldown is expired below.
        assertNull(runBlocking { AvatarImageLoader.load(URL) })

        composeRule.runOnIdle {
            AvatarImageLoader.expireFailureCooldownForTests()
            online.set(true)
            secondMounted.value = true
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) { AvatarImageLoader.peek(URL) != null }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("A").assertDoesNotExist()
        composeRule.onNodeWithText("B").assertDoesNotExist()
        assertEquals(2, fetches.get())
        assertEquals(0, AvatarImageLoader.waitingPublicationKeyCount())
    }

    /** Another URL's success neither wakes the placeholder into a fetch nor changes what it shows. */
    @Test
    fun unrelatedPublicationLeavesThePlaceholderAlone() {
        composeRule.setContent {
            Avatar(title = "Alice", seed = "alice", size = 48.dp, pictureUrl = URL)
        }
        composeRule.waitUntil(5_000) { fetches.get() == 1 }
        composeRule.waitForIdle()

        online.set(true)
        runBlocking { checkNotNull(AvatarImageLoader.load("$URL/other")) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("A").assertExists()
        assertEquals(2, fetches.get())
        assertEquals(1, AvatarImageLoader.waitingPublicationKeyCount())
    }

    /** Removing the placeholder releases its cache registration, so no observer outlives it. */
    @Test
    fun disposedPlaceholderReleasesItsWait() {
        val mounted = mutableStateOf(true)
        composeRule.setContent {
            if (mounted.value) Avatar(title = "Alice", seed = "alice", size = 48.dp, pictureUrl = URL)
        }
        composeRule.waitUntil(5_000) { fetches.get() == 1 }
        composeRule.waitForIdle()
        assertEquals(1, AvatarImageLoader.waitingPublicationKeyCount())
        composeRule.runOnIdle { mounted.value = false }
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) { AvatarImageLoader.waitingPublicationKeyCount() == 0 }
    }

    /**
     * Every production recoverable avatar that loads a profile-image URL (public or private handle) also
     * waits for that key's publication, so a new call site cannot reintroduce the stuck placeholder.
     */
    @Test
    fun everyUrlBackedRecoverableAvatarAdoptsCachedPublications() {
        val calls =
            mainSources().flatMap { (path, source) ->
                CALL.findAll(source).map { match -> path to callWithTrailingLambda(source, match.range.last) }
            }
        val urlBacked = calls.filter { (_, call) -> URL_LOAD.containsMatchIn(call) }
        assertTrue("expected Avatar, ProfileAvatar, legacy and private group callers", urlBacked.size >= 4)
        val missing = urlBacked.filterNot { (_, call) -> "awaitCached =" in call }.map { it.first }
        assertEquals("URL-backed recoverable avatars without cache adoption", emptyList<String>(), missing)
    }

    /** The call's argument list plus its trailing load lambda, where the loader it uses is named. */
    private fun callWithTrailingLambda(
        source: String,
        openParen: Int,
    ): String {
        val arguments = source.kotlinBlockFrom(openParen, "rememberRecoverableAvatar call", '(', ')')
        val afterArguments = openParen + arguments.length
        val lambdaStart = (afterArguments until source.length).firstOrNull { !source[it].isWhitespace() }
        val lambda =
            lambdaStart
                ?.takeIf { source[it] == '{' }
                ?.let { source.kotlinBlockFrom(it, "rememberRecoverableAvatar load") }
                .orEmpty()
        return arguments + lambda
    }

    /** Every production Kotlin source with its path relative to the source root. */
    private fun mainSources(): List<Pair<String, String>> =
        sourceRoot()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(sourceRoot()).path to it.readText() }
            .toList()

    /** The app package's source root, from either the module or the repository working directory. */
    private fun sourceRoot(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android"),
            File("app/src/main/java/dev/ipf/whitenoise/android"),
        ).first(File::isDirectory)

    private companion object {
        const val URL = "https://profiles.example/adopt-me"
        const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        val CALL = Regex("""\brememberRecoverableAvatar\s*\(""")
        val URL_LOAD = Regex("""(?<![\w.])(AvatarImageLoader|PrivateContactAvatarLoader)\.load\(""")
    }
}
