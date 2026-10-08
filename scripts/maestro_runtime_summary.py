"""Reconcile every selected UI leaf and native receipt across a manually requested campaign."""

import argparse
import json
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET

try:
    from scripts.maestro_runtime import CASES, PACKAGE, case_selection, receipt, ui_result
    from scripts.maestro_runtime_selection import matrix_selection
    from scripts.maestro_environment import qualify
except ModuleNotFoundError:
    from maestro_runtime import CASES, PACKAGE, case_selection, receipt, ui_result
    from maestro_runtime_selection import matrix_selection
    from maestro_environment import qualify


def read_json(path):
    """Read a regular report rather than following a substituted filesystem link."""
    if path.is_symlink() or not path.is_file():
        raise ValueError(f'Missing regular evidence: {path.name}')
    result = json.loads(path.read_text())
    if not isinstance(result, dict):
        raise ValueError(f"Expected evidence object: {path.name}")
    return result


def campaign(directory, suite, source, run_id, attempt, api='34', navigation='button'):
    """Require complete selected shards, identical APK identity and independent per-case success evidence."""
    if not re.fullmatch('[0-9a-f]{40}', source) or not re.fullmatch('[1-9][0-9]*', str(run_id)):
        raise ValueError('Exact source and positive workflow identity required')
    if not re.fullmatch('[1-9][0-9]{0,8}', str(attempt)):
        raise ValueError('Positive bounded attempt required')
    results, errors, generations = [], [], set()
    identity = None
    platform = None
    artifacts = set()
    available = {path.name for path in directory.iterdir()} if directory.exists() else set()
    for shard in matrix_selection(suite):
        logical, partition = shard['slice'], shard['partition']
        names = case_selection(logical, partition)
        prefix = f'maestro-runtime-results-{logical}-{partition}-{run_id}-'
        retained = []
        for name in available:
            suffix = name.removeprefix(prefix)
            if name.startswith(prefix) and re.fullmatch('[1-9][0-9]{0,8}', suffix) and int(suffix) <= int(attempt):
                retained.append((int(suffix), name))
                artifacts.add(name)
        artifact = max(retained)[1] if retained else prefix + str(attempt)
        artifacts.add(artifact)
        root = directory / artifact
        reports = root / f'maestro-runtime-{logical}-{partition}'
        try:
            observed = qualify(read_json(root / 'maestro-runtime-environment.json'), source, run_id,
                               artifact.removeprefix(prefix), api, navigation)
            if platform is not None and observed != platform:
                raise ValueError('Shards used different Android images or navigation modes')
            platform = observed
            pair = read_json(root / 'maestro-runtime-pair/pair.json')
            if (pair.get('schema') != 1 or pair.get('source_sha') != source or pair.get('run_id') != str(run_id)
                    or pair.get('package') != PACKAGE or pair.get('distribution') != 'Zapstore'):
                raise ValueError('Foreign workflow or APK source')
            producer = str(pair.get('run_attempt', ''))
            if not re.fullmatch('[1-9][0-9]{0,8}', producer) or int(producer) > int(attempt):
                raise ValueError('Missing or future APK producer')
            if any(not re.fullmatch('[0-9a-f]{64}', str(pair.get(key, '')))
                   for key in ('apk_sha256', 'test_apk_sha256')):
                raise ValueError('Missing APK checksums')
            if identity is not None and pair != identity:
                raise ValueError('Shards used different APK pairs')
            identity = pair
            ledger = read_json(reports / 'results.json')
            recorded = ledger.get('results', [])
            if (ledger.get('suite') != logical or ledger.get('partition') != partition
                    or not isinstance(recorded, list) or any(not isinstance(row, dict) for row in recorded)
                    or ledger.get('expected') != names or [row.get('case') for row in recorded] != names):
                raise ValueError('Missing, duplicate or unexpected case ledger')
            if ledger.get('evidence_complete') is not True:
                errors.append(f'{artifact}: shard reported incomplete evidence')
        except (OSError, ValueError, TypeError, KeyError, ET.ParseError) as error:
            errors.append(f'{artifact}: {error}')
            results.extend({'case': name, 'passed': False, 'not_run': True, 'failure': str(error)} for name in names)
            continue
        for name, row in zip(names, recorded):
            result = {'case': name, 'artifact': artifact, 'passed': False, 'not_run': row.get('not_run') is True}
            try:
                leaf = reports / name
                actual = read_json(leaf / 'result.json')
                if actual != row or row.get('passed') is not True or row.get('cleanup_safe') is not True:
                    raise ValueError(row.get('failure', 'UI or teardown did not pass'))
                generation = row.get('generation', '')
                if not re.fullmatch('[0-9a-f]{32}', generation) or generation in generations:
                    raise ValueError('Missing or reused fixture generation')
                generations.add(generation)
                for flag in ('ready', 'verified', 'closed'):
                    receipt(json.dumps(read_json(leaf / f'{flag}.json')), generation, flag)
                if (CASES[name]['postcondition'] == 'composer-recreated'
                        and read_json(leaf / 'verified.json').get('activityRecreated') is not True):
                    raise ValueError('Actual Activity recreation was not verified')
                ready = read_json(leaf / 'ready.json')
                if (ready.get('accounts') != 3 or ready.get('fixture') != CASES[name].get('fixture', 'basic')
                        or ready.get('uiObserver') != 'maestro'):
                    raise ValueError('Native fixture handoff mismatch')
                if 'OK (1 test)' not in (leaf / 'instrumentation.txt').read_text():
                    raise ValueError('Instrumentation completion missing')
                result['seconds'] = ui_result(leaf / 'junit.xml', name)
                result['passed'] = True
            except (OSError, ValueError, TypeError, KeyError, ET.ParseError) as error:
                result['failure'] = str(error)
            results.append(result)
    if directory.exists():
        extra = available - artifacts
        if extra:
            errors.append(f'Unexpected shard artifacts: {sorted(extra)}')
    return {'schema': 1, 'selection': suite, 'source_sha': source, 'run_id': str(run_id), 'run_attempt': str(attempt),
            'pair': identity, 'platform': platform,
            'expected_count': len(results), 'passed_count': sum(row['passed'] for row in results),
            'results': results, 'errors': errors,
            'evidence_complete': bool(results) and not errors and all(row['passed'] for row in results),
            'full_release_coverage': False}


def main():
    """Persist a complete campaign ledger, including unavailable shards, before returning its verdict."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifacts', type=Path, required=True)
    parser.add_argument('--suite', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = campaign(args.artifacts, args.suite, os.environ['GITHUB_SHA'],
                      os.environ['GITHUB_RUN_ID'], os.environ['GITHUB_RUN_ATTEMPT'],
                      os.environ.get('MAESTRO_ANDROID_API', '34'),
                      os.environ.get('MAESTRO_NAVIGATION_MODE', 'button'))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    message = f"Maestro campaign: {result['passed_count']}/{result['expected_count']} selected UI cases passed."
    print(message)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
            summary.write(message + '\n\nFull release requirements remain separate.\n')
    if not result['evidence_complete']:
        parser.exit(1, 'Campaign evidence is failed or incomplete\n')


if __name__ == '__main__':
    main()
