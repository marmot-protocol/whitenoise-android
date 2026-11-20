package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.ui.chats.newchat.GroupCreationPerson
import dev.ipf.whitenoise.android.ui.chats.newchat.NewGroupRecipientActions
import dev.ipf.whitenoise.android.ui.chats.newchat.NewGroupRecipientContent
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingAction
import dev.ipf.whitenoise.android.ui.profile.AddAccountSheetContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real entry destinations must measure native outlines before their external owner can animate them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class NativeEntryDestinationCompositionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun startGroupMeasuresSearchAndRealPeopleAndMovesEntryAndExitPerimeters() =
        assertEntry(3) {
            NewGroupRecipientContent(
                TextFieldState(),
                listOf("Ada", "Lin").mapIndexed { index, name ->
                    GroupCreationPerson(
                        RecipientSearch.Candidate(index.toString().repeat(64), name, "npub-$index"),
                        null,
                        null,
                    )
                },
                emptyList(),
                false,
                false,
                false,
                NewGroupRecipientActions({}, {}, {}, {}, {}, {}, {}, { _, _ -> }),
                isValidNpub = { true },
            )
        }

    @Test fun emptyStartGroupStillMeasuresItsRealSearchOutline() =
        assertEntry(1) {
            NewGroupRecipientContent(
                TextFieldState(),
                emptyList(),
                emptyList(),
                false,
                false,
                false,
                NewGroupRecipientActions({}, {}, {}, {}, {}, {}, {}, { _, _ -> }),
                isValidNpub = { true },
            )
        }

    @Test fun addProfileMeasuresRealWelcomeActionsAndMovesEntryAndExitPerimeters() =
        assertEntry(2) {
            AddAccountSheetContent(
                amberSignerAvailable = false,
                inFlightAction = OnboardingAction.Idle,
                identity = "",
                importErrorRes = null,
                onCreate = {},
                onLoginWithAmber = {},
                onIdentityChange = {},
                onErrorChange = {},
                onImport = {},
            )
        }

    @Suppress("LongMethod") // Measurement and paused draw frames belong to one real composition lifetime.
    private fun assertEntry(expectedContours: Int, content: @Composable () -> Unit) {
        val morph = SettingsCardMorph(fullScreen = true)
        val page = checkNotNull(morph.page)
        val presence = mutableStateOf(0.25f)
        val sourceBounds = Rect(40f, 180f, 300f, 236f)
        var host = Rect.Zero
        lateinit var source: SettingsContour
        rule.mainClock.autoAdvance = false
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1f) {
                val view = LocalView.current
                CompositionLocalProvider(LocalSettingsCardMorph provides morph) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned {
                                host = it.settingsMorphBounds(view)
                                source =
                                    SettingsContour(
                                        sourceBounds,
                                        RoundRect(sourceBounds, 28f, 28f),
                                        host,
                                        Color.White,
                                        1f,
                                    )
                            }.drawWithContent {
                                if (!host.isEmpty) {
                                    with(page) { drawExternal(source, host, presence.value, Color.Black) }
                                }
                            },
                    ) { content() }
                }
            }
        }
        rule.waitForIdle()
        val native = checkNotNull(page.presentedContours(host, 1f))
        assertEquals("Real destination must supply its outlined controls", expectedContours, native.size)
        native.forEach {
            assertFalse(it.bounds.isEmpty)
            assertTrue(
                "Use the actual rounded segmented control shape",
                listOf(
                    it.corners.topLeftCornerRadius,
                    it.corners.topRightCornerRadius,
                    it.corners.bottomLeftCornerRadius,
                    it.corners.bottomRightCornerRadius,
                ).any { corner -> corner.x > 0f },
            )
            assertEquals(1f, it.width)
            assertTrue("Scroll viewport must be measured inside host", host.contains(it.viewport.center))
            assertTrue("Use the actual scroll viewport, not the whole window", it.viewport.height < host.height)
        }
        val selected = native[nearestSettingsContour(native, source.bounds.center)].key

        fun frame(at: Float): PixelMap {
            rule.runOnIdle { presence.value = at }
            val pixels = rule.onRoot().captureToImage().toPixelMap()
            val perimeter =
                interpolateSettingsContours(
                    listOf(source.copy(key = selected)),
                    native,
                    at,
                    source.bounds.center,
                    host,
                    selected,
                ).first { it.key == selected }
            assertTrue("Paused perimeter must leave source", perimeter.bounds != source.bounds)
            assertTrue(
                "Paused perimeter must not already be settled",
                perimeter.bounds != native.first { it.key == selected }.bounds,
            )
            val x = (perimeter.bounds.center.x - host.left).toInt()
            val y = (perimeter.bounds.top - host.top).toInt()
            val light = (y..y + 2).maxOf { pixels[x, it].red }
            assertTrue("Real destination must draw the moving perimeter at $at", light > 0.2f)
            return pixels
        }
        val early = frame(0.25f)
        val late = frame(0.6f)
        var changed = 0
        for (y in 0 until early.height) {
            for (x in 0 until early.width) {
                if (kotlin.math.abs(early[x, y].red - late[x, y].red) > 0.2f) changed++
            }
        }
        assertTrue("Entry must move native perimeter pixels", changed > 20)
        val closing = frame(0.25f)
        for (y in 0 until early.height) {
            for (x in 0 until early.width) {
                assertEquals("Closing returns through the same native frame", early[x, y].red, closing[x, y].red, 0.01f)
            }
        }
    }
}
