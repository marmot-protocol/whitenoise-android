# PR 3371 temporary preview qualification baseline

This intermediate draft head exists only to obtain the current-master baseline
through the unchanged trusted preview publisher. Its application and build tree
matches Android master `b3b66afefb7459e9ae9206c85c3a85627345cf2c`, including the
published MarmotKit `88d1683e13a1dd60ba102d06c7b5a8acb3635590` runtime.

Do not merge this head or hand its preview links to testers. Retain the exact
signed isolated `.preview.pr3371` APK and provenance before restoring the nightly
candidate in a forward commit. The publisher replaces its fixed asset names.
Only the restored candidate will be eligible for qualification and review.

Use the baseline only on a fresh disposable fixture. Never install this older
runtime over a newer installation, uninstall an app, clear data, alter migration
records, or downgrade a physical device. The baseline and final candidate must
have the same package and trusted certificate, and the candidate must preserve
the synthetic account/history through an in-place install and full cold restart.
