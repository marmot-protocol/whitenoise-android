const assert = require('node:assert/strict')
const { execFileSync } = require('node:child_process')
const { mkdtempSync, rmSync, writeFileSync } = require('node:fs')
const { tmpdir } = require('node:os')
const { join } = require('node:path')
const { test } = require('node:test')
const {
  changedPaths, longestCleanOrder, renderReport, selectCandidates, squashMerge, updateComment,
} = require('./report-pr-merge-order')

/** Create independent pull request tips from one base in a real Git repository. */
function fixture(t, original) {
  const cwd = mkdtempSync(join(tmpdir(), 'test-pr-merge-order-'))
  t.after(() => rmSync(cwd, { recursive: true, force: true }))
  const git = (...args) => execFileSync('git', [
    '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
    '-c', 'commit.gpgsign=false', ...args,
  ], { cwd, encoding: 'utf8' }).trim()
  git('init', '--quiet', '--template=')
  const commit = (filename, lines) => {
    writeFileSync(join(cwd, filename), lines.join('\n') + '\n')
    git('add', '--', filename)
    git('commit', '--quiet', '-m', 'Fixture')
    return git('rev-parse', 'HEAD')
  }
  const base = commit('AppState.kt', original)
  const pr = (number, lines, filename = 'AppState.kt', draft = false) => {
    git('checkout', '--quiet', '--detach', base)
    return { number, head: commit(filename, lines), draft }
  }
  return { cwd, git, base, pr }
}

test('lands two compatible AppState PRs before the older PR that conflicts with both', t => {
  const original = Array.from({ length: 24 }, (_, index) => `line ${index}`)
  const { cwd, git, base, pr } = fixture(t, original)
  const older = original.with(2, 'older first change').with(20, 'older second change')
  const candidates = [pr(10, older), pr(11, original.with(2, 'change A')), pr(12, original.with(20, 'change B'))]
  const plan = longestCleanOrder(cwd, base, candidates)

  assert.deepEqual(plan.order.map(item => item.number), [11, 12])
  assert.deepEqual(plan.waiting.map(item => [item.number, item.paths]), [[10, ['AppState.kt']]])

  const first = squashMerge(cwd, base, candidates[1].head).commit
  const second = squashMerge(cwd, first, candidates[2].head).commit
  assert.equal(git('show', '-s', '--format=%P', second), first)
  assert.match(renderReport(base, candidates, plan, [], 0), /#11 → #12[\s\S]*#10 · <code>AppState\.kt<\/code>/)
})

test('finds a full order even when every pair is clean but the first three-way order conflicts', t => {
  const { cwd, base, pr } = fixture(t, 'a b c a b c d b c e'.split(' '))
  const candidates = [
    pr(10, 'a b c a b y z b c e'.split(' ')),
    pr(11, 'z x a b c d b c'.split(' ')),
    pr(12, 'a b c a b c d b c'.split(' ')),
  ]
  for (const [index, candidate] of candidates.entries()) {
    const tip = squashMerge(cwd, base, candidate.head).commit
    for (const other of candidates.slice(index + 1)) {
      assert.ok(squashMerge(cwd, tip, other.head).commit)
    }
  }
  const afterA = squashMerge(cwd, base, candidates[0].head).commit
  const afterAB = squashMerge(cwd, afterA, candidates[1].head).commit
  assert.deepEqual(squashMerge(cwd, afterAB, candidates[2].head).conflicts, ['AppState.kt'])

  const plan = longestCleanOrder(cwd, base, candidates)
  assert.deepEqual(plan.order.map(item => item.number), [10, 12, 11])
  assert.deepEqual(plan.waiting, [])
})

test('limits the plan to direct overlaps and gives ready PRs preference over drafts', t => {
  const { cwd, base, pr } = fixture(t, ['base'])
  const focus = pr(99, ['focus'])
  const open = [
    pr(2, ['B']),
    pr(3, ['C']),
    pr(4, ['D']),
    pr(5, ['E']),
    pr(6, ['F'], 'AppState.kt', true),
    pr(7, ['unrelated'], 'Other.kt'),
    focus,
  ]
  const { candidates, omitted } = selectCandidates(cwd, base, focus, open)
  assert.deepEqual(candidates.map(item => item.number), [2, 3, 4, 5, 99])
  assert.deepEqual(omitted.map(item => item.number), [6])
  const report = renderReport(base, candidates, longestCleanOrder(cwd, base, candidates), omitted, 3)
  assert.match(report, /1 more overlapping PR\(s\), 3 open PR\(s\) beyond the scan limit/)
})

test('uses both sides of a rename when comparing changed paths', t => {
  const { cwd, git, base } = fixture(t, ['base'])
  git('mv', 'AppState.kt', 'Renamed.kt')
  git('commit', '--quiet', '-m', 'Rename')
  const renamed = git('rev-parse', 'HEAD')
  assert.deepEqual(changedPaths(cwd, base, renamed), ['AppState.kt', 'Renamed.kt'])
})

test('updates only the existing workflow comment and clears it when there is no overlap', async () => {
  const calls = []
  const github = { rest: { issues: {
    createComment: args => calls.push(['create', args.issue_number]),
    updateComment: args => calls.push(['update', args.comment_id]),
    deleteComment: args => calls.push(['delete', args.comment_id]),
  } } }
  const marker = '<!-- whitenoise-android:pr-merge-order -->\n'
  const comments = [
    { user: { login: 'user' }, id: 1, body: marker + 'lookalike' },
    { user: { login: 'github-actions[bot]' }, id: 2, body: 'another workflow' },
    { user: { login: 'github-actions[bot]' }, id: 3, body: marker + 'old report' },
  ]
  const repo = { owner: 'marmot-protocol', repo: 'whitenoise-android' }
  await updateComment(github, repo, 42, comments.slice(0, 2), 'new report')
  await updateComment(github, repo, 42, comments, 'new report')
  await updateComment(github, repo, 42, comments, 'old report')
  await updateComment(github, repo, 42, comments, null)
  assert.deepEqual(calls, [['create', 42], ['update', 3], ['delete', 3]])
})
