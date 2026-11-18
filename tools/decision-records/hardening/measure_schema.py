#!/usr/bin/env python3
"""Measure identity wire bytes and exporter scaling for the HARD-001 review."""
import argparse
from collections import Counter, defaultdict
import gzip
import json
from pathlib import Path
import subprocess
import sys
import tempfile

EXECUTION_IDENTITY = ('decisionId', 'playerId', 'abilityId', 'sourceId', 'kind')
LATER_STACK = ('STACK_RESOLVED', 'STACK_REMOVED', 'STACK_COUNTERED', 'STACK_CLEARED')
END_EVENTS = ('GAME_OUTCOME', 'GAME_FINISHED', 'GAME_EXECUTION_FINISHED')


def compact(row):
    return (json.dumps(row, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')


def remove_fields(values, names):
    return {key: value for key, value in values.items() if key not in names}


def size_pair(data):
    return [len(data), len(gzip.compress(data, compresslevel=6, mtime=0))]


def measure_sizes(path):
    groups = defaultdict(lambda: (bytearray(), bytearray()))
    actions = {}
    stack_identities = {}
    counts = Counter()
    observation_bytes = 0

    with path.open('rb') as source:
        for raw in source:
            row = json.loads(raw)
            baseline = compact(row)
            assert raw == baseline, f'{path}: input does not match the compact reserialization'
            kind = row['type']
            game = row.get('game')
            projected = row.copy()
            changes = []
            counts[kind] += 1

            if kind == 'AI_DECISION':
                decision = row['decision']
                observation_bytes += len(raw) - len(compact(dict(row, decision=remove_fields(decision, ('observation',)))))
                for selected in decision['selectedActions']:
                    actions[game, selected['actionId']] = dict(selected, decisionId=decision['decisionId'], playerId=decision['playerId'])
                observation = decision['observation']
                assert all(observation[key] == row[key] for key in ('turn', 'phase'))
                assert observation['turnPlayerId'] == decision['playerId']
                reduced_observation = remove_fields(observation, ('turn', 'phase', 'turnPlayerId'))
                changed_observation = dict(row, decision=dict(decision, observation=reduced_observation))
                groups['observationContext'][0].extend(baseline)
                groups['observationContext'][1].extend(compact(changed_observation))
            elif kind == 'AI_ACTION_EXECUTION':
                execution = row['execution']
                action_key = (game, execution['actionId'])
                assert all(execution[key] == actions[action_key][key] for key in EXECUTION_IDENTITY)
                projected['execution'] = remove_fields(execution, EXECUTION_IDENTITY)
                changes.append(('executionIdentity', projected))
                if execution['reason'] == 'STACKED':
                    terminal_ids = (execution['stackId'], execution['executedAbilityId'])
                    counts['stackedExecution'] += 1
                    counts['stackedDifferentAbilityId'] += execution['abilityId'] != execution['executedAbilityId']
                    if stack_identities.get(action_key) == terminal_ids:
                        projected = dict(projected, execution=remove_fields(projected['execution'], ('stackId', 'executedAbilityId')))
                        changes.append(('stackIdentity', dict(row, execution=remove_fields(execution, ('stackId', 'executedAbilityId')))))
            elif row.get('actionId') is not None:
                action_key = (game, row['actionId'])
                assert row['decisionId'] == actions[action_key]['decisionId']
                projected = remove_fields(row, ('decisionId',))
                changes.append(('engineDecisionId', projected))
                if kind == 'STACK_ADDED':
                    stack_identities[action_key] = (row['stackId'], row['abilityId'])
                elif kind in LATER_STACK and stack_identities.get(action_key) == (row['stackId'], row['abilityId']):
                    projected = remove_fields(projected, ('stackId', 'abilityId'))
                    changes.append(('stackIdentity', remove_fields(row, ('stackId', 'abilityId'))))
            elif kind == 'STACK_CLEARED' and row.get('clearedActions'):
                refs = row['clearedActions']
                assert all(ref['decisionId'] == actions[game, ref['actionId']]['decisionId'] for ref in refs)
                projected = dict(row, clearedActions=[remove_fields(ref, ('decisionId',)) for ref in refs])
                changes.append(('engineDecisionId', projected))
                reduced_refs = []
                for ref in projected['clearedActions']:
                    assert ref['stackId'] == stack_identities[game, ref['actionId']][0]
                    reduced_refs.append(remove_fields(ref, ('stackId',)))
                projected = dict(projected, clearedActions=reduced_refs)
                changes.append(('stackIdentity', dict(row, clearedActions=[remove_fields(ref, ('stackId',)) for ref in refs])))

            groups['combined'][0].extend(baseline)
            groups['combined'][1].extend(compact(projected) if changes else baseline)
            if changes:
                counts['combinedRows'] += 1
                groups['combinedRows'][0].extend(baseline)
                groups['combinedRows'][1].extend(compact(projected))
            for group, version in changes:
                counts[group] += 1
                groups[group][0].extend(baseline)
                groups[group][1].extend(compact(version))

    return dict(name=path.name, counts=dict(counts), observationBytes=observation_bytes,
                groups={name: list(map(size_pair, versions)) for name, versions in groups.items()})


RUN_EXPORTER = '''\
import json, resource, runpy, sys, time
sys.argv.pop(0)
start = time.perf_counter()
try:
    runpy.run_path(sys.argv[0], run_name='__main__')
finally:
    usage = resource.getrusage(resource.RUSAGE_SELF)
    print(json.dumps(dict(peakRssKiB=usage.ru_maxrss,
                          seconds=time.perf_counter() - start,
                          cpuSeconds=usage.ru_utime + usage.ru_stime)), file=sys.stderr)
'''


def run_exporter(exporter, paths, option, output=None):
    args = [sys.executable, '-c', RUN_EXPORTER, str(exporter), option]
    if output is not None:
        args.append(str(output))
    completed = subprocess.run(args + list(map(str, paths)), text=True, capture_output=True, check=True)
    metrics = json.loads(completed.stderr)
    metrics.update(json.loads(completed.stdout), mode=option, inputBytes=sum(path.stat().st_size for path in paths))
    if output is not None:
        metrics['datasetBytes'] = (output / 'dataset.jsonl').stat().st_size
    return metrics


def repeated_inputs(exporter, sample, fixture_dir):
    lines = sample.read_bytes().splitlines(keepends=True)
    header = json.loads(lines[0])
    body = b''.join(lines[1:])
    paths = []
    for count in (1, 8, 32):
        for index in range(len(paths), count):
            path = fixture_dir / f'run-{index + 1}.jsonl'
            path.write_bytes(compact(dict(header, runId=header['runId'] + f'-repeat-{index + 1}')) + body)
            paths.append(path)
        yield run_exporter(exporter, paths, '--validate-only')
        yield run_exporter(exporter, paths, '--output-dir', fixture_dir / f'export-{count}')


def long_games(exporter, fixture_dir):
    fixture = subprocess.run([sys.executable, '-c',
        'import json, runpy, sys; print(json.dumps(runpy.run_path(sys.argv[1])["fixture"]()))',
        str(exporter.with_name('test_export.py'))], capture_output=True, text=True, check=True)
    header, start, decision, *ending = json.loads(fixture.stdout)
    assert [row['type'] for row in ending[:-1]] == list(END_EVENTS)
    for count in (1000, 4000, 12000):
        path = fixture_dir / f'long-{count}.jsonl'
        with path.open('wb') as output:
            output.write(compact(header))
            output.write(compact(start))
            for index in range(1, count + 1):
                row = dict(decision, eventIndex=index + 1, decision=dict(decision['decision'], decisionId=f'0:{index}'))
                output.write(compact(row))
            for index, row in enumerate(ending[:-1], count + 2):
                output.write(compact(dict(row, eventIndex=index)))
            output.write(compact(ending[-1]))
        yield run_exporter(exporter, [path], '--validate-only')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    sizes = sub.add_parser('sizes')
    sizes.add_argument('sample_directory', type=Path)
    exporter = sub.add_parser('exporter')
    exporter.add_argument('forge', type=Path)
    exporter.add_argument('sample', type=Path)
    args = parser.parse_args()
    if args.command == 'sizes':
        results = [measure_sizes(path) for path in sorted(args.sample_directory.glob('*.jsonl'))]
        assert results, 'no matching source traces'
    else:
        export_path = args.forge / 'tools/decision-records/export.py'
        with tempfile.TemporaryDirectory(prefix='hard001-export-') as scratch:
            fixture_dir = Path(scratch)
            results = dict(repeatedInputs=list(repeated_inputs(export_path, args.sample, fixture_dir)),
                           longGames=list(long_games(export_path, fixture_dir)))
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
