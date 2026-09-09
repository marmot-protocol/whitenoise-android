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
source_sha="$(python3 -c 'import json; print(json.load(open("build/production-release/release-manifest.json"))["sourceCommit"])')"
# shellcheck source=scripts/release-properties.sh
source "$repo_dir/scripts/release-properties.sh"
expected_publisher="$(release_property ZAPSTORE_PUBLISHER_PUBKEY)"
cd "$temporary_dir/listing"
# Offline mode contacts the remote signer but does not upload blobs or publish
# release events. Capture signer output privately; it can include auth URLs.
if ! "$zsp" publish --offline --quiet --skip-metadata --no-compress --commit "$source_sha" zapstore.yaml \
  > "$temporary_dir/preflight.jsonl" 2> "$temporary_dir/preflight.log"; then
  echo 'error: Zapstore signing preflight failed; nothing was publicly published' >&2
  exit 1
fi
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
# PUBLIC SIDE EFFECT: the only online zsp publish invocation in our tooling.
# Do not fetch external metadata or automatically link the Android signing key.
# First-release certificate linking is a separate holder-operated prerequisite.
if ! "$zsp" publish --quiet --skip-metadata --no-compress --skip-certificate-linking --commit "$source_sha" zapstore.yaml \
  > "$temporary_dir/publication.log" 2>&1; then
  echo 'error: Zapstore publication failed or was partial; inspect public state before retrying' >&2
  exit 1
fi
echo "Zapstore public publication command completed for $EXPECTED_VERSION; verify relay and asset receipts."
