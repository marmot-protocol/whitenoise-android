package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import dev.ipf.whitenoise.android.ManualDeviceFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * #3311 on real SystemUI: posts distinct direct-chat and group cards through the production presenter, opens the
 * shade, and reads the pixels SystemUI actually drew in each card's identity slot. Builder extras and Person icons
 * alone cannot prove what the visible circle shows, so this inspects the rendered conversation icon, the long-message
 * card's picture and the small app icon, and saves shade screenshots for before/after review.
 *
 * Opt in with `-e allowShadeProbe true` on an emulator or a disposable, unlocked device with its screen on. It posts
 * synthetic cards for a fixture account only and removes just those cards, their shortcuts and their conversation
 * channels afterwards.
 */
@ManualDeviceFixture
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
class NotificationConversationAvatarAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** Checks the opt-in and device state, then grants posting so the presenter writes real cards. */
    @Before
    fun prepareDevice() {
        assumeTrue(
            "Shade probing requires -e $ARG_ALLOW_SHADE_PROBE true",
            InstrumentationRegistry.getArguments().getString(ARG_ALLOW_SHADE_PROBE) == "true",
        )
        assertTrue("screen must be on", context.getSystemService(PowerManager::class.java).isInteractive)
        assertFalse("device must be unlocked", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        removeFixtureCards()
        device.pressHome()
    }

    /** Leaves no synthetic card, fixture shortcut, fixture channel or open shade behind, and touches nothing else. */
    @After
    fun cleanUp() {
        removeFixtureCards()
        device.pressHome()
    }

    /**
     * Cancels only this fixture's cards, removes only its own conversation shortcuts, and deletes the per-conversation
     * channels posting created for them ("Ruby Identity", "Blue Identity Group" and "Magenta Identity").
     */
    private fun removeFixtureCards() {
        val ids = names.keys.mapNotNull { conversationShortcutId(NotificationFixtureDefaults.ACCOUNT_REF, it) }.toSet()
        manager.activeNotifications
            .filter { it.notification.shortcutId in ids }
            .forEach { manager.cancel(it.tag, it.id) }
        ShortcutManagerCompat.removeDynamicShortcuts(context, ids.toList())
        manager.notificationChannels
            .filter { it.conversationId in ids }
            .forEach { manager.deleteNotificationChannel(it.id) }
    }

    /**
     * Brand-new conversations: the very first card of a direct chat shows the peer in its circle and a group's shows
     * the group, in the heads-up, collapsed and expanded states, and a long first message keeps its picture. The
     * heads-up circle must be found, so a banner that never appeared fails instead of being skipped.
     */
    @Test
    fun brandNewConversationsFirstCardsDrawTheConversationIdentity() {
        val presenter = LocalNotificationPresenter(context).also { it.ensureChannels() }
        // Each fixture conversation is new, so these are the first cards they ever post, one write each.
        post(presenter, DM_GROUP, isDm = true, picture = solid(Color.RED), sender = solid(Color.RED), body = "first")
        val headsUp = capture("first_card_heads_up")
        // Sample the banner now, while the live hierarchy still describes it: once other cards post or the shade
        // opens, the same view ids resolve to other bounds and would sample the wrong part of this screenshot.
        val headsUpCircle = colorOf(headsUp, DM_NAME, "conversation_icon")
        post(presenter, GROUP, isDm = false, picture = solid(Color.BLUE), sender = solid(Color.GREEN), body = "first")
        post(presenter, LONG_GROUP, isDm = true, picture = solid(Color.MAGENTA), sender = null, body = LONG_BODY)

        device.openNotification()
        device.wait(Until.hasObject(By.text(DM_NAME)), WAIT_MS)
        val collapsed = capture("first_card_collapsed")
        assertColor("first direct-chat circle", Color.RED, colorOf(collapsed, DM_NAME, "conversation_icon"))
        assertColor("first group circle", Color.BLUE, colorOf(collapsed, GROUP_NAME, "conversation_icon"))
        assertColor("first long-message picture", Color.MAGENTA, colorOf(collapsed, LONG_NAME, "right_icon"))
        // assertColor fails when the circle was never resolved, so a missing heads-up cannot pass silently.
        assertColor("first heads-up circle", Color.RED, headsUpCircle)

        device.findObject(By.text(DM_NAME))?.swipe(Direction.DOWN, EXPAND_SWIPE)
        device.waitForIdle()
        val expanded = capture("first_card_expanded")
        assertColor("expanded first direct-chat circle", Color.RED, colorOf(expanded, DM_NAME, "conversation_icon"))
        val appIcon = colorOf(expanded, LONG_NAME, "icon")
        if (appIcon != null) assertFalse("the small app icon is not the identity", close(Color.MAGENTA, appIcon))
    }

    /** A second message in the same conversation keeps the same picture in its circle. */
    @Test
    fun laterMessagesKeepTheConversationIdentity() {
        val presenter = LocalNotificationPresenter(context).also { it.ensureChannels() }
        post(presenter, DM_GROUP, isDm = true, picture = solid(Color.RED), sender = solid(Color.RED), body = "first")
        post(presenter, DM_GROUP, isDm = true, picture = solid(Color.RED), sender = solid(Color.RED), body = "second")

        device.openNotification()
        device.wait(Until.hasObject(By.text(DM_NAME)), WAIT_MS)
        val shade = capture("second_card_collapsed")
        assertColor("second direct-chat circle", Color.RED, colorOf(shade, DM_NAME, "conversation_icon"))
    }

    /** Posts one message through the production presenter with ready pictures, as the first post supplies them. */
    @Suppress("LongParameterList") // Mirrors the presenter's picture inputs for one synthetic conversation.
    private fun post(
        presenter: LocalNotificationPresenter,
        groupIdHex: String,
        isDm: Boolean,
        picture: Bitmap?,
        sender: Bitmap?,
        body: String,
    ) {
        val name = names.getValue(groupIdHex)
        val update =
            notificationUpdate(
                groupIdHex = groupIdHex,
                isDm = isDm,
                groupName = name.takeUnless { isDm },
                messageIdHex = "$groupIdHex-${body.hashCode()}",
                sender = notificationUser(displayName = if (isDm) name else "Green Sender"),
                previewText = body,
            )
        val posted =
            runBlocking {
                presenter.show(
                    update,
                    conversationTitleOverride = name,
                    conversationAvatarBitmap = picture,
                    senderAvatarBitmap = sender,
                    shortNpub = { "npub" },
                )
            }
        assertTrue("$name must post", posted)
        device.waitForIdle()
        Thread.sleep(SETTLE_MS)
    }

    /** Saves the current screen for review and returns its pixels. */
    private fun capture(name: String): Bitmap {
        val shot = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "screenshot unavailable" }
        val directory = File(context.getExternalFilesDir(null), "notification-identity").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        return shot
    }

    /** The average color SystemUI drew in the named view of the card titled [title], or null when absent. */
    private fun colorOf(
        shot: Bitmap,
        title: String,
        viewId: String,
    ): Int? {
        var node: UiObject2? = device.findObject(By.text(title))
        var view: UiObject2? = null
        var depth = 0
        while (node != null && view == null && depth < MAX_ROW_DEPTH) {
            view = node.findObject(By.res("android", viewId))
            node = node.parent
            depth++
        }
        return view?.let { averageColor(shot, it.visibleBounds) }
    }

    /** Averages the central quarter of [bounds], away from the circle's anti-aliased edge. */
    private fun averageColor(
        shot: Bitmap,
        bounds: Rect,
    ): Int {
        val inset = Rect(bounds).apply { inset(width() / 4, height() / 4) }
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        for (x in inset.left until inset.right.coerceAtMost(shot.width)) {
            for (y in inset.top until inset.bottom.coerceAtMost(shot.height)) {
                val pixel = shot.getPixel(x, y)
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
                count++
            }
        }
        check(count > 0) { "empty identity bounds $bounds" }
        return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    /** Fails with the drawn color when the identity slot is missing or shows another picture. */
    private fun assertColor(
        what: String,
        expected: Int,
        actual: Int?,
    ) {
        assertNotNull("$what was not drawn", actual)
        assertTrue("$what drew #${Integer.toHexString(checkNotNull(actual))}", close(expected, actual))
    }

    /** Within a small tolerance per channel for SystemUI scaling and color filtering. */
    private fun close(
        expected: Int,
        actual: Int,
    ): Boolean =
        abs(Color.red(expected) - Color.red(actual)) <= TOLERANCE &&
            abs(Color.green(expected) - Color.green(actual)) <= TOLERANCE &&
            abs(Color.blue(expected) - Color.blue(actual)) <= TOLERANCE

    /** A single-color picture whose rendered pixels identify which image SystemUI drew. */
    private fun solid(color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(PICTURE_PX, PICTURE_PX, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return bitmap
    }

    private companion object {
        const val ARG_ALLOW_SHADE_PROBE = "allowShadeProbe"
        const val DM_GROUP = "identity-dm"
        const val GROUP = "identity-group"
        const val LONG_GROUP = "identity-long"
        const val DM_NAME = "Ruby Identity"
        const val GROUP_NAME = "Blue Identity Group"
        const val LONG_NAME = "Magenta Identity"
        val names = mapOf(DM_GROUP to DM_NAME, GROUP to GROUP_NAME, LONG_GROUP to LONG_NAME)
        val LONG_BODY = "A long message that crosses the expanded text threshold. ".repeat(6).trim()
        const val WAIT_MS = 5_000L
        const val SETTLE_MS = 1_500L
        const val MAX_ROW_DEPTH = 10
        const val TOLERANCE = 48
        const val PICTURE_PX = 96
        const val PNG_QUALITY = 100
        const val EXPAND_SWIPE = 0.5f
    }
}
