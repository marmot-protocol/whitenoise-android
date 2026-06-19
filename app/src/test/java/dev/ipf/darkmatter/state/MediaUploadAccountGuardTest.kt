package dev.ipf.darkmatter.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUploadAccountGuardTest {
    @Test
    fun acceptsOnlyTheStillActiveAccountInTheSameMediaSession() {
        assertTrue(shouldAcceptMediaUploadForAccount("acct-a", 7, "acct-a", 7))
    }

    @Test
    fun rejectsMissingSwitchedOrStaleAccountSessions() {
        assertFalse(shouldAcceptMediaUploadForAccount(null, 7, "acct-a", 7))
        assertFalse(shouldAcceptMediaUploadForAccount("acct-a", 7, null, 7))
        assertFalse(shouldAcceptMediaUploadForAccount("acct-a", 7, "acct-b", 7))
        assertFalse(shouldAcceptMediaUploadForAccount("acct-a", 7, "acct-a", 8))
    }
}
