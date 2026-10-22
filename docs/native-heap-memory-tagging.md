# Native heap memory tagging

The main application requests `android:memtagMode="async"`. Debug builds replace
that value with `sync` for fault attribution. Play, Zapstore and preview overlays
inherit this policy, as do the isolated image and animation decoder processes.
Native libraries, ABIs, the minimum Android version and package/signing identities
retain their existing configuration.

The opt-in provides native **heap** hardening on compatible hardware and operating systems.
Stack instrumentation and protection within native libraries' private
suballocators remain separate coverage limits. Unsupported devices must remain usable. Effective runtime mode and compatibility require device evidence.

See the [Android NDK MTE guide](https://developer.android.com/ndk/guides/arm-mte)
and [AOSP MTE guidance](https://source.android.com/docs/security/test/memory-safety/arm-mte).
The source regression runs in the existing release tooling checks:

```sh
python3 -m unittest scripts/test_release_pipeline.py
```

## Qualification before release

Release qualification requires the following evidence in addition to the source check.

- Inspect merged manifests and the final hosted CI APK manifests for both
  distributions, persistent preview and debug: release ASYNC, debug SYNC and inherited
  process policy. Bind observations to the exact APK and native dependency.
- On MTE-capable arm64 hardware, record OS/build and requested versus effective
  tag-check mode in the app and both decoder processes. Ordinary emulators and
  unsupported hardware establish compatibility only. Test GrapheneOS separately
  when available; retain separate results for each OS.
- Preserve populated data through an in-place upgrade and cold start. Exercise
  database/account opening, relay messaging, encryption/decryption, attachment
  transfers, image/animation decoding, audio/video and JNA calls in release ASYNC
  and debug SYNC. Include repeated allocation/free, cancellation and failure paths
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
