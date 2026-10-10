"""Validate direct UI assertions and report source-bound screen execution separately from release proof."""

import hashlib
import json
from pathlib import Path
import yaml


def has_substitution(value):
    """Runtime-expanded selectors cannot establish a maintained literal screen assertion."""
    if isinstance(value, str):
        return '${' in value
    if isinstance(value, dict):
        return any(has_substitution(item) for item in value.values())
    if isinstance(value, list):
        return any(has_substitution(item) for item in value)
    return False


def flow_assertions(path, root, active=None):
    """Collect unconditional assertions; optional, conditional and substituted paths grant no credit."""
    if any(part.is_symlink() for part in [path, *path.parents]):
        raise ValueError("Screen assertion flow cannot be a symlink")
    path = path.resolve()
    root = root.resolve()
    if not path.is_relative_to(root / '.maestro') or path.is_symlink() or not path.is_file():
        raise ValueError('Screen assertions require an owned regular Maestro flow')
    active = set() if active is None else active
    if path in active:
        raise ValueError('Recursive screen assertion flow')
    active = active | {path}
    tokens = list(yaml.scan(path.read_text()))
    if any(isinstance(token, (yaml.tokens.AnchorToken, yaml.tokens.AliasToken)) for token in tokens):
        raise ValueError('Screen assertion flows cannot use aliases')
    documents = list(yaml.safe_load_all(path.read_text()))
    if len(documents) != 2 or not isinstance(documents[1], list):
        raise ValueError('Expected a Maestro header and command list')
    assertions, hashes = [], {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()}
    for command in documents[1]:
        if not isinstance(command, dict):
            continue
        if 'assertVisible' in command:
            selector = command['assertVisible']
            if isinstance(selector, str):
                selector = {'text': selector}
            if isinstance(selector, dict) and (selector.get('optional') or has_substitution(selector)):
                continue
            allowed = {'text', 'id', 'enabled', 'selected', 'checked', 'index', 'focused',
                       'containsChild', 'containsDescendants', 'childOf'}
            if not isinstance(selector, dict) or not selector or not set(selector) <= allowed:
                raise ValueError('Screen assertion requires an unconditional selector')
            assertions.append(selector)
        if 'runFlow' in command:
            nested = command['runFlow']
            if isinstance(nested, str):
                nested = {'file': nested}
            if isinstance(nested, dict) and set(nested) <= {'file', 'env'} and isinstance(nested.get('file'), str):
                child, sources = flow_assertions(path.parent / nested['file'], root, active)
                assertions.extend(child)
                hashes.update(sources)
    return assertions, hashes


def screen_bindings(root, screens, cases):
    """Each named surface needs its own audited mapping or explicit remaining work."""
    document = json.loads((root / 'config/maestro-screen-assertions.json').read_text())
    if document.get('schema') != 1 or not isinstance(document.get('screens'), list):
        raise ValueError('Invalid direct screen assertion inventory')
    expected = {(screen['source'], screen['symbol']) for screen in screens}
    found, result, flows = set(), [], {}
    for row in document['screens']:
        key = (row.get('source'), row.get('symbol'))
        if key in found or key not in expected:
            raise ValueError(f'Duplicate or stale screen mapping: {key}')
        found.add(key)
        bindings = row.get('bindings')
        if not isinstance(bindings, list):
            raise ValueError(f'Missing screen assertions: {key}')
        if not bindings and not str(row.get('remaining', '')).strip():
            raise ValueError(f'Unmapped screen needs specific remaining work: {key}')
        qualified, seen = [], set()
        for binding in bindings:
            case = binding.get('case')
            selector = binding.get('selector')
            identity = (case, json.dumps(selector, sort_keys=True))
            if identity in seen or case not in cases or not isinstance(selector, dict) or not selector:
                raise ValueError(f'Duplicate, unknown or malformed screen assertion: {key}')
            seen.add(identity)
            if not str(binding.get('scope', '')).strip():
                raise ValueError(f'Screen assertion needs its precise fixture scope: {key}')
            if case not in flows:
                flows[case] = flow_assertions(root / cases[case]['flow'], root)
            assertions, hashes = flows[case]
            if selector not in assertions:
                raise ValueError(f'Screen selector is not directly asserted: {key} {case} {selector}')
            qualified.append({**binding, 'flow_sha256': hashes})
        result.append({**row, 'bindings': qualified, 'execution_verified': False})
    if found != expected:
        raise ValueError(f'New screen requires direct assertion audit: {sorted(expected - found)}')
    return result


def executed_screens(bindings, campaign, source):
    """Only the reconciled exact-source per-case campaign grants credit; no inherited success flags."""
    if campaign.get('source_sha') != source or campaign.get('errors'):
        raise ValueError('Screen execution needs the exact reconciled source campaign')
    results = campaign.get('results')
    if not isinstance(results, list):
        raise ValueError('Screen execution needs per-case outcomes')
    if any(not isinstance(row, dict) or not isinstance(row.get('case'), str) for row in results):
        raise ValueError('Malformed screen execution case')
    names = [row.get('case') for row in results]
    if len(names) != len(set(names)):
        raise ValueError('Duplicate screen execution case')
    passed = {row['case']: row for row in results if row.get('passed') is True
              and row.get('not_run') is not True and not row.get('failure')}
    def qualifies(binding):
        hashes = binding.get('flow_sha256')
        return (binding['case'] in passed and isinstance(hashes, dict) and bool(hashes)
                and hashes == passed[binding['case']].get('flow_sha256'))
    return [{**screen, 'execution_verified': any(qualifies(binding) for binding in screen['bindings']),
             'executed_cases': sorted({binding['case'] for binding in screen['bindings'] if qualifies(binding)})}
            for screen in bindings]
