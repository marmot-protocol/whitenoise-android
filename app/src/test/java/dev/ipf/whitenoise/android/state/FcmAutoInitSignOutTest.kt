package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Signing out the last signed-in account turns Firebase auto-init off (#4081), even though the native
 * account list keeps a signed-out entry for that account.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
internal class FcmAutoInitSignOutTest : ExternalSignerSignOutFixture() {
    /** The only account signs out while the refreshed list still holds its signed-out entry. */
    @Test
    fun signingOutTheLastAccountTurnsAutoInitOff() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val state = appState(nativePushFallbackPlatform = platform)

            assertEquals(SignOutCompletion.Complete, state.signOutActiveAccount())

            assertTrue("the signed-out entry must remain listed", engineSignedOut)
            assertEquals(false, platform.autoInitWrites.lastOrNull())
            assertTrue("sign-out must never enable auto-init", true !in platform.autoInitWrites)
        }

    /** A remaining signed-in signer keeps auto-init and the token for the next sync to decide. */
    @Test
    fun remainingSignedInSignerLeavesAutoInit() =
        runBlocking {
            val platform = RecordingNativePushFallbackPlatform(context)
            val state = appState(nativePushFallbackPlatform = platform)
            val remaining =
                listOf(
                    externalSignerAccount(signedOut = true),
                    externalSignerAccount().copy(label = otherAccountRef, accountIdHex = "b".repeat(64)),
                )

            state.notificationDelivery.settleFcmAfterSignOut(nextActiveRef = null, remaining = remaining)

            assertTrue(platform.autoInitWrites.isEmpty())
        }
}
