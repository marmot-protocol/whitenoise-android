package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.chats.AvatarScreenshotFixtures
import dev.ipf.whitenoise.android.ui.chats.CHAT_ROW_AVATAR_SIZE
import dev.ipf.whitenoise.android.ui.chats.ChatRowAvatarMarkers
import dev.ipf.whitenoise.android.ui.chats.ChatRowLayout
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactRow
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.FOLLOWED_PERSON_BADGE_TEST_TAG
import dev.ipf.whitenoise.android.ui.common.FollowedPeople
import dev.ipf.whitenoise.android.ui.common.LocalFollowedPeople
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageSenderAvatarSlot
import dev.ipf.whitenoise.android.ui.group.GroupDetailsHeader
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.graphics.Color as AndroidColor

/**
 * The followed-person badge on every kind of person avatar: direct chat rows (with and without a pin), a sender
 * beside a message, a group member row and the direct chat details header. Followed people carry it; others and the
 * account itself do not.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h1000dp-mdpi")
class FollowedPersonAvatarScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun followedAvatarsLight() {
        render(darkTheme = false, amoled = false)
        capture("followed_person_avatars_light.png")
    }

    @Test
    fun followedAvatarsDark() {
        render(darkTheme = true, amoled = false)
        capture("followed_person_avatars_dark.png")
    }

    @Test
    fun followedAvatarsAmoled() {
        render(darkTheme = true, amoled = true)
        capture("followed_person_avatars_amoled.png")
    }

    /** Right-to-left at 200% text mirrors the badge to the leading-bottom corner without crowding the rows. */
    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h1400dp-mdpi")
    fun followedAvatarsLargeFontRtl() {
        render(darkTheme = false, amoled = false, fontScale = 2f, rtl = true)
        capture("followed_person_avatars_large_font_rtl.png")
    }

    /** Draws the fixture and checks that only avatars of followed people carry a badge, never the account's own. */
    private fun render(
        darkTheme: Boolean,
        amoled: Boolean,
        fontScale: Float = 1f,
        rtl: Boolean = false,
    ) {
        val photo = AvatarScreenshotFixtures.distinctAvatarBitmap(AndroidColor.rgb(191, 54, 87))
        val followed = FollowedPeople.of(listOf(ADA, GRACE, OWN), ownAccountIdHex = OWN)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalFollowedPeople provides followed,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    Surface(color = if (amoled) Color.Black else MaterialTheme.colorScheme.background) {
                        Column(Modifier.width(360.dp).testTag(ROOT_TAG)) {
                            FollowedAvatarFixture(photo)
                        }
                    }
                }
            }
        }
        // Ada: chat row, sender, details header and member row. Grace: pinned chat row and member row.
        composeRule
            .onAllNodesWithTag(FOLLOWED_PERSON_BADGE_TEST_TAG, useUnmergedTree = true)
            .assertCountEquals(6)
    }

    private fun capture(fileName: String) {
        composeRule
            .onNodeWithTag(ROOT_TAG)
            .captureRoboImage("src/test/snapshots/$fileName")
    }

    private companion object {
        const val ROOT_TAG = "followed-person-avatars"
        val ADA = "a".repeat(64)
        val GRACE = "b".repeat(64)
        val LINUS = "c".repeat(64)
        val OWN = "d".repeat(64)
    }

    @Composable
    @Suppress("FunctionNaming", "LongMethod")
    private fun FollowedAvatarFixture(photo: ImageBitmap) {
        DirectChatRow("Ada Lovelace", ADA, photo, pinned = false, preview = "See you at the workshop")
        DirectChatRow("Grace Hopper", GRACE, null, pinned = true, preview = "Pinned and followed")
        DirectChatRow("Linus Example", LINUS, null, pinned = true, preview = "Pinned, not followed")
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            MessageSenderAvatarSlot(
                showSenderAvatar = true,
                title = "Ada Lovelace",
                seed = ADA,
                pictureUrl = null,
                picture = photo,
                enabled = true,
                alignToBubbleBottom = false,
                onClick = {},
            )
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text("Followed sender in a group", Modifier.padding(12.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            MessageSenderAvatarSlot(
                showSenderAvatar = true,
                title = "Linus Example",
                seed = LINUS,
                pictureUrl = null,
                enabled = true,
                alignToBubbleBottom = false,
                onClick = {},
            )
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text("Sender you do not follow", Modifier.padding(12.dp))
            }
        }
        ContactRow(
            title = "Grace Hopper",
            subtitle = "Admin",
            avatarSeed = GRACE,
            avatarUrl = null,
            followedAccount = GRACE,
        )
        ContactRow(
            title = "You",
            subtitle = "Member",
            avatarSeed = OWN,
            avatarUrl = null,
            followedAccount = OWN,
        )
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            GroupDetailsHeader(
                title = "Ada Lovelace",
                subtitle = "",
                description = "npub1ada…lovelace",
                seed = ADA,
                pictureUrl = null,
                picture = photo,
                archived = false,
                followedAccount = ADA,
            )
        }
        ContactRow(
            title = "Ada Lovelace",
            subtitle = "Member",
            avatarSeed = ADA,
            avatarUrl = null,
            avatarImage = photo,
            followedAccount = ADA,
        )
    }

    @Composable
    @Suppress("FunctionNaming")
    private fun DirectChatRow(
        title: String,
        account: String,
        photo: ImageBitmap?,
        pinned: Boolean,
        preview: String,
    ) {
        ChatRowLayout(
            title = title,
            timestampAt = (System.currentTimeMillis() / 1_000L).toULong(),
            rowHasUnread = false,
            selectionMode = false,
            selected = false,
            leadingContent = {
                ChatRowAvatarMarkers(account, pinned) {
                    Avatar(title = title, seed = account, size = CHAT_ROW_AVATAR_SIZE, picture = photo)
                }
            },
            supportingContent = { Text(preview, maxLines = 1) },
            supportingMetadata = null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
