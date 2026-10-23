package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.core.twoFrameGif
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnimatedGroupAvatarTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun visibleGroupSourcesAreBoundedAndBackgroundReleasesAllSlots() {
        val owner = TestOwner()
        var reads = 0
        var mounted by mutableStateOf(true)
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (mounted) {
                    Column {
                        repeat(MAX_PLAYING_GROUP_AVATARS + 1) { index ->
                            AnimatedGroupAvatarOverlay(
                                GroupAvatarAnimation("group-$index") {
                                    reads++
                                    twoFrameGif()
                                },
                                Modifier.size(40.dp),
                                maxEdgePx = 40,
                                animationsEnabled = true,
                                decode = { _, _ -> StillDrawable() },
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitUntil { reads == MAX_PLAYING_GROUP_AVATARS }
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        composeRule.waitUntil { groupAvatarAnimationPermits.availablePermits == MAX_PLAYING_GROUP_AVATARS }
        composeRule.runOnIdle { mounted = false }
    }

    @Test
    fun failedDecoderReleasesItsSlotAndLeavesStaticFallback() {
        var reads = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestOwner()) {
                AnimatedGroupAvatarOverlay(
                    GroupAvatarAnimation("failure") {
                        reads++
                        twoFrameGif()
                    },
                    Modifier.size(40.dp),
                    maxEdgePx = 40,
                    animationsEnabled = true,
                    decode = { _, _ -> error("decoder unavailable") },
                )
            }
        }
        composeRule.waitUntil {
            reads == 1 && groupAvatarAnimationPermits.availablePermits == MAX_PLAYING_GROUP_AVATARS
        }
        composeRule.waitForIdle()
        assertEquals(1, reads)
    }

    @Test
    fun singleFrameGifNeverOpensAnAnimationSession() {
        var reads = 0
        var decodes = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestOwner()) {
                AnimatedGroupAvatarOverlay(
                    GroupAvatarAnimation("static-gif") {
                        reads++
                        dev.ipf.whitenoise.android.media.AnimationSourceFixtures.gif(
                            frames =
                                listOf(
                                    dev.ipf.whitenoise.android.media.AnimationSourceFixtures
                                        .gifFrame(),
                                ),
                        )
                    },
                    Modifier.size(40.dp),
                    maxEdgePx = 40,
                    animationsEnabled = true,
                    decode = { _, _ ->
                        decodes++
                        StillDrawable()
                    },
                )
            }
        }
        composeRule.waitUntil { reads == 1 }
        composeRule.waitForIdle()
        assertEquals(0, decodes)
        assertEquals(MAX_PLAYING_GROUP_AVATARS, groupAvatarAnimationPermits.availablePermits)
    }

    @Test
    fun reducedMotionNeverReadsGroupBytes() {
        var reads = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestOwner()) {
                AnimatedGroupAvatarOverlay(
                    GroupAvatarAnimation("group") {
                        reads++
                        twoFrameGif()
                    },
                    Modifier.size(40.dp),
                    maxEdgePx = 40,
                    animationsEnabled = false,
                    decode = { _, _ -> null },
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(0, reads)
        assertEquals(MAX_PLAYING_GROUP_AVATARS, groupAvatarAnimationPermits.availablePermits)
    }

    @Test
    fun groupActionReviewRetainsTheGroupPlaybackPolicy() {
        var reads = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestOwner()) {
                dev.ipf.whitenoise.android.ui.chats.newchat.GroupReviewDestinationIdentity(
                    dev.ipf.whitenoise.android.ui.chats.newchat.MemberReviewGroup(
                        id = "group",
                        name = "Group",
                        image =
                            androidx.compose.ui.graphics
                                .ImageBitmap(2, 2),
                        groupAnimation =
                            GroupAvatarAnimation("review-group") {
                                reads++
                                twoFrameGif()
                            },
                    ),
                )
            }
        }
        composeRule.waitUntil { reads == 1 }
    }

    @Test
    fun contactRowsForwardGroupAnimationAdmission() {
        var reads = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestOwner()) {
                dev.ipf.whitenoise.android.ui.chats.newchat.ContactRow(
                    title = "Group",
                    subtitle = null,
                    avatarSeed = "group",
                    avatarUrl = null,
                    avatarImage =
                        androidx.compose.ui.graphics
                            .ImageBitmap(2, 2),
                    groupAnimation =
                        GroupAvatarAnimation("contact-row") {
                            reads++
                            twoFrameGif()
                        },
                )
            }
        }
        composeRule.waitUntil { reads == 1 }
    }

    @Test
    fun profileSharedGroupStripForwardsAnimationAdmission() {
        var reads = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestOwner()) {
                dev.ipf.whitenoise.android.ui.profile.PersonSharedGroupAvatars(
                    rows =
                        listOf(
                            dev.ipf.whitenoise.android.ui.profile
                                .PersonSharedGroupRow("group", "Group", 3),
                        ),
                    groupAnimation = {
                        GroupAvatarAnimation("shared-strip") {
                            reads++
                            twoFrameGif()
                        }
                    },
                    avatar = {
                        androidx.compose.ui.graphics
                            .ImageBitmap(2, 2)
                    },
                )
            }
        }
        composeRule.waitUntil { reads == 1 }
    }

    @Test
    fun profileSharedGroupListForwardsAnimationAdmission() {
        var reads = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides TestOwner()) {
                dev.ipf.whitenoise.android.ui.profile.PersonGroupsInCommonContent(
                    rows =
                        listOf(
                            dev.ipf.whitenoise.android.ui.profile
                                .PersonSharedGroupRow("group", "Group", 3),
                        ),
                    unresolved = false,
                    onBack = {},
                    onOpen = {},
                    onAdd = {},
                    onRetry = {},
                    avatar = {
                        androidx.compose.ui.graphics
                            .ImageBitmap(2, 2)
                    },
                    groupAnimation = {
                        GroupAvatarAnimation("shared-list") {
                            reads++
                            twoFrameGif()
                        }
                    },
                )
            }
        }
        composeRule.waitUntil { reads == 1 }
    }

    private class StillDrawable : android.graphics.drawable.Drawable() {
        override fun draw(canvas: android.graphics.Canvas) = Unit

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = 2

        override fun getIntrinsicHeight(): Int = 2
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }
}
