"""Validate direct UI assertions and report source-bound screen execution separately from release proof."""

import hashlib
import json
from pathlib import Path
import yaml

try:
    from scripts.maestro_modal_discovery import modal_owners
except ModuleNotFoundError:
    from maestro_modal_discovery import modal_owners


def has_substitution(value):
    """Runtime-expanded selectors cannot establish a maintained literal screen assertion."""
    if isinstance(value, str):
        return '${' in value
    if isinstance(value, dict):
        return any(has_substitution(item) for item in value.values())
    if isinstance(value, list):
        return any(has_substitution(item) for item in value)
    return False


def flow_steps(path, root, active=None):
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
        if command == 'back':
            assertions.append({'back': True})
            continue
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
                raise ValueError(f'Screen assertion requires an unconditional selector: {path.relative_to(root)} {selector}')
            assertions.append({'assertVisible': selector})
        for action in ('tapOn', 'longPressOn'):
            if action not in command:
                continue
            selector = command[action]
            if isinstance(selector, str):
                selector = {'text': selector}
            if isinstance(selector, dict) and not selector.get('optional') and not has_substitution(selector):
                assertions.append({action: selector})
        if 'runFlow' in command:
            nested = command['runFlow']
            if isinstance(nested, str):
                nested = {'file': nested}
            if isinstance(nested, dict) and set(nested) <= {'file', 'env'} and isinstance(nested.get('file'), str):
                child, sources = flow_steps(path.parent / nested['file'], root, active)
                assertions.extend(child)
                hashes.update(sources)
    return assertions, hashes


def flow_assertions(path, root, active=None):
    steps, hashes = flow_steps(path, root, active)
    assertions, opener = [], None
    for step in steps:
        if 'tapOn' in step or 'longPressOn' in step:
            opener = step.get('tapOn', step.get('longPressOn'))
        elif 'assertVisible' in step:
            selector = step['assertVisible']
            identity = {key: selector[key] for key in ('id', 'text', 'focused', 'selected', 'checked') if key in selector}
            tapped = {key: opener[key] for key in ('id', 'text', 'focused', 'selected', 'checked') if key in opener} if opener else None
            if identity != tapped:
                assertions.append(selector)
    return assertions, hashes


def entered_before(path, root, entry, selector, opener=None):
    """Require a real entry before its state assertion, never a parent assertion repeated after a tap."""
    steps, _ = flow_steps(path, root)
    opening = None
    if opener is not None:
        opening = (opener if set(opener) in ({'tapOn'}, {'longPressOn'}, {'back'}) else {'tapOn': opener})
    opened, entered = opening is None, False
    for step in steps:
        interaction = any(action in step for action in ('tapOn', 'longPressOn', 'back'))
        if opening is None and interaction and not entered:
            return False
        if step == opening:
            opened = True
        if step.get('assertVisible') == entry:
            if not opened:
                return False
            entered = True
        if entered and step.get('assertVisible') == selector:
            return True
    return False


def asserted_after(path, root, opener, selector):
    return entered_before(path, root, selector, selector, opener)


def screen_bindings(root, screens, cases):
    """Each named surface needs its own audited mapping or explicit remaining work."""
    document = json.loads((root / 'config/maestro-screen-assertions.json').read_text())
    if document.get('schema') != 1 or not isinstance(document.get('screens'), list):
        raise ValueError('Invalid direct screen assertion inventory')
    default_remaining = document.get('default_remaining', '')
    if not isinstance(default_remaining, str):
        raise ValueError('Invalid default remaining screen scope')
    expected = {(screen['source'], screen['symbol']) for screen in screens}
    found, result, flows, owners = set(), [], {}, {}
    for row in document['screens']:
        key = (row.get('source'), row.get('symbol'))
        if key in found or key not in expected:
            raise ValueError(f'Duplicate or stale screen mapping: {key}')
        found.add(key)
        source = row['source']
        if source not in owners:
            owners[source] = modal_owners((root / source).read_text())
        is_modal = row['symbol'] in owners[source]
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
            if is_modal:
                entry = binding.get('entry_assertion')
                if not isinstance(entry, dict) or entry not in assertions:
                    raise ValueError(f'Modal owner needs a literal direct entry assertion: {key} {case}')
                visible = binding.get('visible_on_entry') is True
                if visible == ('opened_by' in binding):
                    raise ValueError(f'Modal owner needs exactly one opening action or explicit fixture entry: {key} {case}')
                if not entered_before(root / cases[case]['flow'], root, entry, selector, binding.get('opened_by')):
                    raise ValueError(f'Modal entry is not before its state assertion or already visible before opening: {key} {case}')
            elif 'opened_by' in binding and not asserted_after(
                    root / cases[case]['flow'], root, binding['opened_by'], selector):
                raise ValueError(f'Screen assertion precedes its opening action: {key} {case}')
            qualified.append({**binding, 'flow_sha256': hashes})
        result.append({**row, 'remaining': row.get('remaining', default_remaining),
                       'bindings': qualified, 'execution_verified': False})
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
