"""List every maintained test requirement and surface without turning partial UI checks into release proof."""

import argparse
import json
import re
from pathlib import Path

try:
    from scripts.manual_test_fragments import definitions, load_guide, load_inventory
    from scripts.maestro_suite import CASES as OFFLINE
    from scripts.maestro_runtime import CASES as RUNTIME
    from scripts.check_manual_test_guide import composable_names
    from scripts.maestro_screen_coverage import screen_bindings, executed_screens
except ModuleNotFoundError:
    from manual_test_fragments import definitions, load_guide, load_inventory
    from maestro_suite import CASES as OFFLINE
    from maestro_runtime import CASES as RUNTIME
    from check_manual_test_guide import composable_names
    from maestro_screen_coverage import screen_bindings, executed_screens

ROOT = Path(__file__).resolve().parents[1]
LAYERS = {'core-ci', 'offline-ui', 'native-runtime-ui', 'controlled-integration', 'physical-human', 'release-audit'}
EDGE_DIMENSIONS = (
    'initial-empty-loading-populated-error', 'normal-action', 'cancel-and-back', 'permission-denial',
    'invalid-and-boundary-input', 'repeat-and-concurrent-actions', 'retry-and-interruption',
    'account-and-target-change', 'offline-and-reconnect', 'warm-resume', 'activity-recreation',
    'process-death', 'orientation-and-window-size', 'theme-font-scale-rtl', 'accessibility-and-keyboard',
)
EDGE_CHECKS = {
    'initial-empty-loading-populated-error': 'Open with each supported initial state; verify content, actions and error recovery.',
    'normal-action': 'Complete each advertised action and verify its resulting state at the owning execution layer.',
    'cancel-and-back': 'Dismiss through Cancel, toolbar Back and Android Back; verify drafts, selections and parent destination.',
    'permission-denial': 'Exercise first denial, repeated denial and later grant for each permission this surface requests.',
    'invalid-and-boundary-input': 'Exercise empty, whitespace, malformed, minimum, maximum, oversized and Unicode input where accepted.',
    'repeat-and-concurrent-actions': 'Repeat actions and interleave competing operations; verify no duplication, lost input or stale result.',
    'retry-and-interruption': 'Interrupt an operation, recover and Retry; verify retained intent and the final durable outcome.',
    'account-and-target-change': 'Change account or target while work is pending; verify private state and late results stay with their owner.',
    'offline-and-reconnect': 'Open or act offline, then restore connectivity; verify local usability and correct recovery without duplicate effects.',
    'warm-resume': 'Background and resume the same process with input or work pending; verify the visible state and resumed controls.',
    'activity-recreation': 'Recreate the Activity with pending state; verify restoration without treating handled rotation as recreation.',
    'process-death': 'Kill and relaunch the process using its dedicated fixture; verify only durable state returns and work recovers safely.',
    'orientation-and-window-size': 'Use portrait, landscape and constrained windows; verify reachable controls, dialogs and keyboard geometry.',
    'theme-font-scale-rtl': 'Check supported themes, large font scales and RTL; verify readable content, ordering and unclipped controls.',
    'accessibility-and-keyboard': 'Check semantics, focus traversal, TalkBack and keyboard dismissal; verify the intended control receives each action.',
}


def named_edges(guide, requirements):
    """Retain explicit checklist subcases separately so their parent mapping cannot hide them."""
    edges, seen = [], set()
    for line in guide.splitlines():
        match = re.match(r'^\s*- \*\*([A-Z]{3,4}-\d{3}) ([^*]+):\*\*\s*(.+)$', line)
        if not match:
            continue
        parent, title, body = match.groups()
        key = (parent, title)
        if parent not in requirements or key in seen:
            raise ValueError(f'Unknown or duplicate named edge case: {parent} {title}')
        seen.add(key)
        edges.append({'parent': parent, 'title': title, 'requirement': body, 'full_release_proof': False})
    return edges


def family_plans(root, requirements):
    """Require a maintained campaign prerequisite plan for every permanent requirement family."""
    document = json.loads((root / 'config/test-requirement-layers.json').read_text())
    families = document.get('families', {})
    expected = {test_id.split('-')[0] for test_id in requirements}
    if document.get('schema') != 1 or set(families) != expected:
        raise ValueError('Missing or obsolete requirement-family campaign plan')
    for name, plan in families.items():
        if (not isinstance(plan, dict) or not isinstance(plan.get('layers'), list) or not plan['layers']
                or any(not isinstance(layer, str) for layer in plan['layers'])
                or len(set(plan['layers'])) != len(plan['layers']) or not set(plan['layers']) <= LAYERS
                or not isinstance(plan.get('prerequisites'), str) or not plan['prerequisites'].strip()):
            raise ValueError(f'Invalid campaign layers or prerequisites: {name}')
    return families


