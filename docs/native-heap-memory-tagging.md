# Native heap memory tagging

The main application requests `android:memtagMode="async"`. Debug builds replace
that value with `sync` for fault attribution. Play, Zapstore and preview overlays
inherit this policy, as do the isolated image and animation decoder processes.
Still-image workers fork from the app's application zygote (#3373); that launch
path's effective tagging needs its own device evidence.
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
