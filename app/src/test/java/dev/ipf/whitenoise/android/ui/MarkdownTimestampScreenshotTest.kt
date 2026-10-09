package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownTimestampStyleFfi
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class MarkdownTimestampScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val originalLocale = Locale.getDefault()
    private val originalZone = TimeZone.getDefault()

    @Before
    fun setCalendar() {
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreCalendar() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun lightTimestamps() = capture("markdown_timestamps_light", LayoutDirection.Ltr)

    @Test
    fun darkLargeRtlTimestamps() = capture("markdown_timestamps_dark_large_rtl", LayoutDirection.Rtl)

    private fun capture(
        name: String,
        direction: LayoutDirection,
    ) {
        val rtl = direction == LayoutDirection.Rtl
        rule.setContent {
            WhiteNoiseTheme(darkTheme = rtl, fontScale = if (rtl) 1.6f else 1f) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Surface(Modifier.width(360.dp).testTag("timestamps")) {
                        Column(Modifier.padding(16.dp)) {
                            MarkdownTimestampStyleFfi.entries
                                .filter { it != MarkdownTimestampStyleFfi.RELATIVE }
                                .forEach { style ->
                                    MarkdownTimestampText(
                                        markdownInlinesToAnnotatedString(
                                            listOf(MarkdownInlineFfi.Timestamp(-1, style)),
                                            SpanStyle(),
                                            SpanStyle(),
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            MarkdownTimestampText(
                                buildAnnotatedString {
                                    val token = "<t:0:R>"
                                    appendInlineContent("markdown-timestamp-clock:" + token, "◷")
                                    append(" 10 seconds ago")
                                    addStringAnnotation(TIMESTAMP_TAG, token, 0, length)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("timestamps").captureRoboImage("src/test/snapshots/$name.png")
    }
}
