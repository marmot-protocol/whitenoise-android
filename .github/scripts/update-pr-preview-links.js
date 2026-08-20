const START = '<!-- pr-apk-preview:start -->'
const END = '<!-- pr-apk-preview:end -->'
const LEGACY_COMMENT_MARKER = '<!-- pr-apk-preview -->'
const APK_MIME = 'application/vnd.android.package-archive'

function renderSection({ prNumber, headSha, stableUrl, isolatedUrl }) {
  return [
    START,
    '## Preview APK',
    '',
    `**[Install/update White Noise PR](${stableUrl})** — stable app identity; keeps app data when switching PRs.`,
    '',
    `Built from PR #${prNumber} at \`${headSha.slice(0, 12)}\`. This replaces the installed White Noise PR app and retains its accounts and history.`,
    '',
    `[Isolated PR #${prNumber}](${isolatedUrl}) — use for storage/auth changes or side-by-side testing.`,
    '',
    'Updates automatically on every push to this PR.',
    END,
  ].join('\n')
}

function replaceSection(body, section) {
  const current = body || ''
  const start = current.indexOf(START)
  const end = current.indexOf(END)
  if (start !== -1 && end > start) {
    return `${current.slice(0, start)}${section}${current.slice(end + END.length)}`
  }
  if (start !== -1) {
    const prefix = current.slice(0, start).trimEnd()
    return `${prefix}${prefix.length ? '\n\n' : ''}${section}`
  }
  return `${current}${current.length ? '\n\n' : ''}${section}`
}

async function removeLegacyPreviewComment(github, context, prNumber, core) {
  const comments = await github.paginate(github.rest.issues.listComments, {
    owner: context.repo.owner,
    repo: context.repo.repo,
    issue_number: prNumber,
    per_page: 100,
  })
  const existing = comments.find(comment =>
    comment.user?.login === 'github-actions[bot]' &&
    comment.body?.includes(LEGACY_COMMENT_MARKER)
  )
  if (!existing) {
    return
  }
  await github.rest.issues.deleteComment({
    owner: context.repo.owner,
    repo: context.repo.repo,
    comment_id: existing.id,
  })
  core.info('Removed the legacy preview APK comment now that links live in the description.')
}

async function run({ github, context, core }) {
  const prNumber = Number(process.env.PR_NUMBER)
  const headSha = process.env.HEAD_SHA
  const stableUrl = process.env.STABLE_URL
  const isolatedUrl = process.env.ISOLATED_URL
  if (!prNumber || !headSha || !stableUrl || !isolatedUrl) {
    throw new Error('PR_NUMBER, HEAD_SHA, STABLE_URL, and ISOLATED_URL are required')
  }

  const { data: pr } = await github.rest.pulls.get({
    owner: context.repo.owner,
    repo: context.repo.repo,
    pull_number: prNumber,
  })
  if (pr.state !== 'open' || pr.head?.sha !== headSha) {
    core.info(`Skipping preview links for superseded PR head ${headSha}.`)
    return
  }
  const section = renderSection({ prNumber, headSha, stableUrl, isolatedUrl })
  await github.rest.pulls.update({
    owner: context.repo.owner,
    repo: context.repo.repo,
    pull_number: prNumber,
    body: replaceSection(pr.body, section),
  })
  core.info('Updated the pull request description with preview APK links.')

  // A newer push can land while this job is publishing. Keep the legacy
  // fallback comment unless the PR is still open at this exact build head;
  // the newer job can replace the links and remove it safely.
  const { data: latestPr } = await github.rest.pulls.get({
    owner: context.repo.owner,
    repo: context.repo.repo,
    pull_number: prNumber,
  })
  if (latestPr.state !== 'open' || latestPr.head?.sha !== headSha) {
    core.info(`Keeping the legacy preview comment because PR #${prNumber} moved after publication.`)
    return
  }
  await removeLegacyPreviewComment(github, context, prNumber, core)
}

module.exports = { START, END, APK_MIME, renderSection, replaceSection, removeLegacyPreviewComment, run }
