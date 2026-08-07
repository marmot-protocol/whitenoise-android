const assert = require('node:assert/strict')
const test = require('node:test')
const { isUiFile, renderSection, replaceSection } = require('./update-pr-screenshots')

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

test('replaces the generated section without changing surrounding text', () => {
  const body = 'Intro\n\n<!-- pr-screenshots:start -->\nold\n<!-- pr-screenshots:end -->\n\nFooter'
  assert.equal(
    replaceSection(body, '<!-- pr-screenshots:start -->\nnew\n<!-- pr-screenshots:end -->'),
    'Intro\n\n<!-- pr-screenshots:start -->\nnew\n<!-- pr-screenshots:end -->\n\nFooter',
  )
})
