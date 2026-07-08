package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MissingRelayListKindFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.settings.labelRes
import org.junit.Assert.assertEquals
import org.junit.Test

class MissingRelayListKindLabelTest {
    @Test
    fun missingRelayListKindsUseUserFacingLabels() {
        assertEquals(R.string.nip_65, MissingRelayListKindFfi.NIP65.labelRes)
        assertEquals(R.string.inbox, MissingRelayListKindFfi.INBOX.labelRes)
    }
}