def screen_catalog(root, surfaces, cases, include_companions=False):
    """Discover named UI screens/sheets/dialogs, retaining requirements without inventing execution proof."""
    source_ids = {}
    for entries in surfaces.values():
        for entry in entries:
            source_ids.setdefault(entry['source'], set()).update(entry['test_ids'])
    companions = {}
    for folder in ('test', 'androidTest') if include_companions else ():
        for path in sorted((root / f'app/src/{folder}').rglob('*.kt')):
            # A direct source reference helps find a companion. It is not an executed assertion.
            symbols = re.findall(r'\b([A-Z]\w*)\s*\(', path.read_text())
            for symbol in set(symbols):
                companions.setdefault(symbol, []).append(path.relative_to(root).as_posix())
    result = []
    for path in sorted((root / 'app/src/main/java/dev/ipf/whitenoise/android').rglob('*.kt')):
        source = path.relative_to(root).as_posix()
        text = path.read_text()
        composables = composable_names(text)
        names = {name for name in composables if name.endswith(
            ('Screen', 'Sheet', 'Dialog', 'FullScreen', 'FullScreenView', 'Picker', 'Viewer', 'Drawer', 'Overlay', 'Pane',
             'Menu', 'Panel', 'Popup', 'DialogContent', 'SheetContent', 'PickerContent', 'FullScreenContent',
             'Modal', 'ScreenForAccount', 'Browser'))}
        source_symbols = set(re.findall(r'\bfun\s+([A-Z]\w*)\s*\(', text))
        source_symbols.update(name.rsplit('.', 1)[-1] for name in composables)
        companion_paths = sorted({path for symbol in source_symbols for path in companions.get(symbol, [])})
        if names and not source_ids.get(source):
            raise ValueError(f'UI surface has no maintained source/requirement mapping: {source}')
        ids = sorted(source_ids.get(source, set()))
        related = sorted(name for name, case in cases.items() if set(ids) & set(case['manual_ids']))
        for symbol in sorted(set(names)):
            result.append({'source': source, 'symbol': symbol, 'manual_ids': ids,
                           'related_ui_cases_by_requirement': related,
                           'companion_test_source_references': [
                               {'source': path, 'direct_surface_reference': path in companions.get(symbol.rsplit('.', 1)[-1], [])}
                               for path in companion_paths],
                           'execution_verified': False,
                           'edge_plan': [{'dimension': dimension, 'required_check': EDGE_CHECKS[dimension],
                                          'status': 'unexecuted', 'evidence': [], 'na_reason': None}
                                         for dimension in EDGE_DIMENSIONS],
                           'limits': 'A shared requirement ID is a discovery link, not proof this particular surface was exercised.'})
    return result


def inventory(root=ROOT, include_companions=False):
    """Assemble fragment-aware requirements and partial mappings without claiming execution."""
    guide = load_guide(root)
    requirements = definitions(guide)
    edges = named_edges(guide, requirements)
    plans = family_plans(root, requirements)
    surfaces = load_inventory(root)['categories']
    cases = {name: {'manual_ids': values[1], 'layer': 'offline-ui', 'flow': '.maestro/' + values[0]}
             for name, values in OFFLINE.items()}
    cases.update({name: {**case, 'layer': case.get('layer', 'native-runtime-ui'), 'flow': f'.maestro/runtime/{name}.yaml'}
                  for name, case in RUNTIME.items()})
    unknown = {test_id for case in cases.values() for test_id in case['manual_ids']} - set(requirements)
    if unknown:
        raise ValueError(f'Unknown permanent checklist IDs: {sorted(unknown)}')
    # A mapping names only the explicitly recorded assertions, never the whole release case.
    requirements = {test_id: {'requirement': text, 'partial_ui_cases': [name for name, case in cases.items()
                                if test_id in case['manual_ids']], 'campaign_plan': plans[test_id.split('-')[0]],
                              'named_edge_cases': [edge for edge in edges if edge['parent'] == test_id],
                              'full_release_proof': False}
                    for test_id, text in requirements.items()}
    screens = screen_catalog(root, surfaces, cases, include_companions)
    direct = {(row["source"], row["symbol"]): row for row in screen_bindings(root, screens, cases)}
    screens = [{**screen, **direct[(screen["source"], screen["symbol"]) ]} for screen in screens]
    return {'schema': 1, 'requirements': requirements, 'surfaces': surfaces, 'cases': cases,
            'screen_catalog': screens, 'discovered_screen_count': len(screens),
            'planned_screen_assertion_count': sum(bool(screen['bindings']) for screen in screens),
            'screens_without_direct_assertions': [screen['symbol'] for screen in screens if not screen['bindings']],
            'screen_edge_check_count': sum(len(screen['edge_plan']) for screen in screens),
            'named_edge_cases': edges, 'named_edge_case_count': len(edges),
            'required_edge_dimensions': list(EDGE_DIMENSIONS),
            'unmapped_requirement_ids': sorted(test_id for test_id, case in requirements.items()
                                               if not case['partial_ui_cases']),
            'case_count': len(cases), 'requirement_count': len(requirements),
            'full_release_coverage': False,
            'limits': 'Discovery and case mappings are not executed evidence. Keep every manual case and its subcases as release requirements.'}


