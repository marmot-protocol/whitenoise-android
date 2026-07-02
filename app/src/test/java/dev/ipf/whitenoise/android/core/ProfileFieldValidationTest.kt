package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileFieldValidationTest {
    @Test
    fun pictureUrlAcceptsBlankAndSanitizableHttpsUrls() {
        assertTrue(ProfileFieldValidation.isAcceptablePictureUrl(""))
        assertTrue(ProfileFieldValidation.isAcceptablePictureUrl("   "))
        assertTrue(ProfileFieldValidation.isAcceptablePictureUrl("https://example.com/a.png"))
    }

    @Test
    fun pictureUrlRejectsMalformedOrUnsafeUrls() {
        assertFalse(ProfileFieldValidation.isAcceptablePictureUrl("http://example.com/a.png")) // not https
        assertFalse(ProfileFieldValidation.isAcceptablePictureUrl("not a url"))
        assertFalse(ProfileFieldValidation.isAcceptablePictureUrl("https://127.0.0.1/a.png")) // private host
    }

    @Test
    fun nip05AcceptsBlankAndWellFormedIdentifiers() {
        assertTrue(ProfileFieldValidation.isAcceptableNip05(""))
        assertTrue(ProfileFieldValidation.isAcceptableNip05("alice@example.com"))
        assertTrue(ProfileFieldValidation.isAcceptableNip05("_@example.com"))
        assertTrue(ProfileFieldValidation.isAcceptableNip05("a.b+tag@sub.example.co.uk"))
    }

    @Test
    fun nip05RejectsMalformedIdentifiers() {
        assertFalse(ProfileFieldValidation.isAcceptableNip05("alice")) // no @
        assertFalse(ProfileFieldValidation.isAcceptableNip05("alice@localhost")) // no domain dot
        assertFalse(ProfileFieldValidation.isAcceptableNip05("a@b@c.com")) // two @
        assertFalse(ProfileFieldValidation.isAcceptableNip05("alice @example.com")) // whitespace
    }

    @Test
    fun lud16AcceptsBlankAndWellFormedAddresses() {
        assertTrue(ProfileFieldValidation.isAcceptableLud16(""))
        assertTrue(ProfileFieldValidation.isAcceptableLud16("   "))
        assertTrue(ProfileFieldValidation.isAcceptableLud16("alice@example.com"))
        assertTrue(ProfileFieldValidation.isAcceptableLud16("Alice@EXAMPLE.com"))
        assertTrue(ProfileFieldValidation.isAcceptableLud16("a_b-c.d@sub.example.co.uk"))
    }

    @Test
    fun lud16RejectsMalformedAddressesBeforeResolution() {
        assertFalse(ProfileFieldValidation.isAcceptableLud16("alice"))
        assertFalse(ProfileFieldValidation.isAcceptableLud16("alice@localhost"))
        assertFalse(ProfileFieldValidation.isAcceptableLud16("a@b@example.com"))
        assertFalse(ProfileFieldValidation.isAcceptableLud16("al/ice@example.com"))
        assertFalse(ProfileFieldValidation.isAcceptableLud16("alice@example.com/path"))
        assertFalse(ProfileFieldValidation.isAcceptableLud16("alice@example.com?x=y"))
        assertFalse(ProfileFieldValidation.isAcceptableLud16("alice@example.com#fragment"))
        assertFalse(ProfileFieldValidation.isAcceptableLud16("alice@https://example.com"))
        assertFalse(ProfileFieldValidation.isAcceptableLud16("alice@example..com"))
        assertFalse(ProfileFieldValidation.isAcceptableLud16("alice@-example.com"))
        assertFalse(ProfileFieldValidation.isAcceptableLud16("alice@example-.com"))
    }
}
