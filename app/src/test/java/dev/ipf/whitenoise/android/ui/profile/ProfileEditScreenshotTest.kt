package dev.ipf.whitenoise.android.ui.profile

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy

/** Focused profile reference frames use cached synthetic images and stub every network/publication boundary. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ProfileEditScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Read mode uses full-contrast fields and an Edit action. */
    @Test fun profileReadLight() = capture("profile_read_light")

    /** Standard dark preserves its tonal field/canvas separation. */
    @Test fun profileReadDark() = capture("profile_read_dark", dark = true)

    /** AMOLED remains black with native white outlines. */
    @Test fun profileReadAmoled() = capture("profile_read_amoled", dark = true, amoled = true)

    /** Editing reveals the separate image-source controls and pinned Save. */
    @Test fun profileEditingLight() = capture("profile_editing_light", editing = true)

    /** Settings-avatar entry opens the existing picture source menu without committing a draft. */
    @Test
    fun profilePictureActionsDirectEntry() =
        capture(
            file = "profile_picture_actions_direct_entry",
            directPictureActions = true,
        )

    /** Edit and Suggest name use light action text against the dark form surfaces. */
    @Test fun profileEditingDark() = capture("profile_editing_dark", editing = true, dark = true)

    /** Suggest name fills the name field from MDK's pseudonym generator while editing (#1584). */
    @Test fun profileEditingSuggestedName() = capture("profile_editing_suggested_light", editing = true, suggest = true)

    /** Long RTL text and twice-sized type use a scrollable form beneath the pinned Save. */
    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h780dp-mdpi")
    fun profileEditingRtlLarge() = capture("profile_editing_rtl_large", editing = true, rtlLarge = true)

    /** The verified seal is inside the address field, followed by Lightning and About. */
    @Test fun profileVerifiedFields() = capture("profile_verified_fields", fields = true)

    /** Staged validation errors remain inline and Save is disabled. */
    @Test fun profileInvalidAddress() =
        capture(
            "profile_invalid_address",
            editing = true,
            invalid = true,
            fields = true,
        )

    /** The banner uses the pinned 2:1 crop above the independently cached avatar. */
    @Test fun profileCachedImages() = capture("profile_cached_images", images = true)

    /** Shared fields have stable monochrome outlines at 3x density. */
    @Test
    @Config(qualifiers = "en-rUS-w360dp-h780dp-xxhdpi")
    fun profileAmoledXxhdpi() = capture("profile_read_amoled_xxhdpi", dark = true, amoled = true)

    /** A 3x-density banner keeps its 2:1 crop and its own bounded first cached frame (#2762). */
    @Test
    @Config(qualifiers = "en-rUS-w360dp-h780dp-xxhdpi")
    fun profileBannerXxhdpi() = capture("profile_banner_xxhdpi", images = true)

    /** Records a single current profile frame; image metadata never triggers a network request. */
    @Suppress("LongMethod") // A single frame installs the full profile presentation fixture.
    private fun capture(
        file: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        editing: Boolean = false,
        rtlLarge: Boolean = false,
        fields: Boolean = false,
        invalid: Boolean = false,
        images: Boolean = false,
        suggest: Boolean = false,
        directPictureActions: Boolean = false,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val account = "0101010101010101010101010101010101010101010101010101010101010101"
        val engine = if (suggest) pseudonymEngine() else null
        val appState = profilePortTestState(context, "alice", account, marmot = engine)
        val avatar = "https://profile-screenshot.invalid/avatar.png"
        val banner = "https://profile-screenshot.invalid/banner.png"
        if (images) {
            AvatarImageLoader.putCached(
                avatar,
                Bitmap
                    .createBitmap(80, 80, Bitmap.Config.ARGB_8888)
                    .apply {
                        eraseColor(android.graphics.Color.rgb(127, 174, 149))
                    }.asImageBitmap(),
            )
            // Banners are cached under their own decode target now (#2762), so the
            // first frame is seeded at the width this device actually draws.
            AvatarImageLoader.putCachedBanner(
                banner,
                context.resources.displayMetrics.widthPixels,
                stripedBannerBitmap(context.resources.displayMetrics.widthPixels),
            )
        }
        val cached =
            profilePortTestMetadata("Alice", "A public profile shared with my contacts.").copy(
                nip05 = "alice@example.com",
                lud16 = "alice@example.com",
                picture = if (images) avatar else null,
                banner = if (images) banner else null,
            )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (rtlLarge) 2f else density.fontScale),
                LocalLayoutDirection provides if (rtlLarge) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                    ProfileEditScreen(
                        appState,
                        {},
                        { cached },
                        { awaitCancellation() },
                        { true },
                        resolveAddress = { account },
                        resolveLightning = { true },
                        openPictureActionsOnEntry = directPictureActions,
                    )
                }
            }
        }
        if (editing) composeRule.onNodeWithTag("profile.edit").performClick()
        if (suggest) {
            composeRule.onNodeWithTag("profile.suggest_name").performClick()
            composeRule.onNodeWithText("Quiet Otter").assertExists()
        }
        if (invalid) {
            composeRule
                .onNodeWithTag("profile.address_field")
                .performScrollTo()
                .performTextReplacement("not an address")
        } else if (fields) {
            composeRule.onNodeWithTag("profile.about_field").performScrollTo()
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$file.png")
    }
}

/**
 * A striped 2:1 banner [widthPx] wide.
 *
 * Fine vertical detail at the real decode target is what an avatar-sized entry could not carry, so
 * the recorded frame changes visibly if the banner ever falls back to the 512px cap.
 */
private fun stripedBannerBitmap(widthPx: Int): androidx.compose.ui.graphics.ImageBitmap {
    val width = widthPx.coerceAtLeast(2)
    val bitmap = Bitmap.createBitmap(width, width / 2, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.rgb(130, 158, 181))
    val paint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(31, 48, 66) }
    var x = 0
    while (x < width) {
        canvas.drawRect(x.toFloat(), 0f, (x + 8).toFloat(), (width / 2).toFloat(), paint)
        x += 16
    }
    return bitmap.asImageBitmap()
}

/** An engine that only answers the pseudonym draw, so the suggested name is deterministic in the frame. */
private fun pseudonymEngine(): MarmotInterface =
    Proxy.newProxyInstance(
        MarmotInterface::class.java.classLoader,
        arrayOf(MarmotInterface::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "randomProfilePseudonym" -> "Quiet Otter"
            "toString" -> "PseudonymEngineFixture"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> throw UnsupportedOperationException("Unused native test call: ${method.name}")
        }
    } as MarmotInterface
