# MarmotKit November 8 nightly evaluation

Testing-only adoption of `0.15.0-nightly.20261108` over the
[November 6 nightly](marmotkit-nightly-20261106.md).

## Published artifact

- `marmotkit-android-0.15.0-nightly.20261108.zip` and its sibling checksum
  are available on the immutable [release](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.15.0-nightly.20261108).
- Source and builder both resolve to `580082a5840cc86d05263a0337041897f39864b0` in the
  [publishing run](https://github.com/marmot-protocol/mdk/actions/runs/42487537739).
- The downloaded ZIP SHA-256 is
  `a870da8b8c87ad04242dcfe709ab04f1c4d518e9c9c05585ce1e95cd17cc7f4e`.
  It matches the published checksum and GitHub asset digest.
- The manifest records workspace `0.15.0-dev`, Android API 26, and the exact-source
  Cargo.lock digest `1cee4d41fcca1bcb1a0363f81fb49669c11c30e3d49c9b0cb5a77c412c7dd85b`.
  Android's artifact parser accepted the manifest, layout, and all four ABI libraries.

The manifest omits the Cargo feature list. The exact
[workflow](https://github.com/marmot-protocol/mdk/blob/580082a5840cc86d05263a0337041897f39864b0/.github/workflows/bindings.yaml)
and [builder](https://github.com/marmot-protocol/mdk/blob/580082a5840cc86d05263a0337041897f39864b0/crates/marmot-uniffi/kotlin-bindings.sh)
map `OTLP_EXPORT=1` and `PRODUCT_ANALYTICS_EXPORT=1` to the reviewed
`otlp-export,product-analytics-export` selection. The successful
[arm64-v8a](https://github.com/marmot-protocol/mdk/actions/runs/42487537739/job/128752804015),
[armeabi-v7a](https://github.com/marmot-protocol/mdk/actions/runs/42487537739/job/128752804072),
[x86](https://github.com/marmot-protocol/mdk/actions/runs/42487537739/job/128752803996), and
[x86_64](https://github.com/marmot-protocol/mdk/actions/runs/42487537739/job/128752804000)
builds use that configuration. The builder keeps experimental device groups disabled.

## Generated API

The signature advances from 432 types, 431 checksums, and 5 helper declarations
to 447 types, 446 checksums, and 5 helpers. Emoji set management accounts for the
15 added types and 15 added checksums. The existing account recovery status
and two chat-list query checksums change. Chat-list filters add direct, group,
muted, and text predicates. `DeviceLinkSnapshotFfi` gains a defaulted
`deviceGroupStalled` field. Android's existing admin departure flow already
demotes the account before requesting Leave.

The generated signature SHA-256 is
`d53ee800c30038aed9e12f3adc1683edb8b5910d16bdd71ba6c2423e5f3cf131`.
The matching native libraries and generated Kotlin come from the same archive.

## Retained-data qualification

The populated upgrade baseline is the November 6 pin at `9a45434c`. The candidate
includes new recovery, emoji-set, chat-list, removal validation, and device
inactivity migrations. Run the hosted populated upgrade and cold-restart
campaign before claiming retained-data qualification.
