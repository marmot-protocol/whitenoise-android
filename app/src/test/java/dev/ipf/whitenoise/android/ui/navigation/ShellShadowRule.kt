package dev.ipf.whitenoise.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Shadow-mode evidence for one test: starts a fresh [ShellShadowLog], and after the test asserts that at least one
 * comparison between the shadow navigator and the hand-written route ran and that none of them disagreed. The count
 * is printed as one `shell-shadow` line, which lands in the test's XML report for the evidence table.
 */
class ShellShadowRule : TestRule {
    /** Wraps [base] with the reset before and the shadow assertions after. */
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            /** Runs the test, then records and asserts its shadow comparisons. */
            override fun evaluate() {
                ShellShadowLog.reset()
                try {
                    base.evaluate()
                } finally {
                    println(
                        "shell-shadow ${description.className}#${description.methodName} " +
                            "comparisons=${ShellShadowLog.comparisons} mismatches=${ShellShadowLog.mismatchCount} " +
                            "origins=${ShellShadowLog.originCounts}",
                    )
                    ShellShadowLog.mismatches.forEach { println("shell-shadow-mismatch $it") }
                }
                val comparisons = ShellShadowLog.comparisons
                val mismatches = ShellShadowLog.mismatches
                assertTrue("no shadow comparison ran", comparisons > 0)
                assertEquals("shadow mismatches", emptyList<ShellShadowMismatch>(), mismatches)
            }
        }
}

/**
 * Seeds the shell's Settings section the way a restored Activity brings it back: writes the saved section fields and
 * sends the navigator the matching [ShellNavEvent.SectionRestored], so a seeded test starts both owners in agreement.
 */
internal fun MainShellStateHolder.restoreSection(
    section: MainSection,
    detail: SettingsDetail? = null,
) {
    sectionState.settingsDetailName = detail?.name
    sectionState.sectionName = section.name
    dispatchShellNav(ShellNavEvent.SectionRestored(section, detail))
}
