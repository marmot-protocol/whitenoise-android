package dev.ipf.whitenoise.android.ui.navigation

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The route audit that replaced the shadow comparison is not vacuous: it flags each malformed stack shape, and a
 * saved section that no longer follows the stack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShellRouteAuditTest {
    private val conversation = ShellRoute.Conversation("account-a", "group-1", ConversationOpenContext())

    /** Every stack the reducer can build passes, including opener chains, and each broken shape is named. */
    @Test
    fun theShapeCheckFlagsEveryMalformedStack() {
        assertNull(shellRouteShapeProblem(CHATS_ROOT + settingsChain(SettingsDetail.Relays) + conversation))
        val diagnostics = CHATS_ROOT + settingsChain(SettingsDetail.Developer) + ShellRoute.Diagnostics
        assertNull(shellRouteShapeProblem(diagnostics))
        // A page opened from another keeps that opener below it instead of its fixed parents (#4095).
        val fromAbout = CHATS_ROOT + settingsChain(SettingsDetail.About) + ShellRoute.Settings(SettingsDetail.Developer)
        assertNull(shellRouteShapeProblem(fromAbout))
        val relaysFromHome = listOf(ShellRoute.Settings(null), ShellRoute.Settings(SettingsDetail.Relays))
        assertNull(shellRouteShapeProblem(CHATS_ROOT + relaysFromHome))
        assertNotNull(shellRouteShapeProblem(fromAbout + ShellRoute.Settings(SettingsDetail.About)))

        assertNotNull(shellRouteShapeProblem(emptyList()))
        assertNotNull(shellRouteShapeProblem(listOf(ShellRoute.Settings(null))))
        assertNotNull(shellRouteShapeProblem(CHATS_ROOT + ShellRoute.Chats))
        assertNotNull(shellRouteShapeProblem(CHATS_ROOT + ShellRoute.Settings(SettingsDetail.Relays)))
        assertNotNull(shellRouteShapeProblem(CHATS_ROOT + ShellRoute.Diagnostics))
        assertNotNull(shellRouteShapeProblem(CHATS_ROOT + conversation + conversation.copy(groupIdHex = "group-2")))
        assertNotNull(shellRouteShapeProblem(CHATS_ROOT + conversation + ShellRoute.Settings(null)))
    }

    /** The audit counts every check and records a finding with its origin. */
    @Test
    fun theAuditRecordsFindings() {
        ShellRouteAudit.reset()
        assertTrue(ShellRouteAudit.check("good", CHATS_ROOT))
        assertFalse(ShellRouteAudit.check("bad", CHATS_ROOT + ShellRoute.Diagnostics))
        assertEquals(2L, ShellRouteAudit.checks)
        assertEquals(1L, ShellRouteAudit.findingCount)
        assertEquals("bad", ShellRouteAudit.findings.single().origin)
        ShellRouteAudit.reset()
    }

    /**
     * A section state the navigator no longer saves through, as a replaced Activity's is, reports its stale saved
     * section once the stack moves on.
     */
    @Test
    fun aSavedSectionThatStopsFollowingTheStackIsReported() {
        val navigator = ShellNavigator()
        val first = MainShellSectionState(SavedStateHandle(), navigator)
        first.bind("personal", 4)
        first.dispatchSection(ShellNavEvent.OpenSettings(SettingsDetail.Appearance))
        assertNull(first.persistenceProblem())

        val replacement = MainShellSectionState(SavedStateHandle(), navigator)
        replacement.bind("personal", 4)
        navigator.dispatch(ShellNavEvent.OpenSettings(SettingsDetail.Relays))

        assertNull(replacement.persistenceProblem())
        assertNotNull(first.persistenceProblem())
    }
}
