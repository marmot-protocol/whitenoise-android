#!/usr/bin/env bash
# One-time public NIP-09 deletion of the retired org.parres.whitenoise app.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
legacy_app_id='org.parres.whitenoise'
[[ "${GITHUB_ACTIONS:-}" == true && "${GITHUB_REF:-}" == refs/heads/master && \
   "${GITHUB_WORKFLOW:-}" == 'Android Zapstore - PUBLIC Legacy App Deletion' ]] || {
  echo 'error: legacy deletion must use the dedicated protected GitHub workflow' >&2
  exit 1
}
[[ "${EXPECTED_APP_ID:-}" == "$legacy_app_id" && \
   "${CONFIRMATION:-}" == "DELETE ZAPSTORE $legacy_app_id" ]] || {
  echo 'error: exact legacy app deletion confirmation is required' >&2
  exit 1
}
[[ "${SIGN_WITH:-}" == bunker://* ]] || { echo 'error: scoped Zapstore bunker connection is required' >&2; exit 1; }
[[ "${BUNKER_CLIENT_KEY:-}" =~ ^[0-9a-fA-F]{64}$ ]] || { echo 'error: missing valid bunker client key' >&2; exit 1; }

# shellcheck source=scripts/release-properties.sh
source "$repo_dir/scripts/release-properties.sh"
[[ "$(release_property ZSP_VERSION)" == 0.4.17 ]] || { echo 'error: update deletion source pin with ZSP' >&2; exit 1; }
export EXPECTED_PUBLISHER
EXPECTED_PUBLISHER="$(release_property ZAPSTORE_PUBLISHER_PUBKEY)"

umask 077
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT
git -C "$temporary_dir" init -q source
git -C "$temporary_dir/source" fetch -q --depth 1 https://github.com/zapstore/zsp.git c50c1ccbf32d7fa6ae00f74990ce2c83f3bac6a1
git -C "$temporary_dir/source" checkout -q --detach FETCH_HEAD
(
  cd "$temporary_dir/source"
  GOWORK=off go test "$repo_dir/scripts/delete-legacy-zapstore-app.go" "$repo_dir/scripts/delete-legacy-zapstore-app_test.go"
  GOWORK=off go build -o "$temporary_dir/delete-legacy-zapstore-app" "$repo_dir/scripts/delete-legacy-zapstore-app.go"
)
"$temporary_dir/delete-legacy-zapstore-app"
