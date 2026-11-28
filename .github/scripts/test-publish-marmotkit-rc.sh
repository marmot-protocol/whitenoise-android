#!/usr/bin/env bash

set -euo pipefail

repo_dir=$(cd "$(dirname "$0")/../.." && pwd)
script="$repo_dir/.github/scripts/publish-marmotkit-rc.sh"
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
cd "$scratch"
mkdir bin proposal
export PATH="$scratch/bin:$PATH"
export MOCK_DIR="$scratch" MOCK_REMOTE="$scratch/remote.git"
export GH_TOKEN=fixture GITHUB_REPOSITORY=marmot-protocol/whitenoise-android
export GITHUB_RUN_ID=12 GITHUB_RUN_ATTEMPT=1
export GIT_AUTHOR_NAME=Fixture GIT_AUTHOR_EMAIL=fixture@example.test
export GIT_COMMITTER_NAME=Fixture GIT_COMMITTER_EMAIL=fixture@example.test
branch=refs/heads/automation/marmotkit-rc
lock=app/src/main/marmotkit/MARMOT_VERSION

cat > bin/gh <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail

repo="repos/$GITHUB_REPOSITORY"
managed=refs/heads/automation/marmotkit-rc
bare() { git --git-dir="$MOCK_REMOTE" -c commit.gpgsign=false "$@"; }

case "$1 $2" in
  'api --method')
    case "$3 $4" in
      "GET $repo/pulls")
        if [[ "$5 $6" == '-f state=open' && -f "$MOCK_DIR/open.json" ]]; then
          tip=$(bare rev-parse "$managed")
          jq --arg head "$tip" '.[0].head.sha = $head' "$MOCK_DIR/open.json"
        elif [[ "$5 $6" == '-f state=closed' && -f "$MOCK_DIR/closed.json" ]]; then
          cat "$MOCK_DIR/closed.json"
        else
          echo '[]'
          if [[ -f "$MOCK_DIR/race-after-read" ]]; then
            tip=$(bare rev-parse "$managed")
            human=$(bare commit-tree "$tip^{tree}" -p "$tip" -m 'Adapt Android call sites')
            bare update-ref "$managed" "$human" "$tip"
            printf '%s\n' "$human" > "$MOCK_DIR/human-oid"
            rm -f "$MOCK_DIR/race-after-read"
          fi
        fi
        ;;
      "POST $repo/git/refs")
        bare update-ref "${6#ref=}" "${8#sha=}"
        ;;
      "POST $repo/pulls")
        [[ "$6 $8 ${14}" == 'head=automation/marmotkit-rc base=develop draft=true' ]]
        [[ ! -f "$MOCK_DIR/open.json" ]]
        jq -n --arg title "${10#title=}" --rawfile body "${12#body=@}" \
          '[{number:42,base:{ref:"develop"},draft:true,title:$title,body:$body}]' > "$MOCK_DIR/open.json"
        cp "${12#body=@}" "$MOCK_DIR/pr-body"
        ;;
      "DELETE $repo/git/refs/heads/"*)
        bare update-ref -d "refs/heads/${4#"$repo/git/refs/heads/"}"
        ;;
      *) exit 1 ;;
    esac
    ;;
  'api graphql')
    document=$4
    oid=$(jq -r '.variables.input.expectedHeadOid' "$document")
    branch=$(jq -r '.variables.input.branch.branchName' "$document")
    lock=$(jq -r '.variables.input.fileChanges.additions[0].path' "$document")
    export GIT_INDEX_FILE="$MOCK_DIR/index.fixture"
    bare read-tree "$oid"
    blob=$(jq -j '.variables.input.fileChanges.additions[0].contents | @base64d' "$document" | bare hash-object -w --stdin)
    bare update-index --add --cacheinfo "100644,$blob,$lock"
    tree=$(bare write-tree)
    commit=$(jq -r '.variables.input.message | .headline, "", .body' "$document" | bare commit-tree "$tree" -p "$oid")
    bare update-ref "refs/heads/$branch" "$commit" "$oid"
    printf '%s\n' "$commit"
    ;;
  'api user') echo fixture-publisher ;;
  "api $repo/commits/"*)
    commit=${2##*/}
    if [[ -f "$MOCK_DIR/concurrent-push" && "$commit" != "$(bare rev-parse "$managed")" ]]; then
      mv "$MOCK_DIR/concurrent-push" "$MOCK_DIR/race-after-read"
    fi
    if [[ -f "$MOCK_DIR/reopen-during-signing" && "$commit" != "$(bare rev-parse "$managed")" ]]; then
      mv "$MOCK_DIR/reopen-during-signing" "$MOCK_DIR/open.json"
    fi
    if [[ ! -f "$MOCK_DIR/human-oid" || "$commit" != "$(cat "$MOCK_DIR/human-oid")" ]]; then
      echo fixture-publisher
    fi
    ;;
  *) exit 1 ;;
