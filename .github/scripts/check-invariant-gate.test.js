const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const {
  REGISTRY_PATH,
  evaluate,
  isBugIssue,
  parseDeclaration,
  parseRegistry,
  run,
} = require('./check-invariant-gate')

const GATE_PATH = 'app/src/test/java/dev/ipf/whitenoise/android/state/StalenessGuardCoverageTest.kt'
const REGISTRY = [
  '<!-- invariant-gates:start -->',
  '| Gate | Invariant | Owning primitive or boundary |',
  '| --- | --- | --- |',
  `| [\`StalenessGuardCoverageTest\`](../${GATE_PATH}) | Latest wins. | Guard |`,
  '<!-- invariant-gates:end -->',
].join('\n')
const registry = parseRegistry(REGISTRY)
const bug = { number: 7, issueType: { name: 'Bug' }, labels: { nodes: [{ name: 'bug' }] } }
const feature = { number: 8, issueType: { name: 'Feature' }, labels: { nodes: [{ name: 'enhancement' }] } }
const productionFile = { filename: 'app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt', status: 'modified' }

/** Evaluates one fixture pull request against the fixture registry. */
function gate({ closingIssues = [bug], files = [productionFile], body = 'Summary' } = {}) {
  return evaluate({ closingIssues, files, body, registry })
}

test('parses gate names and repository-relative paths from the registry table', () => {
  assert.deepEqual([...registry], [['StalenessGuardCoverageTest', GATE_PATH]])
  assert.equal(parseRegistry('| [`Outside`](../x/OutsideCoverageTest.kt) | a | b |').size, 0)
})

test('reads every checked-in registry row the JVM consistency test validates', () => {
  const markdown = fs.readFileSync(path.join(__dirname, '..', '..', REGISTRY_PATH), 'utf8')
  const gates = parseRegistry(markdown)
  assert.ok(gates.size > 0)
  for (const [name, file] of gates) assert.ok(file.endsWith(`/${name}.kt`), `${name} -> ${file}`)
})

test('treats an issue as a bug by native type or by the bug label', () => {
  assert.equal(isBugIssue(bug), true)
  assert.equal(isBugIssue({ issueType: { name: 'Task' }, labels: { nodes: [{ name: 'bug' }] } }), true)
  assert.equal(isBugIssue({ issueType: { name: 'Bug' }, labels: { nodes: [] } }), true)
  assert.equal(isBugIssue(feature), false)
  assert.equal(isBugIssue({ issueType: null, labels: { nodes: [] } }), false)
})

