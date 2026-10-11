# Diagnostic startup capture

Use this only for an explicitly requested startup investigation. The diagnostic
option is off by default and production builds keep it off even when requested.
It records host stage entry/exit before potentially blocking binding calls and
works without successful MDK initialization, audit consent, or a remote collector.

Build the existing staging release variant with `-PstartupDiagnostics=true`, or
manually dispatch **Android Staging APK** on the reviewed branch with the
`startup_diagnostics` input enabled. The existing audit-attestation policy allows
manual dispatch only by `Datawav` from a `datawav/` branch. Keep that policy; do
not disable audit requirements or substitute a different signer to obtain an APK.
The workflow uses the existing staging key.
The version name ends in `-startup-diagnostics`; package and versionCode stay on
the staging update track. Do not substitute a dev/preview APK for an update to
an affected staging installation.

Before delivery compare the candidate with the affected signed APK: package,
certificate digest, acceptable versionCode, and the exact MarmotKit archive/native
libraries. Check Android migration changes separately. Never infer compatibility
from matching package names or a green build alone. Preserve a private backup
before any physical-device qualification; never uninstall or clear storage.

The diagnostic build writes one bounded latest receipt under private app storage.
Writes run on a separate thread with at most one pending snapshot; disk faults
cannot turn into bootstrap failures. The memory ring keeps 96 events and at most
24 active stage observations. Retry observes the existing attempt and does not
reset the record. Between-stage gaps are named rather than blamed on an earlier
completed stage. Thrown failures retain the innermost failed stage after unwind.

On a timeout, **Copy** includes the active stage, durations and native pin.
**Share startup diagnostics** creates a host-only text file (32 KiB maximum) and
opens the system chooser after the user's tap. It never enables uploads, queries
MDK log files, or changes diagnostic-sharing preferences. Only three export copies
are retained. Export has a five-second presentation wait and at most one writer;
if disk remains blocked, repeated taps cannot create extra writers. A late result
cannot present after the failure route changes or the app locks.

The file includes fixed stage/event names, monotonic durations, UTC process start,
Android API, OS build fingerprint and build revisions. It excludes throwable text/stacks, private paths,
account/group/message identifiers, keys, payloads and message contents. The
recipient is selected by the user in the share sheet.

For the affected user: install the verified signed APK as an in-place update,
open staging, wait for Retry, tap Copy, then tap Share startup diagnostics and send
the text file to the investigating developer. A recorded `NATIVE_CONSTRUCT` or
`MARMOT_START` stall identifies a host boundary, not a native root cause. Follow
that evidence with targeted native instrumentation or a thread/system trace;
this diagnostic build does not repair the underlying failure.
