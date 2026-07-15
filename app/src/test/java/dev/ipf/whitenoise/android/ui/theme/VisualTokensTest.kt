package dev.ipf.whitenoise.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class VisualTokensTest {
    @Test
    fun scrimAlphaTokensPreserveExistingVisualLevels() {
        assertEquals(
            listOf(0.35f, 0.4f, 0.45f, 0.5f, 0.55f, 0.6f, 0.62f),
            listOf(
                ScrimAlpha.Light,
                ScrimAlpha.LightEmphasis,
                ScrimAlpha.Medium,
                ScrimAlpha.MediumEmphasis,
                ScrimAlpha.Strong,
                ScrimAlpha.Gradient,
                ScrimAlpha.Heavy,
            ),
        )
    }
}