def markdown_inventory(result, source_sha=None):
    """Render every requirement, named edge and discovered surface as an unchecked campaign guide."""
    if source_sha is not None and not re.fullmatch('[0-9a-f]{40}', source_sha):
        raise ValueError('Full source commit required for campaign links')
    base = f'https://github.com/marmot-protocol/whitenoise-android/blob/{source_sha}/' if source_sha else '../../'
    lines = ['# Complete testing campaign inventory', '',
             'This is an execution plan. Every acceptance point remains unchecked; partial UI mappings are not full proof.', '',
             f"{result['requirement_count']} permanent requirements; {result['named_edge_case_count']} explicitly named subcases; "
             f"{result['discovered_screen_count']} named UI surfaces; {result['case_count']} defined UI journeys; "
             f"{result['screen_edge_check_count']} surface-specific edge checks to qualify.", '',
             'For every applicable surface, record Pass, Fail, unexecuted or a justified N/A for each dimension:', '',
             *[f'- [ ] {dimension}' for dimension in EDGE_DIMENSIONS], '',
             'Activity recreation and process death need separate fixtures: handled rotation proves neither.', '',
             '## Requirements and campaign prerequisites', '']
    for test_id, requirement in result['requirements'].items():
        lines.extend([requirement['requirement'], '',
                      'Required campaigns: ' + ', '.join(requirement['campaign_plan']['layers']) + '.', '',
                      requirement['campaign_plan']['prerequisites'], ''])
        for edge in requirement['named_edge_cases']:
            lines.extend([f"- [ ] **{edge['parent']} {edge['title']}** — {edge['requirement']}", ''])
        if requirement['partial_ui_cases']:
            links = [f'[{name}]({base}{result["cases"][name]["flow"]})' for name in requirement['partial_ui_cases']]
            lines.extend(['Partial UI journeys: ' + ', '.join(links) + '.', ''])
        else:
            lines.extend(['No partial Maestro journey covers this requirement. Execute its listed campaigns and full acceptance.', ''])
    lines.extend(['## Screens, sheets and dialogs', '',
                  'Same-ID UI links are discovery hints. They do not prove that this particular surface was opened.', ''])
    for screen in result['screen_catalog']:
        lines.extend([f"### [{screen['symbol']}]({base}{screen['source']})", '',
                      'Permanent requirements: ' + ', '.join(screen['manual_ids']) + '.', ''])
        for binding in screen['bindings']:
            lines.extend([f"Direct planned assertion: [{binding['case']}]({base}{result['cases'][binding['case']]['flow']}) — {binding['scope']} Execution remains unverified.", ''])
        if not screen['bindings']:
            lines.extend(['Remaining screen coverage: ' + screen['remaining'], ''])
        if screen['companion_test_source_references']:
            references = [f'[{Path(item["source"]).name}]({base}{item["source"]})'
                          for item in screen['companion_test_source_references']]
            lines.extend(['Existing test references to this source file: ' + ', '.join(references)
                          + '. A same-file reference may test a shared parent; this surface and its execution remain unverified.', ''])
        lines.extend(['Record the result and source-bound evidence for each applicable check. '
                      'A N/A needs a specific reason; no check inherits its outcome from a parent or a related journey.', ''])
        for edge in screen['edge_plan']:
            lines.append(f"- [ ] **{edge['dimension']}** — {edge['required_check']} Result: unexecuted; evidence: none.")
        lines.append('')
    return '\n'.join(lines) + '\n'


def main():
    """Write the coverage inventory; reject a full-proof request while mappings remain partial."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--require-full', action='store_true')
    parser.add_argument('--require-screens', action='store_true', help='Require a direct maintained assertion for every discovered surface')
    parser.add_argument('--markdown-output', type=Path)
    parser.add_argument('--source-sha')
    args = parser.parse_args()
    if args.source_sha and not re.fullmatch('[0-9a-f]{40}', args.source_sha):
        parser.error('Full source commit required for campaign identity')
    result = inventory(include_companions=True)
    result['source_sha'] = args.source_sha
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    if args.markdown_output:
        args.markdown_output.parent.mkdir(parents=True, exist_ok=True)
        args.markdown_output.write_text(markdown_inventory(result, args.source_sha))
    print(f"{result['case_count']} UI journeys; {result['requirement_count']} maintained requirements; "
          f"{result['discovered_screen_count']} named UI surfaces; full release proof remains required")
    if args.require_screens and result['screens_without_direct_assertions']:
        parser.exit(1, 'Screens still need direct assertions: ' + ', '.join(result['screens_without_direct_assertions']) + '\n')
    if args.require_full and not result['full_release_coverage']:
        parser.exit(1, 'Partial UI assertions cannot certify every screen and edge case\n')


if __name__ == '__main__':
    main()
