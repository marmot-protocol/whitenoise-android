package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.CompromiseWarningItemFfi
import dev.ipf.whitenoise.android.R

/**
 * The compromise warning's text: one sentence per distinct reason, then what the key can still do. With [steps] it
 * goes on to what to do. It never says the account was secured or anything was blocked.
 */
@Composable
internal fun compromiseWarningBody(
    warning: List<CompromiseWarningItemFfi>,
    steps: Boolean,
): String =
    buildList {
        add(compromiseWarningReasons(warning))
        add(stringResource(R.string.compromise_warning_explainer))
        if (steps) {
            add(stringResource(R.string.compromise_warning_steps_title) + "\n" + compromiseWarningSteps())
        }
    }.joinToString("\n\n")

/** One sentence per distinct reason the warning gathers, in the order MDK gathered them. */
@Composable
internal fun compromiseWarningReasons(warning: List<CompromiseWarningItemFfi>): String =
    warning
        .map { it.reason }
        .distinct()
        .map { stringResource(compromiseWarningText(it)) }
        .joinToString(" ")

/** The numbered steps: remove unknown devices, remove unknown signer sessions, move to a new account. */
@Composable
internal fun compromiseWarningSteps(): String =
    listOf(
        R.string.compromise_warning_step_remove,
        R.string.compromise_warning_step_signer,
        R.string.compromise_warning_step_move,
    ).map { stringResource(it) }.joinToString("\n")
