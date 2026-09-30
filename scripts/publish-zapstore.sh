#!/usr/bin/env bash
# Public publication is intentionally isolated from every build/internal workflow.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
[[ "${GITHUB_ACTIONS:-}" == true && "${GITHUB_REF:-}" == refs/heads/master && \
   "${GITHUB_WORKFLOW:-}" == 'Android Zapstore - PUBLIC Publication' ]] || {
  echo 'error: public publication must use the dedicated protected GitHub workflow' >&2
  exit 1
}
[[ "${EXPECTED_VERSION:-}" =~ ^20[0-9]{2}\.[0-9]{1,2}\.[0-9]{1,2}$ && \
   "${CONFIRMATION:-}" == "PUBLISH ZAPSTORE $EXPECTED_VERSION" ]] || {
  echo 'error: explicit confirmation of this public version is required' >&2
  exit 1
}
zsp="${1:?Pass the pinned zsp executable}"
readback="${2:-}"
[[ -n "$readback" && -x "$readback" ]] || { echo 'error: pass the built Zapstore readback tool' >&2; exit 1; }
# Bounded so a remote signer that never answers fails in minutes, not at the job limit.
preflight_timeout="${ZSP_PREFLIGHT_TIMEOUT_SECONDS:-120}"
publish_timeout="${ZSP_PUBLISH_TIMEOUT_SECONDS:-480}"
retry_delay="${ZSP_RETRY_DELAY_SECONDS:-20}"
for value in "$preflight_timeout" "$publish_timeout" "$retry_delay"; do
  [[ "$value" =~ ^[0-9]{1,4}$ ]] || { echo 'error: invalid ZSP timeout or retry delay' >&2; exit 1; }
