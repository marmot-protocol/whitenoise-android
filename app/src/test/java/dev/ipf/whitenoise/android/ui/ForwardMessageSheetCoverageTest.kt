package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ForwardMessageSheetCoverageTest {
    @Test
    fun forwardActionStaysInVisibleSheetContentAboveImeInsets() {
        val body = whiteNoiseAppSource().readText().functionBody("ForwardMessageSheet")

        assertTrue(
            "the forward sheet must not pin the action to the expanded sheet height, which hides it at the partial detent",
            ".fillMaxHeight()" !in body &&
                ".align(Alignment.BottomCenter)" !in body,
        )
        assertTrue(
            "the Forward action bar must still ride above the IME when search opens the keyboard",
            ".imePadding()" in body,
        )
        assertTrue(
            "the scrollable target list must be height-constrained so the Forward action remains visible at the partial detent",
            Regex(
                """LazyColumn\s*\(.*\.heightIn\s*\(\s*max\s*=\s*targetListMaxHeight\s*\)""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(body),
        )
    }

    @Test
    fun searchFocusExpandsForwardSheet() {
        val body = whiteNoiseAppSource().readText().functionBody("ForwardMessageSheet")

        assertTrue(
            "the forward sheet should expand when search receives focus so the keyboard pushes the sheet up instead of covering actions",
            "var searchFocused by remember" in body &&
                ".onFocusChanged { searchFocused = it.isFocused }" in body &&
                Regex(
                    """LaunchedEffect\s*\(\s*searchFocused\s*\)\s*\{\s*if\s*\(\s*searchFocused\s*\)\s*sheetState\.expand\(\)\s*\}""",
                    RegexOption.DOT_MATCHES_ALL,
                ).containsMatchIn(body),
        )
    }

    @Test
    fun forwardButtonRemainsOutsideScrollableTargetList() {
        val body = whiteNoiseAppSource().readText().functionBody("ForwardMessageSheet")
        val listIndex = body.indexOf("LazyColumn(")
        val buttonIndex = body.indexOf("Button(", listIndex)

        assertTrue(
            "the Forward button should stay outside the scrollable list so list scrolling cannot hide it",
            listIndex >= 0 && buttonIndex > listIndex,
        )
    }

    private fun whiteNoiseAppSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/WhiteNoiseApp.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/WhiteNoiseApp.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing WhiteNoiseApp.kt source file")
}
