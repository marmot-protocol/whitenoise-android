package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Cards from the earlier expanded-choice build return to the standard template; other custom views stay. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationLegacyReactionRailTest {
    private val context get() = RuntimeEnvironment.getApplication()

    /** A received-reaction card's own custom views pass through untouched, as the same object. */
    @Test
    fun unflaggedCustomViewsAreReturnedUnchanged() {
        val card = decorated(Bundle())

        assertSame(card, withoutLegacyReactionRail(context, card))
    }

    /** A card carrying only the earlier routing copy drops it, keeps its views and leaves the original intact. */
    @Test
    fun routingCopyIsDroppedWithoutTouchingTheViews() {
        val card = decorated(Bundle().apply { putBundle(LEGACY_EXTRA_FIXED_REACTION_TARGET, Bundle()) })

        val cleaned = withoutLegacyReactionRail(context, card)

        assertFalse(cleaned.extras.containsKey(LEGACY_EXTRA_FIXED_REACTION_TARGET))
        assertNotNull(cleaned.contentView)
        assertNotNull(cleaned.bigContentView)
        assertTrue(card.extras.containsKey(LEGACY_EXTRA_FIXED_REACTION_TARGET))
    }

    /** The earlier image-choice layout is removed together with its marker and routing copy. */
    @Test
    fun flaggedExpandedLayoutIsRemoved() {
        val card =
            decorated(
                Bundle().apply {
                    putBoolean(LEGACY_EXTRA_EXPANDED_REACTION_CHOICES, true)
                    putBundle(LEGACY_EXTRA_FIXED_REACTION_TARGET, Bundle())
                },
            )

        val cleaned = withoutLegacyReactionRail(context, card)

        assertNull(cleaned.bigContentView)
        assertFalse(cleaned.extras.containsKey(LEGACY_EXTRA_EXPANDED_REACTION_CHOICES))
        assertFalse(cleaned.extras.containsKey(LEGACY_EXTRA_FIXED_REACTION_TARGET))
    }

    /** Cleanup keeps the card's group alert behavior: a child stays CHILDREN and a silent write stays silent. */
    @Test
    fun cleanupKeepsGroupAlertBehavior() {
        val rail = Bundle().apply { putBoolean(LEGACY_EXTRA_EXPANDED_REACTION_CHOICES, true) }
        val child = decorated(rail, groupAlert = NotificationCompat.GROUP_ALERT_CHILDREN)
        val silent = decorated(rail, silent = true)

        assertEquals(Notification.GROUP_ALERT_CHILDREN, withoutLegacyReactionRail(context, child).groupAlertBehavior)
        val quiet = withoutLegacyReactionRail(context, silent)
        assertTrue(quiet.wasBuiltSilent())
        assertTrue(quiet.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    /** A grouped card with collapsed and expanded custom views, the given extras and alert behavior. */
    private fun decorated(
        extras: Bundle,
        silent: Boolean = false,
        groupAlert: Int = NotificationCompat.GROUP_ALERT_ALL,
    ): Notification =
        NotificationCompat
            .Builder(context, "fixture")
            .setGroup("fixture-group")
            .setGroupAlertBehavior(groupAlert)
            .setSilent(silent)
            .setOnlyAlertOnce(silent)
            .setSmallIcon(R.drawable.ic_stat_whitenoise)
            .setContentTitle("Fixture contact")
            .setCustomContentView(RemoteViews(context.packageName, R.layout.notification_reaction_inline))
            .setCustomBigContentView(RemoteViews(context.packageName, R.layout.notification_reaction_inline))
            .addExtras(extras)
            .build()
}
