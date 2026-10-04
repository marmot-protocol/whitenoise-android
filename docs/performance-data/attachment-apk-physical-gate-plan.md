# Physical received-APK gate runbook

Context: [#2781](https://github.com/marmot-protocol/whitenoise-android/issues/2781) and tracker
[#2779](https://github.com/marmot-protocol/whitenoise-android/issues/2779). The
[emulator qualification](attachment-apk-installer-2026-10-03.md) covers the received-APK open boundary on disposable
emulators. This runbook covers the same probe on one explicitly authorized physical device: what each step changes on
the phone, what evidence each criterion produces, and what it does not cover.

**Status (2026-10-04):** run on a Pixel 9 Pro XL (Android 17, API 37) at the clean master head `6cee08a06`, with the
probe and checker that observe the screen after every dispatch, force the readback and gate the large package. The
Zapstore base run, the cancel-retry run, the 30.95 MiB package with the simulated no-installer branch, the Play base run
and the Play cancel-retry plus large-package run all qualified with no checker violations ([results](#results-at-the-master-head)).
Installation of a received APK is never confirmed, because the probe dismisses the installer, and the gaps under "Not
covered" remain.

## Physical criteria and how each is covered

| Physical criterion | Mechanism | Status | Notes |
| --- | --- | --- | --- |
| Valid received APK downloads, verifies, launches the system installer | Existing `valid` case, stage `dispatch-allowed` | Run at the master head, qualified | Same probe as the emulator run, driven by `apk_physical_runner.py run` |
| Invalid APK-shaped file settles as a distinct state | Existing `no-manifest` and `truncated` cases, plus `generic` and `conflict` | Run at the master head, qualified | `InvalidPackage` before any launch |
| Install permission denied, then granted | Host toggles the app-op on the isolated package between stages | Run at the master head, qualified | Requires `--allow-install-app-op-toggle`; the original mode is recorded and restored |
| Cancellation and retry | `--cancel-retry`: the shared held-body cancellation probe runs on the `valid` case before publication | Run at the master head, qualified | Cancel acknowledged and the socket closed within 0.25 s, ten ordinary joins refused, 30 s quiet, exact bytes on the deliberate retry |
| 30 to 50 MiB package | `--large-apk`: a host-built, genuinely signed 30 to 31 MiB package sent through the shipping controller | Run at 30.95 MiB (32,450,112 bytes), qualified | Inside the issue's 30 to 50 MiB range and under the 32 MiB Android sender cap; above 32 MiB is not sendable from Android, see below |
| No installer available | `--no-installer-branch`: the real open path meets `ActivityNotFoundException` through a context wrapper | Simulated branch only | Exercises the app's `NoInstaller` branch on the device, does not induce a genuine installer-less platform state |
| Process recreation during download | `controller-apk-recreation` | Emulator only | Qualified separately on owned emulators; not run on a physical device |

## Device facts at the master-head run

Read-only checks before every run, each with an explicit `-s <serial>`: the phone on the launcher, awake and unlocked,
on AC power (54 %), no active instrumentation, no `adb reverse` mappings, and the isolated app and test packages
installed. The runner's own preflight reported API 37, `arm64-v8a`, a complete isolated identity, no existing reverses and
the install app-op at `default`, and `ready_for_run: true`. Both candidate signers were identical to the installed test
package, and the device's `sha256sum` of each installed `base.apk` equalled the candidate for both distributions.

## Preconditions

Host:

- A worktree at the exact head under test, clean and committed. The physical evidence must name that head and the APK
  SHA-256 pairs it installed.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`,
  `ANDROID_HOME` (for example `~/Library/Android/sdk`), build-tools `36.1.0` (`aapt2`, `apksigner`, `zipalign` present).
- The developer debug keystore at `~/.android/debug.keystore`. The payload builder signs with it, the install command
  compares signer certificates with it, neither prints it.
- `python3 -m unittest discover -s tools/attachment-fixture -p 'test_*.py'` passes at that head.
- A private, owner-only run directory outside the repository, for example `/private/tmp/wn-2781-pixel` with mode
  `0700`. Ledgers, blobs, pulled APKs and optional raw transcripts live there and are never committed.

Phone:

- USB attached, authorized for adb, screen unlocked and kept awake, on power.
- Nobody else is using the Pixel: no concurrent installs, no other `adb reverse` mappings, no scripted input from
  another agent. The two emulators may keep running, they are not touched by this gate.
- The owner is present for every `run`: the system installer sheet reaches the screen during the allowed stage and is
  dismissed with Back by the probe. The owner must not tap Install.

## Owner authorizations required

Each item is a separate, explicit decision. The tooling refuses without the corresponding flag.

1. **In-place update** of `dev.ipf.whitenoise.android.medialatency` and
   `dev.ipf.whitenoise.android.medialatency.test` for user 0 (`adb install -r -t --user 0`, never `-d`, never `-g`,
   never `uninstall`, never `pm clear`). Flag: `--confirm-in-place-update`, which is also all the Play flavor needs,
   because it replaces the Zapstore build of the same package id. `--allow-fresh-install-of-isolated-identity` is needed
   only when the app package is absent from the phone.
2. **App-op toggling** of `REQUEST_INSTALL_PACKAGES` on that one package only (`default`, `deny`, `allow`, then the
   recorded original). Android kills the isolated app's process on each change, by design. Flag:
   `--allow-install-app-op-toggle` (Zapstore only, refused for Play).
3. **The system package installer on screen**, once per allowed dispatch of a valid or generic package, dismissed with
   Back, nothing installed. Flag: `--allow-installer-on-screen`.
4. **Owner present, phone idle** for the whole run set. Flag: `--owner-present-device-idle`.
5. Two `adb reverse --no-rebind` mappings for the run's duration, removed in `finally`.
6. Generated fixture data inside the isolated app's private storage, removed by the final stage on success. A failed
   run may leave a generated session directory in that sandbox, removing it would need `pm clear`, which the tooling
   never issues, so the owner decides whether to leave it, re-run, or remove the isolated app themselves.
7. The isolated identity stays installed afterwards, as earlier Pixel sessions left it. Removal is an owner action.
8. Optional `--private-debug`: raw instrumentation transcripts, one file per stage, mode `0600`, in the run root only.

## Commands in order

Shell variables used below:

```bash
cd <worktree at the exact head under test>
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
ADB="$ANDROID_HOME/platform-tools/adb"
TOOLS="$ANDROID_HOME/build-tools/36.1.0"
SERIAL=<serial from adb devices>
RUN=/private/tmp/wn-2781-pixel
mkdir -p "$RUN" && chmod 700 "$RUN"
```

1. **Host checks, no device contact.**

   ```bash
   git status --short && git rev-parse HEAD
   python3 -m unittest discover -s tools/attachment-fixture -p 'test_*.py'
   ```

2. **Preflight, read-only.** The proposed first command once authorizations are given. It verifies the serial, the
   emulator check, API 37 and arm64, lists which isolated packages are present, lists existing reverses and, for a
   self-update build with the app present, the current app-op mode.

   ```bash
   python3 tools/attachment-fixture/apk_physical_runner.py preflight --adb "$ADB" \
     --serial "$SERIAL" --physical-fixture-serial "$SERIAL" --distribution Zapstore
   ```

3. **Build the Zapstore isolated identity, host only.** The init script renames only the `dev` flavor to the
   `.medialatency` suffix and the "Media Latency Lab" label.

   ```bash
   ./gradlew --init-script scripts/media-latency.init.gradle \
     :app:assembleDevZapstoreDebug :app:assembleDevZapstoreDebugAndroidTest --no-daemon
   APP_APK=$(ls app/build/outputs/apk/devZapstore/debug/*-universal-debug.apk)
   TEST_APK=$(ls app/build/outputs/apk/androidTest/devZapstore/debug/*.apk)
   shasum -a 256 "$APP_APK" "$TEST_APK"
   ```

4. **Install in place. State change on the phone.** Verifies both package names with `aapt2`, verifies both candidate
   signers are identical, pulls every installed isolated APK as the retained restore copy and refuses if its signer
   differs, then installs app first and test second, each with `-r -t --user 0`, and proves the device's
   `sha256sum` of each installed `base.apk` equals the candidate. Writes `install-receipt.json` into the backup
   directory.

   ```bash
   python3 tools/attachment-fixture/apk_physical_runner.py install --adb "$ADB" \
     --serial "$SERIAL" --physical-fixture-serial "$SERIAL" \
     --app-apk "$APP_APK" --test-apk "$TEST_APK" --backup-dir "$RUN/backup-zapstore" --build-tools "$TOOLS" \
     --confirm-in-place-update
   ```

5. **Base Zapstore run. State changes: app-op `default`, `deny`, `allow`, then restored, installer sheet on screen,
   two reverses added and removed.** Three stages in three processes, as on the emulators.

   ```bash
   python3 tools/attachment-fixture/apk_physical_runner.py run --adb "$ADB" \
     --serial "$SERIAL" --physical-fixture-serial "$SERIAL" --budget-profile pixel-api37-arm64 \
     --distribution Zapstore --root "$RUN/zapstore-base" --output "$RUN/zapstore-base.json" \
     --owner-present-device-idle --allow-installer-on-screen --allow-install-app-op-toggle
   ```

6. **Cancel and retry.** Same as 5 plus `--cancel-retry`, new root and output. Adds roughly 45 seconds: the shared
   probe cancels the held `valid` body, verifies ten refused ordinary reads, waits a 30 second quiet interval, then
   admits one deliberate Retry.

   ```bash
   python3 tools/attachment-fixture/apk_physical_runner.py run --adb "$ADB" \
     --serial "$SERIAL" --physical-fixture-serial "$SERIAL" --budget-profile pixel-api37-arm64 \
     --distribution Zapstore --root "$RUN/zapstore-cancel" --output "$RUN/zapstore-cancel.json" \
     --owner-present-device-idle --allow-installer-on-screen --allow-install-app-op-toggle --cancel-retry
   ```

7. **Build the 30 to 31 MiB signed payload, host only.** Pads the built test APK with one stored asset entry, runs
   `zipalign`, then `apksigner sign` with the debug key, the same key as the installed test package, so the installer
   sees a genuinely signed update of that package. Prints a receipt with size and SHA-256.

   ```bash
   python3 tools/attachment-fixture/apk_payload.py --source-apk "$TEST_APK" --output "$RUN/large.apk" \
     --sdk-root "$ANDROID_HOME"
   ```

8. **Large package and the simulated no-installer branch.** Same as 5 plus `--large-apk "$RUN/large.apk"
   --build-tools "$TOOLS" --no-installer-branch`, new root and output. `apksigner` must accept the payload before the
   device is contacted. The payload is served to the probe over the loopback fixture server's
   `/__payload/large-apk` endpoint, outside the counted acquisition ledger, then sent through the shipping controller
   like every other case.

   ```bash
   python3 tools/attachment-fixture/apk_physical_runner.py run --adb "$ADB" \
     --serial "$SERIAL" --physical-fixture-serial "$SERIAL" --budget-profile pixel-api37-arm64 \
     --distribution Zapstore --root "$RUN/zapstore-large" --output "$RUN/zapstore-large.json" \
     --owner-present-device-idle --allow-installer-on-screen --allow-install-app-op-toggle \
     --large-apk "$RUN/large.apk" --build-tools "$TOOLS" --no-installer-branch
   ```

9. **Play flavor.** Build, install in place (this replaces the Zapstore isolated build, same package id, retained in
   `backup-play`), then run without the app-op flag, which the runner refuses for Play. A Play build has no
   installer, so every valid or large package must answer `InstallUnsupported`.

   ```bash
   ./gradlew --init-script scripts/media-latency.init.gradle \
     :app:assembleDevPlayDebug :app:assembleDevPlayDebugAndroidTest --no-daemon
   APP_APK=$(ls app/build/outputs/apk/devPlay/debug/*-universal-debug.apk)
   TEST_APK=$(ls app/build/outputs/apk/androidTest/devPlay/debug/*.apk)
   python3 tools/attachment-fixture/apk_physical_runner.py install --adb "$ADB" \
     --serial "$SERIAL" --physical-fixture-serial "$SERIAL" \
     --app-apk "$APP_APK" --test-apk "$TEST_APK" --backup-dir "$RUN/backup-play" --build-tools "$TOOLS" \
     --confirm-in-place-update
   python3 tools/attachment-fixture/apk_physical_runner.py run --adb "$ADB" \
     --serial "$SERIAL" --physical-fixture-serial "$SERIAL" --budget-profile pixel-api37-arm64 \
     --distribution Play --root "$RUN/play-base" --output "$RUN/play-base.json" \
     --owner-present-device-idle --allow-installer-on-screen
   python3 tools/attachment-fixture/apk_physical_runner.py run --adb "$ADB" \
     --serial "$SERIAL" --physical-fixture-serial "$SERIAL" --budget-profile pixel-api37-arm64 \
     --distribution Play --root "$RUN/play-gaps" --output "$RUN/play-gaps.json" \
     --owner-present-device-idle --allow-installer-on-screen --cancel-retry --large-apk "$RUN/large.apk" \
     --build-tools "$TOOLS"
   ```

Every `run` exits non-zero and raises unless its report is `qualified`. A failed or partial run keeps its ledger and
report in place, never reset. Use a new `--root` per attempt.

## Known pitfalls

Four things a physical run exposes that emulator runs do not:

1. **A foreign UiAutomation holder.** Android allows one UiAutomation connection per device. The `android` CLI leaves
   `com.android.cli.interact.instrumentation` running on a device after a session, and while it is active the probe
   crashes on its first `getUiAutomation()` with `UiAutomationService ... already registered`. Check
   `dumpsys activity processes` for any `mInstr=ActiveInstrumentation` before every run and stop on one. Stopping a
   holder is a device-state change that needs its own owner approval, and nothing else on the phone should use the
   `android` CLI during a gate.
2. **The fixture server's idle bound.** A pooled client sends its next upload whenever it is ready, and a request whose
   body has started is not replayed. The fixture therefore waits for a request line under a long idle bound and applies
   its five-second bound only once a request has begun, so a slower per-case cycle than an emulator's is not cut off.
3. **A cancelled attempt never completes.** It ends with the client's disconnect, so a cancel-retry run treats a
   disconnect as terminal for finalization while the checker still requires the exact shape.
4. **Large packages stage slowly.** For a 31 MiB package the system installer is still copying the file when the probe
   would press Back, and Back does not cancel the staging. The probe waits, bounded, for the installer's progress
   indicator to disappear and reports `installer_settled`, `installer_progress_seen` and `installer_staging_ms`. The
   checker requires all three for the large case with an installer: the indicator must have been seen, the installer
   must have finished staging, and the staging time must be above zero, so a wait that never recognized the indicator
   cannot qualify.

The system installer appears for a few seconds on each allowed dispatch and is dismissed without installing. Put the
phone on the home screen and leave it idle before every run: the probe's Back presses would otherwise land on whatever
app is underneath.

## Results at the master head

Clean master head `6cee08a06`, Pixel 9 Pro XL, API 37 arm64, every run preceded by the read-only state checks above and
followed by a restore check (install app-op back at `default`, no reverses, phone on the launcher). Isolated builds
installed in place with no uninstall and no clearing; the device `sha256sum` of each installed `base.apk` equalled the
candidate.

| Build | App APK SHA-256 | Test APK SHA-256 |
| --- | --- | --- |
| Zapstore | `839357037b545f7c35cd4b53d549abf6a40c0f49a9179f5fa6a1a0b0bd5a7f9a` | `4e42e8b4029629759c85ea7bc94bfc87d7145e1d6678b47a8aa460eff1019b8d` |
| Play | `5aa0b1c351d784f72ea6dd127abbc9164cadd2c2232b4569fc66b200dda46a21` | `227d0d4a8cc138f7ac0c1ab08a1bfebec40a6c7d79188f1d0bc33faebda8ea5e` |

The large payload is 32,450,112 bytes (30.95 MiB), signed with the debug key and accepted by `apksigner` before the device
was contacted (SHA-256 `c7b19fa22b7506673807b325861251b803bbfa3392161e2282646215793ea7f8`).

| Run | Result | Installer and platform outcomes |
| --- | --- | --- |
| Zapstore base | qualified, 5 uploads and 5 acquisitions | valid denied: `InstallPermissionRequired`, no installer in 1.08 s; valid and generic allowed: `Opened`, installer seen after 144 and 112 ms and settled after 135 and 139 ms of staging; conflict: `Opened`, no installer; no-manifest and truncated: `InvalidPackage` |
| Zapstore cancel-retry | qualified, 6 acquisitions | Cancel acknowledged in 187 ms, socket closed in 196 ms, ten ordinary joins refused, 30 s quiet, exact bytes on the deliberate retry; the same dispatch outcomes as the base run |
| Zapstore large and simulated no-installer | qualified, 6 uploads and 6 acquisitions | large allowed: `Opened`, installer seen after 109 ms, progress indicator seen, settled after 354 ms of staging; valid with the simulated branch: `NoInstaller`, reported as simulated |
| Play base | qualified, 5 uploads and 5 acquisitions | valid and generic: `InstallUnsupported`, no installer in 1.09 s; conflict: `Opened`, no installer; no-manifest and truncated: `InvalidPackage` |
| Play cancel-retry and large | qualified, 6 uploads and 7 acquisitions | Cancel acknowledged in 190 ms, socket closed in 230 ms, ten ordinary joins refused, 30 s quiet, exact retry; large: `InstallUnsupported`, no installer |

Every run reached `ledger_finalized`, recorded the original app-op and restored it, removed its reverses, and reported
`installation_confirmed: false`. The ledger shows each package acquired once, the cancel-retry runs adding exactly one
more acquisition, and the large payload fetched once outside the counted ledger. The raw reports, ledgers, install
receipts, APK digests and checksums stay in a private directory outside the repository.

## Evidence per criterion and how it is checked

All checks are `apk_checker.check_apk`, applied by the runner with the selected gaps, and recorded in the report's
`apk_check`. Every report carries `installation_confirmed: false`, `performance_qualified: false` and
`no_installer_platform_qualified: false`.

| Criterion | Evidence rows | Check |
| --- | --- | --- |
| Valid APK to installer | `apk-transfer-valid` with finite latency and peaks, `apk-received` exact, `apk-dispatch (valid, allowed)` | result `Opened`, `installer_shown` true, `transfer_reused` true, one upload and one acquisition for that fixture in the ledger |
| Invalid package | `apk-dispatch (no-manifest, any)`, `(truncated, any)` | `InvalidPackage`, installer never shown, on every distribution |
| Metadata promotion | `(generic, allowed)` and `(conflict, allowed)` | generic reaches the installer only after validation, conflicting MIME stays `Opened` or `NoHandler` with no installer |
| Denied then granted | `(valid, denied)` then `(valid, allowed)` in separate processes | `InstallPermissionRequired` then `Opened`, ledger still shows one acquisition, so the denied dispatch reused the completed download |
| Cancel and retry | `held-body-cancellation` row, ledger `hold_acquisition`, `held` at 1024 bytes, `cancel_marker`, `disconnect`, `release_acquisition`, a second `get` that completes | socket close within 5 s, `deliberate_retry_exact_bytes`, `no_work_cancel_confirmed`, exactly two attempts for the valid fixture, body bytes equal uploads plus the 1024 byte held prefix, no other case touched |
| No partial file to the installer | structural plus the cancel run | the production materializer publishes only a complete file, the probe asserts exact bytes before publication, and the retried transfer is the only completed one |
| 30 to 31 MiB package | `apk-transfer-large`, `apk-received large` exact, `(large, allowed)` or `(large, n/a)`, one `payload_fetch` and `payload_complete` | `Opened` with installer on Zapstore, `InstallUnsupported` on Play, exactly one genuine upload and acquisition of the payload plus its 16-byte tag; at the master head the Zapstore case also needs `installer_settled`, `installer_progress_seen` and a staging time above zero |
| No installer, app branch | `(valid, no-installer-simulated)` | `NoInstaller`, installer not shown, file still reused, reported as simulated |
| Transfer separate from dispatch | per-case transfer rows versus per-dispatch rows | the two phases are different metrics and the ledger counts only transfers |

Install provenance: `install-receipt.json` records candidate and device SHA-256 per package and the retained previous
APKs. The report's `environment` is API and ABI only, no serial, model, account or locator appears in any report.

## Restore

- The app-op is restored to the recorded original in `finally`. Verify with
  `adb -s "$SERIAL" shell appops get dev.ipf.whitenoise.android.medialatency REQUEST_INSTALL_PACKAGES`.
- Reverses are removed in `finally`. Verify with `adb -s "$SERIAL" reverse --list`.
- The retained previous APKs under each backup directory are the restore copies. Reinstalling one may be a version
  downgrade that Android accepts only with `-d`, which this tooling never passes, so restoring an older build is an
  owner decision performed by hand.
- The isolated identity remains installed. Removing it, or clearing its data, is an owner action.
- The private run root holds ledgers, blobs, pulled APKs and optional transcripts. Only the redacted JSON reports feed
  the eventual repository report, after review.

## Known risks and open questions

- **Installer visibility.** The probe resolves the installer package with `queryIntentActivities` from the app
  context. On the Android 17 Pixel the system installer was found and shown for every allowed valid, generic and large
  dispatch, so package visibility did not hide it.
- **Installer sheet shape.** The dispatched valid package is the fixture's own test APK, so the installer shows an
  update prompt for the installed test package. The probe presses Back three times, nobody taps Install.
- **`appops set` on a user build.** It worked from the adb shell, so the deny and grant stages were driven by the host.
  If a build refuses it, the run stops and the alternative is the owner toggling Install unknown apps for "Media Latency
  Lab" in Settings, outside this runner.
- **Timing.** Each stage has a 600 s probe deadline and a 900 s host timeout, the cancel run adds a 30 s quiet
  interval, and the large run uploads and downloads 31 MiB over USB adb reverse.
- **Private space.** User 10 also holds the orphan test package. `--user 0` installs update the shared code and
  leave user 10's install state alone, they do not add the app to the private space.
- **Shared adb server.** The emulators used by other work share the host adb server. The fixture servers bind
  ephemeral host ports and the reverses are per device, so there is no port contention, but a device-side
  `--no-rebind` conflict would stop the run before any stage.

## Not covered, and the minimal work each needs

- **Process recreation during download.** Qualified on owned emulators by `controller-apk-recreation`, where the
  probe ends its own process while a real download is held and a new process completes it from the committed prefix.
  Not run on a physical device: the recreation runner refuses a non-emulator serial by design, and the probe ending its
  own process on a phone is a separate owner decision.
- **A genuine installer-less platform state.** Not inducible on the owner's phone without disabling the system
  package installer, which is a system change this plan does not request. The simulated branch covers the app's
  `NoInstaller` handling only. A disposable emulator with the installer disabled for user 0 is the only truthful
  platform-level test and is outside this physical gate.
- **Packages above 32 MiB.** The shipping Android controller refuses attachments whose plaintext exceeds
  `ConversationController.MEDIA_RETAINED_MAX_BYTES` (32 MiB), so a 33 to 50 MiB package cannot be sent from
  Android. The native `Marmot.uploadMedia` path documents no size cap in the 0.12.0 binding, and the app does not
  configure a receive-side `transferLimit`, so a larger package would need the native sender and a separate probe to
  establish the receive-side limit empirically. The 30.95 MiB payload here is the largest the Android sender can send.
- **Manual flows.** The rendered file card, notifications and MED-018 outcomes remain manual and unchecked.
