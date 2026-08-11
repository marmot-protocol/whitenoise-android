const assert = require('node:assert/strict')
const test = require('node:test')
const {
  isUiFile,
  isMissingVisualCoverage,
  declaresNoVisualChanges,
  renderSection,
  replaceSection,
  run,
} = require('./update-pr-screenshots')

const pr = {
  base: { repo: { full_name: 'marmot/base' }, sha: 'base-sha' },
  head: { repo: { full_name: 'contributor/fork' }, sha: 'head-sha' },
}

test('recognizes UI-affecting files', () => {
  assert.equal(isUiFile('app/src/main/java/dev/ipf/whitenoise/android/ui/Screen.kt'), true)
  assert.equal(isUiFile('app/src/main/res/values/strings.xml'), true)
  assert.equal(isUiFile('app/src/main/java/dev/ipf/whitenoise/android/core/Model.kt'), false)
})

test('renders fork-safe before and after links for modified snapshots', () => {
  const section = renderSection(pr, [{
    filename: 'app/src/test/snapshots/chat light.png',
    status: 'modified',
  }])
  assert.match(section, /marmot\/base\/blob\/base-sha\/app\/src\/test\/snapshots\/chat%20light\.png\?raw=1/)
  assert.match(section, /contributor\/fork\/blob\/head-sha\/app\/src\/test\/snapshots\/chat%20light\.png\?raw=1/)
})

test('warns when UI code changes without a baseline update', () => {
  const section = renderSection(pr, [{
    filename: 'app/src/main/java/dev/ipf/whitenoise/android/ui/Screen.kt',
    status: 'modified',
  }])
  assert.match(section, /UI-affecting files changed/)
})

test('requires a committed baseline for UI-affecting changes', () => {
  assert.equal(isMissingVisualCoverage([{
    filename: 'app/src/main/java/dev/ipf/whitenoise/android/ui/Screen.kt',
    status: 'modified',
  }]), true)
  assert.equal(isMissingVisualCoverage([{
    filename: 'app/src/main/java/dev/ipf/whitenoise/android/ui/Screen.kt',
    status: 'modified',
  }, {
    filename: 'app/src/test/snapshots/screen.png',
    status: 'modified',
  }]), false)
  assert.equal(isMissingVisualCoverage([{
    filename: 'app/src/test/snapshots/obsolete.png',
    status: 'removed',
  }]), true)
  assert.equal(isMissingVisualCoverage([{
    filename: 'app/src/main/java/dev/ipf/whitenoise/android/core/Model.kt',
    status: 'modified',
  }]), false)
})

test('renders the exact head provenance with snapshot evidence', () => {
  const section = renderSection(pr, [{
    filename: 'app/src/test/snapshots/screen.png',
    status: 'added',
  }])
  assert.match(section, /baselines at head `head-sha`/)
  assert.match(section, /\| Current \|/)
})

test('updates the description before failing a UI change with no baseline', async () => {
  const updates = []
  const failures = []
  const github = {
    paginate: async () => [{
      filename: 'app/src/main/java/dev/ipf/whitenoise/android/ui/Screen.kt',
      status: 'modified',
    }],
    rest: {
      pulls: {
        listFiles: () => {},
        get: async () => ({ data: { ...pr, number: 42, body: 'Summary' } }),
        update: async request => updates.push(request),
      },
      issues: {},
    },
  }
  const context = {
    payload: { pull_request: { ...pr, number: 42, body: 'Summary' } },
    repo: { owner: 'marmot', repo: 'base' },
  }
  const core = {
    info: () => {},
    warning: () => {},
    setFailed: message => failures.push(message),
  }

  await run({ github, context, core })

  assert.equal(updates.length, 1)
  assert.match(updates[0].body, /UI-affecting files changed/)
  assert.equal(failures.length, 1)
  assert.match(failures[0], /committed Roborazzi screenshot baseline/)
})

test('recognizes the declared behavioral opt-out', () => {
  assert.equal(declaresNoVisualChanges('Summary\n\nVisual changes: none (behavioral timing fix)'), true)
  assert.equal(declaresNoVisualChanges('visual changes: NONE'), true)
  assert.equal(declaresNoVisualChanges('Summary with visual changes: none inline'), false)
  assert.equal(declaresNoVisualChanges('Visual changes: two screenshots'), false)
  assert.equal(declaresNoVisualChanges(''), false)
  assert.equal(declaresNoVisualChanges(null), false)
  assert.equal(declaresNoVisualChanges('<!-- Visual changes: none -->'), false)
  assert.equal(declaresNoVisualChanges('```\nVisual changes: none\n```'), false)
  assert.equal(declaresNoVisualChanges('Visual changes:\nnone'), false)
  assert.equal(declaresNoVisualChanges('<!-- hidden -->\nVisual changes: none'), true)
  assert.equal(declaresNoVisualChanges('```js\ncode\n```\n\nVisual changes: none (behavioral)'), true)
})

test('accepts a UI change with no baseline when the description declares no visual changes', async () => {
  const updates = []
  const failures = []
  const body = 'Summary\n\nVisual changes: none'
  const github = {
    paginate: async () => [{
      filename: 'app/src/main/java/dev/ipf/whitenoise/android/ui/Screen.kt',
      status: 'modified',
    }],
    rest: {
      pulls: {
        listFiles: () => {},
        get: async () => ({ data: { ...pr, number: 42, body } }),
        update: async request => updates.push(request),
      },
      issues: {},
    },
  }
  const context = {
    payload: { pull_request: { ...pr, number: 42, body } },
    repo: { owner: 'marmot', repo: 'base' },
  }
  const core = {
    info: () => {},
    warning: () => {},
    setFailed: message => failures.push(message),
  }

  await run({ github, context, core })

  assert.equal(updates.length, 1)
  assert.match(updates[0].body, /declares "Visual changes: none"/)
  assert.equal(failures.length, 0)
})

test('comment fallback remains blocking when the description cannot be edited', async () => {
  const comments = []
  const failures = []
  const github = {
    paginate: async method => method.name === 'listFiles'
      ? [{
          filename: 'app/src/main/java/dev/ipf/whitenoise/android/ui/Screen.kt',
          status: 'modified',
        }]
      : [],
    rest: {
      pulls: {
        listFiles: function listFiles() {},
        get: async () => ({ data: { ...pr, number: 42, body: 'Summary' } }),
        update: async () => { throw new Error('description edits disabled') },
      },
      issues: {
        listComments: function listComments() {},
        createComment: async request => comments.push(request),
        updateComment: async () => {},
      },
    },
  }
  const context = {
    payload: { pull_request: { ...pr, number: 42, body: 'Summary' } },
    repo: { owner: 'marmot', repo: 'base' },
  }
  const core = {
    info: () => {},
    warning: () => {},
    setFailed: message => failures.push(message),
  }

  await run({ github, context, core })

  assert.equal(comments.length, 1)
  assert.match(comments[0].body, /UI-affecting files changed/)
  assert.equal(failures.length, 1)
})

test('replaces the generated section without changing surrounding text', () => {
  const body = 'Intro\n\n<!-- pr-screenshots:start -->\nold\n<!-- pr-screenshots:end -->\n\nFooter'
  assert.equal(
    replaceSection(body, '<!-- pr-screenshots:start -->\nnew\n<!-- pr-screenshots:end -->'),
    'Intro\n\n<!-- pr-screenshots:start -->\nnew\n<!-- pr-screenshots:end -->\n\nFooter',
  )
})
