"""List every maintained test requirement and surface without turning partial UI checks into release proof."""

import argparse
import json
from pathlib import Path

try:
    from scripts.manual_test_fragments import definitions, load_guide, load_inventory
    from scripts.maestro_suite import CASES as OFFLINE
    from scripts.maestro_runtime import CASES as RUNTIME
except ModuleNotFoundError:
    from manual_test_fragments import definitions, load_guide, load_inventory
    from maestro_suite import CASES as OFFLINE
    from maestro_runtime import CASES as RUNTIME

ROOT = Path(__file__).resolve().parents[1]


def inventory(root=ROOT):
    requirements = definitions(load_guide(root))
    surfaces = load_inventory(root)['categories']
    cases = {name: {'manual_ids': values[1], 'layer': 'offline-ui'} for name, values in OFFLINE.items()}
    cases.update({name: {**case, 'layer': 'native-runtime-ui'} for name, case in RUNTIME.items()})
    unknown = {test_id for case in cases.values() for test_id in case['manual_ids']} - set(requirements)
    if unknown:
        raise ValueError(f'Unknown permanent checklist IDs: {sorted(unknown)}')
    # A mapping names only the explicitly recorded assertions, never the whole release case.
    requirements = {test_id: {'requirement': text, 'partial_ui_cases': [name for name, case in cases.items()
                                if test_id in case['manual_ids']], 'full_release_proof': False}
                    for test_id, text in requirements.items()}
    return {'schema': 1, 'requirements': requirements, 'surfaces': surfaces, 'cases': cases,
            'case_count': len(cases), 'requirement_count': len(requirements),
            'full_release_coverage': False,
            'limits': 'Discovery and case mappings are not executed evidence. Keep every manual case and its subcases as release requirements.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--require-full', action='store_true')
    args = parser.parse_args()
    result = inventory()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(f"{result['case_count']} UI journeys; {result['requirement_count']} maintained requirements; full release proof remains required")
    if args.require_full and not result['full_release_coverage']:
        parser.exit(1, 'Partial UI assertions cannot certify every screen and edge case\n')


if __name__ == '__main__':
    main()
