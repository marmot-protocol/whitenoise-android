#!/usr/bin/env bash

set -euo pipefail

proposal=${1:?Pass the verified proposal directory.}
repo=${GITHUB_REPOSITORY:?}
repo_api="repos/$repo"
base=develop
branch_prefix=automation/marmotkit-rc
lock_path=app/src/main/marmotkit/MARMOT_VERSION
proposal_lock="$proposal/MARMOT_VERSION"

fail() {
  echo "::error::$*" >&2
  exit 1
}

remote_git() {
  # The checkout token is not persisted. Use the scoped credential for GitHub ref operations.
  git -c credential.helper= -c 'credential.helper=!gh auth git-credential' "$@"
}

# Require a verified GitHub commit attributed to the dedicated publisher.
verify_pin_commit() {
  gh api "$repo_api/commits/$1" | jq -e --arg publisher "$publisher" '
    .commit.verification.verified == true and .author.login == $publisher and
    (.committer.login == "web-flow" or .committer.login == $publisher)
  ' > /dev/null
}

# Find an open bot proposal across RC versions, including the former shared branch.
open_pin() {
  gh api --method GET --paginate --slurp "$repo_api/pulls" -f state=open -f per_page=100 |
    jq -r --arg repo "$repo" --arg prefix "$branch_prefix" '
      first(.[][] | select(.head.repo.full_name == $repo) |
        select(.head.ref == $prefix or (.head.ref | test("^" + $prefix + "-[0-9]+[.][0-9]+[.][0-9]+-rc[.][0-9]+$"))) |
        .number) // empty
    '
}

# PR history retains the head branch name after its ref is deleted.
existing_version_pr() {
  gh api --method GET "$repo_api/pulls" -f state=all -f head="${repo%%/*}:$branch" |
    jq -r '.[0].number // empty'
}

[[ -n "${GH_TOKEN:-}" ]] || fail 'Set MARMOTKIT_BUMP_TOKEN to a dedicated bot-user token (Contents and Pull requests write).'
publisher=$(gh api user --jq .login)
[[ -n "$publisher" ]] || fail 'The bump credential must identify a dedicated bot user.'

