# Genuine received APKs at the Android open boundary, 2026-10-03

Context: [#2781](https://github.com/marmot-protocol/whitenoise-android/issues/2781) and tracker
[#2779](https://github.com/marmot-protocol/whitenoise-android/issues/2779). This report qualifies, on disposable
emulators, what the real Android open path does with files that were genuinely sent and received. It changes no shipping
behavior and installs nothing. It is an emulator measurement and does not cover a physical device.

## Method

`controller-apk` sends five generated files through the shipping `ConversationController.sendAttachments` path to a
generated peer over loopback and receives each one genuinely:

| Case | File label | Bytes |
| --- | --- | ---: |
| valid | this fixture's own signed APK, `application/vnd.android.package-archive` | 5,059,369 |
| generic | the same bytes, `application/octet-stream` | 5,059,369 |
| conflict | the same bytes, `image/png` | 5,059,369 |
| no-manifest | a ZIP with a `classes.dex` entry and no `AndroidManifest.xml` | 156 |
| truncated | the first half of the valid package | 2,529,684 |

The prepare stage requires exact bytes and publishes each verified file with the production document materializer.
Later stages run in **new processes**: the host sets the install-unknown-apps app-op for only the isolated fixture
package, and each stage reopens the restored runtime with acquisition unavailable, deletes the published copy of the
file, republishes it from native retention (the stage fails if that read did not happen) and calls the real
`openAttachmentExternally`. The host toggles the app-op between stages because changing it
kills the app process. The probe records the exact
`OpenAttachmentResult` and whether the system package installer actually reached the screen, by polling the active window
for the package that handles APK installs, then dismisses it with Back. Nothing is ever installed or replaced.

The server ledger is authoritative: one upload and one acquisition per case across all stages, so a denied or blocked
dispatch reuses the completed download. `apk_checker.py` fails closed and records `installation_confirmed: false`.

## Source cohort

- Signed commit `da7dad7cdab3abcbd45c227723a3f19553d73cd6`, on master `60988a94eb4c431fb35e36251d4aa60ac5204218` plus the
  fixture changes. The tree was clean and committed when each case was built.
- MDK pin `122bd90ffac60bb6311346e228d0f609a18521ee`, native bytes unchanged.
- Owned AVDs only: `wn_2779_fixture_api30` and `wn_2779_fixture_api36`, both arm64-v8a.

| Case | App APK SHA-256 | Test APK SHA-256 |
| --- | --- | --- |
| Play | `20c720d954dfa76018b6394cb94df8c49792367d53bf06ce31bff9bb2cb379c7` | `4b70b7bee4a9630e69a106e657f6068be7ccb68b98b64c380993bebc9bc443e9` |
| Zapstore | `05d3d0af3aa2b41e753588a2c496e3cfc4e1962fd6e153297414296e0abc8b1d` | `0fff0e90c8795cf3f3a3f054ab3c263efe249eb53947f41d4b85678d14c2d9b8` |

The same APK pair ran on both API levels for each flavor.

## Results

All four cases pass the checker and the ledger, with 5 uploads and 5 acquisitions each. Outcomes were identical on API 30
and API 36. Dispatch time is the call into the open path, in milliseconds (API 30 / API 36), not an installer budget.

**Zapstore** (self-update enabled, so the installer is reachable):

| Case | Result | Installer shown | Dispatch ms |
| --- | --- | --- | ---: |
| Valid signed package, permission denied | InstallPermissionRequired | no | 1 / 1 |
| Valid signed package, permission allowed | Opened | yes | 5 / 8 |
| Same bytes labelled `application/octet-stream`, allowed | Opened | yes | 3 / 5 |
| Same bytes labelled `image/png`, allowed | Opened | no | 4 / 4 |
| ZIP with a dex entry and no manifest | InvalidPackage | no | 1 / 1 |
| Truncated package | InvalidPackage | no | 1 / 1 |

**Play** (no self-update, so no installer exists):

| Case | Result | Installer shown | Dispatch ms |
| --- | --- | --- | ---: |
| Valid signed package | InstallUnsupported | no | 1 / 1 |
| Same bytes labelled `application/octet-stream` | InstallUnsupported | no | 0 / 1 |
| Same bytes labelled `image/png` | Opened | no | 8 / 27 |
| ZIP with a dex entry and no manifest | InvalidPackage | no | 1 / 2 |
| Truncated package | InvalidPackage | no | 1 / 9 |

## What this shows

- A received valid package reaches the system installer on a build that permits it, and only after the permission is
  granted; with it denied the result is the distinct `InstallPermissionRequired`, not a generic failure.
- A generic binary MIME type plus an APK name is promoted only after the ZIP and manifest validation, and the same bytes
  labelled with a conflicting specific MIME stay on the ordinary file-view path with no installer.
- A ZIP with no manifest and a truncated package are rejected as `InvalidPackage` on every distribution before any
  installer launch.
- A Play build answers `InstallUnsupported` for a valid package, truthfully, whatever the permission.
- A blocked or denied dispatch never causes another transfer: the retained, verified file is reused.

## Preserved development runs

- The first run toggled the app-op from inside the instrumentation process. Android killed the process, the run exited
  without reporting any dispatch, and the checker failed it for missing every dispatch row. The transfer rows from that
  run were exact, and the restructure into host-toggled stages followed.

## Not qualified here

Physical-device coverage (a valid and an invalid package, deny then grant, cancel and retry, and process recreation
during the download), a 30 to 50 MiB package (the Android controller sends at most 32 MiB in total, so 30 to 32 MiB
needs a host-built signed payload and anything above 32 MiB needs the separate MDK sender), the
no-installer-available state on a device, the rendered
file card and notification, and any actual installation. Private raw reports, logs and checksums are retained outside the
repository.
