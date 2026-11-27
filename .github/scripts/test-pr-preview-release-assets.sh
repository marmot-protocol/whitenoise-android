#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
export PATH="$tmp:$PATH" GITHUB_REPOSITORY=example/app PR_NUMBER=42
export GH_CALLS="$tmp/gh-calls" RELEASE_PAGES="$tmp/pages.json"
export RELEASE_STATUS=200 PR_STATE=closed

cat > "$tmp/gh" <<'FAKE_GH'
#!/usr/bin/env bash
set -eu
printf '%s\n' "$*" >> "$GH_CALLS"
case "$*" in
  'api repos/example/app/releases/tags/android-pr-previews --jq .id')
    case "$RELEASE_STATUS" in
      200) echo 11 ;;
      *) echo "gh: API error (HTTP $RELEASE_STATUS)" >&2; exit 1 ;;
    esac
    ;;
  'api repos/example/app/releases/11/assets?per_page=100 --paginate --slurp')
    cat "$RELEASE_PAGES"
    ;;
  'api repos/example/app/pulls/42 --jq .state')
    printf '%s\n' "$PR_STATE"
    ;;
  'api --method DELETE repos/example/app/releases/assets/71' | \
  'api --method DELETE repos/example/app/releases/assets/74')
    ;;
  *) exit 2 ;;
esac
FAKE_GH
chmod +x "$tmp/gh"

assets="$script_dir/pr-preview-release-assets.sh"
printf '%s\n' '[
  [{"name":"whitenoise-pr-42-stable.apk","id":71},{"name":"whitenoise-pr-421-stable.apk","id":72}],
  [{"name":"other.apk","id":73},{"name":"whitenoise-pr-42-isolated.apk","id":74}]
]' > "$RELEASE_PAGES"
"$assets" prune
[[ $(grep ' --method DELETE ' "$GH_CALLS") == "$(printf '%s\n' \
  'api --method DELETE repos/example/app/releases/assets/71' \
  'api --method DELETE repos/example/app/releases/assets/74')" ]]
printf 'ok - deletes exactly both preview names, including assets on later pages\n'

: > "$GH_CALLS"
PR_STATE=open "$assets" prune
! grep -q ' --method DELETE ' "$GH_CALLS"

: > "$GH_CALLS"
printf '%s\n' '[[{"name":"whitenoise-pr-421-stable.apk","id":72}]]' > "$RELEASE_PAGES"
"$assets" prune
! grep -Eq ' --method DELETE |/pulls/' "$GH_CALLS"

: > "$GH_CALLS"
RELEASE_STATUS=404 "$assets" prune
RELEASE_STATUS=404 "$assets" check-capacity stable
! grep -Eq ' --method DELETE |/assets\?|/pulls/' "$GH_CALLS"
if RELEASE_STATUS=403 "$assets" prune 2>"$tmp/error"; then
  echo 'Unexpected success on a failed inventory request' >&2
  exit 1
fi
grep -Fq 'HTTP 403' "$tmp/error"
printf 'ok - protects reopened PRs and handles missing releases and failed API reads\n'

# Include replacements on the last page to detect a one-page count or
# replacement lookup. Both channels must fit before either upload can start.
write_inventory() {
  jq -n --argjson count "$1" --arg name "$2" '
    [range($count) | {name: "asset-\(.)", id: .}] + [{name: $name, id: 71}] |
    [range(0; length; 100) as $i | .[$i:$i+100]]
  ' > "$RELEASE_PAGES"
}

write_inventory 948 whitenoise-pr-42-stable.apk
"$assets" check-capacity stable isolated
write_inventory 949 whitenoise-pr-42-stable.apk
"$assets" check-capacity stable
if "$assets" check-capacity stable isolated 2>"$tmp/error"; then
  echo 'Combined preview publication exceeded the capacity reserve' >&2
  exit 1
fi
grep -Fq 'has 950 assets and needs 1 new slots' "$tmp/error"
write_inventory 948 other.apk
if "$assets" check-capacity stable isolated 2>"$tmp/error"; then
  echo 'Two new preview assets unexpectedly fit in one slot' >&2
  exit 1
fi
grep -Fq 'has 949 assets and needs 2 new slots' "$tmp/error"
write_inventory 999 whitenoise-pr-42-stable.apk
"$assets" check-capacity stable
printf 'ok - counts all release assets and reserves capacity for new names only\n'