base_sha=$(cat "$proposal/base.txt")
version=$(jq -er '.version' "$proposal/proposal.json")
impact=$(jq -er '.impact' "$proposal/proposal.json")
metadata_commit=$(jq -er '.metadata_commit' "$proposal/proposal.json")
release_run=$(jq -er '.workflow_run' "$proposal/proposal.json")
manifest_features=$(jq -er '.manifest_features_verified | if type == "boolean" then tostring else error("missing feature status") end' "$proposal/proposal.json")
mdk_url=https://github.com/marmot-protocol/mdk
[[ "$base_sha" =~ ^[a-f0-9]{40}$ && "$metadata_commit" =~ ^[a-f0-9]{40}$ ]] || fail 'Invalid proposal revision.'
[[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-rc\.(0|[1-9][0-9]*)$ ]] || fail 'Invalid RC version.'
[[ "$impact" == patch || "$impact" == minor ]] || fail 'Invalid RC impact.'
[[ "$release_run" =~ ^https://github\.com/marmot-protocol/mdk/actions/runs/[1-9][0-9]*$ ]] || fail 'Invalid publishing run URL.'
grep -Fxq "artifact-id=$version" "$proposal_lock" || fail 'The candidate lock does not match the proposal version.'
branch="$branch_prefix-$version"
branch_ref="refs/heads/$branch"

remote_git fetch --quiet origin "refs/heads/$base"
if [[ "$(git rev-parse FETCH_HEAD)" != "$base_sha" ]]; then
  echo "$base moved while the candidate was being verified. Run the workflow again to use the new base."
  exit 0
fi

pr=$(open_pin) || fail 'Could not check for an open RC pull request.'
if [[ -n "$pr" ]]; then
  echo "PR #$pr is open. Leaving its pin and metadata in place."
  exit 0
fi

pr=$(existing_version_pr) || fail "Could not check the proposal history for $version."
if [[ -n "$pr" ]]; then
  echo "PR #$pr already proposed $version. Leaving it in place."
  exit 0
fi

existing_ref=$(remote_git ls-remote --heads origin "$branch_ref") ||
  fail 'Could not check the version branch.'
if [[ -n "$existing_ref" ]]; then
  echo "::warning::$branch already exists without a PR. Review it before opening a draft for $version."
  exit 0
fi

scratch=$(mktemp -d)
trial="automation/marmotkit-rc-run-${GITHUB_RUN_ID:?}-${GITHUB_RUN_ATTEMPT:?}"
trial_created=false
# Remove only the temporary signing ref and local scratch files.
cleanup() {
  if [[ "$trial_created" == true ]]; then
    gh api --method DELETE "$repo_api/git/refs/heads/$trial" > /dev/null ||
      echo "::warning::Could not remove $trial." >&2
  fi
  rm -rf "$scratch"
}
trap cleanup EXIT

cat > "$scratch/body.md" <<EOF
Proposes MarmotKit $version on develop. The published RC metadata classifies this update as **$impact**.

- [Publishing run]($release_run)
- [UniFFI changes and storage migrations]($mdk_url/blob/$metadata_commit/rc/signature_delta.json)
- [Integration notes]($mdk_url/blob/$metadata_commit/rc/integration-notes.md)

Review the Android call sites and storage changes before marking this ready. Run Android CI on the resulting pin.

Part of #4255.
EOF
if [[ "$manifest_features" == false ]]; then
  cat >> "$scratch/body.md" <<EOF

The $version ZIP manifest omits its feature list. Before marking this ready, verify the source, builder and effective Android feature flags for every ABI in the publishing run. Record evidence for this exact version below.
EOF
fi

gh api --method POST "$repo_api/git/refs" -f ref="refs/heads/$trial" -f sha="$base_sha" > /dev/null
trial_created=true
jq -n --arg repo "$repo" --arg branch "$trial" --arg base "$base_sha" \
  --arg title "Pin MarmotKit $version" --arg path "$lock_path" --rawfile contents "$proposal_lock" '{
    query: "mutation($input: CreateCommitOnBranchInput!) { createCommitOnBranch(input: $input) { commit { oid } } }",
    variables: {input: {
      branch: {repositoryNameWithOwner: $repo, branchName: $branch},
      expectedHeadOid: $base,
      message: {headline: $title, body: "MarmotKit-RC-Bump: true"},
      fileChanges: {additions: [{path: $path, contents: ($contents | @base64)}]}
    }}
  }' > "$scratch/commit.json"
commit=$(gh api graphql --input "$scratch/commit.json" --jq '.data.createCommitOnBranch.commit.oid')
[[ "$commit" =~ ^[a-f0-9]{40}$ ]] || fail 'GitHub did not return the signed pin commit.'
verify_pin_commit "$commit" || fail 'GitHub did not verify the signed pin commit under the bot user.'

pr=$(open_pin) || fail 'Could not recheck for an open RC pull request.'
if [[ -n "$pr" ]]; then
  echo "PR #$pr opened during verification. Leaving it in place."
  exit 0
fi

# Creating a new ref cannot replace one that was published in the meantime.
gh api --method POST "$repo_api/git/refs" -f ref="$branch_ref" -f sha="$commit" > /dev/null
pr=$(existing_version_pr) || fail "Could not recheck the proposal history for $version."
if [[ -n "$pr" ]]; then
  echo "PR #$pr already proposed $version. Leaving it in place."
  exit 0
fi
current_ref=$(remote_git ls-remote --heads origin "$branch_ref") ||
  fail 'Could not verify the automation branch head.'
[[ "${current_ref%%$'\t'*}" == "$commit" ]] || fail 'The automation branch changed before opening the draft.'
gh api --method POST "$repo_api/pulls" -f head="$branch" -f base="$base" -f title="Pin MarmotKit $version on $base" \
  -F body=@"$scratch/body.md" -F draft=true --jq .html_url ||
  fail "Could not open a draft. The signed pin is preserved on $branch."
