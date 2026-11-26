const { spawnSync } = require('node:child_process')
const { randomUUID } = require('node:crypto')
const { mkdtempSync, rmSync } = require('node:fs')
const { tmpdir } = require('node:os')
const { join } = require('node:path')

const MAX_PRS_PER_PLAN = 5
const MAX_PATHS_PER_PR = 6
const MAX_INVENTORY = 100
const SIMULATION_ID = randomUUID()

/** Run Git with an argument list and keep NUL-delimited filenames intact. */
function gitAt(cwd, args, allowedStatuses = [0]) {
  const result = spawnSync('git', args, {
    cwd,
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
    env: {
      ...process.env,
      GIT_AUTHOR_NAME: 'PR merge order',
      GIT_AUTHOR_EMAIL: 'pr-merge-order@example.invalid',
      GIT_COMMITTER_NAME: 'PR merge order',
      GIT_COMMITTER_EMAIL: 'pr-merge-order@example.invalid',
      GIT_TERMINAL_PROMPT: '0',
    },
  })
  if (result.error) throw result.error
  if (!allowedStatuses.includes(result.status)) {
    throw new Error(`git ${args[0]} failed (${result.status}): ${result.stderr.trim()}`)
  }
  return result
}

/** Collect conflicted files plus paths that Git reports only in structured messages. */
function conflictPaths(fields) {
  const separator = fields.indexOf('')
  if (separator < 0) throw new Error('Git omitted the merge conflict message separator')
  const paths = new Set(fields.slice(0, separator))
  for (let index = separator + 1; index < fields.length - 1;) {
    const count = Number(fields[index])
    const messageType = index + count + 1
    if (!Number.isSafeInteger(count) || count < 0 || messageType + 1 >= fields.length - 1) {
      throw new Error('Git returned an invalid merge conflict message')
    }
    if (fields[messageType].startsWith('CONFLICT')) {
      for (const path of fields.slice(index + 1, messageType)) paths.add(path)
    }
    index = messageType + 2
  }
  return [...paths]
}

/** Simulate one squash merge and report conflict paths without checking out the candidate. */
function squashMerge(cwd, base, head) {
  const result = gitAt(cwd, [
    'merge-tree', '--write-tree', '--name-only', '--messages', '-z', base, head,
  ], [0, 1])
  const [tree, ...paths] = result.stdout.split('\0')
  if (!/^[0-9a-f]{40}$/.test(tree)) throw new Error('Git returned an invalid merge tree')
  if (result.status === 1) return { conflicts: conflictPaths(paths) }

  const commit = gitAt(cwd, [
    '-c', 'commit.gpgsign=false', 'commit-tree', tree, '-p', base, '-m', `Simulated squash merge ${SIMULATION_ID}`,
  ]).stdout.trim()
  return { commit, tree }
}

/** Search all candidate orders until a longest clean prefix is known. */
function longestCleanOrder(cwd, base, candidates) {
  let best = { order: [], tip: base }
  const explored = new Set()

  /** Extend a clean prefix with each remaining PR against the integrated tip. */
  function search(tip, tree, order, remaining) {
    // Synthetic squash commits share only master ancestry with the PR heads.
    // An identical tree and remaining PRs therefore give the same future merges.
    const state = `${tree}:${remaining.map(pr => pr.number).join(',')}`
    if (explored.has(state)) return
    explored.add(state)

    if (order.length > best.order.length) best = { order, tip }
    for (const candidate of remaining) {
      if (best.order.length === candidates.length) return
      const next = squashMerge(cwd, tip, candidate.head)
      if (next.commit) {
        search(next.commit, next.tree, [...order, candidate], remaining.filter(pr => pr !== candidate))
      }
    }
  }

  search(base, base, [], candidates)
  const waiting = candidates
    .filter(pr => !best.order.includes(pr))
    .map(pr => ({ ...pr, paths: squashMerge(cwd, best.tip, pr.head).conflicts }))
  return { order: best.order, waiting }
}

/** Read changed paths from the merge base, including both endpoints of renames. */
function changedPaths(cwd, base, head) {
  return gitAt(cwd, [
    'diff', '--name-only', '--no-renames', '-z', `${base}...${head}`, '--',
  ]).stdout.split('\0').filter(Boolean)
}

/** Prefer ready PRs, then earlier PR numbers, when clean orders are equally long. */
function byPreference(a, b) {
  return Number(a.draft) - Number(b.draft) || a.number - b.number
}

