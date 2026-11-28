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
prefix=refs/heads/automation/marmotkit-rc
lock=app/src/main/marmotkit/MARMOT_VERSION

cat > bin/gh <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail

repo="repos/$GITHUB_REPOSITORY"
# Apply real Git ref preconditions in an isolated bare repository.
bare() { git --git-dir="$MOCK_REMOTE" -c commit.gpgsign=false "$@"; }

case "$1 $2" in
  'api --method')
    case "$3 $4" in
      'GET --paginate')
        [[ "$5 $6 $7 $8 $9 ${10}" == "--slurp $repo/pulls -f state=open -f per_page=100" ]]
        # Put the active proposals on the second page to exercise pagination.
        jq -n --slurpfile open "$MOCK_DIR/open.json" '[[], $open[0]]'
        ;;
      "GET $repo/pulls")
        [[ "$5 $6 $7" == '-f state=all -f' ]]
        head_label=${8#head=}
        jq -s --arg head "$head_label" 'add | map(select(.head.label == $head))' \
          "$MOCK_DIR/open.json" "$MOCK_DIR/closed.json"
        ;;
      "POST $repo/git/refs")
        ref=${6#ref=}
        sha=${8#sha=}
        if [[ "$ref" != refs/heads/automation/marmotkit-rc-run-* && -f "$MOCK_DIR/race-ref" ]]; then
          bare update-ref "$ref" "$(cat "$MOCK_DIR/race-ref")" ''
          rm "$MOCK_DIR/race-ref"
        fi
        bare update-ref "$ref" "$sha" ''
        ;;
      "POST $repo/pulls")
        [[ "$8 ${14}" == 'base=develop draft=true' ]]
        head=${6#head=}
        tip=$(bare rev-parse "refs/heads/$head")
        jq -n --arg repo "$GITHUB_REPOSITORY" --arg head "$head" --arg sha "$tip" \
          --arg title "${10#title=}" --rawfile body "${12#body=@}" --slurpfile open "$MOCK_DIR/open.json" '
          $open[0] + [{number:42,base:{ref:"develop"},draft:true,title:$title,body:$body,
            head:{ref:$head,label:(($repo | split("/")[0]) + ":" + $head),sha:$sha,repo:{full_name:$repo}}}]
        ' > "$MOCK_DIR/new-open.json"
        mv "$MOCK_DIR/new-open.json" "$MOCK_DIR/open.json"
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
    signing_branch=$(jq -r '.variables.input.branch.branchName' "$document")
    lock_path=$(jq -r '.variables.input.fileChanges.additions[0].path' "$document")
    export GIT_INDEX_FILE="$MOCK_DIR/index.fixture"
    bare read-tree "$oid"
    blob=$(jq -j '.variables.input.fileChanges.additions[0].contents | @base64d' "$document" | bare hash-object -w --stdin)
    bare update-index --add --cacheinfo "100644,$blob,$lock_path"
    tree=$(bare write-tree)
    commit=$(jq -r '.variables.input.message | .headline, "", .body' "$document" | bare commit-tree "$tree" -p "$oid")
    bare update-ref "refs/heads/$signing_branch" "$commit" "$oid"
    printf '%s\n' "$commit"
    ;;
  'api user') echo fixture-publisher ;;
  "api $repo/commits/"*)
    if [[ -f "$MOCK_DIR/reopen-during-signing" ]]; then
      mv "$MOCK_DIR/reopen-during-signing" "$MOCK_DIR/open.json"
    fi
    jq -n --argjson verified "$(cat "$MOCK_DIR/verified")" \
      --arg author "$(cat "$MOCK_DIR/author")" --arg committer "$(cat "$MOCK_DIR/committer")" '
      {commit:{verification:{verified:$verified}},author:{login:$author},committer:{login:$committer}}
    '
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
printf '[]\n' > open.json
printf '[]\n' > closed.json
printf 'true\n' > verified
printf 'fixture-publisher\n' > author
printf 'web-flow\n' > committer

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
# Reads the tip of the requested RC branch and fails if it does not exist.
remote_tip() { git --git-dir="$MOCK_REMOTE" rev-parse --verify "$prefix-$1"; }
# Fail if publishing touched a version that should have no branch.
require_absent() {
  if remote_tip "$1" > /dev/null 2>&1; then
    echo "Unexpected branch for MarmotKit $1." >&2
    exit 1
  fi
}
# Moves the current proposal to PR history while leaving its Git branch intact.
close_proposal() {
  jq -s 'add' closed.json open.json > history.json
  mv history.json closed.json
  printf '[]\n' > open.json
}

