package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Invariant gate for #3311: every rich message card, in the conversation template and the long-message text block,
 * carries one shared conversation identity snapshot through first post, enrichment, private contact pictures and
 * every rewrite of a live card, while redacted and hidden-preview cards stay generic.
 */
class NotificationIdentityImageCoverageTest {
    /** The presenter sets the picture only through the shared snapshot, and never on a redacted card. */
    @Test
    fun richCardsShareOneIdentitySnapshotAndRedactedCardsGetNone() {
        val presenter = source("LocalNotificationPresenter.kt")
        assertFalse("identity pictures go through setConversationIdentityImage", ".setLargeIcon(" in presenter)
        val firstPost =
            presenter
                .substringAfter("NotificationStyleChoice.Messaging -> {")
                .substringBefore("messagingPost =")
        val guarded =
            firstPost
                .substringAfter("if (!redactContent) {\n")
                .substringBefore("\n                            }")
        assertTrue("the first payload carries the identity picture", "setConversationIdentityImage(" in guarded)
        assertTrue("the shortcut monogram seed keeps card and shortcut alike", "seed = messagingShortcutId" in guarded)
        val image = source("NotificationIdentityImage.kt").extensionBody("identityImage")
        assertTrue("a group never borrows a member's face", "senderAvatar.takeIf { directChat }" in image)
    }

    /** Late pictures rewrite both card templates with the same snapshot instead of only the conversation style. */
    @Test
    fun enrichmentRewritesBothTemplatesWithTheSnapshot() {
        val body = source("LocalNotificationPresenter.kt").functionBody("enrichedIdentityNotification")
        assertTrue("conversation template", "enrichedMessagingStyle(active, sender)" in body)
        assertTrue("long-message text block", "EXTRA_EXPANDED_SINGLE_MESSAGE_SENDER" in body)
        assertTrue("the same snapshot", "setConversationIdentityImage(identity)" in body)
        assertTrue("one silent rewrite", "setOnlyAlertOnce(true)" in body)
    }

    /** Every final write applies the current private contact choice to a direct chat's picture. */
    @Test
    fun finalWriteRefreshesTheDirectChatPictureFromTheContactChoice() {
        val presenter = source("LocalNotificationPresenter.kt")
        val post = presenter.functionBody("postNotificationSafely")
        assertTrue("the final write uses the combined refresh", "withCurrentContactPictures(notification)" in post)
        val refresh = presenter.functionBody("withCurrentContactPictures")
        assertTrue("the identity picture follows the contact choice", "withCurrentIdentityContact(" in refresh)
        assertTrue("redacted cards are never refreshed", "EXTRA_CONTENT_REDACTED" in refresh)
    }

    /** A late picture or contact refresh rewrites the live card, so no rewrite may drop or replace the identity. */
    @Test
    fun liveCardRewritesNeverDropTheIdentityPicture() {
        val presenter = source("LocalNotificationPresenter.kt")
        assertFalse("no path clears the picture", "setLargeIcon(null" in presenter)
        val refresh = source("NotificationIdentityImage.kt").functionBody("withCurrentIdentityContact")
        assertTrue("only a direct chat's own contact changes its picture", "IDENTITY_CONTACT" in refresh)
        assertTrue("a cleared choice falls back to the monogram", "notificationMonogramBitmap(title, seed)" in refresh)
    }

    /** Hidden-preview and lock-screen versions are rebuilt from scratch without any identity picture or key. */
    @Test
    fun genericVersionsCarryNoIdentity() {
        val privacy = source("NotificationPreviewPrivacy.kt")
        listOf("notificationWithoutPreview", "genericPublicVersion", "previewRoutingExtras").forEach { name ->
            val body = privacy.functionBody(name)
            assertFalse("$name must not set a picture", "LargeIcon" in body || "IdentityImage" in body)
            assertFalse("$name must not copy the identity extras", "EXTRA_IDENTITY_IMAGE" in body)
        }
        val redacted = source("LocalNotificationPresenter.kt").functionBody("redactedPublicVersion")
        assertFalse("the lock-screen version stays generic", "LargeIcon" in redacted || "IdentityImage" in redacted)
    }

    /** Body of an extension function such as `fun Receiver.name(...)`, which [functionBody] does not match. */
    private fun String.extensionBody(name: String): String {
        val start =
            Regex("""\bfun\s+[\w.]+\.${Regex.escape(name)}\s*\(""").find(this)?.range?.last
                ?: error("Missing extension function $name")
        val parameters = kotlinBlockFrom(start, "parameters for $name", opening = '(', closing = ')')
        return kotlinBlockFrom(start + parameters.length, "function $name", searchForOpening = true)
    }

    /** Reads one notifications source by file name. */
    private fun source(name: String): String = File(notificationsRoot(), name).readText()

    /** The notifications package, whether Gradle runs from the module or the repository. */
    private fun notificationsRoot(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/notifications"),
            File("app/src/main/java/dev/ipf/whitenoise/android/notifications"),
        ).firstOrNull { it.exists() } ?: error("Missing notifications source root")
}
