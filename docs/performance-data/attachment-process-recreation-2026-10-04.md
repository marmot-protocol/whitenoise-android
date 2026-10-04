# Process recreation during a received-APK download, 2026-10-04

Context: [#2781](https://github.com/marmot-protocol/whitenoise-android/issues/2781) and tracker
[#2779](https://github.com/marmot-protocol/whitenoise-android/issues/2779). This qualifies process recreation during the
download of a received APK on disposable emulators. It does not run on a physical device, and it never confirms an
installation.

## What it proves

A received package is downloading when the app process dies, and the next process completes it **once**, with a file
that is provably the whole package, before anything reaches the system installer.

1. **A real partial body.** One generated package is sent through the shipping controller. The fixture then holds its
   ciphertext body at 2 MiB, and the probe starts the real receiver download. The probe waits until the native feed
   shows at least 1 MiB received and the server has recorded the held body, so the process dies with bytes in flight.
2. **Abrupt death, not a cancel.** The probe writes the identity the next process needs and then ends its own process
   with `Process.killProcess`. Nothing cancels, pauses, releases or cleans up, which is what the system does when it
   reclaims memory. The host accepts the stage only if the instrumentation ended in a crash **and** the held-prefix row
   had already been reported, so a process killed while it was still starting is never mistaken for the scenario.
3. **No overlap.** The host releases the hold, then waits for the server's own `disconnect` of the interrupted
   acquisition before it launches the second process, and the checker requires the replacement's request to come after
   that event. Two acquisitions can never have been alive together.
4. **The same path a reader's tap uses.** The new process reopens the restored runtime, records the native state it
   found, and retries through `downloadAttachmentPlaintextSource`, the production verified-file publication. Only then
   does it compare the published file's size and SHA-256 with the sender's copy and call the real
   `openAttachmentExternally`, so a partial file cannot reach the installer.

`apk_recreation_checker.py` fails closed on: no real partial prefix, an upload that is not the package plus its AEAD
tag, anything other than exactly two acquisitions of it and no HEAD, a first acquisition that is not held at the prefix
or that completes, a second that starts before the first ended, a replacement that does not deliver exactly the bytes it
still needed, an unexplained cancel or payload control, a file not proven exact, and a wrong platform outcome for the
distribution. Thirteen checker tests remove or falsify each proof in turn, and disabling the ledger or metric checks
makes several of them fail.

## Results

Ten runs with no other device activity, five on each environment, at the clean head `57a3d33af` (code identical to the commit that follows it, which only updates this report), **all qualified with no
checker violations** under the checker that also requires the screen to have been watched for an installer after the
recreated dispatch:

| Environment | Runs | Native state found | Replacement request | Retry to verified file | Platform outcome |
| --- | --- | --- | --- | --- | --- |
| API 30 arm64, Play | 5 of 5 | `DOWNLOADING`, attempt 1 (attempt 2 in 3 runs) | `206`, ranged from byte 2,097,152, `If-Range` matched | 3,095 to 3,321 ms (median 3,204) | `InstallUnsupported`, no installer (watched for 1.05 to 1.08 s) |
| API 36 arm64, Zapstore | 5 of 5 | `DOWNLOADING`, attempt 1 | `206`, ranged from byte 2,097,152, `If-Range` matched | 3,097 to 3,224 ms (median 3,198) | `Opened`, installer shown and settled (32 to 121 ms of staging) |

The native bytes received when the process died were 1.0 to 1.4 MB, behind the 2 MiB the server had written because the
engine coalesces progress, and the replacement still resumed from the full committed 2 MiB prefix. **The interrupted
download is resumed from its committed prefix across process death, not restarted:** the replacement fetched only the
remaining bytes (the package's ciphertext minus those 2,097,152), and the ledger shows exactly one upload and two acquisitions per run. The
retry time is end to end: process start, runtime reopen, the ranged fetch of the rest, verification and publication.

The existing `controller-apk` fixture qualifies unchanged on both environments after the probe's send path was factored
out for this stage.

## Earlier runs, kept

An earlier batch of ten runs qualified under the checker as it was then. That checker did not yet require the screen to be
watched for an installer after the recreated dispatch, so the current checker rejects those reports for missing
`installer_observed_ms`, and they are not claimed. They are preserved with the runs above.

## A failed run, kept

One run of an earlier batch failed with no metrics at all: the first process crashed before it sent anything, because
the isolated app's process was killed externally while that stage was starting. The runner now requires the held-prefix
row before it accepts a crash as the intended end, and records the instrumentation's own result lines for a failed
stage. The failed run is preserved beside the clean runs and is not claimed.

## Not claimed

- **A physical device.** None was used, and the probe ends its own process, which has not been run on the Pixel.
- **System-initiated death.** The process is ended by its own `SIGKILL`, not by the low-memory killer or a task swipe.
- **Automatic resume.** The fixture runs no durable WorkManager job, so this covers a reader returning and tapping
  again, not Android restarting the download on its own.
- **A larger package.** Only the 4.6 MB package with a 2 MiB hold. The 31 MB host payload is not used here.
- **Installation.** The installer is opened and dismissed, never confirmed, and a Play build never launches one.
- **Other gaps.** A genuinely installer-less platform state, packages above the 32 MiB sender cap and the manual MED
  flows are not covered here.