done
[[ "${SIGN_WITH:-}" == bunker://* ]] || { echo 'error: a scoped ZAPSTORE_SIGN_WITH bunker connection is required' >&2; exit 1; }
[[ "${BUNKER_CLIENT_KEY:-}" =~ ^[0-9a-fA-F]{64}$ ]] || { echo 'error: missing valid bunker client key' >&2; exit 1; }
signer_pubkey="${SIGN_WITH#bunker://}"
signer_pubkey="${signer_pubkey%%\?*}"
signer_pubkey="$(printf '%s' "$signer_pubkey" | tr '[:upper:]' '[:lower:]')"
[[ "$signer_pubkey" =~ ^[0-9a-f]{64}$ ]] || { echo 'error: invalid bunker address' >&2; exit 1; }
# This is the bunker transport identity, not necessarily its signing identity.
# The latter is checked against the signed preflight events below.
umask 077
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT
export XDG_CONFIG_HOME="$temporary_dir/config"
mkdir -p "$XDG_CONFIG_HOME/zsp/bunker-keys"
printf '%s\n' "$BUNKER_CLIENT_KEY" > "$XDG_CONFIG_HOME/zsp/bunker-keys/$signer_pubkey.key"

# Restore only the reviewed listing into a fresh directory. Current master copy
# or assets must not silently replace the listing bundled with the candidate.
cd "$repo_dir"
python3 - "$temporary_dir/listing" <<'PY'
import json, os, shutil, sys, zipfile
from pathlib import Path
sys.path.insert(0, 'scripts')
from release_bundle import verify_bundle, properties, verify_source_metadata, check_tag
bundle = Path('build/production-release')
manifest = json.loads((bundle / 'release-manifest.json').read_text())
verify_bundle(bundle, os.environ['MANIFEST_SHA256'], os.environ['EXPECTED_VERSION'],
              manifest['sourceCommit'], manifest['buildRunId'], manifest['buildRunAttempt'], properties())
verify_source_metadata(bundle, manifest)
check_tag(manifest['sourceCommit'], manifest['versionName'])
target = Path(sys.argv[1])
target.mkdir()
with zipfile.ZipFile(bundle / f"store-assets-{manifest['versionName']}.zip") as archive:
    for entry in archive.infolist():
        if entry.is_dir():
            continue
        name = Path(entry.filename)
        if name.is_absolute() or '..' in name.parts:
            raise SystemExit('Unsafe listing path')
        out = target / name
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(archive.read(entry))
apk_dir = target / 'build/production-release'
apk_dir.mkdir(parents=True)
for apk in bundle.glob('*.apk'):
    shutil.copyfile(apk, apk_dir / apk.name)
PY
read -r source_sha version_code apk_sha256 < <(python3 -c '
import json
m = json.load(open("build/production-release/release-manifest.json"))
apk = [name for name in m["files"] if name.endswith(".apk")]
assert len(apk) == 1
print(m["sourceCommit"], m["versionCode"], m["files"][apk[0]]["sha256"])')
[[ "$source_sha" =~ ^[0-9a-f]{40}$ && "$version_code" =~ ^[0-9]+$ && "$apk_sha256" =~ ^[0-9a-f]{64}$ ]] || {
  echo 'error: cannot read the reviewed candidate identity' >&2
  exit 1
}
# shellcheck source=scripts/release-properties.sh
source "$repo_dir/scripts/release-properties.sh"
expected_publisher="$(release_property ZAPSTORE_PUBLISHER_PUBKEY)"
readback_args=(-relay "$(release_property ZAPSTORE_RELAY)" -publisher "$expected_publisher"
  -app "$(release_property ZAPSTORE_APP_ID)" -version "$EXPECTED_VERSION" -apk-sha256 "$apk_sha256")

# Portable timeout (macOS has no coreutils timeout). Returns 124 on timeout.
run_with_timeout() {
  local seconds="$1"
  shift
  local expired="$temporary_dir/timeout-$RANDOM$RANDOM"
  "$@" &
  local pid=$!
  ( sleep "$seconds" && touch "$expired" && kill -TERM "$pid" && sleep 5 && kill -KILL "$pid" ) >/dev/null 2>&1 &
  local watchdog=$!
  local status=0
  wait "$pid" || status=$?
  kill "$watchdog" 2>/dev/null || true
  wait "$watchdog" 2>/dev/null || true
  if [[ -e "$expired" ]]; then
    rm -f "$expired"
    return 124
  fi
  return "$status"
}

cd "$temporary_dir/listing"
# Offline mode contacts the remote signer but does not upload blobs or publish
# release events, so it is always safe to retry. Capture signer output
# privately; it can include auth URLs.
preflight_ok=false
for attempt in 1 2 3; do
  status=0
  run_with_timeout "$preflight_timeout" "$zsp" publish --offline --quiet --skip-metadata --no-compress \
    --commit "$source_sha" zapstore.yaml > "$temporary_dir/preflight.jsonl" 2> "$temporary_dir/preflight.log" || status=$?
  if [[ "$status" == 0 ]]; then
    preflight_ok=true
    break
  fi
  if [[ "$status" == 124 ]]; then
    echo "Zapstore signing preflight attempt $attempt: the remote signer did not answer within ${preflight_timeout}s" >&2
  else
    echo "Zapstore signing preflight attempt $attempt failed (exit $status)" >&2
  fi
  [[ "$attempt" == 3 ]] || sleep "$retry_delay"
done
"$preflight_ok" || { echo 'error: Zapstore signing preflight failed; nothing was publicly published' >&2; exit 1; }
python3 - "$temporary_dir/preflight.jsonl" "$expected_publisher" <<'PY'
import json, sys
from pathlib import Path
events = []
for line in Path(sys.argv[1]).read_text().splitlines():
    if line.startswith('{'):
        events.append(json.loads(line))
if not events or not {32267, 30063}.issubset({e.get('kind') for e in events}):
    raise SystemExit('error: missing Zapstore signing preflight events')
if any(e.get('pubkey') != sys.argv[2] or len(e.get('sig', '')) != 128 for e in events):
    raise SystemExit('error: Zapstore signer differs from the pinned publisher or returned unsigned events')
PY
# Never publish over an existing or partial release for this version.
"$readback" absent "${readback_args[@]}" || {
  echo 'error: Zapstore already has events for this version, or its state is unknown; inspect before publishing' >&2
  exit 1
}
# PUBLIC SIDE EFFECT: the only online zsp publish invocation in our tooling.
# Do not fetch external metadata or automatically link the Android signing key.
# First-release certificate linking is a separate holder-operated prerequisite.
# ZSP signs everything before it publishes release events, so a run stopped
# while waiting on the signer has at most uploaded the APK blob. Retry only
# after the relay confirms that no release or asset event exists.
published=false
for attempt in 1 2; do
  status=0
  run_with_timeout "$publish_timeout" "$zsp" publish --quiet --skip-metadata --no-compress --skip-certificate-linking \
    --commit "$source_sha" zapstore.yaml > "$temporary_dir/publication.log" 2>&1 || status=$?
  if [[ "$status" == 0 ]]; then
    published=true
    break
  fi
  if [[ "$status" == 124 ]]; then
    echo "Zapstore publication attempt $attempt did not finish within ${publish_timeout}s" >&2
  else
    echo "Zapstore publication attempt $attempt failed (exit $status)" >&2
  fi
  sleep "$retry_delay"
  "$readback" absent "${readback_args[@]}" || {
    echo 'error: Zapstore publication was partial or its state is unknown; inspect relay and Blossom state before retrying' >&2
    exit 1
  }
done
"$published" || { echo 'error: Zapstore publication failed; the relay has no events for this version' >&2; exit 1; }
"$readback" verify "${readback_args[@]}" -version-code "$version_code" -commit "$source_sha" \
  -cert-sha256 "$(release_property APP_SIGNING_SHA256)" -blossom "$(release_property ZAPSTORE_BLOSSOM)" || {
  echo 'error: Zapstore publication completed but the relay or CDN does not match the reviewed candidate' >&2
  exit 1
}
echo "Zapstore public publication of $EXPECTED_VERSION verified on the relay and CDN."
