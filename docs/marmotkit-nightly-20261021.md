# MarmotKit nightly 20261021 evaluation

This testing-only refresh of [PR #3371](https://github.com/marmot-protocol/whitenoise-android/pull/3371)
and [issue #3370](https://github.com/marmot-protocol/whitenoise-android/issues/3370)
replaces the [20 October evaluation](marmotkit-nightly-20261020.md). Keep the PR draft
until the requested hosted qualification, authorized Pixel 9a isolated-preview
test and review gates pass. Readiness is for review of this testing-only change;
do not merge or publish a production release. Preserve every existing app and its
data: no uninstall, clearing, downgrade or migration-record repair.

## Verified published artifact

| Field | Value |
| --- | --- |
| Release | [`marmotkit-v0.14.0-nightly.20261021`](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.14.0-nightly.20261021) |
| ZIP | `marmotkit-android-0.14.0-nightly.20261021.zip` |
| SHA-256 | `09ab27547a11edb1d200e0338a95f5ee92fe6636f45cfa64035d55c65cba770c` |
| Source, builder and tag commit | `8c214fee07466b0b7b63f8dfa39877cce1f63573` |
| Manifest name / version | `marmotkit-android` / `0.14.0-nightly.20261021` |
| Archive root | `marmotkit-android-0.14.0-nightly.20261021` |
| Workspace / minimum Android API | `0.14.0-dev` / `26` |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| Cargo.lock SHA-256 | `e12b94c089d057ab01eac79547089270c7bc5bb54d2e0b2ec6d9a9238f727464` |
| Toolchain | Rust/Cargo `1.97.1`; NDK `27.2.12479018` |

The calculated ZIP SHA-256 matches its sibling checksum and GitHub asset digest.
The checksum asset's own digest also matches GitHub. The embedded identity,
source/builder, immutable tag and exact-source Cargo.lock agree. The unchanged
Android validator accepts all nine payload files and verifies four ELF
architectures, digests, exports and segment alignment, including 64-bit 16 KiB.

The manifest still omits `features`. Its feature status stays **unverified**;
the validator is not weakened. The exact-source
[builder](https://github.com/marmot-protocol/mdk/blob/8c214fee07466b0b7b63f8dfa39877cce1f63573/.github/workflows/bindings.yaml#L45-L52)
and successful [publishing run](https://github.com/marmot-protocol/mdk/actions/runs/39546166339)
confirm `OTLP_EXPORT=1` and `PRODUCT_ANALYTICS_EXPORT=1`, retaining the existing
`otlp-export,product-analytics-export` selection on separate provenance evidence.

## API and source comparison

The previous reviewed nightly is MDK `67cd40d8`, ZIP
`a0befdc55e21ecd65c5b61af797ec36fb570e0c76e92a44a964b0dd892f5daf3`.
Its hosted signature comes from [Android CI 39516172585](https://github.com/marmot-protocol/whitenoise-android/actions/runs/39516172585).
Counts remain **384 types / 371 checksums / 5 helper declarations**. Preparation
passes with the existing counts; no count was adjusted to suppress a mismatch.

- `AttachmentMigrationStateFfi` adds `COMPACTING`. Counts alone do not reveal
  this nested enum change; the complete generated Kotlin diff was inspected.
- `watchAgentTextStream` retains its parameters/return type. Its documentation
  now names bundled public-CA roots instead of OS trust; its UniFFI checksum
  changes from `24253` to `15795`. No other checksum changes.
- The Kotlin helper files are byte-identical. No generated binding is edited.
- Production Android has no exhaustive handling of the migration enum. The
  fixture waits for both the expected legacy-byte balance and `COMPLETE` or
  `NOT_NEEDED`; transient `COMPACTING` cannot pass completion.

The inspected signature SHA-256 is
`e9be2810f149ae87aa0e8527af2b4b83e8f2792f4a84852c1c6d016aacdb9869`.
Hosted `:app:stageMarmotKitApiSignature` must reproduce it. The baseline-to-nightly
constructor/deployment and `StorageFull` compatibility changes described in the
20 October record remain in this PR; White Noise services and keychain ownership
are unchanged.

The 12-commit source range adds bounded reconciliation progress
([MDK #2490](https://github.com/marmot-protocol/mdk/pull/2490)), preserves retryable
stalled recovery routes ([#2503](https://github.com/marmot-protocol/mdk/pull/2503)),
adds database compaction ([#2496](https://github.com/marmot-protocol/mdk/pull/2496)),
and replaces the QUIC OS verifier with bundled Mozilla roots to avoid the Android
JNI initialization panic ([#2331](https://github.com/marmot-protocol/mdk/pull/2331)).
Directory extraction and Hermes test changes do not add Android host APIs.

## Migration and qualification

There is no newly numbered account migration relative to the 20 October nightly;
the current-master `88d1683e` baseline still crosses 0113 and the background
legacy-body migration. Newly created databases use incremental auto-vacuum.
Existing databases retain their mode until native compaction: at least 25% free
pages, enough volume space for the reserve plus two copies of live data, and at
most 64 MiB live data. Later maintenance/removals reclaim freed pages. Native
reads/writes can wait during compaction; usage reports the preceding snapshot.
Android must not implement VACUUM, change schema rows or invent recovery logic.

Rerun the populated hosted campaign against current master: populated baseline,
in-place upgrade, cold restart, pending-upload promotion, post-promotion restart,
and recovery with comparison refusal and silence. Preserve exact accounts/history
and both payloads; do not count ignored cases as passes. The two ignored cases
remain tied to open [MDK #2375](https://github.com/marmot-protocol/mdk/issues/2375)
and [#2381](https://github.com/marmot-protocol/mdk/issues/2381).

The PR records exact Android source, hosted run outcomes, signatures and APK
digests. The successful 20 October campaign at `755d06e1f` is historical evidence,
not qualification of this pin. All compilation, tests and runtime checks run on
GitHub under [current CI policy](ci-request-policy.md).

Exact-source [MDK CI 39462029491](https://github.com/marmot-protocol/mdk/actions/runs/39462029491)
passed. Retained individual test evidence includes compaction shrink/incremental
reclaim (Rust shard 3), free-page threshold/blocked WAL truncate/closing storage
(shard 1), size/space limits/rollback retry/new incremental databases (shard 5),
transaction refusal (shard 2)
and interruption before copy (shard 4). These are upstream tests. The prior small
Android fixture did not establish large-database compaction, actual OS ENOSPC,
corrupt/lost-media handling or the file-publication/SQL-deletion crash window.
Do not infer compaction happened merely because migration settled.

The previous source's Simulator Nightly failed a replay-budget assertion. No
exact-`8c214fee` Simulator Nightly result was found; the new recovery fixes and
Android passes cannot by themselves clear that separate gap. Real public-broker
QUIC connectivity also needs separate qualification.

The disposable debug-signed `.mdkqualification` pair does not clear the trusted
preview handoff gate. A matching signed baseline is now retained as described
below; populated upgrade and cold-restart evidence on the final signed candidate
is still required. Do not distribute the historical preview as a qualified candidate.

### Remaining qualification campaign

The expanded hosted fixture uses a 16 MiB completed attachment on each account
and the original 1 KiB pending upload. It requires at least 24 MiB of actual
closed-database shrinkage, zero retained WAL bytes, exact plaintext through
bounded reads, and the retained accounts/history after migration and restart.
It does not infer compaction from a settled status alone.
After migration settles, it keeps MDK running for up to 120 seconds for the
separate 15-second maintenance tick to compact eligible databases. It observes
file sizes, then rechecks settled native state and exact retained bytes before
closing. The same 24 MiB shrink and zero closed-WAL assertions remain required;
the fixture never invokes VACUUM or changes database rows itself.

External instrumentation is confined to the rootable GitHub emulator and the
disposable `.mdkqualification` process. It verifies the complete published
x86_64 library SHA-256 before using any native offset. The guarded legacy DELETE
preparation call is identified by its return address, SQL pointer and byte length;
the worker blocks there and the host sends SIGKILL. On reopening without starting
workers, MDK must report a committed ready media file and its still-retained legacy
body in the same account. Ordinary subsequent native migration must recover it.
No SQL, schema or native library is patched.
The pinned Frida 17.5.1 server uses `--disable-preload`: its default Android
preload injects spawning agents into `system_server` and zygote, which this
campaign does not need because it attaches directly to the fixture PID.
The option prevents that unnecessary startup path after a hosted run recorded
a zygote abort before any app hook armed. See the pinned upstream
[preload implementation](https://github.com/frida/frida-core/blob/17.5.1/src/linux/linux-host-session.vala#L91-L96).
After this witness, the already-published file bypasses new-allocation reserve
admission, so this campaign does not repeat the earlier reserve-paused-read case.
Its required kernel-ENOSPC pause and retained legacy-body evidence are separate.

A separate write barrier fills only a bounded 64 MiB tmpfs at one synthetic media
directory, after native admission and before the actual write. The real syscall
must fail with errno 28, and native state must pause with the legacy body retained.
The fixture then removes only its filler, unmounts that disposable volume, and
lets MDK recover. Corrupted and removed synthetic receiver files must never return
unauthenticated plaintext; explicit native acquisition must restore exact bytes.
The damaged-body fixture follows Android's deliberate Retry path,
`downloadAttachmentAgain`, because initial `requestExplicitAttachment` demand
does not reset an already-completed acquisition. A failed read or an explicit
unavailable result with empty bytes is valid rejection; any returned plaintext
fails qualification. Both accounts' full plaintext and history are checked after
recovery. MDK remains running until its own sweep removes a replaced orphan,
then plaintext is checked again before the next fault selects the sole receiver
body. The fixture never deletes that orphan or repairs native records itself.
Pending-upload observations retain native send disposition, confirmation/history
presence and local-reference availability separately; a returned message ID alone
does not prove publication. A promotion failure keeps the campaign failed but
allows the independent receiver-file recovery checks after a qualified cold
restart. No re-send, replacement download or persistence repair satisfies the
pending-upload assertion. These remain proposed assertions until a linked hosted
run passes.

The disposable emulator becomes root before its relay/blob reverse ports are
created. Fault injection requires that existing root state and never restarts
`adbd`; transport mappings are verified initially and after native faults.
Restarting `adbd` after mapping setup discards the listeners and prevents both
send confirmation and explicit blob recovery, which cannot qualify native
promotion or damaged-file recovery.

The trusted baseline was obtained from the genuine temporary PR3371 head whose
application/build tree is master `b3b66afefb7459e9ae9206c85c3a85627345cf2c`.
The baseline and final candidate are signed by the unchanged trusted default-branch
publisher, with the same `.preview.pr3371` package and version. The signed baseline
must be retained before the fixed candidate asset is replaced. The separate
`mdk_preview_qualification` campaign compares signed application bytes with the
exact-head unsigned build, requires ARM64 support from the hosted emulator image,
and creates synthetic history through ordinary release UI. The driver verifies
Settings arrival instead of treating an avatar tap as navigation success; it
preserves the original profile and restores the account-row viewport before
reading only its public key. Composer checks identify the focused field within
the attachment/dictation control group and over its empty placeholder: Compose
exposes this scrolling `BasicTextField` as `ScrollView`, not `EditText`. A shown
keyboard remains separately required, and focus/IME observations are retained
even on failure. It disables emulator
networking and verifies that no default network remains before the in-place
candidate upgrade, again before cold restart, and after the retained identity
check. Relay recovery cannot substitute for retained local history. Neither a
re-signed APK nor the debug instrumentation pair can substitute for these exact signed bytes. Only after these
hosted smokes may the selected Pixel 9a receive the isolated candidate for its
separate device check; its existing production/dev packages remain untouched.

The initial explicit comparison selected `-gpu swiftshader -feature -Vulkan`,
supported by [Android's emulator troubleshooting guide](https://developer.android.com/studio/run/emulator-troubleshooting).
Two attempts on emulator 37.2.12 with automatic `software` rendering lost the
emulator at the baseline composer, before candidate installation. Startup selected
Lavapipe Vulkan and SwANGLE GLES; color-buffer errors preceded the loss, but the
exact exit cause remains unproven. The retained kernel tail has no OOM entry;
post-failure free memory does not exclude earlier pressure. The explicit renderer
is an infrastructure experiment, not passing qualification evidence. It preserves
the signed APKs, ARM64 bridge and all upgrade, cold-restart and keyboard assertions.
Emulator version, requested options, allowlisted guest graphics properties, host
memory, process RSS and kernel diagnostics are retained. These diagnostics contain
no process arguments or emulator memory dumps.

The first explicit-renderer run passed the baseline and displayed upgraded
history, then lost the emulator during composer focus. It did not qualify the
upgrade or cold restart. A separate diagnostic command, `emulator -version`,
also failed because its graphical launcher required an unavailable audio library.
Version evidence now reads only `Pkg.Revision` from the installed SDK metadata.
A hosted-Linux-only observer wraps the same SDK launcher in place and preserves
its bytes, directory, arguments and inherited environment. It records child exit
status or signal, timestamps, peak child RSS and available cgroup OOM counters,
and distinguishes exit before driver completion from normal action teardown.
Kernel core dumps are disabled; raw crash databases and memory are never uploaded.
The observer never restarts a dead emulator or changes a qualification assertion.
Two subsequent attempts at `e8fd6dd` recorded host QEMU `SIGSEGV` before driver
completion, at the old baseline composer and before candidate installation.
Their cgroup OOM counters stayed zero. Neither completed a qualification phase;
the underlying crashing function is still unknown. The same baseline, driver and
renderer had passed at `9b47cf9`, so another unchanged retry is not the next step.

The signed-preview lane retains the controlled comparison version,
**37.1.11 stable**, the nearest prior stable release in
[Android's release notes](https://developer.android.com/studio/releases/emulator).
The [official archive](https://developer.android.com/studio/emulator_archive)
identifies Linux build `15917651`, with published ZIP SHA-256
`95771e0ae431897b2a4bd2d97fa095f29a8b0624a7b216baf529f9306161c266`.
The pinned action's `emulator-build` input selects that build; the prelaunch hook
fails before modifying the launcher unless installed `Pkg.Revision` is exactly
`37.1.11`. Because the action continues after a failed prelaunch hook, the driver
independently rechecks that revision and the observer installation before any
qualification or device operation. The archive was separately downloaded and its
SHA-256 verified during static diagnosis; the action itself does not verify that
archive digest. API 34, the system-image selection, ARM64 bridge, 4 GiB guest RAM
and all application assertions remain unchanged.

Each launcher invocation now retains an atomic `emulator-process-<pid>.json`,
including whether it launched an AVD. A later teardown helper cannot overwrite
the primary process's exit evidence. Regression coverage preserves the first
failed child's record across a successful helper and rejects a version mismatch
before changing SDK files or running qualification. Raw arguments, memory and
crash databases remain excluded.

### Explicit ANGLE renderer comparison

The 37.1.11 legacy SwiftShader comparison also terminated with host SIGSEGV.
Separate bounded debugger runs confirmed an executable fault address without a
retained filesystem module; they did not establish a root cause or an MDK crash.
The signed-preview lane now explicitly selects `-gpu swangle -feature -Vulkan`.
[Android documents SwANGLE](https://developer.android.com/studio/run/emulator-acceleration)
as ANGLE with SwiftShader, distinct from automatic software selection.

Diagnostic [39631114045](https://github.com/marmot-protocol/whitenoise-android/actions/runs/39631114045)
kept the same emulator build, system image, e701aee APKs and assertions. Its logs
confirmed `vulkan_mode_selected:swiftshader gles_mode_selected:swangle`, ANGLE
2.1.17841 and SwiftShader 5.0.0. Baseline, in-place upgrade, full cold restart and
retained identity passed; the primary emulator exited normally after driver
completion. Artifact `12359786449` retains the evidence. This supports the
renderer configuration as an infrastructure workaround, not proof of the original
fault's root cause. The comparison was debugger-assisted and remains diagnostic;
the current signed candidate must pass all phases without GDB before Pixel use.
No debugger or diagnostic-source override is part of this PR's normal workflow.

The ready-head attachment campaign separately failed at ADB reverse binding before
controller-resume instrumentation. Its first native receive probe passed. Failure
reports now retain only fixed binding/transport categories, the attempted service
role and the owned mapping count before that attempt. They preserve `--no-rebind`,
fail closed, and clean up only successfully owned mappings. The two unchanged
scenarios passed diagnostic [39629420667](https://github.com/marmot-protocol/whitenoise-android/actions/runs/39629420667);
the original binding cause is unknown and the full attachment campaign is still
required. No transport reset, occupied-port overwrite or failure retry was added.

## Retention

This nightly can become eligible for deletion around **20 November 2026** (30
days); do not rely on a precise cleanup hour. Retain the original ZIP, checksum,
manifest, ELF report, source/builder evidence, old/new signatures and their full
diff, and exact tested APKs. Hosted qualification artifacts retain 30 days; some
other CI artifacts retain seven. A warm cache is not durable retention. The
original verified ZIP can use the documented artifact override after expiry;
never silently substitute a rebuilt archive or change the pin to another source.
Keep migrated fixtures isolated and move them forward; never downgrade them.