/** Prioritize shared application code and screenshots over shared administrative files. */
function importantPath(path) {
  return (path.startsWith('app/src/main/') && path.endsWith('.kt')) ||
    (path.startsWith('app/src/test/snapshots/') && path.endsWith('.png'))
}

/** Select the requested PR and its most relevant direct overlaps. */
function selectCandidates(focus, open, pathsByHead) {
  const focusPaths = new Set(pathsByHead.get(focus.head))
  const overlaps = []
  for (const pr of open) {
    if (pr.number === focus.number) continue
    const shared = pathsByHead.get(pr.head).filter(path => focusPaths.has(path))
    if (shared.length) {
      shared.sort((a, b) => Number(importantPath(b)) - Number(importantPath(a)))
      overlaps.push({ ...pr, shared, important: importantPath(shared[0]) })
    }
  }
  overlaps.sort((a, b) => Number(b.important) - Number(a.important) || byPreference(a, b))
  const candidates = [focus, ...overlaps.slice(0, MAX_PRS_PER_PLAN - 1)].sort(byPreference)
  return { candidates, omitted: overlaps.slice(MAX_PRS_PER_PLAN - 1) }
}

/** Format untrusted filenames as text, including Markdown and control characters. */
function formatPath(path) {
  const printable = path.replace(/[\p{Cc}\p{Cf}]/gu, char =>
    `\\u{${char.codePointAt(0).toString(16)}}`)
  const shortened = printable.length > 240 ? `${printable.slice(0, 240)}…` : printable
  return `<code>${shortened.replace(/[&<>"'@`]/g, char => `&#${char.charCodeAt(0)};`)}</code>`
}

/** Render the recommended sequence with the exact Git revisions and any excluded PRs. */
function renderReport(base, candidates, { order, waiting }, omitted, unscanned) {
  const refs = candidates.map(pr => `#${pr.number} (\`${pr.head.slice(0, 12)}\`)`).join(', ')
  const lines = [
    '### PR merge order',
    '',
    `Simulated squash merges on \`master\` (\`${base.slice(0, 12)}\`) for ${refs}.`,
    '',
    order.length
      ? `**Land first** · ${order.map(pr => `#${pr.number}`).join(' → ')}`
      : '**Resolve conflicts with `master` before queueing.**',
  ]
  if (waiting.length) {
    lines.push('', order.length ? '**Wait until the sequence above lands, then update from `master`.**'
      : '**Conflicts**', '')
    for (const pr of waiting) {
      const paths = pr.paths.slice(0, MAX_PATHS_PER_PR).map(formatPath).join(', ') ||
        'Git reported a conflict without a specific path.'
      const extra = pr.paths.length - MAX_PATHS_PER_PR
      lines.push(`- #${pr.number} · ${paths}${extra > 0 ? ` and ${extra} more` : ''}`)
    }
  }
  const shared = candidates.filter(pr => pr.shared)
  if (shared.length) {
    lines.push('', '<details>', '<summary>Shared files</summary>', '')
    for (const pr of shared) {
      const paths = pr.shared.slice(0, MAX_PATHS_PER_PR).map(formatPath).join(', ')
      const extra = pr.shared.length - MAX_PATHS_PER_PR
      lines.push(`- #${pr.number} · ${paths}${extra > 0 ? ` and ${extra} more` : ''}`)
    }
    lines.push('', '</details>')
  }
  if (omitted.length || unscanned) {
    lines.push('', '**Outside this plan** · ' + [
      omitted.length && `${omitted.length} more overlapping PR(s)`,
      unscanned && `${unscanned} open PR(s) beyond the scan limit`,
    ].filter(Boolean).join(', ') + '.')
  }
  lines.push('', 'Git conflicts only. Queue CI validates the combined changes. Rerun after heads or `master` move.')
  return lines.join('\n')
}

/** Read open PRs targeting master and order them deterministically. */
async function openPulls(github, repo) {
  const prs = await github.paginate(github.rest.pulls.list, {
    ...repo, base: 'master', state: 'open', per_page: 100,
  })
  return prs.map(pr => ({ number: pr.number, draft: Boolean(pr.draft), head: pr.head.sha }))
    .sort(byPreference)
}

