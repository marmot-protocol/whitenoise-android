package dev.ipf.whitenoise.android.ui.profile

import dev.ipf.whitenoise.android.ui.settings.settingsRouteDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/** The picker has two real entry points; Back must follow its captured parent. */
class ProfileNavigationMotionTest {
    @Test fun nestedPickerBackReturnsTowardGroupsInsteadOfAdvancingAgain() {
        val groups = ProfileSheetPage.GROUPS_IN_COMMON
        val picker = ProfileSheetPage.ADD_TO_GROUPS
        val root = ProfileSheetPage.PROFILE
        assertEquals(0, profileSheetPageDepth(root, root))
        assertEquals(1, profileSheetPageDepth(groups, root))
        assertEquals(2, profileSheetPageDepth(picker, groups))
        assertEquals(
            1,
            settingsRouteDirection(profileSheetPageDepth(groups, groups), profileSheetPageDepth(picker, groups)),
        )
        assertEquals(
            -1,
            settingsRouteDirection(profileSheetPageDepth(picker, groups), profileSheetPageDepth(groups, groups)),
        )
        assertEquals(1, profileSheetPageDepth(picker, root))
        assertEquals(-1, settingsRouteDirection(profileSheetPageDepth(picker, root), profileSheetPageDepth(root, root)))
    }
}
