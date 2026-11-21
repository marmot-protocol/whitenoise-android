package dev.ipf.whitenoise.android.notifications

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek

internal fun encodeProfiles(state: NotificationProfilesState): JSONObject =
    JSONObject()
        .put("profiles", JSONArray(state.profiles.map(::encodeProfile)))
        .put("showInChatList", state.showInChatList)
        .put("tileAdded", state.tileAdded)
        .put("presetsSeeded", state.presetsSeeded)
        .apply {
            when (val manual = state.manual) {
                is NotificationProfileManual.On ->
                    put(
                        "manual",
                        JSONObject().put("type", "on").put("profileId", manual.profileId).apply {
                            manual.untilMs?.let { put("untilMs", it) }
                        },
                    )
                is NotificationProfileManual.Paused ->
                    put("manual", JSONObject().put("type", "paused").put("untilMs", manual.untilMs))
                null -> Unit
            }
        }

private fun encodeProfile(profile: NotificationProfile): JSONObject =
    JSONObject()
        .put("id", profile.id)
        .put("name", profile.name)
        .put(
            "allowedChats",
            JSONArray(
                profile.allowedChats.map { chat ->
                    JSONObject().put("accountRef", chat.accountRef).put("groupIdHex", chat.groupIdHex)
                },
            ),
        ).put(
            "allowedPeople",
            JSONArray(
                profile.allowedPeople.map { person ->
                    JSONObject().put("accountRef", person.accountRef).put("accountIdHex", person.accountIdHex)
                },
            ),
        ).put("allowMentions", profile.allowMentions)
        .put("allowAllDirectMessages", profile.allowAllDirectMessages)
        .put("allowRepeatedMessages", profile.allowRepeatedMessages)
        .put("allowGroupChanges", profile.allowGroupChanges)
        .put("createdAtMs", profile.createdAtMs)
        .apply {
            profile.emoji?.let { put("emoji", it) }
            profile.schedule?.let { schedule ->
                put(
                    "schedule",
                    JSONObject()
                        .put("enabled", schedule.enabled)
                        .put("start", schedule.startMinuteOfDay)
                        .put("end", schedule.endMinuteOfDay)
                        .put("days", JSONArray(schedule.days.sorted().map { it.value })),
                )
            }
        }

internal fun decodeProfiles(json: JSONObject): NotificationProfilesState {
    val array = json.optJSONArray("profiles") ?: JSONArray()
    val profiles =
        (0 until array.length())
            .mapNotNull { array.optJSONObject(it)?.let(::decodeProfile) }
            .distinctBy { it.id }
            .take(MAX_NOTIFICATION_PROFILES)
    return NotificationProfilesState(
        profiles = profiles,
        manual = json.optJSONObject("manual")?.let(::decodeManual),
        showInChatList = json.optBoolean("showInChatList", false),
        tileAdded = json.optBoolean("tileAdded", false),
        presetsSeeded = json.optBoolean("presetsSeeded", false),
    )
}

private fun decodeManual(choice: JSONObject): NotificationProfileManual? {
    val untilMs = if (choice.has("untilMs")) choice.getLong("untilMs") else null
    val profileId = choice.optString("profileId").takeIf { it.isNotEmpty() }
    return when (choice.optString("type")) {
        "on" -> profileId?.let { NotificationProfileManual.On(it, untilMs) }
        "paused" -> untilMs?.let(NotificationProfileManual::Paused)
        else -> null
    }
}

private fun decodeProfile(json: JSONObject): NotificationProfile? {
    val id = json.optString("id")
    val name = normalizedNotificationProfileName(json.optString("name"))
    if (id.isEmpty() || name.isEmpty()) return null
    return NotificationProfile(
        id = id,
        name = name,
        allowedChats = decodeChats(json.optJSONArray("allowedChats") ?: JSONArray()),
        allowMentions = json.optBoolean("allowMentions", true),
        schedule = json.optJSONObject("schedule")?.let(::decodeSchedule),
        createdAtMs = json.optLong("createdAtMs", 0L),
        allowedPeople = decodePeople(json.optJSONArray("allowedPeople") ?: JSONArray()),
        allowAllDirectMessages = json.optBoolean("allowAllDirectMessages", false),
        allowRepeatedMessages = json.optBoolean("allowRepeatedMessages", false),
        allowGroupChanges = json.optBoolean("allowGroupChanges", false),
        emoji = normalizedNotificationProfileEmoji(json.optString("emoji").takeIf { json.has("emoji") }),
    )
}

private fun decodeChats(array: JSONArray): Set<NotificationProfileChat> =
    (0 until array.length())
        .mapNotNull { index ->
            array.optJSONObject(index)?.let { chat ->
                NotificationProfileChat(chat.optString("accountRef"), chat.optString("groupIdHex"))
            }
        }.filter { it.accountRef.isNotEmpty() && it.groupIdHex.isNotEmpty() }
        .map(::normalizedChat)
        .toSet()

private fun decodePeople(array: JSONArray): Set<NotificationProfilePerson> =
    (0 until array.length())
        .mapNotNull { index ->
            array.optJSONObject(index)?.let { person ->
                NotificationProfilePerson(person.optString("accountRef"), person.optString("accountIdHex"))
            }
        }.filter { it.accountRef.isNotEmpty() && it.accountIdHex.isNotEmpty() }
        .map(::normalizedPerson)
        .toSet()

private fun decodeSchedule(json: JSONObject): NotificationProfileSchedule {
    val days = json.optJSONArray("days") ?: JSONArray()
    return NotificationProfileSchedule(
        enabled = json.optBoolean("enabled", false),
        startMinuteOfDay = json.optInt("start", -1),
        endMinuteOfDay = json.optInt("end", -1),
        days = (0 until days.length()).mapNotNull { dayOfWeek(days.optInt(it)) }.toSet(),
    )
}

/** ISO day number (Monday 1 to Sunday 7), or null when out of range. */
private fun dayOfWeek(value: Int): DayOfWeek? = DayOfWeek.entries.firstOrNull { it.value == value }
