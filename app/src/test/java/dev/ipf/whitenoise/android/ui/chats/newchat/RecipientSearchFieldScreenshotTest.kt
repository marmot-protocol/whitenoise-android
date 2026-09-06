package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pixel coverage for the mutually exclusive paste, QR, and clear recipient-field actions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class RecipientSearchFieldScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboard: ClipboardManager = context.getSystemService(ClipboardManager::class.java)

    /** Gives every fixture the same text-only clipboard without reading external clipboard state. */
    @Before
    fun initializeSyntheticClipboard() {
        clipboard.clearPrimaryClip()
        clipboard.setPrimaryClip(ClipData.newPlainText("recipient", CLIPBOARD_NPUB))
    }

    /** Prevents the synthetic recipient identifier from leaking into another Robolectric test. */
    @After
    fun clearSyntheticClipboard() {
        clipboard.clearPrimaryClip()
    }

    /** Light mode shows paste and QR only while empty, then replaces both with Clear. */
    @Test
    fun emptyAndFilledActionsLight() {
        render(darkTheme = false, amoled = false)

        assertActionContract()
        capture("recipient_search_field_actions_light.png")
    }

    /** Narrow 200%-text RTL keeps each localized action visible and touchable without overlap. */
    @Test
    @Config(sdk = [36], qualifiers = "en-w280dp-h780dp-mdpi")
    fun emptyAndFilledActionsNarrowLargeFontRtlDark() {
        render(
            darkTheme = true,
            amoled = false,
            width = 280,
            fontScale = 2f,
            layoutDirection = LayoutDirection.Rtl,
        )

        assertActionContract()
        capture("recipient_search_field_actions_narrow_large_font_rtl_dark.png")
    }

    /** AMOLED preserves the action silhouettes and rounded field boundary on a black surface. */
    @Test
    fun emptyAndFilledActionsAmoled() {
        render(darkTheme = true, amoled = true)

        assertActionContract()
        capture("recipient_search_field_actions_amoled.png")
    }

    /** Renders the real production field twice so empty and filled trailing actions share one baseline. */
    private fun render(
        darkTheme: Boolean,
        amoled: Boolean,
        width: Int = 360,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        val emptyState = TextFieldState()
        val filledState = TextFieldState(initialText = FILLED_QUERY)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    Surface(
                        modifier = Modifier.width(width.dp).testTag(ROOT_TAG),
                        color = if (amoled) Color.Black else MaterialTheme.colorScheme.surface,
                    ) {
                        Column {
                            RecipientSearchField(
                                state = emptyState,
                                placeholder = context.getString(R.string.search_people_hint),
                                onPasteRejected = {},
                                onScanQr = {},
                                modifier =
                                    Modifier
                                        .paddingForRecipientSearchFixture()
                                        .testTag(EMPTY_FIELD_TAG),
                            )
                            RecipientSearchField(
                                state = filledState,
                                placeholder = context.getString(R.string.search_people_hint),
                                onPasteRejected = {},
                                onScanQr = {},
                                modifier =
                                    Modifier
                                        .paddingForRecipientSearchFixture()
                                        .testTag(FILLED_FIELD_TAG),
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Matches the same production spacing used by New Message, New Group, and Add Members. */
    private fun Modifier.paddingForRecipientSearchFixture(): Modifier =
        padding(
            horizontal = Dimens.spaceLg,
            vertical = Dimens.spaceSm,
        )

    /** Proves empty and filled fields expose no duplicate or contradictory trailing action. */
    private fun assertActionContract() {
        val paste = context.getString(R.string.paste)
        val scanQr = context.getString(R.string.scan_qr_code)
        val clear = context.getString(R.string.clear)

        assertVisibleAction(EMPTY_FIELD_TAG, paste)
        assertVisibleAction(EMPTY_FIELD_TAG, scanQr)
        assertAbsentAction(EMPTY_FIELD_TAG, clear)
        assertVisibleAction(FILLED_FIELD_TAG, clear)
        assertAbsentAction(FILLED_FIELD_TAG, paste)
        assertAbsentAction(FILLED_FIELD_TAG, scanQr)
    }

    /** Requires a localized field action to be displayed with the platform minimum touch target. */
    private fun assertVisibleAction(
        fieldTag: String,
        localizedLabel: String,
    ) {
        composeRule
            .onNode(actionLabelInside(fieldTag, localizedLabel), useUnmergedTree = true)
            .assertIsDisplayed()
        val touchBounds =
            composeRule
                .onNode(actionButtonInside(fieldTag, localizedLabel))
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .touchBoundsInRoot
        val minimumPixels = with(composeRule.density) { MINIMUM_TOUCH_TARGET.toPx() }
        assertTrue("$localizedLabel touch width: ${touchBounds.width}px", touchBounds.width >= minimumPixels)
        assertTrue("$localizedLabel touch height: ${touchBounds.height}px", touchBounds.height >= minimumPixels)
    }

    /** Rejects stale alternatives in the field state that must own a different trailing action. */
    private fun assertAbsentAction(
        fieldTag: String,
        localizedLabel: String,
    ) {
        composeRule
            .onNode(actionLabelInside(fieldTag, localizedLabel), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    /** Scopes an unmerged icon label to one field so the combined fixture cannot mask duplicates. */
    private fun actionLabelInside(
        fieldTag: String,
        localizedLabel: String,
    ): SemanticsMatcher = hasContentDescription(localizedLabel) and hasAnyAncestor(hasTestTag(fieldTag))

    /** Selects the merged clickable owner whose touch bounds include minimum-target expansion. */
    private fun actionButtonInside(
        fieldTag: String,
        localizedLabel: String,
    ): SemanticsMatcher =
        hasContentDescription(localizedLabel) and
            hasClickAction() and
            hasAnyAncestor(hasTestTag(fieldTag))

    /** Captures only the bounded two-state surface after all semantic invariants pass. */
    private fun capture(fileName: String) {
        composeRule.onNodeWithTag(ROOT_TAG).captureRoboImage("src/test/snapshots/$fileName")
    }

    private companion object {
        val MINIMUM_TOUCH_TARGET = 48.dp
        const val ROOT_TAG = "recipient-search-field-actions"
        const val EMPTY_FIELD_TAG = "recipient-search-field-empty"
        const val FILLED_FIELD_TAG = "recipient-search-field-filled"
        const val FILLED_QUERY = "Ada Lovelace"
        const val CLIPBOARD_NPUB = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"
    }
}