esac
MOCK
chmod +x bin/gh

git init --quiet --bare "$MOCK_REMOTE"
git init --quiet -b develop work
git -C work remote add origin "$MOCK_REMOTE"
mkdir -p "work/$(dirname "$lock")"
echo 'artifact-id=0.19.0' > "work/$lock"
git -C work add "$lock"
git -C work -c commit.gpgsign=false commit --quiet -m Stable
git -C work push --quiet origin develop
git -C work rev-parse HEAD > proposal/base.txt

# Writes a verified proposal fixture for one published RC.
write_proposal() {
  jq -n --arg version "$1" '{
    version:$version, impact:"minor",
    metadata_commit:"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    workflow_run:"https://github.com/marmot-protocol/mdk/actions/runs/42",
    manifest_features_verified:false
  }' > proposal/proposal.json
  echo "artifact-id=$1" > proposal/MARMOT_VERSION
}

# Runs the publisher against the isolated bare remote and mock GitHub API.
publish() { (cd work && bash "$script" "$scratch/proposal"); }
# Reads the exact revision that an open test PR would reference.
remote_tip() { git --git-dir="$MOCK_REMOTE" rev-parse "$branch"; }

write_proposal 0.20.0-rc.2
publish
first=$(remote_tip)
git --git-dir="$MOCK_REMOTE" show "$first:$lock" | cmp -s - proposal/MARMOT_VERSION
grep -q 'Proposes MarmotKit 0.20.0-rc.2 on develop' pr-body
grep -q 'manifest omits its feature list' pr-body
[[ -z "$(git --git-dir="$MOCK_REMOTE" for-each-ref 'refs/heads/automation/marmotkit-rc-run-*')" ]]
publish | grep -q 'PR #42 is open'
[[ "$(remote_tip)" == "$first" ]]

jq '.[0] |= (.body += "\nReviewed the builder flags.\n" | .title = "Android RC test")' open.json > changed.json
mv changed.json open.json
cp open.json expected.json
# A newer RC leaves the head and review metadata intact even while the PR is a draft.
write_proposal 0.20.0-rc.3
publish | grep -q 'PR #42 is open'
[[ "$(remote_tip)" == "$first" ]]
cmp open.json expected.json

jq '.[0].draft = false' open.json > ready.json
cp ready.json open.json
publish | grep -q 'PR #42 is open'
[[ "$(remote_tip)" == "$first" ]]
cmp open.json ready.json

# A closed pin stays closed for the same RC, but a newer version can be proposed.
jq --arg head "$first" '.[0].head.sha = $head' open.json > closed.json
rm open.json
write_proposal 0.20.0-rc.2
publish | grep -q 'A closed PR already proposed this pin'
[[ "$(remote_tip)" == "$first" && ! -f open.json ]]

write_proposal 0.20.0-rc.3
cp ready.json reopen-during-signing
publish | grep -q 'An RC pull request opened during verification'
[[ "$(remote_tip)" == "$first" ]]
cmp open.json ready.json
rm open.json
publish
updated=$(remote_tip)
[[ "$updated" != "$first" ]]
git --git-dir="$MOCK_REMOTE" show "$updated:$lock" | cmp -s - proposal/MARMOT_VERSION
grep -q 'Proposes MarmotKit 0.20.0-rc.3 on develop' pr-body

# A normal merge may leave the automation ref at a commit now on develop.
git -C work push --quiet origin "$updated:refs/heads/develop"
printf '%s\n' "$updated" > proposal/base.txt
rm open.json
write_proposal 0.20.0-rc.4
publish
integrated=$(remote_tip)
git --git-dir="$MOCK_REMOTE" show "$integrated:$lock" | cmp -s - proposal/MARMOT_VERSION
rm open.json

write_proposal 0.20.0-rc.5
touch concurrent-push
if publish > race.log 2>&1; then
  echo "Expected a stale lease after a concurrent push." >&2
  exit 1
fi
grep -q 'stale info' race.log
[[ "$(remote_tip)" == "$(cat human-oid)" ]]
publish | grep -q 'Preserving those changes.'
[[ "$(remote_tip)" == "$(cat human-oid)" ]]

# An amendment can retain the trailer and the bot's author. Its committer is a person.
amendment=$(git --git-dir="$MOCK_REMOTE" -c commit.gpgsign=false commit-tree "$integrated^{tree}" -p "$updated" \
  -m 'Amend the pin' -m 'MarmotKit-RC-Bump: true')
git --git-dir="$MOCK_REMOTE" update-ref "$branch" "$amendment"
printf '%s\n' "$amendment" > human-oid
publish | grep -q 'Preserving those changes.'
[[ "$(remote_tip)" == "$amendment" ]]