write_proposal 0.20.0-rc.2
publish
first=$(remote_tip 0.20.0-rc.2)
git --git-dir="$MOCK_REMOTE" show "$first:$lock" | cmp -s - proposal/MARMOT_VERSION
grep -q 'Proposes MarmotKit 0.20.0-rc.2 on develop' pr-body
grep -q 'manifest omits its feature list' pr-body

# A newer RC preserves an open PR's head and review metadata in either state.
jq '.[0] |= (.body += "\nReviewed the builder flags.\n" | .title = "Android RC test")' open.json > draft.json
cp draft.json open.json
write_proposal 0.20.0-rc.3
publish | grep -q 'PR #42 is open'
cmp draft.json open.json
[[ "$(remote_tip 0.20.0-rc.2)" == "$first" ]]
require_absent 0.20.0-rc.3
jq '.[0].draft = false' open.json > ready.json
cp ready.json open.json
publish | grep -q 'PR #42 is open'
cmp ready.json open.json
close_proposal

# Retiring an older proposal preserves its human commits without blocking the next RC.
human=$(git --git-dir="$MOCK_REMOTE" -c commit.gpgsign=false commit-tree "$first^{tree}" -p "$first" -m 'Adapt Android call sites')
git --git-dir="$MOCK_REMOTE" update-ref "$prefix-0.20.0-rc.2" "$human" "$first"
publish
second=$(remote_tip 0.20.0-rc.3)
git --git-dir="$MOCK_REMOTE" show "$second:$lock" | cmp -s - proposal/MARMOT_VERSION
[[ "$(remote_tip 0.20.0-rc.2)" == "$human" ]]
close_proposal
git --git-dir="$MOCK_REMOTE" update-ref -d "$prefix-0.20.0-rc.3" "$second"
publish | grep -q 'already proposed 0.20.0-rc.3'
require_absent 0.20.0-rc.3

# A reopened PR on an earlier version retains its original ref and notes.
write_proposal 0.20.0-rc.4
cp ready.json reopen-during-signing
publish | grep -q 'opened during verification'
cmp ready.json open.json
[[ "$(remote_tip 0.20.0-rc.2)" == "$human" ]]
require_absent 0.20.0-rc.4
printf '[]\n' > open.json
publish
[[ "$(remote_tip 0.20.0-rc.4)" != "$human" ]]
close_proposal

# Creating a version branch is atomic with respect to a pre-existing human ref.
write_proposal 0.20.0-rc.5
printf '%s\n' "$human" > race-ref
if publish > race.log 2>&1; then
  echo 'Expected a create-only ref conflict.' >&2
  exit 1
fi
[[ "$(remote_tip 0.20.0-rc.5)" == "$human" ]]
publish | grep -q 'already exists without a PR'

# Return realistic API objects so the publisher's real jq predicate runs.
write_proposal 0.20.0-rc.6
for scenario in unsigned wrong-author human-committer; do
  printf 'true\n' > verified
  printf 'fixture-publisher\n' > author
  printf 'web-flow\n' > committer
  case "$scenario" in
    unsigned) printf 'false\n' > verified ;;
    wrong-author) printf 'another-user\n' > author ;;
    human-committer) printf 'another-user\n' > committer ;;
  esac
  if publish > verification.log 2>&1; then
    echo "Expected signature or ownership verification to reject $scenario." >&2
    exit 1
  fi
  grep -q 'did not verify the signed pin commit' verification.log
  require_absent 0.20.0-rc.6
done
[[ -z "$(git --git-dir="$MOCK_REMOTE" for-each-ref 'refs/heads/automation/marmotkit-rc-run-*')" ]]
