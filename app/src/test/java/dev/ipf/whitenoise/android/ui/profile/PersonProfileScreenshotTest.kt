package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Profile and shared-group presentation only; fixtures never create identities, follow people or join groups. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class PersonProfileScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Public profile and pinned Message. */
    @Test fun light() = profile("light")

    /** Dark public profile. */
    @Test fun dark() = profile("dark", dark = true)

    /** AMOLED surfaces and grouped actions. */
    @Test fun amoled() = profile("amoled", dark = true, amoled = true)

    /** Actual unknown relationship state remains disabled. */
    @Test fun unknownFollow() = profile("unknown_follow", follow = ProfileFollowRowState(false, false, false))

    /** Existing native creation busy state remains visible without navigating away. */
    @Test fun busyMessage() = profile("busy_message", busy = true)

    /** Native self-profile omits contact-only actions. */
    @Test fun self() = profile("self", self = true)

    /** Bounded 520 dp profile content and centered bottom action. */
    @Test
    @Config(qualifiers = "en-w1000dp-h780dp-mdpi")
    fun tablet() = profile("tablet")

    /** RTL and large text in a short adaptive window. */
    @Test
    @Config(qualifiers = "en-w780dp-h360dp-mdpi")
    fun shortLargeRtl() = profile("short_rtl_200", rtl = true, scale = 2f)

    /** Authoritative shared groups remain separate from Add. */
    @Test fun sharedGroups() = groups("groups", false)

    /** Unavailable roster explains incomplete evidence while keeping known rows. */
    @Test fun partialGroups() = groups("groups_partial", true)

    /** Confirmed empty groups offers the explicit existing Add action. */
    @Test fun emptyGroups() = groups("groups_empty", false, emptyList())

    /** Private local editor is scrollable and uses the shared field styling. */
    @Test fun privateDetails() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ContactPrivateDetailsDialog("Public name", "Local nickname", "Private local note", {}, { _, _ -> })
            }
        }
        capture("private_details")
    }

    /** Uses pure projection values and empty callbacks, with no native effects or private key material. */
    @Suppress("LongParameterList")
    private fun profile(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
        scale: Float = 1f,
        busy: Boolean = false,
        self: Boolean = false,
        follow: ProfileFollowRowState = ProfileFollowRowState(true, false, true),
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = scale) {
                    PersonProfileContent(
                        PersonProfilePresentation(
                            "Alice",
                            "Alice",
                            "public-person",
                            null,
                            null,
                            false,
                            "Building useful things with friends.",
                            "npub1" + "a".repeat(58),
                            "alice@example.com",
                            true,
                            "alice@example.com",
                            true,
                            self,
                        ),
                        scroll = rememberScrollState(),
                        follow = follow,
                        busy = busy,
                        canPromote = true,
                        showSharedGroups = true,
                        copied = false,
                        onBack = {},
                        onMessage = {},
                        onFollow = {},
                        onPrivateDetails = {},
                        onStartGroup = {},
                        onGroupEntry = {},
                        onPromote = {},
                        onCopy = {},
                        onAvatar = {},
                        onBanner = {},
                        onCopyLightning = {},
                        sharedAvatars = { PersonSharedGroupAvatars(rows) },
                    )
                }
            }
        }
        capture(name)
    }

    /** Records only supplied native-projection-shaped rows, including partial and empty states. */
    private fun groups(
        name: String,
        unresolved: Boolean,
        data: List<PersonSharedGroupRow> = rows,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                PersonGroupsInCommonContent(data, unresolved, {}, {}, {}, {})
            }
        }
        capture(name)
    }

    /** Freeze progress animation at one deterministic frame before recording. */
    private fun capture(name: String) {
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/person_profile_$name.png")
    }

    private val rows =
        listOf(
            PersonSharedGroupRow("friends", "Friends", 4),
            PersonSharedGroupRow("design", "Design", 7),
            PersonSharedGroupRow("local", "Local community", 12),
        )
}