/** Fetch immutable PR heads and optionally master into the bare snapshot. */
function fetchRevisions(cwd, pulls, includeMaster = false) {
  const heads = [...new Set(pulls.map(pr => pr.head))]
  const refs = heads.map(head => {
    if (!/^[0-9a-f]{40}$/.test(head)) throw new Error('Expected a full PR head SHA')
    return `+${head}:refs/merge-order/${head}`
  })
  if (includeMaster) refs.unshift('+refs/heads/master:refs/heads/master')
  if (!refs.length) return
  gitAt(cwd, [
    'fetch', '--no-tags', '--no-write-fetch-head', '--filter=blob:none', 'origin',
    ...refs,
  ])
}

/** Initialize a bare partial clone without checking out pull request code. */
function fetchSnapshot(cwd, repo, pulls) {
  gitAt(cwd, ['init', '--bare', '--template='])
  gitAt(cwd, ['remote', 'add', 'origin', `https://github.com/${repo.owner}/${repo.repo}.git`])
  gitAt(cwd, ['config', 'remote.origin.promisor', 'true'])
  gitAt(cwd, ['config', 'remote.origin.partialclonefilter', 'blob:none'])
  fetchRevisions(cwd, pulls, true)
  return gitAt(cwd, ['rev-parse', 'refs/heads/master']).stdout.trim()
}

/** Keep the focus PR in the bounded inventory regardless of its priority. */
function inventoryFor(open, focus) {
  return [focus, ...open.filter(pr => pr.number !== focus.number).slice(0, MAX_INVENTORY - 1)]
}

/** Identify the ordered PR heads and review states that determine one simulation. */
function candidateKey(candidates) {
  return candidates.map(pr => `${pr.number}:${pr.head}:${pr.draft}`).join(',')
}

/** Inspect one open PR using trusted workflow inputs and write its Actions job summary. */
async function run({ github, context, core }) {
  const repo = context.repo
  const eventPR = context.payload.pull_request
  const number = eventPR?.number ?? Number(context.payload.inputs?.pr_number)
  if (!Number.isSafeInteger(number) || number < 1) throw new Error('Expected a pull request number')
  const open = await openPulls(github, repo)
  const focus = open.find(pr => pr.number === number)
  if (!focus || (eventPR && eventPR.head.sha !== focus.head)) {
    await core.summary.addRaw('This event is stale or its pull request does not target master.').write()
    return
  }

  const inventory = inventoryFor(open, focus)
  const cwd = mkdtempSync(join(tmpdir(), 'android-pr-merge-order-'))
  try {
    const base = fetchSnapshot(cwd, repo, inventory)
    const pathsByHead = new Map(inventory.map(pr => [pr.head, changedPaths(cwd, base, pr.head)]))
    let { candidates } = selectCandidates(focus, inventory, pathsByHead)
    for (let attempt = 0; attempt < 2; attempt++) {
      const plan = longestCleanOrder(cwd, base, candidates)
      const latest = await openPulls(github, repo)
      const currentFocus = latest.find(pr => pr.number === number)
      if (currentFocus?.head !== focus.head) {
        await core.summary.addRaw('The focus pull request changed or no longer targets master.').write()
        return
      }
      const currentInventory = inventoryFor(latest, currentFocus)
      const missing = currentInventory.filter(pr => !pathsByHead.has(pr.head))
      fetchRevisions(cwd, missing)
      for (const pr of missing) pathsByHead.set(pr.head, changedPaths(cwd, base, pr.head))

      const current = selectCandidates(currentFocus, currentInventory, pathsByHead)
      const { data: master } = await github.rest.git.getRef({ ...repo, ref: 'heads/master' })
      if (master.object.sha !== base) {
        await core.summary.addRaw('Master changed during the run. Rerun for a current plan.').write()
        return
      }
      if (candidateKey(candidates) !== candidateKey(current.candidates)) {
        candidates = current.candidates
        continue
      }

      const unscanned = latest.length - currentInventory.length
      const report = candidates.length > 1 || unscanned
        ? renderReport(base, candidates, plan, current.omitted, unscanned)
        : null
      await core.summary.addRaw(report ?? 'No other open pull request targeting master touches the same paths.').write()
      return
    }
    await core.summary.addRaw('The overlapping candidates changed twice during the run. Rerun for a current plan.').write()
  } finally {
    rmSync(cwd, { recursive: true, force: true })
  }
}

module.exports = { changedPaths, longestCleanOrder, renderReport, selectCandidates, squashMerge, run }
