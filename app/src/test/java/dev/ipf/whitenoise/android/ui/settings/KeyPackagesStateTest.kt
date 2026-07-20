package dev.ipf.whitenoise.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPackagesStateTest {
    @Test
    fun initialStateContainsStableSectionsButNoEmptyPlaceholder() {
        val state =
            keyPackagesState(
                hasActiveAccount = false,
                loaded = false,
                loading = false,
                working = false,
                packageCount = 0,
            )

        assertEquals(
            listOf(KeyPackagesSection.Publishing, KeyPackagesSection.Published),
            state.sections,
        )
        assertFalse(state.actionsEnabled)
        assertFalse(state.showLoadingIndicator)
        assertEquals(0, state.packageCount)
    }

    @Test
    fun loadedEmptyStateAddsTheEmptySection() {
        val state =
            keyPackagesState(
                hasActiveAccount = true,
                loaded = true,
                loading = false,
                working = false,
                packageCount = 0,
            )

        assertEquals(
            listOf(
                KeyPackagesSection.Publishing,
                KeyPackagesSection.Published,
                KeyPackagesSection.Empty,
            ),
            state.sections,
        )
        assertTrue(state.actionsEnabled)
    }

    @Test
    fun packageRowsStayVisibleDuringRefreshWhileActionsAreDisabled() {
        val state =
            keyPackagesState(
                hasActiveAccount = true,
                loaded = true,
                loading = true,
                working = false,
                packageCount = 3,
            )

        assertTrue(KeyPackagesSection.PackageList in state.sections)
        assertTrue(state.showLoadingIndicator)
        assertFalse(state.actionsEnabled)
        assertTrue(state.packageActionsEnabled)
        assertEquals(3, state.packageCount)
    }

    @Test
    fun mutationDisablesToolbarAndPackageActions() {
        val state =
            keyPackagesState(
                hasActiveAccount = true,
                loaded = true,
                loading = false,
                working = true,
                packageCount = 3,
            )

        assertFalse(state.actionsEnabled)
        assertFalse(state.packageActionsEnabled)
        assertTrue(KeyPackagesSection.PackageList in state.sections)
    }
}
