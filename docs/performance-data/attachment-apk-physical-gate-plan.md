# Physical received-APK gate for #2781, prepared 2026-10-03, not yet run

Refs [#2781](https://github.com/marmot-protocol/whitenoise-android/issues/2781) and tracker
[#2779](https://github.com/marmot-protocol/whitenoise-android/issues/2779). The
[emulator qualification](attachment-apk-installer-2026-10-03.md) covers the received-APK open boundary on disposable
emulators. The issue additionally requires **exact-head physical-device coverage**. This document is the runbook for
that gate: what is ready, what each step changes on the phone, what evidence each criterion produces, and what still
needs owner decisions or further probe work.

**Status: nothing in this plan has been executed on a physical device. The gate is prepared, not qualified. No
criterion of #2781 is closed by this document.** The host tooling is unit-tested, the probe additions compile, and
neither has run against a phone.

## What the issue requires and what is ready

| Physical criterion named by #2781 | Mechanism | Ready to run | Notes |
| --- | --- | --- | --- |
| Valid received APK downloads, verifies, launches the system installer | Existing `valid` case, stage `dispatch-allowed` | Yes | Same probe as the emulator run, driven by `apk_physical_runner.py run` |
| Invalid APK-shaped file settles as a distinct state | Existing `no-manifest` and `truncated` cases, plus `generic` and `conflict` | Yes | `InvalidPackage` before any launch |
| Install permission denied, then granted | Host toggles the app-op on the isolated package between stages | Yes | Requires `--allow-install-app-op-toggle`; the original mode is recorded and restored |
| Cancellation and retry | `--cancel-retry`: the shared held-body cancellation probe runs on the `valid` case before publication | Yes, compile-only | New probe path, never executed on any device yet, see risks |
| 30 to 50 MiB package | `--large-apk`: a host-built, genuinely signed 30 to 31 MiB package sent through the shipping controller | Yes, compile-only | 31 MiB is inside the issue's range and under the 32 MiB Android sender cap, above 32 MiB is not sendable, see below |
| No installer available | `--no-installer-branch`: the real open path meets `ActivityNotFoundException` through a context wrapper | Partially | Exercises the app's `NoInstaller` branch on the device, does not induce a genuine installer-less platform state |
| Process recreation during download | Not implemented | No | Design and open questions below, belongs on an owned emulator first |

## Device facts gathered read-only on 2026-10-03

Only read-only adb queries were issued against the Pixel, each with an explicit `-s 46131FDAS003CG`. Nothing was
installed, launched, toggled or removed.

| Command | Result |
| --- | --- |
| `adb devices -l` | Pixel 9 Pro XL (`komodo`), plus two emulators owned by other work |
| `shell getprop ro.build.version.sdk` / `ro.build.version.release` / `ro.product.cpu.abi` / `ro.build.type` | `37` / `17` / `arm64-v8a` / `user` (release keys) |
| `shell getprop ro.kernel.qemu` | empty, a physical device |
| `shell pm list packages dev.ipf.whitenoise` | `dev.ipf.whitenoise.android.medialatency.test` is installed, the app `dev.ipf.whitenoise.android.medialatency` is **absent** |
| `shell pm path dev.ipf.whitenoise.android.medialatency` | exit 1, confirms the app is absent |
| `shell dumpsys package dev.ipf.whitenoise.android.medialatency.test` (filtered) | `minSdk 30`, `targetSdk 36`, installed for user 0 and user 10 (Private space), last updated 2026-10-02 16:47, signer hash `779511c1` |
| `shell dumpsys package dev.ipf.whitenoise.android.dev` (filtered) | same signer hash `779511c1` |
| local `keytool -exportcert` on `~/.android/debug.keystore`, hashed as `Signature.hashCode()` | `779511c1`, so locally built debug APKs are in-place compatible with the installed test package |
| `shell cmd package resolve-activity --brief -a android.intent.action.INSTALL_PACKAGE -d content://fixture/apk -t application/vnd.android.package-archive` | `com.google.android.packageinstaller/com.android.packageinstaller.InstallStart` |
| `shell dumpsys package com.google.android.packageinstaller` (filtered) | installed and enabled for both users |
| `shell appops get dev.ipf.whitenoise.android.medialatency REQUEST_INSTALL_PACKAGES` | `No UID`, the app is absent so no app-op exists yet |
| `shell dumpsys user` (filtered) | no device-policy or user restrictions |
| `shell settings get secure install_non_market_apps` | `1` |
| `shell pm list users` | `0:Owner` running, `10:Private space` |
| `shell df -h /data` | 11 GiB free of 109 GiB |
| `shell dumpsys battery` (filtered) | 26 %, AC powered |
| `reverse --list` | empty |
| `shell ls -l` on the installed test `base.apk` | world-readable, so a read-only `adb pull` can retain it as the restore copy |

Surprises: the isolated **app** package is gone although its test package remains, so the first install on the Pixel is
a fresh install of that package, not an in-place update. The emulator report's own APK bytes differ from the Pixel's
retained test package, so the first step is necessarily a rebuild at the exact head.

## Preconditions

Host:

- A worktree at the exact head under test, clean and committed. The physical evidence must name that head and the APK
  SHA-256 pairs it installed.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`,
  `ANDROID_HOME=/Users/mubarak/Library/Android/sdk`, build-tools `36.1.0` (`aapt2`, `apksigner`, `zipalign` present).
- The developer debug keystore at `~/.android/debug.keystore`. The payload builder signs with it, the install command
  compares signer certificates with it, neither prints it.
- `python3 -m unittest discover -s tools/attachment-fixture -p 'test_*.py'` passes at that head.
- A private, owner-only run directory outside the repository, for example `/private/tmp/wn-2781-pixel` with mode
  `0700`. Ledgers, blobs, pulled APKs and optional raw transcripts live there and are never committed.

Phone:

- USB attached, authorized for adb, screen unlocked and kept awake, on power (26 % at preflight is low for a run set).
- Nobody else is using the Pixel: no concurrent installs, no other `adb reverse` mappings, no scripted input from
  another agent. The two emulators may keep running, they are not touched by this gate.
- The owner is present for every `run`: the system installer sheet reaches the screen during the allowed stage and is
  dismissed with Back by the probe. The owner must not tap Install.

## Owner authorizations required

Each item is a separate, explicit decision. The tooling refuses without the corresponding flag.

1. **Fresh install** of `dev.ipf.whitenoise.android.medialatency` and **in-place update** of
   `dev.ipf.whitenoise.android.medialatency.test` for user 0 (`adb install -r -t --user 0`, never `-d`, never `-g`,
   never `uninstall`, never `pm clear`). Flags: `--confirm-in-place-update --allow-fresh-install-of-isolated-identity`
   the first time, `--confirm-in-place-update` alone for later heads and for the Play flavor, which replaces the
   Zapstore build of the same package id.
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
cd /Users/mubarak/Workspace/marmot-protocol/wn-2781-physical-gate
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/Users/mubarak/Library/Android/sdk
ADB="$ANDROID_HOME/platform-tools/adb"
TOOLS="$ANDROID_HOME/build-tools/36.1.0"
SERIAL=46131FDAS003CG
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
     --confirm-in-place-update --allow-fresh-install-of-isolated-identity
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
   --no-installer-branch`, new root and output. The payload is served to the probe over the loopback fixture server's
   `/__payload/large-apk` endpoint, outside the counted acquisition ledger, then sent through the shipping controller
   like every other case.

   ```bash
   python3 tools/attachment-fixture/apk_physical_runner.py run --adb "$ADB" \
     --serial "$SERIAL" --physical-fixture-serial "$SERIAL" --budget-profile pixel-api37-arm64 \
     --distribution Zapstore --root "$RUN/zapstore-large" --output "$RUN/zapstore-large.json" \
     --owner-present-device-idle --allow-installer-on-screen --allow-install-app-op-toggle \
     --large-apk "$RUN/large.apk" --no-installer-branch
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
     --owner-present-device-idle --allow-installer-on-screen --cancel-retry --large-apk "$RUN/large.apk"
   ```

Every `run` exits non-zero and raises unless its report is `qualified`. A failed or partial run keeps its ledger and
report in place, never reset. Use a new `--root` per attempt.

## What the first two attempts found

Two attempts on the Pixel (2026-10-04) found four things that the emulator runs could not, three in this tooling:

1. **A foreign UiAutomation holder.** Android allows one UiAutomation connection per device. The `android` CLI leaves
   `com.android.cli.interact.instrumentation` running on a device after a session, and while it is active the probe
   crashes on its first `getUiAutomation()` with `UiAutomationService ... already registered`. Check
   `dumpsys activity processes` for any `mInstr=ActiveInstrumentation` before every run and stop on one. Stopping a
   holder is a device-state change that needs its own owner approval, and nothing else on the phone should use the
   `android` CLI during a gate.
2. **A five-second keep-alive bound in the fixture server.** It closed idle pooled connections exactly when a Pixel's
   slower per-case cycle sent the next upload, and a request whose body has started is not replayed, so the native
   upload failed before any byte arrived. Fixed in `fixture_server.py`: the wait for a request line has its own much
   longer bound.
3. **A cancel run could never finalize.** The runner waited for a `complete` event on every GET, but a body cancelled
   on purpose ends with the client's disconnect. A cancel-retry run now treats a disconnect as terminal; the checker
   still requires the exact shape.
4. **The installer outlived a large dismissal.** For a 31 MiB package the system installer was still staging the file
   when the probe pressed Back, Back did not cancel the staging, and the dialog stayed on the owner's screen. The probe
   now waits, bounded, for the installer's progress indicator to disappear and reports `installer_settled` and
   `installer_staging_ms`. A large package with a staging time of zero means the indicator was never recognized and
   the wait proved nothing, so read that field before trusting the large case.

The owner should expect the system installer to appear for a few seconds on each allowed dispatch and to be dismissed
without installing. Press Home and leave the phone idle before every run: the probe's Back presses would otherwise
land on whatever app is underneath.

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
| 30 to 31 MiB package | `apk-transfer-large`, `apk-received large` exact, `(large, allowed)` or `(large, n/a)`, one `payload_fetch` and `payload_complete` | `Opened` with installer on Zapstore, `InstallUnsupported` on Play, exactly one genuine upload and acquisition of 32,440,336 ciphertext bytes |
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
  context. It worked on API 30 and 36 emulators. If Android 17 package visibility hides it on the Pixel, the valid
  dispatch would report `Opened` with `installer_shown` false and the checker would fail truthfully. The mitigation
  would be a test-only `<queries>` intent entry in the androidTest manifest, which means a new head and a rerun.
- **Installer sheet shape.** The dispatched valid package is the fixture's own test APK, so the installer shows an
  update prompt for the installed test package. The probe presses Back three times, nobody taps Install.
- **`appops set` on a user build.** Expected to work from the adb shell. If it is refused, the deny and grant stages
  cannot be driven by the host, the run stops, and the alternative is the owner toggling Install unknown apps for
  "Media Latency Lab" in Settings, outside this runner.
- **Untested probe additions.** The cancel-retry reuse, the payload fetch and the no-installer wrapper compiled but
  never ran on any device. The first execution may surface a probe defect rather than a product fact, which is why
  the report keeps `instrumentation_passed` and the JUnit outcome per stage.
- **Timing.** Each stage has a 600 s probe deadline and a 900 s host timeout, the cancel run adds a 30 s quiet
  interval, the large run uploads and downloads 31 MiB over USB adb reverse.
- **Private space.** User 10 also holds the orphan test package. `--user 0` installs update the shared code and
  leave user 10's install state alone, they do not add the app to the private space.
- **Shared adb server.** The emulators used by other work share the host adb server. The fixture servers bind
  ephemeral host ports and the reverses are per device, so there is no port contention, but a device-side
  `--no-rebind` conflict would stop the run before any stage.

## Not covered, and the minimal work each needs

- **Process recreation during download.** Not implemented. Proposed shape: in the prepare process, receive every
  other case, send `valid`, write the manifest early, hold the body at 1024 bytes, start the download and report
  `held`; the host force-stops only the isolated package, waits for the ledger `disconnect`, releases the hold; a new
  `recover` stage reopens the runtime, asserts the open path answers `MissingArtifact` before any retry (no partial
  file reaches the installer), calls the shipping `retryAttachmentDownload`, requires exact bytes, then continues with
  the dispatch stages. The checker would require two attempts for `valid` (one disconnected, one complete), a host
  force-stop marker and a changed process id. Open questions that need a device, so an owned emulator first: the
  native transfer state after process death, whether an ordinary read auto-resumes or needs the deliberate Retry, and
  how `am instrument -w` reports a process that was force-stopped mid-run. Scheduler-driven interruption belongs with
  #2878.
- **A genuine installer-less platform state.** Not inducible on the owner's phone without disabling the system
  package installer, which is a system change this plan does not request. The simulated branch covers the app's
  `NoInstaller` handling only. A disposable emulator with the installer disabled for user 0 is the only truthful
  platform-level test and is outside this physical gate.
- **Packages above 32 MiB.** The shipping Android controller refuses attachments whose plaintext exceeds
  `ConversationController.MEDIA_RETAINED_MAX_BYTES` (32 MiB), so a 33 to 50 MiB package cannot be sent from
  Android. The native `Marmot.uploadMedia` path documents no size cap in the 0.12.0 binding, and the app does not
  configure a receive-side `transferLimit`, so a larger package would need the native sender and a separate probe to
  establish the receive-side limit empirically. The 31 MiB payload here satisfies the issue's 30 to 50 MiB range
  without weakening any budget.
- **Manual flows.** The rendered file card, notifications and MED-018 outcomes remain manual and unchecked.
