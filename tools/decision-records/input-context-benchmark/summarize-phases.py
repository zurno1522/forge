#!/usr/bin/env python3
"""Validate phase-probe accounting, and summarize measured rounds without correcting profiler overhead."""
import argparse
import collections
import json
from pathlib import Path
import statistics

PHASES = ('capture', 'canonicalOrdering', 'contextFreeze', 'eventFreeze', 'publication', 'serialization', 'disclosureDelivery')
MODES = ('DISABLED', 'COPY', 'BASE_CHOICE', 'INPUT_CHOICE', 'CAPTURE_ONLY', 'FREEZE_ONLY', 'PUBLISH_ONLY', 'SERIALIZE_ONLY', 'DISCLOSURE_ONLY')


def validate(rows, profiled):
    measured = [r for r in rows if not r['memorySample']]
    keys = [(r['boardCardsPerPlayer'], r['disclosureFacts'], r['mode'], r['round']) for r in measured]
    rounds = {r['round'] for r in measured}
    if rounds != set(range(len(rounds))) or len(rounds) < 4 or len(keys) != len(set(keys)) or len(keys) != 2 * 2 * 9 * len(rounds):
        raise ValueError('Missing or duplicate fixture/mode/round')
    for r in rows:
        if r['profiled'] != profiled or r['iterations'] <= 0:
            raise ValueError('Invalid profiling/iteration declaration')
        if r['mode'] not in MODES or 'UNAVAILABLE' in r['statuses']:
            raise ValueError('Invalid mode or unavailable capture disguised as a performance sample')
        n = r['iterations']; mode = r['mode']
        serialized = r['serializedCharactersIncludingWarmup']
        if (serialized > 0) != (mode in ('INPUT_CHOICE', 'BASE_CHOICE', 'SERIALIZE_ONLY')):
            raise ValueError('Serialization sink character accounting failed')
        calls = {p: r['phases'][p]['calls'] for p in PHASES}
        if mode in ('DISABLED', 'COPY'):
            if r['workConsumed'] or r['events'] or r['callbacks'] != n or any(calls.values()):
                raise ValueError('Disabled/copied mode performed observation work')
        if mode in ('BASE_CHOICE', 'INPUT_CHOICE') and (r['callbacks'] != n or r['events'] != 2 * n):
            raise ValueError('Choice callback/event accounting failed')
        if mode in ('INPUT_CHOICE', 'CAPTURE_ONLY') and sum(r['statuses'].values()) != n:
            raise ValueError('Missing input contexts')
        if not profiled and any(calls.values()):
            raise ValueError('Control JVM was instrumented')
        if profiled:
            expected = dict(capture=n if mode in ('INPUT_CHOICE', 'CAPTURE_ONLY') else 0,
                            contextFreeze=n if mode in ('INPUT_CHOICE', 'CAPTURE_ONLY') else 0,
                            eventFreeze=2*n if mode in ('INPUT_CHOICE', 'BASE_CHOICE') else n if mode == 'FREEZE_ONLY' else 0,
                            publication=2*n if mode in ('INPUT_CHOICE', 'BASE_CHOICE') else n if mode == 'PUBLISH_ONLY' else 0,
                            serialization=2*n if mode in ('INPUT_CHOICE', 'BASE_CHOICE') else n if mode == 'SERIALIZE_ONLY' else 0,
                            disclosureDelivery=n if mode == 'DISCLOSURE_ONLY' else 0)
            if any(calls[p] != value for p, value in expected.items()):
                raise ValueError('Phase call accounting failed: ' + str((mode, calls, expected)))
            if mode in ('INPUT_CHOICE', 'CAPTURE_ONLY') and calls['canonicalOrdering'] <= 0:
                raise ValueError('Canonical ordering was not instrumented')
            if r['memorySample'] and r['phases']['peakSampledPayloadBytes'] <= 0:
                raise ValueError('Missing sampled payload retention')
            if mode in ('INPUT_CHOICE', 'CAPTURE_ONLY'):
                for field in ('cpuNanos', 'allocatedBytes'):
                    if r['phases']['capture'][field] < sum(r['phases'][child][field] for child in ('contextFreeze','canonicalOrdering')):
                        raise ValueError('Child spans exceed inclusive capture')
                    if r['phases']['publication'][field] < r['phases']['serialization'][field]:
                        raise ValueError('Serialization exceeds inclusive publication')
    return measured


def distribution(values):
    return dict(median=statistics.median(values), minimum=min(values), maximum=max(values), rounds=values)


