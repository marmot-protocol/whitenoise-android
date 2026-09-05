# Invite acceptance during native catch-up

Tracks [issue #2487](https://github.com/marmot-protocol/whitenoise-android/issues/2487)
and [PR #2489](https://github.com/marmot-protocol/whitenoise-android/pull/2489).

`InviteContentionDeviceTest` exercises the actual Android conversation controller,
JNI bindings, account worker, encrypted invitation, and native persistence on a
device. It does not inject a busy exception or automate a screen tap.

The relay fixture withholds end-of-stored-events (EOSE) responses for up to ten
seconds during `catchUpAccounts()`. Native catch-up can finish earlier under its
own query budget, at which point the test releases the relay. The controller attempts to accept a pending invite
while the account worker owns that catch-up. The test records the native error
type and controller result. A baseline failure must retain the pending invite
across native restart. The patched run must accept the same invitation, preserve
acceptance across restart, and deliver a message to the other fixture account.

## Isolation

- Use only `dev.ipf.whitenoise.android.preview.pr2489` and its `.test` package.
  The test refuses other target packages and skips unless the explicit argument
  below is supplied.
- Install with `adb install -r -t`. Never uninstall or clear a physical device's
  app data. Run instrumentation directly; ordinary `connectedAndroidTest`
  teardown can uninstall the app.
- Both builds use the same package, version code, signing certificate, test APK,
  and native SDK fixture library. Only the Android retry implementation differs.
- The fixture creates its own identities under
  `files/invite-contention-fixture/<fixture-id>/{sender,receiver}`. Its small `fixture.json`
  records test account references and the group identifier; protocol state and
  signing keys remain in native storage. Keep this directory between runs.
- Keep the relay process alive between comparisons: its test events are held
  in memory. The relay binds only to laptop loopback and is reached over USB.

## Native fixture prerequisite

The shipped MarmotKit 0.9.18 binding rejects plaintext loopback relays and does
not expose the Rust loopback opt-in. A local fixture library is therefore needed
for this controlled reproduction. Do not ship or publish that library.

Use the exact pinned MDK revision
`f734b31176ad628d5e5dfcb047f80e4ec7bb826c`. In a separate checkout, change only
`Marmot::new` in `crates/marmot-uniffi/src/lib.rs` to choose:

```rust
let fixture_loopback = root_path.contains("/invite-contention-fixture/")
    && relay_urls == ["ws://127.0.0.1:19488"];
Self::open(
    root_path,
    relay_urls,
    MarmotAppConfig::default().with_allow_loopback_relay_endpoints(fixture_loopback),
    None,
)
```

Build `marmot-uniffi` for `aarch64-linux-android` using the repository's release
profile, endpoint defaults, and Android NDK configuration:

```sh
source crates/marmot-uniffi/marmotkit-release-profile.env
source crates/marmot-uniffi/marmotkit-endpoints.env
ANDROID_NDK_HOME=<installed-ndk-directory> CARGO_PROFILE_RELEASE_STRIP=symbols \
  cargo ndk -t arm64-v8a --platform 26 build --release --locked \
  -p marmot-uniffi --features otlp-export
```

The local native build changes only permission to dial
the fixture relay; retain all catch-up, busy-error, and invite logic unchanged.
Replace `lib/arm64-v8a/libmarmot_uniffi.so` in both local test APK copies with
that identical library, realign them for 16 KiB pages, and re-sign both with the
same Android debug certificate. Keep shipped APKs and the artifact cache intact.

## Android build and run

Build the baseline from `4604235d6` and the fix from the PR branch, with the
identical test source copied into both. Locally enable the normally disabled
`previewDebug` variant in `androidComponents.beforeVariants`; restore that
build-file edit after assembly. Use these preview settings for both builds:

```sh
PR_NUMBER=2489 PR_PREVIEW_CHANNEL=isolated PREVIEW_HEAD_SHA=<source-commit> \
  ./gradlew :app:assemblePreviewPlayDebug :app:assemblePreviewPlayDebugAndroidTest \
  -Pandroid.injected.build.abi=arm64-v8a
```

Start `python3 scripts/invite_contention_relay.py` on the laptop (requires the
`websockets` Python package). Set `PIXEL_SERIAL` to the intended test device:

```sh
adb -s "$PIXEL_SERIAL" reverse tcp:19488 tcp:19488
adb -s "$PIXEL_SERIAL" reverse tcp:19489 tcp:19489
adb -s "$PIXEL_SERIAL" logcat -T 1 -v threadtime \
  InviteBusyProbe:I DMConversation:W '*:S' > invite-contention-logcat.txt
```

In another terminal, install the baseline fixture APK and the test APK with
`adb -s "$PIXEL_SERIAL" install -r -t <apk>`, then run:

```sh
adb -s "$PIXEL_SERIAL" shell am instrument -w -r \
  -e class dev.ipf.whitenoise.android.state.InviteContentionDeviceTest \
  -e inviteExpectedAccepted false -e inviteFixtureId comparison-1 \
  dev.ipf.whitenoise.android.preview.pr2489.test/androidx.test.runner.AndroidJUnitRunner
```

Inspect the instrumentation result, not just the shell exit code. The baseline
test passes when it observes the expected failure: three real busy responses,
`GROUP_INVITE_ACCEPT / RESOURCE_BUSY`, and an invitation that remains pending.

Update the target APK in place to the patched fixture APK. Run the same command
with `-e inviteExpectedAccepted true`. Do not reset the app, fixture, or relay
between these two runs. The successful comparison consumes the invitation, so a
new comparison needs a new `inviteFixtureId` (use the same value for its baseline
and patched runs). This creates a separate fixture directory without clearing
any existing data.

When done, stop the owned logcat and relay processes and remove only these
forwards:

```sh
adb -s "$PIXEL_SERIAL" reverse --remove tcp:19488
adb -s "$PIXEL_SERIAL" reverse --remove tcp:19489
```

## Interpretation

This test can prove the native catch-up contention path and Android retry
behavior. It cannot identify the original reporter's exact busy subtype: their
exported main log buffer starts after the reported failure. Keep that incident
limitation separate from the controlled device evidence.

## Observed Pixel result (2026-09-05)

The matched comparison passed on a Pixel 9 Pro XL running Android 17 / API 37.
Both direct instrumentation runs reported `OK (1 test)`.

| Observation | Baseline `4604235d6` | Patched `9d7cbb734` |
| --- | --- | --- |
| Native acceptance attempts | Three `AccountWorkerBusy` failures | Two `AccountWorkerBusy` failures, then success |
| Result measured from join start | Failed in 1.57 s with `GROUP_INVITE_ACCEPT / RESOURCE_BUSY` | Accepted in 2.30 s |
| Native restart | Invitation remained pending | Acceptance remained persisted |
| Message after acceptance | Not sent | Other fixture account received it |
| Instrumentation duration | 15.702 s | 15.667 s |

The fixture metadata SHA-256 was checked immediately before and after the APK
update and was identical:
`f0e6e7fcfa27d632fa6052aa6ee6957548c0e7d228843705c330caf77894193c`.
The patched run reused the pending invitation; it did not create new identities
or a replacement invitation. No app was uninstalled or cleared for the comparison.

The relay had a ten-second EOSE hold configured, but native catch-up completed
in about two seconds. The observed failure proves that the baseline's retry
window can expire during a short catch-up; it does not demonstrate a ten-second
native lock. The focused JVM tests separately cover ten-second contention and
retry-deadline boundaries.

Both local APK copies contain native fixture library SHA-256
`3f497a948924d6e9aae6f2b6bf7ecb8e5d9231817b5928f305225907544693d0`.
This library is rebuilt from the pinned source with the scoped loopback opt-in;
it is not the byte-identical shipped SDK. No worker or invite logic was changed
in the native fixture.

This controller fixture does not bootstrap the full app shell. Its background
roster, timeline, and read-state refreshes log `Marmot is not initialized`; those
fixture-wiring warnings do not establish production refresh behavior. Acceptance,
persisted group state, sending, and peer receipt are verified against the real
native runtimes. This is not a UI gesture or full-screen integration test.

The final Android fast completion gate passed: both dev flavors compile,
Android-test compilation, ktlint, detekt, Android lint, and the 24 focused
`IdempotentMutationRetryTest` / `DiagnosticFormatterTest` JVM tests passed.
