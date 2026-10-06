package dev.ipf.whitenoise.android.ui.share

import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.PrivateContactAvatarLoader
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ContactPictureChange
import dev.ipf.whitenoise.android.state.contactPicturePng
import dev.ipf.whitenoise.android.ui.common.GroupAvatar
import dev.ipf.whitenoise.android.ui.conversation.messages.ForwardTargetRow
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Both production destination rows resolve private pixels through the selected account, not the active one. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class PrivateContactDestinationAvatarTest {
    @get:Rule val rule = createComposeRule()
    private val contact = "40".repeat(32)

    /** A cached active-account picture cannot appear in another account's rows with no override. */
    @Test fun activeAccountOnlyPictureCannotLeakIntoDestinationRows() = assertPickerOwners(hasA = true, hasB = false)

    /** A destination account can display its own picture while the globally active account remains unchanged. */
    @Test fun destinationAccountOnlyPictureAppearsInBothRows() = assertPickerOwners(hasA = false, hasB = true)

    /** Distinct records for the same contact always select the destination account's pixels. */
    @Test fun bothAccountsWithPicturesStillDisplayOnlyTheDestinationOwner() = assertPickerOwners(hasA = true, hasB = true)

    /** The shared group renderer rejects a mismatched private handle even if a caller supplies one. */
    @Test fun groupAvatarRejectsAnActiveAccountHandleForAnotherOwner() {
        val appState = emptyAppState(accounts = accounts(), activeAccountRef = "a")
        appState.contactPictureStore.save("a", contact, "", "", ContactPictureChange.Replace(contactPicturePng(Color.RED))) {
            true
        }
        val source = checkNotNull(appState.contactAvatarSource(contact, "a"))
        runBlocking { AvatarImageLoader.load(source) }
        rule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.testTag("wrong-owner")) {
                    GroupAvatar(appState, group("group"), "Contact", contact, 48.dp, source, accountRef = "b")
                }
            }
        }
        rule.waitForIdle()
        assertEquals(0, colorPixels("wrong-owner", Color.RED))
    }

    /** A captured handle loses display authority when its selected local account is signed out. */
    @Test fun signedOutDestinationRejectsItsCapturedPrivateHandle() {
        val owners = accounts().map { if (it.label == "b") it.copy(signedOut = true) else it }
        val appState = emptyAppState(accounts = owners, activeAccountRef = "a")
        appState.contactPictureStore.save("b", contact, "", "", ContactPictureChange.Replace(contactPicturePng(Color.BLUE))) {
            true
        }
        val source = PrivateContactAvatarLoader.source(checkNotNull(appState.contactPictureStore.reference("b", contact)), null)
        runBlocking { PrivateContactAvatarLoader.load(source, "b") }
        rule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.testTag("signed-out-owner")) {
                    GroupAvatar(appState, group("group"), "Contact", contact, 48.dp, source, accountRef = "b")
                }
            }
        }
        rule.waitForIdle()
        assertEquals(0, colorPixels("signed-out-owner", Color.BLUE))
        assertNull(appState.contactAvatarSource(contact, "b"))
    }

    /** Retires only this Robolectric fixture's process-local image caches. */
    @After fun clearPixels() = AvatarImageLoader.clear()

    /** Builds the actual forward/share row composables with the same peer and a different selected owner. */
    private fun assertPickerOwners(hasA: Boolean, hasB: Boolean) {
        AvatarImageLoader.clear()
        val appState = emptyAppState(accounts = accounts(), activeAccountRef = "a")
        listOf("a" to hasA, "b" to hasB).filter { it.second }.forEach { (account, _) ->
            val color = if (account == "a") Color.RED else Color.BLUE
            appState.contactPictureStore.save(account, contact, "", "", ContactPictureChange.Replace(contactPicturePng(color))) {
                true
            }
        }
        if (hasA) runBlocking { AvatarImageLoader.load(checkNotNull(appState.contactAvatarSource(contact, "a"))) }
        val item = ChatListItem(group("group"), null, contact, 2, null)
        rule.setContent {
            WhiteNoiseTheme {
                Column {
                    Box(Modifier.testTag("forward-owner")) {
                        ForwardTargetRow(appState, item, "Contact", "b", "self-b", false, {})
                    }
                    Box(Modifier.testTag("share-owner")) {
                        ShareTargetRow(item, "Contact", false, "self-b", "b", appState, {})
                    }
                }
            }
        }
        if (hasB) {
            val source = checkNotNull(appState.contactAvatarSource(contact, "b"))
            rule.waitUntil(5_000) { PrivateContactAvatarLoader.peek(source, "b") != null }
        }
        rule.waitForIdle()
        assertEquals("Choosing destination B must not activate it", "a", appState.activeAccountRef)
        listOf("forward-owner", "share-owner").forEach { tag ->
            assertEquals("$tag must not borrow A's cached image", 0, colorPixels(tag, Color.RED))
            if (hasB) assertTrue("$tag should render B's private picture", colorPixels(tag, Color.BLUE) > 300)
        }
    }

    /** Counts exact synthetic colours so initials, text and theme backgrounds cannot satisfy an image assertion. */
    private fun colorPixels(tag: String, color: Int): Int {
        val bitmap = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { it == color }
    }

    /** Both local accounts stay signed in while the picker changes only its displayed destination owner. */
    private fun accounts() = listOf(testAccount("a", "self-a"), testAccount("b", "self-b"))
}
