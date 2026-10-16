package dev.ipf.whitenoise.android.maestro

import dev.ipf.whitenoise.android.MainActivity

/** Fixed expected text is independent of the formatter exercised by the real production Copy action. */
internal fun maestroPollClipboard(
    postcondition: String,
    activity: MainActivity,
): MaestroPresentationClipboard? {
    val expected =
        when (postcondition) {
            "poll-copy-open", "poll-copy-multiple", "poll-copy-closed" ->
                "Poll: Maestro fixture poll\n1. Maestro Tea\n2. Maestro Coffee\n3. Maestro Water"
            "poll-copy-unicode" -> "Poll: Maestro ¿Qué comemos? 🍜\n1. שלום עולם\n2. 寿司\n3. Cafe\u0301"
            else -> return null
        }
    return MaestroPresentationClipboard(activity, expected)
}

/** A passed Copy requires the expected foreground payload and verified removal before Activity teardown. */
internal fun verifyMaestroPollClipboard(clipboard: MaestroPresentationClipboard?): Boolean {
    clipboard?.verifyAndClear()
    return clipboard?.verified == true && clipboard.cleared
}
