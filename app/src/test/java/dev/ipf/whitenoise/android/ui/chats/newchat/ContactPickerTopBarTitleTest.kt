package dev.ipf.whitenoise.android.ui.chats.newchat

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactPickerTopBarTitleTest {
    private val pickerTitle = "Add members"
    private val oneMember = "1 member"
    private val membersFormat = "%1\$d members"

    @Test
    fun emptySelectionUsesCallerSuppliedPickerTitle() {
        assertEquals(
            pickerTitle,
            contactPickerTopBarTitle(
                pickerTitle = pickerTitle,
                selectedCount = 0,
                oneMember = oneMember,
                membersFormat = membersFormat,
            ),
        )
    }

    @Test
    fun singleSelectionUsesSingularMemberLabel() {
        assertEquals(
            oneMember,
            contactPickerTopBarTitle(
                pickerTitle = pickerTitle,
                selectedCount = 1,
                oneMember = oneMember,
                membersFormat = membersFormat,
            ),
        )
    }

    @Test
    fun multipleSelectionUsesPluralMembersCount() {
        assertEquals(
            "3 members",
            contactPickerTopBarTitle(
                pickerTitle = pickerTitle,
                selectedCount = 3,
                oneMember = oneMember,
                membersFormat = membersFormat,
            ),
        )
    }
}
