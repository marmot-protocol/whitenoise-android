# Native heap memory tagging

The main application requests `android:memtagMode="async"`. Debug builds replace
that value with `sync` for fault attribution. Play, Zapstore and preview overlays
inherit this policy, as do the isolated image and animation decoder processes.
On stock Android the still-image worker forks from the app's application zygote
(#3373). A worker starts from its zygote's heap-tagging state, and Bionic cannot
turn tagging back on in a process where it was disabled
([`SetHeapTaggingLevel`](https://android.googlesource.com/platform/bionic/+/refs/tags/android-15.0.0_r1/libc/bionic/heap_tagging.cpp#144)).
In the AOSP source, read on 31 October 2026:

- Android 11 and 12.0 start every application zygote with runtime flags 0, which
  turns its tagging off
  ([`AppZygote`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/core/java/android/os/AppZygote.java#113)),
  so its workers run without MTE and without pointer tagging (TBI). Whether any
  Android 12.0 device enables MTE was not checked; the TBI loss applies to arm64
  devices on those versions.
- Android 12.1 gives the application zygote the app's own level
  ([`decideTaggingLevelForAppZygote`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.1.0_r1/services/core/java/com/android/server/am/ProcessList.java#1751)),
  so workers keep MTE on capable hardware, and keep TBI for an app targeting
  above API 31, as this one does.
- Android 13 and later also give it the app's level
  ([`AppZygote`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/core/java/android/os/AppZygote.java#112)),
  so workers keep MTE on MTE hardware. Without MTE hardware, the app's TBI level
  is reset to none for an app targeting above API 31
  ([`getMemorySafetyRuntimeFlagsForSecondaryZygote`](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/com/android/internal/os/Zygote.java#1488)),
  the opposite of what that compat change's own description says. Its workers
  still request TBI and Bionic refuses it, so they run untagged.

Workers started without the application zygote, as master starts them, keep the
app's level. GrapheneOS exec-spawns those workers instead, each with the app's
own level. Everything above is a source reading, not a device observation. On a
non-MTE arm64 device or emulator, a worker's libc log line `SetHeapTaggingLevel:
re-enabling tagging after it was disabled is not supported` confirms the TBI
loss; the qualification below records each worker's state on MTE hardware.

Native libraries, ABIs, the minimum Android version and package/signing identities
retain their existing configuration.

The opt-in provides native **heap** hardening on compatible hardware and operating systems.
Stack instrumentation and protection within native libraries' private
suballocators remain separate coverage limits. Unsupported devices must remain usable. Effective runtime mode and compatibility require device evidence.

See the [Android NDK MTE guide](https://developer.android.com/ndk/guides/arm-mte)
and [AOSP MTE guidance](https://source.android.com/docs/security/test/memory-safety/arm-mte).
The manifest values are requests; effective checking follows the OS and hardware
policy. In the GrapheneOS 17 source inspected on 22 October 2026,
[manifest opt-in makes the memory-tagging setting immutable and enabled for apps with 64-bit native code](https://github.com/GrapheneOS/platform_frameworks_base/blob/17/core/java/android/ext/settings/app/AswUseMemoryTagging.java#L25-L63).
On MTE-capable hardware,
[Zygote selects its ASYNC launch policy when that setting is enabled](https://github.com/GrapheneOS/platform_frameworks_base/blob/17/core/java/com/android/internal/os/Zygote.java#L1366-L1404),
including apps requesting SYNC. The manifest request does not identify the
effective fault mode. GrapheneOS qualification confirms MTE enabled. Fault-mode
identification and a separate per-mode test matrix are unnecessary.

The source regression runs in the existing release tooling checks:

```sh
python3 -m unittest scripts/test_release_pipeline.py
```

## Qualification before release

Release qualification requires the following evidence in addition to the source check.

- Inspect merged manifests and the final hosted CI APK manifests for both
  distributions, persistent preview and debug: the release ASYNC request, debug SYNC
  request and inherited process policy. Bind observations to the exact APK and native dependency.
- On MTE-capable arm64 hardware, record OS/build, manifest requests and MTE state
  (enabled, disabled or unverified) in the app, both decoder processes and the
  image worker's application zygote. Require
  MTE enabled on capable hardware; no fault-mode probe is required. Ordinary emulators and
  unsupported hardware establish compatibility only. Test GrapheneOS separately
  when available; retain separate results for each OS.
- Preserve populated data through an in-place upgrade and cold start. Exercise
  database/account opening, relay messaging, encryption/decryption, attachment
  transfers, image/animation decoding, audio/video and JNA calls in release and
  debug builds with MTE enabled on capable hardware. Include repeated allocation/free, cancellation and failure paths
  with synthetic fixtures and the existing device lease.
- Compare startup, peak memory and representative media/messaging latency with
  an opt-out baseline of the same revision on the same hardware. Record measurements
  and unresolved costs; overhead remains unmeasured until then.
- Retain MTE fault signal/code and symbolized native diagnostics privately.
  Diagnose faults in the owning dependency, repair and repeat the failing case;
  preserve the requested tagging policy throughout qualification.

MTE-capable device results, release compatibility and overhead measurements remain
pending until their exact-build evidence is recorded. Keep issue #3392 open until
its qualification requirements are met.
