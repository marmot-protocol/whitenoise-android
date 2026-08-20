const assert = require('node:assert/strict')
const test = require('node:test')
const {
  renderSection,
  replaceSection,
  run,
  START,
  END,
} = require('./update-pr-preview-links')

const links = {
  prNumber: 2143,
  headSha: 'ba3a01bd3fd43c9c438ec53d4eb203da446fe419',
  stableUrl: 'https://nostr.download/abc123.apk',
  isolatedUrl: 'https://nostr.download/def456.apk',
}

test('renders preview links with start/end markers', () => {
  const section = renderSection(links)
  assert.match(section, /^<!-- pr-apk-preview:start -->/)
  assert.match(section, /<!-- pr-apk-preview:end -->$/)
  assert.match(section, /Install\/update White Noise PR/)
  assert.match(section, /Built from PR #2143 at `ba3a01bd3fd4`/)
  assert.match(section, /Isolated PR #2143/)
})

test('appends preview section when description has no prior block', () => {
  const updated = replaceSection('Fix overlap bug.', renderSection(links))
  assert.match(updated, /^Fix overlap bug\.\n\n<!-- pr-apk-preview:start -->/)
})

test('replaces an existing preview section in place', () => {
  const prior = [
    'Fix overlap bug.',
    '',
    START,
    'old preview block',
    END,
  ].join('\n')
  const updated = replaceSection(prior, renderSection(links))
  assert.doesNotMatch(updated, /old preview block/)
  assert.match(updated, /abc123\.apk/)
})

test('replaces an unterminated preview section without duplicating its start marker', () => {
  const prior = [
    'Fix overlap bug.',
    '',
    START,
    'orphaned preview block',
  ].join('\n')
  const updated = replaceSection(prior, renderSection(links))
  assert.equal(updated.match(new RegExp(START, 'g')).length, 1)
  assert.doesNotMatch(updated, /orphaned preview block/)
  assert.match(updated, /^Fix overlap bug\.\n\n<!-- pr-apk-preview:start -->/)
})

test('does not update links or delete the fallback comment for a superseded head', async () => {
  const calls = []
  const github = {
    rest: {
      pulls: {
        get: async () => ({
          data: {
            state: 'open',
            head: { sha: 'newer-head' },
            body: 'Current description',
          },
        }),
        update: async args => calls.push(['update', args]),
      },
      issues: {
        listComments: async args => {
          calls.push(['listComments', args])
          return { data: [] }
        },
        deleteComment: async args => calls.push(['deleteComment', args]),
      },
    },
    paginate: async (...args) => {
      calls.push(['paginate', args])
      return []
    },
  }
  const previousEnv = {
    PR_NUMBER: process.env.PR_NUMBER,
    HEAD_SHA: process.env.HEAD_SHA,
    STABLE_URL: process.env.STABLE_URL,
    ISOLATED_URL: process.env.ISOLATED_URL,
  }
  Object.assign(process.env, {
    PR_NUMBER: String(links.prNumber),
    HEAD_SHA: links.headSha,
    STABLE_URL: links.stableUrl,
    ISOLATED_URL: links.isolatedUrl,
  })

  try {
    await run({
      github,
      context: { repo: { owner: 'marmot-protocol', repo: 'whitenoise-android' } },
      core: { info: () => {} },
    })
  } finally {
    for (const [key, value] of Object.entries(previousEnv)) {
      if (value === undefined) delete process.env[key]
      else process.env[key] = value
    }
  }

  assert.deepEqual(calls, [])
})

test('keeps the fallback comment when the PR head changes after updating links', async () => {
  const calls = []
  const pullRequests = [
    { state: 'open', head: { sha: links.headSha }, body: 'Current description' },
    { state: 'open', head: { sha: 'newer-head' }, body: 'Current description' },
  ]
  const github = {
    rest: {
      pulls: {
        get: async () => ({ data: pullRequests.shift() }),
        update: async args => calls.push(['update', args]),
      },
      issues: {
        listComments: async args => {
          calls.push(['listComments', args])
          return { data: [] }
        },
        deleteComment: async args => calls.push(['deleteComment', args]),
      },
    },
    paginate: async (...args) => {
      calls.push(['paginate', args])
      return []
    },
  }
  const previousEnv = {
    PR_NUMBER: process.env.PR_NUMBER,
    HEAD_SHA: process.env.HEAD_SHA,
    STABLE_URL: process.env.STABLE_URL,
    ISOLATED_URL: process.env.ISOLATED_URL,
  }
  Object.assign(process.env, {
    PR_NUMBER: String(links.prNumber),
    HEAD_SHA: links.headSha,
    STABLE_URL: links.stableUrl,
    ISOLATED_URL: links.isolatedUrl,
  })

  try {
    await run({
      github,
      context: { repo: { owner: 'marmot-protocol', repo: 'whitenoise-android' } },
      core: { info: () => {} },
    })
  } finally {
    for (const [key, value] of Object.entries(previousEnv)) {
      if (value === undefined) delete process.env[key]
      else process.env[key] = value
    }
  }

  assert.equal(calls.filter(([name]) => name === 'update').length, 1)
  assert.equal(calls.filter(([name]) => name === 'paginate').length, 0)
  assert.equal(calls.filter(([name]) => name === 'deleteComment').length, 0)
})
