package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail

/** Settings search's entry for the Devices row on the Account page, still found by its earlier name. */
internal fun linkedDevicesSearchEntry(): SettingsSearchEntry =
    hubEntry(
        "Account.linked_devices",
        R.string.linked_devices,
        R.string.account,
        SettingsDetail.Account,
        "search.Account.1",
        "linked_devices",
        alias = R.string.settings_search_alias_linked_devices,
        account = true,
    )
