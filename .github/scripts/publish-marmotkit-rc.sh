#!/usr/bin/env bash

set -euo pipefail

proposal=${1:?Pass the verified proposal directory.}
repo=${GITHUB_REPOSITORY:?}
repo_api="repos/$repo"
base=develop
branch=automation/marmotkit-rc
branch_ref="refs/heads/$branch"
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

verified_commit_owner() {
  # A local amendment can retain the original author and trailer. Check the committer too.
  gh api "$repo_api/commits/$1" --jq \
    'select(.commit.verification.verified and (.committer.login == "web-flow" or .committer.login == .author.login)) | .author.login // empty'
}

pulls_for_branch() {
  gh api --method GET "$repo_api/pulls" -f state=open -f head="${repo%%/*}:$branch"
}

no_open_pin() {
  # Recheck PR state independently of the Git ref lease before publishing.
  local current_pulls
  current_pulls=$(pulls_for_branch) || fail 'Could not recheck the active RC pull request.'
  [[ "$(jq 'length' <<< "$current_pulls")" == 0 ]]
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

remote_git fetch --quiet origin "refs/heads/$base"
if [[ "$(git rev-parse FETCH_HEAD)" != "$base_sha" ]]; then
  echo "$base moved while the candidate was being verified. Run the workflow again to use the new base."
  exit 0
fi

open_prs=$(pulls_for_branch)
pr=$(jq -r '.[0].number // empty' <<< "$open_prs")
if [[ "$(jq 'length' <<< "$open_prs")" -gt 1 || -n "$pr" && "$(jq -r '.[0].base.ref' <<< "$open_prs")" != "$base" ]]; then
  fail "Resolve the existing PR from $branch before publishing."
fi
if [[ -n "$pr" ]]; then
  echo "PR #$pr is open. Leaving its pin and metadata in place."
  exit 0
fi

remote=$(remote_git ls-remote --heads origin "$branch_ref")
read -r inspected _ <<< "$remote" || true
proposal_matches=false
if [[ -n "$inspected" ]]; then
  remote_git fetch --quiet origin "$inspected"
  ahead=$(git rev-list --count "$inspected" --not "$base_sha")
  if [[ "$ahead" != 0 ]]; then
    owner=$(verified_commit_owner "$inspected")
    if [[ "$ahead" != 1 || "$owner" != "$publisher" ||
        "$(git show -s --format='%(trailers:key=MarmotKit-RC-Bump,valueonly)' "$inspected")" != true ||
        "$(git diff-tree --no-commit-id --name-only -r "$inspected")" != "$lock_path" ]]; then
      echo "$branch contains changes outside the automation's pin commit. Preserving those changes."
      exit 0
    fi
  fi
  if git show "$inspected:$lock_path" | cmp -s - "$proposal_lock"; then
    proposal_matches=true
    closed_prs=$(gh api --method GET "$repo_api/pulls" -f state=closed -f base="$base" -f head="${repo%%/*}:$branch")
    if jq -e --arg commit "$inspected" 'any(.[]; .head.sha == $commit)' <<< "$closed_prs" > /dev/null; then
      echo "A closed PR already proposed this pin. Keeping it closed."
      exit 0
    fi
  fi
fi

scratch=$(mktemp -d)
trial="automation/marmotkit-rc-run-${GITHUB_RUN_ID:?}-${GITHUB_RUN_ATTEMPT:?}"
trial_created=false
cleanup() {
  if [[ "$trial_created" == true ]]; then
    gh api --method DELETE "$repo_api/git/refs/heads/$trial" > /dev/null ||
      echo "::warning::Could not remove $trial." >&2
  fi
  rm -rf "$scratch"
}
trap cleanup EXIT

cat > "$scratch/generated.md" <<EOF
<!-- marmotkit-rc-bump:start -->
Proposes MarmotKit $version on develop. The published RC metadata classifies this update as **$impact**.

- [Publishing run]($release_run)
- [UniFFI changes and storage migrations]($mdk_url/blob/$metadata_commit/rc/signature_delta.json)
- [Integration notes]($mdk_url/blob/$metadata_commit/rc/integration-notes.md)

Review the Android call sites and storage changes before marking this ready. Run Android CI on the resulting pin.

Part of #4255.
EOF
if [[ "$manifest_features" == false ]]; then
  cat >> "$scratch/generated.md" <<EOF

The $version ZIP manifest omits its feature list. Before marking this ready, verify the source, builder and effective Android feature flags for every ABI in the publishing run. Record evidence for this exact version below.
EOF
fi
printf '\n<!-- marmotkit-rc-bump:end -->\n' >> "$scratch/generated.md"

commit=$inspected
if [[ "$proposal_matches" == false ]]; then
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
  [[ "$(verified_commit_owner "$commit")" == "$publisher" ]] ||
    fail 'GitHub did not verify the signed pin commit under the bot user.'

  remote_git fetch --quiet origin "$commit"
  if ! no_open_pin; then
    echo 'An RC pull request opened during verification. Leaving it in place.'
    exit 0
  fi
  remote_git push --force-with-lease="$branch_ref:$inspected" origin "$commit:$branch_ref"
fi

if ! no_open_pin; then
  echo 'An RC pull request is already open. Leaving it in place.'
  exit 0
fi
current_ref=$(remote_git ls-remote --heads origin "$branch_ref") ||
  fail 'Could not verify the automation branch head.'
[[ "${current_ref%%$'\t'*}" == "$commit" ]] || fail 'The automation branch changed before opening the draft.'
gh api --method POST "$repo_api/pulls" -f head="$branch" -f base="$base" -f title="Pin MarmotKit $version on $base" \
  -F body=@"$scratch/generated.md" -F draft=true --jq .html_url
