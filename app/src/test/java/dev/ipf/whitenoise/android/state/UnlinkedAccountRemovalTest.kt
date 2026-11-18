package dev.ipf.whitenoise.android.state

import android.app.Application
import androidx.core.content.pm.ShortcutManagerCompat
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.LocalCleanupReportFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.WipeOutcomeFfi
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.share.PrivateShareFiles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resumeWithException

/**
 * Removing an unlinked account from the sign-in screen, where no account is active, is the same wipe as removing
 * the account in view: what Android itself keeps for the account goes with MDK's data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnlinkedAccountRemovalTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private var wiped = false
    private var localCleanupCompleted = true

    private val unlinked =
        AccountSummaryFfi(
            label = UNLINKED,
            accountIdHex = "ab".repeat(32),
            localSigning = true,
            externalSigning = false,
            signedOut = true,
            running = false,
            unlinked = true,
        )

    @Suppress("UNCHECKED_CAST")
    private val marmot =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "signOutAndWipe" ->
                    WipeOutcomeFfi(
                        groupsLeft = 0u,
                        groupLeaveFailures = emptyList(),
                        keyPackagesDeleted = 0u,
                        keyPackageFailures = emptyList(),
                        localCleanup = LocalCleanupReportFfi(completed = localCleanupCompleted, reason = null),
                        groupsLeftToSiblings = 0u,
                        siblingsNotReached = false,
                    ).also { wiped = localCleanupCompleted }
                "listAccounts" -> if (wiped) emptyList() else listOf(unlinked)
                "accountUnreadSummary", "chatList" -> emptyList<Any>()
                "toString" -> "UnlinkedAccountRemovalMarmotFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> {
                    val failure = UnsupportedOperationException("Unexpected Marmot call: ${method.name}")
                    val continuation = arguments?.lastOrNull() as? Continuation<Any?> ?: throw failure
                    continuation.resumeWithException(failure)
                    COROUTINE_SUSPENDED
                }
            }
        } as MarmotInterface

    /** Starts each test without launcher shortcuts another test left behind. */
    @Before
    fun setUp() {
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
    }

    /** An app on the sign-in screen: the unlinked account is saved, and nothing is active. */
    private fun appState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = listOf(unlinked),
            activeAccountRef = null,
        ).also { state ->
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(state, AppMarmotRuntime(rootPath = "test", marmot = marmot))
        }

    /** A decrypted upload source of the kind a draft attachment leaves in the cache. */
    private fun decryptedMedia(): File =
        File(context.cacheDir, MediaCacheDirs.UPLOAD_SOURCES)
            .apply { mkdirs() }
            .resolve("plain.bin")
            .apply { writeBytes(byteArrayOf(1)) }

    /** A finished private original held on [unlinked]'s draft shelf. */
    private fun draftOriginal(files: PrivateShareFiles): android.net.Uri {
        val (uri, file) = files.newFile()
        file.writeBytes(byteArrayOf(1))
        files.finish(uri, "file", "application/octet-stream", 1)
        files.leases.saveShelf(unlinked.accountIdHex, GROUP, listOf(uri))
        return uri
    }

    /** Removal takes the account's draft originals, decrypted media and preferences along with MDK's data. */
    @Test
    fun removalClearsWhatAndroidKeepsForTheAccount() =
        runBlocking {
            val state = appState()
            val files = PrivateShareFiles(context)
            val original = draftOriginal(files)
            val media = decryptedMedia()
            state.memberMutePreferences.setMuted(UNLINKED, GROUP, MEMBER, muted = true)
            try {
                assertTrue(state.removeUnlinkedAccount(UNLINKED))

                assertNull(files.resolve(original))
                assertFalse(media.exists())
                assertFalse(state.memberMutePreferences.isMuted(UNLINKED, GROUP, MEMBER))
                assertEquals(emptyList<AccountSummaryFfi>(), state.accounts)
                assertNull(state.activeAccountRef)
                assertEquals(AppPhase.Onboarding, state.phase)
            } finally {
                files.leases.releaseAccount(unlinked.accountIdHex)
            }
        }

    /** A wipe MDK could not finish is reported as a failure and leaves the account and its originals in place. */
    @Test
    fun anUnfinishedWipeIsNotReportedAsRemoved() =
        runBlocking {
            localCleanupCompleted = false
            val state = appState()
            val files = PrivateShareFiles(context)
            val original = draftOriginal(files)
            try {
                assertFalse(state.removeUnlinkedAccount(UNLINKED))

                assertNotNull(files.metadata(original))
                assertEquals(listOf(unlinked), state.accounts)
            } finally {
                files.leases.releaseAccount(unlinked.accountIdHex)
            }
        }

    private companion object {
        const val UNLINKED = "unlinked-account"
        const val GROUP = "aa11bb22"
        const val MEMBER = "11aa22bb"
    }
}
