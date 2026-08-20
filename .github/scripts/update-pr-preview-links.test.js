const assert = require('node:assert/strict')
const test = require('node:test')
const {
  renderSection,
  replaceSection,
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
