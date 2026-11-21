package dev.ipf.whitenoise.android.notifications

import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class NotificationProfileEvaluatorTest {
    private val utc = ZoneId.of("UTC")
    private val berlin = ZoneId.of("Europe/Berlin")
    private val newYork = ZoneId.of("America/New_York")

    private fun activeAt(
        schedule: NotificationProfileSchedule,
        local: String,
        zone: ZoneId = utc,
    ): Boolean = scheduleWindowContaining(schedule, localMs(local, zone), zone) != null

    private fun activeAtInstant(
        schedule: NotificationProfileSchedule,
        instant: String,
        zone: ZoneId,
    ): Boolean = scheduleWindowContaining(schedule, Instant.parse(instant).toEpochMilli(), zone) != null

    @Test
    fun sameDayWindowIncludesItsStartAndExcludesItsEnd() {
        val work = profileSchedule("09:00", "17:00", WeekdaysOnly)
        assertFalse(activeAt(work, "2026-11-20T08:59:59"))
        assertTrue(activeAt(work, "2026-11-20T09:00"))
        assertTrue(activeAt(work, "2026-11-20T16:59:59"))
        assertFalse(activeAt(work, "2026-11-20T17:00"))
        assertFalse(activeAt(work, "2026-11-21T10:00"))
    }

    @Test
    fun overnightWindowBelongsToTheDayItStarts() {
        val sleep = profileSchedule("22:00", "08:00", WeekdaysOnly)
        // Friday 22:00 runs into Saturday morning even though Saturday is not selected.
        assertTrue(activeAt(sleep, "2026-11-20T22:00"))
        assertTrue(activeAt(sleep, "2026-11-21T07:59"))
        assertFalse(activeAt(sleep, "2026-11-21T08:00"))
        // Sunday night is not covered, so early Monday is quiet only from Monday's own window.
        assertFalse(activeAt(sleep, "2026-11-22T23:00"))
        assertFalse(activeAt(sleep, "2026-11-23T03:00"))
        assertTrue(activeAt(sleep, "2026-11-23T22:30"))
    }

    @Test
    fun disabledOrInvalidSchedulesNeverMatch() {
        assertFalse(activeAt(profileSchedule("22:00", "08:00", enabled = false), "2026-11-20T23:00"))
        assertFalse(activeAt(profileSchedule("22:00", "22:00"), "2026-11-20T22:00"))
        assertFalse(activeAt(profileSchedule("22:00", "08:00", emptySet()), "2026-11-20T23:00"))
    }

    @Test
    fun startInsideADaylightSavingGapMovesForwardByTheGap() {
        val early = profileSchedule("02:30", "06:00")
        assertFalse(activeAt(early, "2026-03-29T03:15", berlin))
        assertTrue(activeAt(early, "2026-03-29T03:30", berlin))
        // America/New_York loses 02:00-03:00 on 8 March; a 01:00-03:00 window lasts one real hour.
        val night = profileSchedule("01:00", "03:00")
        assertTrue(activeAtInstant(night, "2026-03-08T06:30:00Z", newYork))
        assertFalse(activeAtInstant(night, "2026-03-08T07:00:00Z", newYork))
    }

    @Test
    fun endInsideADaylightSavingOverlapUsesTheFirstOccurrence() {
        val sleep = profileSchedule("22:00", "02:30", setOf(DayOfWeek.SATURDAY))
        // Berlin repeats 02:00-03:00 on 25 October: 02:15 CEST is in, 02:45 CEST and 02:15 CET are out.
        assertTrue(activeAtInstant(sleep, "2026-10-25T00:15:00Z", berlin))
        assertFalse(activeAtInstant(sleep, "2026-10-25T00:45:00Z", berlin))
        assertFalse(activeAtInstant(sleep, "2026-10-25T01:15:00Z", berlin))
        val late = profileSchedule("00:30", "01:30", setOf(DayOfWeek.SUNDAY))
        assertTrue(activeAtInstant(late, "2026-11-01T05:15:00Z", newYork))
        assertFalse(activeAtInstant(late, "2026-11-01T05:45:00Z", newYork))
        assertFalse(activeAtInstant(late, "2026-11-01T06:15:00Z", newYork))
    }

    @Test
    fun aWindowThatCollapsesAcrossAGapIsInactive() {
        val collapsed = profileSchedule("02:30", "03:00")
        assertFalse(activeAt(collapsed, "2026-03-29T03:10", berlin))
        assertFalse(activeAt(collapsed, "2026-03-29T03:40", berlin))
        assertTrue(activeAt(collapsed, "2026-03-30T02:45", berlin))
    }

    @Test
    fun schedulesFollowTheZoneButManualExpiryDoesNot() {
        val sleep = profileSchedule("22:00", "07:00")
        val now = Instant.parse("2026-11-20T22:30:00Z").toEpochMilli()
        assertTrue(scheduleWindowContaining(sleep, now, utc) != null)
        assertTrue(scheduleWindowContaining(sleep, now, berlin) != null)
        assertFalse(scheduleWindowContaining(sleep, now, newYork) != null)
        val manual =
            NotificationProfilesState(
                profiles = listOf(testProfile("focus")),
                manual = NotificationProfileManual.On("focus", now + 3_600_000L),
            )
        listOf(utc, berlin, newYork).forEach { zone ->
            assertEquals(now + 3_600_000L, activeNotificationProfile(manual, now, zone)?.endsAtMs)
            assertNull(activeNotificationProfile(manual, now + 3_600_000L, zone))
        }
    }

    @Test
    fun manualChoiceBeatsAnOpenScheduleAndExpiresBackToIt() {
        val now = localMs("2026-11-20T23:00", utc)
        val state =
            NotificationProfilesState(
                profiles = listOf(testProfile("sleep", profileSchedule("22:00", "07:00")), testProfile("work")),
                manual = NotificationProfileManual.On("work", now + 60_000L),
            )
        val manual = activeNotificationProfile(state, now, utc)
        assertEquals("work", manual?.profile?.id)
        assertEquals(NotificationProfileSource.MANUAL, manual?.source)
        val after = activeNotificationProfile(state, now + 60_000L, utc)
        assertEquals("sleep", after?.profile?.id)
        assertEquals(NotificationProfileSource.SCHEDULE, after?.source)
        assertEquals(localMs("2026-11-21T07:00", utc), after?.endsAtMs)
    }

    @Test
    fun aManualChoiceForADeletedProfileIsIgnored() {
        val now = localMs("2026-11-20T12:00", utc)
        val state = NotificationProfilesState(listOf(testProfile("work")), NotificationProfileManual.On("gone", null))
        assertNull(activeNotificationProfile(state, now, utc))
        val untilOff = state.copy(manual = NotificationProfileManual.On("work", null))
        assertNull(activeNotificationProfile(untilOff, now, utc)?.endsAtMs)
        assertEquals("work", activeNotificationProfile(untilOff, now, utc)?.profile?.id)
    }

    @Test
    fun aPauseHidesTheCurrentWindowButNotTheNextOne() {
        val pausedAt = localMs("2026-11-20T23:00", utc)
        val windowEnd = localMs("2026-11-21T07:00", utc)
        val state =
            NotificationProfilesState(
                profiles = listOf(testProfile("sleep", profileSchedule("22:00", "07:00"))),
                manual = NotificationProfileManual.Paused(windowEnd),
            )
        assertNull(activeNotificationProfile(state, pausedAt, utc))
        assertNull(activeNotificationProfile(state, windowEnd - 1, utc))
        assertEquals("sleep", activeNotificationProfile(state, localMs("2026-11-21T22:00", utc), utc)?.profile?.id)
    }

    @Test
    fun overlappingSchedulesPickTheLatestStartThenListOrder() {
        val now = localMs("2026-11-20T23:30", utc)
        val overlapping =
            NotificationProfilesState(
                listOf(
                    testProfile("evening", profileSchedule("20:00", "23:59")),
                    testProfile("sleep", profileSchedule("23:00", "07:00")),
                ),
            )
        assertEquals("sleep", activeNotificationProfile(overlapping, now, utc)?.profile?.id)
        val tied =
            NotificationProfilesState(
                listOf(
                    testProfile("first", profileSchedule("23:00", "06:00")),
                    testProfile("second", profileSchedule("23:00", "07:00")),
                ),
            )
        assertEquals("first", activeNotificationProfile(tied, now, utc)?.profile?.id)
    }

    @Test
    fun verdictMatrixSuppressesEverythingButAllowedChatsAndMentions() {
        val allowed = NotificationProfileChat("account-a", "group-a")
        val profile = testProfile("sleep", allowedChats = setOf(allowed))
        val other = "group-b"
        assertFalse(notificationProfileSuppresses(null, profileTestUpdate(groupIdHex = other)))
        assertFalse(notificationProfileSuppresses(profile, profileTestUpdate(groupIdHex = other, isFromSelf = true)))
        assertFalse(notificationProfileSuppresses(profile, profileTestUpdate()))
        assertFalse(notificationProfileSuppresses(profile, profileTestUpdate(groupIdHex = "GROUP-A")))
        assertFalse(notificationProfileSuppresses(profile, profileTestUpdate(reactionEmoji = "👍")))
        assertFalse(
            notificationProfileSuppresses(profile, profileTestUpdate(trigger = NotificationTriggerFfi.GROUP_INVITE)),
        )
        assertTrue(notificationProfileSuppresses(profile, profileTestUpdate(accountRef = "account-b")))
        assertTrue(notificationProfileSuppresses(profile, profileTestUpdate(groupIdHex = other)))
        assertTrue(notificationProfileSuppresses(profile, profileTestUpdate(groupIdHex = other, isDm = true)))
        assertFalse(notificationProfileSuppresses(profile, profileTestUpdate(groupIdHex = other, isMention = true)))
        assertTrue(
            notificationProfileSuppresses(
                profile,
                profileTestUpdate(groupIdHex = other, isMention = true, reactionEmoji = "👍"),
            ),
        )
        assertTrue(
            notificationProfileSuppresses(
                profile.copy(allowMentions = false),
                profileTestUpdate(groupIdHex = other, isMention = true),
            ),
        )
        assertTrue(
            notificationProfileSuppresses(
                profile,
                profileTestUpdate(groupIdHex = other, trafficClass = NotificationTrafficClassFfi.AGENT_ACTIVITY),
            ),
        )
        NotificationTriggerFfi.entries.filter { it != NotificationTriggerFfi.NEW_MESSAGE }.forEach { trigger ->
            assertTrue(
                "$trigger",
                notificationProfileSuppresses(
                    profile,
                    profileTestUpdate(groupIdHex = other, trigger = trigger, isMention = true),
                ),
            )
        }
    }

    @Test
    fun eachSwitchedOnExceptionLetsOnlyItsOwnKindThrough() {
        val quiet = testProfile("quiet", allowMentions = false)
        val other = profileTestUpdate(groupIdHex = "group-b")
        assertTrue(notificationProfileSuppresses(quiet, other))

        val person = NotificationProfilePerson("account-a", "abcd")
        val people = quiet.copy(allowedPeople = setOf(person))
        assertFalse(notificationProfileSuppresses(people, profileTestUpdate(groupIdHex = "group-b", sender = "ABCD")))
        assertFalse(notificationProfileSuppresses(people, profileTestUpdate(sender = "abcd", reactionEmoji = "👍")))
        assertTrue(notificationProfileSuppresses(people, profileTestUpdate(accountRef = "account-b", sender = "abcd")))
        assertTrue(notificationProfileSuppresses(people, other))

        val dms = quiet.copy(allowAllDirectMessages = true)
        assertFalse(notificationProfileSuppresses(dms, profileTestUpdate(groupIdHex = "dm", isDm = true)))
        assertFalse(notificationProfileSuppresses(dms, other, directConversation = true))
        assertTrue(notificationProfileSuppresses(dms, other))

        val changes = quiet.copy(allowGroupChanges = true)
        listOf(
            NotificationTriggerFfi.GROUP_INVITE,
            NotificationTriggerFfi.REMOVED_FROM_GROUP,
            NotificationTriggerFfi.MADE_ADMIN,
            NotificationTriggerFfi.REMOVED_AS_ADMIN,
        ).forEach { trigger ->
            assertFalse("$trigger", notificationProfileSuppresses(changes, profileTestUpdate(trigger = trigger)))
            assertTrue("$trigger", notificationProfileSuppresses(quiet, profileTestUpdate(trigger = trigger)))
        }
        assertTrue(notificationProfileSuppresses(changes, other))

        val repeats = quiet.copy(allowRepeatedMessages = true)
        assertTrue(notificationProfileSuppresses(repeats, other, repeated = false))
        assertFalse(notificationProfileSuppresses(repeats, other, repeated = true))
        assertTrue(notificationProfileSuppresses(quiet, other, repeated = true))
        assertTrue(notificationProfileSuppresses(repeats, profileTestUpdate(reactionEmoji = "👍"), repeated = true))
        val invite = profileTestUpdate(trigger = NotificationTriggerFfi.GROUP_INVITE)
        assertTrue(notificationProfileSuppresses(repeats, invite, repeated = true))
        assertFalse(notificationProfileSuppresses(repeats, profileTestUpdate(isFromSelf = true)))
    }

    @Test
    fun aRepeatCountsOnlyInsideThreeMinutesAfterTheHeldMessage() {
        val held = 1_000_000L
        assertFalse(notificationProfileRepeatOpen(null, held))
        assertTrue(notificationProfileRepeatOpen(held, held))
        assertTrue(notificationProfileRepeatOpen(held, held + NOTIFICATION_PROFILE_REPEAT_WINDOW_MS - 1))
        assertFalse(notificationProfileRepeatOpen(held, held + NOTIFICATION_PROFILE_REPEAT_WINDOW_MS))
        assertFalse(notificationProfileRepeatOpen(held, held - 1))
    }

    @Test
    fun nextTransitionCoversManualExpiryWindowEndAndNextStart() {
        val now = localMs("2026-11-20T12:00", utc)
        assertNull(
            nextNotificationProfileTransitionMs(NotificationProfilesState(listOf(testProfile("work"))), now, utc),
        )
        val sleep = testProfile("sleep", profileSchedule("22:00", "07:00", WeekdaysOnly))
        assertEquals(
            localMs("2026-11-20T22:00", utc),
            nextNotificationProfileTransitionMs(NotificationProfilesState(listOf(sleep)), now, utc),
        )
        val inside = localMs("2026-11-20T23:00", utc)
        assertEquals(
            localMs("2026-11-21T07:00", utc),
            nextNotificationProfileTransitionMs(NotificationProfilesState(listOf(sleep)), inside, utc),
        )
        val manual = NotificationProfilesState(listOf(sleep), NotificationProfileManual.On("sleep", inside + 60_000L))
        assertEquals(inside + 60_000L, nextNotificationProfileTransitionMs(manual, inside, utc))
        // After Saturday 07:00 the weekday-only window next starts on Monday evening.
        assertEquals(
            localMs("2026-11-23T22:00", utc),
            nextNotificationProfileTransitionMs(
                NotificationProfilesState(listOf(sleep)),
                localMs("2026-11-21T07:00", utc),
                utc,
            ),
        )
    }

    @Test
    fun nextTransitionLooksPastAStartSkippedByADaylightSavingGap() {
        // Berlin skips 02:00-03:00 on Sunday 29 March 2026, so a Sunday 02:30-03:00 window collapses that week.
        val sundayNight = testProfile("night", profileSchedule("02:30", "03:00", setOf(DayOfWeek.SUNDAY)))
        assertEquals(
            ZonedDateTime.of(2026, 4, 5, 2, 30, 0, 0, berlin).toInstant().toEpochMilli(),
            nextNotificationProfileTransitionMs(
                NotificationProfilesState(listOf(sundayNight)),
                ZonedDateTime.of(2026, 3, 24, 12, 0, 0, 0, berlin).toInstant().toEpochMilli(),
                berlin,
            ),
        )
    }

    @Test
    fun nextLocalTimeIsStrictlyInTheFutureAndFollowsDaylightSaving() {
        val now = localMs("2026-11-20T06:00", utc)
        assertEquals(localMs("2026-11-20T07:00", utc), nextLocalTimeMs(7 * 60, now, utc))
        assertEquals(localMs("2026-11-21T06:00", utc), nextLocalTimeMs(6 * 60, now, utc))
        assertEquals(localMs("2026-11-21T05:00", utc), nextLocalTimeMs(5 * 60, now, utc))
        val beforeGap = localMs("2026-03-28T23:00", berlin)
        assertEquals(Instant.parse("2026-03-29T01:30:00Z").toEpochMilli(), nextLocalTimeMs(150, beforeGap, berlin))
    }

    @Test
    fun namesAreTrimmedAndCappedByCodePoint() {
        assertEquals("Sleep", normalizedNotificationProfileName("  Sleep "))
        val emoji = "😴".repeat(45)
        val capped = normalizedNotificationProfileName(emoji)
        assertEquals(MAX_NOTIFICATION_PROFILE_NAME_CODE_POINTS, capped.codePointCount(0, capped.length))
    }

    @Test
    fun manualDurationsAreTimedFromTheTapAndNeverEndInThePast() {
        val sleep = testProfile("sleep", profileSchedule("22:00", "07:00"))
        val drawnAt = localMs("2026-11-21T06:59", utc)
        val tappedAt = localMs("2026-11-21T07:01", utc)
        assertEquals(
            localMs("2026-11-21T07:00", utc),
            until(sleep, NotificationProfileDuration.UNTIL_SCHEDULE_END, drawnAt),
        )
        // Tapped after 07:00, "Until 07:00" means tomorrow's end, not a moment already past.
        assertEquals(
            localMs("2026-11-22T07:00", utc),
            until(sleep, NotificationProfileDuration.UNTIL_SCHEDULE_END, tappedAt),
        )
        assertEquals(tappedAt + 3_600_000L, until(sleep, NotificationProfileDuration.ONE_HOUR, tappedAt))
        assertNull(until(sleep, NotificationProfileDuration.UNTIL_TURNED_OFF, tappedAt))
        // A schedule switched off meanwhile falls back to one hour rather than staying on indefinitely.
        val unscheduled = sleep.copy(schedule = sleep.schedule?.copy(enabled = false))
        assertEquals(
            tappedAt + 3_600_000L,
            until(unscheduled, NotificationProfileDuration.UNTIL_SCHEDULE_END, tappedAt),
        )
    }

    private fun until(
        profile: NotificationProfile,
        duration: NotificationProfileDuration,
        nowMs: Long,
    ): Long? = notificationProfileManualUntilMs(profile, duration, nowMs, utc)
}
