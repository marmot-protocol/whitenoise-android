package dev.ipf.whitenoise.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * A post-condition for one test: starts a fresh [ShellRouteAudit], and after the test asserts that at least one
 * audit of the route owner's stack ran and that none found a malformed stack or a saved section that disagrees with
 * it. The count is printed as one `shell-route-audit` line, which lands in the test's XML report.
 *
 * It only shows the route owner was driven and its stack stayed well formed. It is not evidence that the shell
 * behaves as on master: the reducer builds canonical stacks by construction, so wrong events pass it, and the test's
 * own assertions are what pin behaviour.
 */
class ShellRouteAuditRule : TestRule {
    /** Wraps [base] with the reset before and the audit assertions after. */
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            /** Runs the test, then records and asserts its route audits. */
            override fun evaluate() {
                ShellRouteAudit.reset()
                try {
                    base.evaluate()
                } finally {
                    println(
                        "shell-route-audit ${description.className}#${description.methodName} " +
                            "checks=${ShellRouteAudit.checks} findings=${ShellRouteAudit.findingCount} " +
                            "origins=${ShellRouteAudit.originCounts}",
                    )
                    ShellRouteAudit.findings.forEach { println("shell-route-audit-finding $it") }
                }
                val checks = ShellRouteAudit.checks
                val findings = ShellRouteAudit.findings
                assertTrue("no route audit ran", checks > 0)
                assertEquals("route audit findings", emptyList<ShellRouteFinding>(), findings)
            }
        }
}

/**
 * Seeds the shell's Settings section the way a restored Activity brings it back, as one
 * [ShellNavEvent.SectionRestored] that the section state then saves.
 */
internal fun MainShellStateHolder.restoreSection(
    section: MainSection,
    detail: SettingsDetail? = null,
) {
    dispatchShellNav(ShellNavEvent.SectionRestored(section, detail))
}

/** Opens [item] with [open] through the route owner, as a list tap that has finished preparing does. */
internal fun MainShellStateHolder.openForTest(
    item: dev.ipf.whitenoise.android.state.ChatListItem,
    open: ConversationOpenContext = ConversationOpenContext(),
) {
    dispatchShellNav(ShellNavEvent.OpenConversation(conversationRoute(item, open)))
}

/** Closes the open conversation through the route owner. */
internal fun MainShellStateHolder.closeForTest() {
    dispatchShellNav(ShellNavEvent.CloseConversation)
}