def summarize(control, profiled):
    base = validate(control, False); measured = validate(profiled, True)
    groups = collections.defaultdict(list)
    for label, data in [('control', base), ('profiled', measured)]:
        for r in data: groups[(label, r['boardCardsPerPlayer'], r['disclosureFacts'], r['mode'])].append(r)
    result = dict(schemaVersion=1, fixtureResults=[], validation=dict(controlRounds=len(base), profiledRounds=len(measured),
                  accounting=True, equivalentPolicyFixtures=True), limitations=[
        'Inclusive phase spans overlap. Capture includes its internal freeze and ordering; publication includes sink serialization.',
        'Exclusive residuals subtract child spans per round; they include checks, projection/buffer work and profiling overhead.',
        'Profiled and control run in separate sequential JVMs. Differences include profiling perturbation and JIT/run variation, not a correction factor.',
        'Serialization uses the production SimulationTraceWriter and BufferedWriter against a character-counting sink; collector envelope construction, UTF-8 encoding and filesystem I/O are excluded.',
        'Memory-only rows sample union-reachable JSON payloads at freeze-return and serialization boundaries. This is a sampled lower bound, not exact peak transient heap.',
        'GC deltas cover the process measurement window and can include setup/JIT garbage. They do not attribute GC causality to one operation.',
        'Fixtures have two players, 4/30 battlefield cards per player, 7-card hands, 32-card libraries and 0/32 disclosures. They do not establish multiplayer/long-game behavior.',
        'No gameplay policy is executed. Measured rounds provide a range, not a confidence interval.'])
    for board in (4, 30):
        for facts in (0, 32):
            fixture = dict(boardCardsPerPlayer=board, disclosureFacts=facts, modes={})
            for mode in MODES:
                b = groups[('control', board, facts, mode)]; p = groups[('profiled', board, facts, mode)]
                hashes = {r['policySha256'] for r in b+p}
                if len(hashes) != 1: raise ValueError('Control/profile policy differs: ' + str((board, facts, mode)))
                item = dict(policySha256=next(iter(hashes)), control={}, profiled={})
                for label, data in [('control', b), ('profiled', p)]:
                    item[label] = dict(cpuMicrosPerOperation=distribution([r['cpuNanos']/r['iterations']/1000 for r in data]),
                                       allocatedBytesPerOperation=distribution([r['allocatedBytes']/r['iterations'] for r in data]),
                                       gcCollections=sum(r['gcCollections'] for r in data), gcMillis=sum(r['gcMillis'] for r in data),
                                       phases={phase: dict(cpuMicrosPerOperation=distribution([r['phases'][phase]['cpuNanos']/r['iterations']/1000 for r in data]),
                                                          allocatedBytesPerOperation=distribution([r['phases'][phase]['allocatedBytes']/r['iterations'] for r in data])) for phase in PHASES})
                if mode in ('INPUT_CHOICE', 'CAPTURE_ONLY'):
                    for name, parent, children in [('projectionResidual', 'capture', ('contextFreeze', 'canonicalOrdering')),
                                                   ('publicationResidual', 'publication', ('serialization',))]:
                        item[name] = dict(cpuMicrosPerOperation=distribution([(r['phases'][parent]['cpuNanos']-sum(r['phases'][child]['cpuNanos'] for child in children))/r['iterations']/1000 for r in p]),
                                          allocatedBytesPerOperation=distribution([(r['phases'][parent]['allocatedBytes']-sum(r['phases'][child]['allocatedBytes'] for child in children))/r['iterations'] for r in p]))
                item['representativeRawBytes'] = max(r['representativeRawBytes'] for r in p)
                item['representativeGzipBytes'] = max(r['representativeGzipBytes'] for r in p)
                item['lastContextReachableBytes'] = max(r['lastContextReachableBytes'] for r in p)
                item['disclosureFactsReachableBytes'] = max(r['disclosureFactsReachableBytes'] for r in p)
                fixture['modes'][mode] = item
            samples = [r for r in profiled if r['memorySample'] and r['boardCardsPerPlayer']==board and r['disclosureFacts']==facts]
            if {r['mode'] for r in samples} != {'INPUT_CHOICE', 'CAPTURE_ONLY', 'FREEZE_ONLY'} or len(samples) != 3:
                raise ValueError('Missing memory-only samples')
            fixture['memoryOnlySamples'] = {r['mode']: r['phases']['peakSampledPayloadBytes'] for r in samples}
            result['fixtureResults'].append(fixture)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    summary = summarize(json.loads((args.directory/'control.json').read_text()), json.loads((args.directory/'profiled.json').read_text()))
    args.output.write_text(json.dumps(summary, indent=2)+'\n')
    print('Validated phase accounting, control/profile policy equivalence and memory samples.')


if __name__ == '__main__':
    main()