test('fails a bug fix that neither changes, names nor exempts a gate', () => {
  const outcome = gate()
  assert.equal(outcome.status, 'fail')
  assert.match(outcome.message, /closes bug #7 but neither changes a registered invariant gate/)
})

test('passes a bug fix that changes a registered gate', () => {
  const outcome = gate({ files: [productionFile, { filename: GATE_PATH, status: 'modified' }] })
  assert.deepEqual(outcome, {
    status: 'pass',
    message: 'Bug fix changes registered invariant gate(s): StalenessGuardCoverageTest.',
  })
})

test('does not count a removed gate file as a gate change', () => {
  assert.equal(gate({ files: [{ filename: GATE_PATH, status: 'removed' }] }).status, 'fail')
})

test('passes a bug fix that names an already-applicable registered gate', () => {
  const outcome = gate({ body: 'Summary\n\nInvariant gate: `StalenessGuardCoverageTest`.' })
  assert.equal(outcome.status, 'pass')
  assert.match(outcome.message, /names registered invariant gate\(s\): StalenessGuardCoverageTest/)
})

test('passes a bug fix with each allowed exemption reason and an explanation', () => {
  for (const reason of ['one-off', 'upstream', 'non-production']) {
    const outcome = gate({ body: `Invariant gate exemption: ${reason} — a reason reviewers can challenge` })
    assert.equal(outcome.status, 'pass', reason)
    assert.match(outcome.message, new RegExp(`exemption: ${reason}`))
  }
})

test('fails an unregistered gate name, an unknown reason, none, or a bare reason', () => {
  const bodies = [
    'Invariant gate: MissingCoverageTest',
    'Invariant gate: none',
    'Invariant gate exemption: none',
    'Invariant gate exemption: trivial — it was small anyway',
    'Invariant gate exemption: one-off',
  ]
  for (const body of bodies) assert.equal(gate({ body }).status, 'fail', body)
})

test('fails an invalid declaration even when a registered gate also changed', () => {
  const outcome = gate({ files: [{ filename: GATE_PATH, status: 'added' }], body: 'Invariant gate: Typo' })
  assert.equal(outcome.status, 'fail')
  assert.match(outcome.message, /"Typo" is not a gate registered/)
})

test('ignores declarations hidden in comments, code fences or split lines', () => {
  const hidden = [
    '<!-- Invariant gate: StalenessGuardCoverageTest -->',
    '```\nInvariant gate exemption: upstream — owned by the engine\n```',
    'Invariant gate:\nStalenessGuardCoverageTest',
    'Note that invariant gate: StalenessGuardCoverageTest applies',
  ]
  for (const body of hidden) assert.equal(gate({ body }).status, 'fail', body)
  assert.deepEqual(parseDeclaration('Invariant gate: A, B').gates, ['A', 'B'])
})

test('keeps a declaration hidden behind a shorter or annotated inner fence', () => {
  const hidden = [
    '````md\n```kotlin\nInvariant gate: StalenessGuardCoverageTest\n```\n````',
    '````\n``` not a closer\nInvariant gate: StalenessGuardCoverageTest\n````',
    '~~~~\n~~~ trailing text\nInvariant gate: StalenessGuardCoverageTest',
    '```\nInvariant gate: StalenessGuardCoverageTest\n~~~',
  ]
  for (const body of hidden) assert.equal(gate({ body }).status, 'fail', body)
  const closed = '````\n```\ninner\n```\n````\n\nInvariant gate: StalenessGuardCoverageTest'
  assert.equal(gate({ body: closed }).status, 'pass')
})

test('run follows closing-issue pages and full label lists before deciding a PR closes no bug', async () => {
  const pages = [
    { nodes: [feature], pageInfo: { hasNextPage: true, endCursor: 'page-2' } },
    {
      nodes: [{
        number: 9,
        repository: { owner: { login: 'marmot' }, name: 'base' },
        issueType: { name: 'Task' },
        labels: { nodes: [{ name: 'area' }], pageInfo: { hasNextPage: true } },
      }],
      pageInfo: { hasNextPage: false, endCursor: null },
    },
  ]
  const cursors = []
  const labelRequests = []
  const failures = []
  const github = {
    graphql: async (_query, variables) => {
      cursors.push(variables.after)
      const closingIssuesReferences = pages[cursors.length - 1]
      return { repository: { pullRequest: { body: 'Summary', closingIssuesReferences } } }
    },
    paginate: async (method, request) => {
      if (method !== github.rest.issues.listLabelsOnIssue) return [productionFile]
      labelRequests.push(request)
      return [{ name: 'area' }, { name: 'bug' }]
    },
    rest: { pulls: { listFiles: () => {} }, issues: { listLabelsOnIssue: () => {} } },
  }
  const context = { payload: { pull_request: { number: 42 } }, repo: { owner: 'marmot', repo: 'base' } }
  const core = { info: () => {}, setFailed: message => failures.push(message) }

  const outcome = await run({ github, context, core, registryMarkdown: REGISTRY })

  assert.deepEqual(cursors, [null, 'page-2'])
  assert.deepEqual(labelRequests, [{ owner: 'marmot', repo: 'base', issue_number: 9, per_page: 100 }])
  assert.equal(outcome.status, 'fail')
  assert.match(failures[0], /closes bug #9/)
})

test('does not apply to feature-only pull requests or pull requests without closing issues', () => {
  assert.equal(gate({ closingIssues: [feature] }).status, 'not-applicable')
  assert.equal(gate({ closingIssues: [] }).status, 'not-applicable')
  assert.equal(gate({ closingIssues: [feature, bug] }).status, 'fail')
})

/** Runs the workflow entry point against a stubbed GitHub client and records the job outcome. */
async function runWith({ closingIssues, files, body }) {
  const failures = []
  const infos = []
  const github = {
    graphql: async (_query, variables) => {
      assert.deepEqual(variables, { owner: 'marmot', repo: 'base', number: 42, after: null })
      return { repository: { pullRequest: { body, closingIssuesReferences: { nodes: closingIssues } } } }
    },
    paginate: async () => files,
    rest: { pulls: { listFiles: () => {} } },
  }
  const context = { payload: { pull_request: { number: 42 } }, repo: { owner: 'marmot', repo: 'base' } }
  const core = { info: message => infos.push(message), setFailed: message => failures.push(message) }
  await run({ github, context, core, registryMarkdown: REGISTRY })
  return { failures, infos }
}

test('run fails the job for an omitted declaration and passes once the live description declares one', async () => {
  const omitted = await runWith({ closingIssues: [bug], files: [productionFile], body: 'Summary' })
  assert.equal(omitted.failures.length, 1)

  const declared = await runWith({
    closingIssues: [bug],
    files: [productionFile],
    body: 'Summary\n\nInvariant gate exemption: upstream — enforced by the engine',
  })
  assert.deepEqual(declared.failures, [])
  assert.match(declared.infos[0], /exemption: upstream/)
})

test('run passes a feature-only pull request without a declaration', async () => {
  const outcome = await runWith({ closingIssues: [feature], files: [productionFile], body: '' })
  assert.deepEqual(outcome.failures, [])
  assert.match(outcome.infos[0], /No closing issue is a bug/)
})
