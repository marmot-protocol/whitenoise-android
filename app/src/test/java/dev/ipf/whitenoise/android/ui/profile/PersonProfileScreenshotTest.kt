package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.account.StartingAccount
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

    @Test fun membershipActionsLight() = membershipActions("light")

    @Test fun membershipActionsDark() = membershipActions("dark", dark = true)

    @Test fun membershipActionsAmoled() = membershipActions("amoled", dark = true, amoled = true)

    @Test fun membershipActionsLargeRtl() = membershipActions("large_rtl", rtl = true)

    /** The production destructive confirmation names exact identities with accessible controls. */
    @Test fun membershipConfirmationLight() = membershipConfirmation("light")

    /** Dark confirmation retains readable identity and destructive-action contrast. */
    @Test fun membershipConfirmationDark() = membershipConfirmation("dark", dark = true)

    /** AMOLED confirmation uses the existing pure-black dialog surface. */
    @Test fun membershipConfirmationAmoled() = membershipConfirmation("amoled", dark = true, amoled = true)

    /** Long identity text scrolls without clipping the confirm/cancel controls in large RTL layout. */
    @Test fun membershipConfirmationLargeRtl() = membershipConfirmation("large_rtl", rtl = true)

    /** Renders the actual production dialog; it never creates a native controller or commits membership. */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun membershipConfirmation(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
    ) {
        val fixture = MembershipAuthorityFixture()
        val state = MembershipVisitState(PersonProfileOwner { true })
        val context =
            MembershipRemovalContext(
                PersonSharedGroupRow(fixture.fast.id, "Friends", 3),
                fixture.account,
                fixture.person,
            )
        try {
            composeRule.setContent {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 2f else 1f) {
                        MembershipRemovalConfirmation(
                            fixture.state,
                            state,
                            context,
                            "River",
                            stringResource(R.string.shared_group_remove_person),
                        ) {}
                    }
                }
            }
            composeRule.onNodeWithText("Remove River from Friends").assertIsDisplayed()
            composeRule.onNodeWithTag("confirmation.confirm").assertIsDisplayed()
            composeRule.onNodeWithTag("confirmation.cancel").assertIsDisplayed()
            composeRule.onNodeWithText(fixture.fast.id).assertDoesNotExist()
            captureScreenRoboImage("src/test/snapshots/shared_group_membership_${name}_confirmation.png")
        } finally {
            fixture.close()
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun membershipActions(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 2f else 1f) {
                    PersonGroupsInCommonContent(
                        rows = listOf(PersonSharedGroupRow("friends", "Friends", 4)),
                        unresolved = false,
                        onBack = {},
                        onOpen = {},
                        onAdd = {},
                        onRetry = {},
                        removeLabel = { "Remove River from ${it.title}" },
                        onRemove = {},
                        canRemove = { true },
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/shared_group_membership_$name.png")
        composeRule.onNodeWithTag("groups_in_common.menu.friends").performClick()
        composeRule.onNodeWithText("Remove River from Friends").assertIsDisplayed()
        captureScreenRoboImage("src/test/snapshots/shared_group_membership_${name}_menu.png")
    }

    /** Private local editor is scrollable and uses the shared field styling. */
    @Test fun privateDetails() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ContactPrivateDetailsDialog(
                    "Public name",
                    "Local nickname",
                    "Private local note",
                    {},
                    { _, _ -> },
                    viewportInsets = WindowInsets(0),
                )
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
                        startingAs =
                            if (self) {
                                null
                            } else {
                                StartingAccount("a".repeat(64), "Carol", null, "npub1carol")
                            },
                        person =
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
                        fromGroup = false,
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
