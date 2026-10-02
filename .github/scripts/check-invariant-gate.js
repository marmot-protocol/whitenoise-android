// Bug-fix invariant gate. A pull request that closes a bug must change a gate
// registered in docs/invariant-gates.md, name an applicable registered gate, or
// declare an allowed exemption with a reason. Everything else passes untouched.
// The check only reads GitHub API metadata, so it needs no secrets and works
// for fork pull requests with the default read-only token.
const fs = require('node:fs')
const path = require('node:path')

const REGISTRY_PATH = 'docs/invariant-gates.md'
const REGISTRY_START = '<!-- invariant-gates:start -->'
const REGISTRY_END = '<!-- invariant-gates:end -->'
const REGISTRY_ROW = /^\| \[`([A-Za-z0-9_]+)`\]\(\.\.\/([^)\s]+)\) \|/
const GATE_LINE = /^invariant gate:[ \t]*(.*)$/gim
const EXEMPTION_LINE = /^invariant gate exemption:[ \t]*(.*)$/gim
const EXEMPTION_REASON = /^`?([a-z][a-z-]*)`?[ \t]*(?:[—–:-][ \t]*)?(.*)$/i
const EXEMPTION_REASONS = ['one-off', 'upstream', 'non-production']
const MIN_EXPLANATION_LENGTH = 10
const DOCS = 'docs/invariant-gates.md#bug-fix-requirement'

/** Maps each registered gate name to its repository-relative test path, read from the marked registry table. */
function parseRegistry(markdown) {
  const text = markdown || ''
  const start = text.indexOf(REGISTRY_START)
  const end = text.indexOf(REGISTRY_END)
  const gates = new Map()
  if (start === -1 || end <= start) return gates
  for (const line of text.slice(start, end).split('\n')) {
    const match = REGISTRY_ROW.exec(line.trim())
    if (match) gates.set(match[1], match[2])
  }
  return gates
}

/** Returns the description without fenced code or HTML comments, so hidden text cannot satisfy the gate. */
function visibleProse(body) {
  return (body || '')
    .replace(/(```|~~~)[\s\S]*?(?:\1|$)/g, '')
    .replace(/<!--[\s\S]*?(?:-->|$)/g, '')
}

/** Collects the gate names and exemption declarations written as single visible lines in the description. */
function parseDeclaration(body) {
  const prose = visibleProse(body)
  const gates = [...prose.matchAll(GATE_LINE)]
    .flatMap(match => match[1].split(','))
    .map(name => name.trim().replace(/^[`*]+|[`*.]+$/g, ''))
    .filter(name => name.length > 0)
  const exemptions = [...prose.matchAll(EXEMPTION_LINE)].map(match => {
    const reason = EXEMPTION_REASON.exec(match[1].trim())
    return reason
      ? { reason: reason[1].toLowerCase(), explanation: reason[2].trim() }
      : { reason: '', explanation: '' }
  })
  return { gates, exemptions }
}

/** True when a closing issue is a bug by native issue type or by the `bug` label. */
function isBugIssue(issue) {
  const labels = (issue.labels?.nodes || issue.labels || []).map(label => (label.name || label).toLowerCase())
  return issue.issueType?.name === 'Bug' || labels.includes('bug')
}

/** Lists problems with the declared gates and exemptions; an empty list means every declaration is valid. */
function declarationErrors(declaration, registry) {
  const errors = declaration.gates
    .filter(name => !registry.has(name))
    .map(name => `"${name}" is not a gate registered in ${REGISTRY_PATH}.`)
  for (const { reason, explanation } of declaration.exemptions) {
    if (!EXEMPTION_REASONS.includes(reason)) {
      errors.push(`Exemption reason "${reason}" is not one of: ${EXEMPTION_REASONS.join(', ')}.`)
    } else if (explanation.length < MIN_EXPLANATION_LENGTH) {
      errors.push(`Exemption "${reason}" needs an explanation of why no reusable gate applies.`)
    }
  }
  return errors
}

/**
 * Decides the gate for one pull request. Returns `not-applicable` when no closing issue is a bug, `pass` when a
 * registered gate changed or a valid declaration exists, and `fail` for an omission or an invalid declaration.
 */
function evaluate({ closingIssues, files, body, registry }) {
  const bugs = (closingIssues || []).filter(isBugIssue).map(issue => `#${issue.number}`)
  if (bugs.length === 0) {
    return { status: 'not-applicable', message: 'No closing issue is a bug, so no invariant-gate declaration is required.' }
  }
  const declaration = parseDeclaration(body)
  const errors = declarationErrors(declaration, registry)
  if (errors.length > 0) {
    return { status: 'fail', message: `Invalid invariant-gate declaration for ${bugs.join(', ')}: ${errors.join(' ')} See ${DOCS}.` }
  }
  const registeredPaths = new Map([...registry].map(([name, file]) => [file, name]))
  const changedGates = (files || [])
    .filter(file => file.status !== 'removed' && registeredPaths.has(file.filename))
    .map(file => registeredPaths.get(file.filename))
  if (changedGates.length > 0) {
    return { status: 'pass', message: `Bug fix changes registered invariant gate(s): ${changedGates.join(', ')}.` }
  }
  if (declaration.gates.length > 0) {
    return { status: 'pass', message: `Bug fix names registered invariant gate(s): ${declaration.gates.join(', ')}.` }
  }
  if (declaration.exemptions.length > 0) {
    const reasons = declaration.exemptions.map(exemption => exemption.reason).join(', ')
    return { status: 'pass', message: `Bug fix declares an invariant-gate exemption: ${reasons}.` }
  }
  return {
    status: 'fail',
    message:
      `This pull request closes bug ${bugs.join(', ')} but neither changes a registered invariant gate, ` +
      'names one ("Invariant gate: FooCoverageTest"), nor declares an exemption ' +
      `("Invariant gate exemption: one-off|upstream|non-production — reason"). See ${DOCS}.`,
  }
}

const PULL_REQUEST_QUERY = `query($owner: String!, $repo: String!, $number: Int!) {
  repository(owner: $owner, name: $repo) {
    pullRequest(number: $number) {
      body
      closingIssuesReferences(first: 50) {
        nodes { number issueType { name } labels(first: 20) { nodes { name } } }
      }
    }
  }
}`

/** Loads the pull request's live description, closing issues and files, then fails the job on a gate violation. */
async function run({ github, context, core, registryMarkdown }) {
  const owner = context.repo.owner
  const repo = context.repo.repo
  const number = context.payload.pull_request.number
  const result = await github.graphql(PULL_REQUEST_QUERY, { owner, repo, number })
  const pullRequest = result.repository.pullRequest
  const files = await github.paginate(github.rest.pulls.listFiles, { owner, repo, pull_number: number, per_page: 100 })
  const registry = parseRegistry(registryMarkdown ?? readRegistry())
  const outcome = evaluate({
    closingIssues: pullRequest.closingIssuesReferences.nodes,
    files,
    body: pullRequest.body,
    registry,
  })
  if (outcome.status === 'fail') {
    core.setFailed(outcome.message)
  } else {
    core.info(outcome.message)
  }
  return outcome
}

/** Reads the registry from the checked-out pull-request head, or returns an empty table when it is absent. */
function readRegistry() {
  const file = path.join(process.cwd(), REGISTRY_PATH)
  return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : ''
}

module.exports = {
  EXEMPTION_REASONS,
  REGISTRY_PATH,
  declarationErrors,
  evaluate,
  isBugIssue,
  parseDeclaration,
  parseRegistry,
  run,
  visibleProse,
}
