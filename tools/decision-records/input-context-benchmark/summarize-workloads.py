#!/usr/bin/env python3
"""Validate profile-declared workload evidence without treating unavailable capture as savings."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
from statistics import median

WORKLOADS = ('SMALL', 'PRIVATE32', 'PUBLIC32', 'MULTIPLAYER', 'CAPPED')
CONFIG = {'SMALL': (2, 4, 0, 'NONE'), 'PRIVATE32': (2, 30, 4, 'PRIVATE32'),
          'PUBLIC32': (2, 30, 4, 'PUBLIC32'), 'MULTIPLAYER': (4, 18, 8, 'PUBLIC32'),
          'CAPPED': (4, 40, 12, 'PUBLIC32')}
MODES = ('DISABLED', 'COPY', 'UNACCOUNTED_BASE', 'ACCOUNTED_BASE', 'NUMERIC', 'ENTITY', 'CAPTURE')
INPUTS = {'NUMERIC', 'ENTITY', 'CAPTURE'}
PHASES = ('capture', 'canonicalOrdering', 'contextFreeze', 'eventFreeze', 'publication', 'serialization', 'disclosureDelivery')


def require(value, message):
    if not value: raise ValueError(message)


def distribution(values):
    return dict(median=median(values), minimum=min(values), maximum=max(values), rounds=values)


def validate(rows, profiled):
    measured = [r for r in rows if not r['memorySample'] and not r['exhaustionSample']]
    keys = [(r['workload'], r['mode'], r['round']) for r in measured]
    rounds = {r['round'] for r in measured}
    require(rounds == set(range(len(rounds))) and len(rounds) >= 4, 'Missing measured rounds')
    require(set(keys) == {(w, m, r) for w in WORKLOADS for m in MODES for r in rounds} and len(keys) == len(set(keys)), 'Missing or duplicate workload/mode')
    for r in rows:
        mode = r['mode']; n = r['iterations']; off = mode in {'DISABLED', 'COPY'}
        require(mode in MODES and r['workload'] in WORKLOADS and r['profiled'] == profiled and n > 0, 'Invalid workload declaration')
        require(tuple(r[key] for key in ('players', 'boardCardsPerPlayer', 'stackEntries', 'delivery')) == CONFIG[r['workload']], 'Fixture dimensions differ from their declared workload')
        require(not (r['memorySample'] and r['exhaustionSample']), 'Ambiguous sample')
        require(set(r['statuses']) <= {'CAPTURED', 'PARTIAL', 'UNAVAILABLE'}, 'Unknown capture status')
        require(all(v >= 0 for v in r['statuses'].values()), 'Invalid status count')
        available = sum(v for k, v in r['statuses'].items() if k != 'UNAVAILABLE')
        unavailable = r['statuses'].get('UNAVAILABLE', 0)
        require(not unavailable or r['exhaustionSample'], 'Unavailable capture disguised as a cost sample')
        require(available + unavailable == (n if mode in INPUTS else 0), 'Input counts do not reconcile')
        require(r['callbacks'] == (0 if mode == 'CAPTURE' else n), 'Callback count')
        require(r['events'] == (0 if off or mode == 'CAPTURE' else 2*n), 'Event count')
        account = r['accountingDelta']
        tracked = mode in {'ACCOUNTED_BASE', 'NUMERIC', 'ENTITY'}
        for key in ('entered', 'prepared', 'callbackStarted', 'callbackReturned', 'startAttempts', 'startReturned', 'terminalAttempts', 'terminalReturned', 'finished'):
            require(account[key] == (n if tracked else 0), 'Source accounting mismatch: ' + key)
        require(account['inputUnavailable'] == (unavailable if tracked else 0), 'Input unavailable/accounting mismatch')
        require(not any(account[key] for key in ('omitted', 'callbackThrew', 'observationFailures', 'omittedUnsupported', 'omittedClosed', 'omittedFailure')), 'Failed fixture')
        if off:
            require(not any(r['phases'][key]['calls'] for key in PHASES), 'Disabled/copied phase work')
            require(r['workConsumed'] == 0 and r['remainingWork'] is None, 'Disabled/copied capture work')
        if r['delivery'] == 'PUBLIC32' and mode in INPUTS and r['workload'] != 'CAPPED':
            require(r['linkedDisclosureFacts'] == available*32, 'Public proof workload did not establish 32 bindings per available view')
        if r['workload'] == 'CAPPED':
            require(r['linkedDisclosureFacts'] == 0, 'Capped entity views cannot prove unique public presentations')
        if r['delivery'] == 'PRIVATE32':
            require(r['linkedDisclosureFacts'] == 0, 'Private disclosure was bound')
        if mode == 'ENTITY':
            require(r['diagnosticOptionEntries'] == available * r['players'] * r['boardCardsPerPlayer'], 'Option pool count')
            require(r['linkedOptions'] > 0, 'Public option links were not exercised')
        calls = {key: r['phases'][key]['calls'] for key in PHASES}
        if not profiled:
            require(not any(calls.values()), 'Instrumented control')
        else:
            expected = dict(capture=n if mode in INPUTS else 0, eventFreeze=0 if off or mode == 'CAPTURE' else 2*n,
                            publication=0 if off or mode == 'CAPTURE' else 2*n, serialization=0 if off or mode == 'CAPTURE' else 2*n)
            require(all(calls[key] == value for key, value in expected.items()), 'Phase calls do not reconcile')
            require(calls['contextFreeze'] == (n if mode in INPUTS else 0), 'Context freeze count')
            if r['memorySample']: require(r['phases']['peakSampledPayloadBytes'] > 0, 'Missing memory sample')
            for field in ('cpuNanos', 'allocatedBytes'):
                require(r['phases']['publication'][field] >= r['phases']['serialization'][field], 'Serialization exceeds publication')
                if mode in INPUTS:
                    require(r['phases']['capture'][field] >= sum(r['phases'][child][field] for child in ('contextFreeze', 'canonicalOrdering')), 'Child spans exceed capture')
        if r['exhaustionSample']:
            require(available > 0 and unavailable > 0 and r['remainingWork'] == 0, 'Long fixture did not exhaust budget')
            require(sum(w['invocations'] for w in r['windows']) == n, 'Missing exhaustion windows')
            require(sum(w['available'] for w in r['windows']) == available and sum(w['unavailable'] for w in r['windows']) == unavailable, 'Exhaustion status mismatch')
            require(all(w['available'] + w['unavailable'] == w['invocations'] for w in r['windows']), 'Mixed window imbalance')
            offset = 0
            for window in r['windows']:
                require(window['fromInvocation'] == offset and window['invocations'] > 0, 'Noncontiguous exhaustion windows')
                offset += window['invocations']
    memory = [r for r in rows if r['memorySample']]
    require(len(memory) == (len(WORKLOADS) if profiled else 0), 'Missing/duplicate memory samples')
    require({r['workload'] for r in memory} == (set(WORKLOADS) if profiled else set()), 'Memory workload mismatch')
    require(len([r for r in rows if r['exhaustionSample']]) == 1, 'Missing exhaustion sample')
    return measured


def summarize(control, profiled):
    rows = control + profiled
    declared = {'inputContextProfile' in row for row in rows}
    require(len(declared) == 1, 'Mixed legacy and declared producer profiles')
    profiles = {row.get('inputContextProfile', 'PERMITTED_CHOICE_VIEW_V4') for row in rows}
    require(len(profiles) == 1 and profiles <= {'PERMITTED_CHOICE_VIEW_V4', 'PERMITTED_CHOICE_VIEW_V5', 'PERMITTED_CHOICE_VIEW_V6', 'PERMITTED_CHOICE_VIEW_V7', 'PERMITTED_CHOICE_VIEW_V8', 'PERMITTED_CHOICE_VIEW_V9'},
            'Unknown or mismatched producer profiles')
    groups = defaultdict(list)
    for label, rows in [('control', validate(control, False)), ('profiled', validate(profiled, True))]:
        for row in rows: groups[label, row['workload'], row['mode']].append(row)
    fixtures = []
    for workload in WORKLOADS:
        fixture = dict(workload=workload, modes={})
        for mode in MODES:
            data = [r for label in ('control', 'profiled') for r in groups[label, workload, mode]]
            require(len({r['policySha256'] for r in data}) == 1, 'Control/profile or round policy mismatch')
            item = dict(policySha256=data[0]['policySha256'], lastContextReachableBytes=max(r['lastContextReachableBytes'] for r in data),
                        rawContextBytes=max(r['lastContextRawBytes'] for r in data),
                        controllerLedgersReachableBytes=max(r['controllerLedgersReachableBytes'] for r in data),
                        statuses=data[0]['statuses'], omissions=data[0]['omissions'])
            for label in ('control', 'profiled'):
                rows = groups[label, workload, mode]
                item[label] = dict(cpuMicrosPerOperation=distribution([r['cpuNanos']/r['iterations']/1000 for r in rows]),
                                   allocatedBytesPerOperation=distribution([r['allocatedBytes']/r['iterations'] for r in rows]))
                if label == 'profiled':
                    item[label]['phases'] = {phase: dict(
                        cpuMicrosPerOperation=distribution([r['phases'][phase]['cpuNanos']/r['iterations']/1000 for r in rows]),
                        allocatedBytesPerOperation=distribution([r['phases'][phase]['allocatedBytes']/r['iterations'] for r in rows])) for phase in PHASES}
            fixture['modes'][mode] = item
        sample = next(r for r in profiled if r['memorySample'] and r['workload'] == workload)
        fixture['memorySample'] = {key: sample[key] for key in ('lastContextReachableBytes', 'controllerLedgersReachableBytes', 'disclosureFactPayloadReachableBytes', 'publicProofSlots', 'proofShellShallowBytes')}
        fixture['memorySample']['payloadUnionSampledPeak'] = sample['phases']['peakSampledPayloadBytes']
        fixtures.append(fixture)
    return dict(schemaVersion=1, profile=next(iter(profiles)), fixtures=fixtures,
                exhaustion=[{key: row[key] for key in ('profiled', 'iterations', 'statuses', 'remainingWork', 'accountingDelta', 'windows')}
                            for rows in (control, profiled) for row in rows if row['exhaustionSample']],
                limitations=[
                    'Synthetic callback-once fixtures do not execute AI policy. Wall-clock Monte Carlo outcomes are not controls.',
                    'Healthy cost rounds reject unavailable captures; exhaustion windows report available, mixed and unavailable work separately.',
                    'Serialization uses the production writer with a character-counting sink; collector conversion, UTF-8 and physical I/O are excluded.',
                    'Retained graph sizes include shared objects; proof shells exclude the referenced Game graph. Sizes must not be blindly summed.',
                    'Sampled payload-union peaks are lower bounds, not exact transient heap peaks. Builder locals, writer buffers and other roots remain outside the sample.',
                    'Control/profile differences include instrumentation and JIT variation; these ranges are not capacity or deadline-neutrality guarantees.'])


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('directory', type=Path); parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = summarize(json.loads((args.directory/'control.json').read_text()), json.loads((args.directory/'profiled.json').read_text()))
    args.output.write_text(json.dumps(result, indent=2)+'\n')
    print('Validated', result['profile'], 'workload, source-accounting, public-proof, phase and exhaustion evidence.')

if __name__ == '__main__': main()
