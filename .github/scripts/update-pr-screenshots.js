const START = '<!-- pr-screenshots:start -->'
const END = '<!-- pr-screenshots:end -->'
const SNAPSHOT_PREFIX = 'app/src/test/snapshots/'

function escapeCell(value) {
  return value.replaceAll('|', '\\|')
}

function repoFileUrl(repository, sha, path) {
  const encodedPath = path.split('/').map(encodeURIComponent).join('/')
  return `https://github.com/${repository}/blob/${sha}/${encodedPath}?raw=1`
}

function isUiFile(path) {
  return (path.startsWith('app/src/main/java/') && path.includes('/ui/') && path.endsWith('.kt')) ||
    path.startsWith('app/src/main/res/') ||
    (path.startsWith('app/src/test/java/') && path.includes('/ui/screenshot/')) ||
    path.startsWith(SNAPSHOT_PREFIX)
}

function isMissingVisualCoverage(files) {
  const hasUiChanges = files.some(file => isUiFile(file.filename))
  const hasSnapshotChanges = files.some(file =>
    file.filename.startsWith(SNAPSHOT_PREFIX) &&
    file.filename.endsWith('.png') &&
    file.status !== 'removed')
  return hasUiChanges && !hasSnapshotChanges
}

function renderSection(pr, files) {
  const snapshots = files
    .filter(file => file.filename.startsWith(SNAPSHOT_PREFIX) && file.filename.endsWith('.png'))
    .sort((a, b) => a.filename.localeCompare(b.filename))
  const uiFiles = files.filter(file => isUiFile(file.filename))
  const lines = [START, '## Visual changes', '']

  if (snapshots.length === 0) {
    lines.push(uiFiles.length === 0
      ? 'No UI-affecting files were detected in this pull request.'
      : '⚠️ UI-affecting files changed, but no committed Roborazzi screenshots changed. Please add or update a screenshot test when the change is visual.')
  } else {
    lines.push(
      `Generated from the committed Roborazzi baselines at head \`${pr.head.sha}\`. This section updates automatically when new commits are pushed.`,
      '',
    )
    for (const file of snapshots) {
      const title = escapeCell(file.filename.slice(SNAPSHOT_PREFIX.length))
      const before = repoFileUrl(pr.base.repo.full_name, pr.base.sha, file.previous_filename || file.filename)
      const after = repoFileUrl(pr.head.repo.full_name, pr.head.sha, file.filename)

      lines.push(`### ${title}`, '')
      if (file.status === 'added') {
        lines.push('| Current |', '| --- |', `| ![${title}](${after}) |`)
      } else if (file.status === 'removed') {
        lines.push('| Removed baseline |', '| --- |', `| ![${title}](${before}) |`)
      } else {
        lines.push('| Before | After |', '| --- | --- |', `| ![Before: ${title}](${before}) | ![After: ${title}](${after}) |`)
      }
      lines.push('')
    }
  }

  lines.push(END)
  return lines.join('\n')
}

function replaceSection(body, section) {
  const current = body || ''
  const start = current.indexOf(START)
  const end = current.indexOf(END)
  if (start !== -1 && end > start) {
    return `${current.slice(0, start)}${section}${current.slice(end + END.length)}`
  }
  return `${current}${current.length ? '\n\n' : ''}${section}`
}

async function run({ github, context, core }) {
  const { data: pr } = await github.rest.pulls.get({
    owner: context.repo.owner,
    repo: context.repo.repo,
    pull_number: context.payload.pull_request.number,
  })
  const files = await github.paginate(github.rest.pulls.listFiles, {
    owner: context.repo.owner,
    repo: context.repo.repo,
    pull_number: pr.number,
    per_page: 100,
  })
  const section = renderSection(pr, files)

  try {
    await github.rest.pulls.update({
      owner: context.repo.owner,
      repo: context.repo.repo,
      pull_number: pr.number,
      body: replaceSection(pr.body, section),
    })
    core.info('Updated the pull request description with the visual changes section.')
  } catch (error) {
    core.warning(`Could not edit the pull request description; using a comment instead: ${error.message}`)
    const comments = await github.paginate(github.rest.issues.listComments, {
      owner: context.repo.owner,
      repo: context.repo.repo,
      issue_number: pr.number,
      per_page: 100,
    })
    const existing = comments.find(comment => comment.user.type === 'Bot' && comment.body?.includes(START))
    const request = {
      owner: context.repo.owner,
      repo: context.repo.repo,
      issue_number: pr.number,
      body: section,
    }
    if (existing) {
      await github.rest.issues.updateComment({ ...request, comment_id: existing.id })
    } else {
      await github.rest.issues.createComment(request)
    }
  }

  if (isMissingVisualCoverage(files)) {
    core.setFailed(
      'UI-affecting files changed without a committed Roborazzi screenshot baseline. ' +
      'Add or update a screenshot test and commit its generated PNG.',
    )
  }
}

module.exports = { isUiFile, isMissingVisualCoverage, renderSection, replaceSection, repoFileUrl, run }
