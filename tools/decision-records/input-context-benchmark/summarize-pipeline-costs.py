"""Validate synthetic pipeline receipts and real UTF-8 files; never export learning examples."""
import argparse
from collections import defaultdict
import hashlib
import importlib.util
import json
from pathlib import Path
from statistics import median

FIXTURES = {'SMALL', 'MULTIPLAYER', 'CAPPED'}
MODES = {'NUMBER', 'MAX_X', 'DAMAGE', 'EXHAUSTED', 'CAPTURE_FAILURE', 'WRITER_FAILURE'}
PHASES = ('capture', 'canonicalOrdering', 'contextFreeze', 'eventFreeze', 'publication', 'serialization', 'disclosureDelivery')


def require(ok, message):
    if not ok: raise ValueError(message)


def natural(v): return type(v) is int and v >= 0


def validate_row(r, profiled):
    require(type(r['measurementSchemaVersion']) is int and r['measurementSchemaVersion'] == 1 and r['scope'] == 'SYNTHETIC_OPERATIONS_WITH_REAL_COLLECTOR_UTF8', 'Unknown pipeline scope')
    require(r['profile'] == 'PERMITTED_CHOICE_VIEW_V29' and r['trainingReady'] is False and r['profiled'] is profiled, 'Wrong producer/profile declaration')
    require(r['fixture'] in FIXTURES and r['mode'] in MODES and natural(r['round']), 'Unknown stratum')
    n = r['iterations']; mode = r['mode']; unavailable = mode in ('EXHAUSTED', 'CAPTURE_FAILURE'); failed = mode.endswith('FAILURE')
    require(natural(n) and n > 0 and natural(r['warmup']) and r['warmup'] > 0, 'Invalid operation count')
    require(all(natural(r[k]) for k in ('callbacks', 'choiceEvents', 'entryLocationRecords')) and r['callbacks'] == n and r['choiceEvents'] == 2*n and r['entryLocationRecords'] == n, 'Callback/event/entry receipts differ')
    statuses = r['statuses']; require(set(statuses) <= {'CAPTURED', 'PARTIAL', 'UNAVAILABLE'} and all(natural(v) for v in statuses.values()) and sum(statuses.values()) == n, 'Input counts differ')
    require(statuses.get('UNAVAILABLE', 0) == (n if unavailable else 0), 'Unavailable input disguised as healthy work')
    if unavailable: require(r['omissionReasons'].get('BUDGET_EXHAUSTED' if mode == 'EXHAUSTED' else 'CAPTURE_FAILED') == n, 'Missing intentional omission')
    delta = r['sourceDelta']
    require(all(natural(v) for v in delta.values()), 'Invalid source count')
    for k in ('entered', 'prepared', 'callbackStarted', 'callbackReturned', 'startAttempts', 'startReturned', 'terminalAttempts', 'terminalReturned', 'finished'):
        require(delta[k] == n, 'Source receipt differs: ' + k)
    require(delta['inputUnavailable'] == (n if unavailable else 0) and delta['observationFailures'] == (n if mode == 'CAPTURE_FAILURE' else 0), 'Wrong failure/input source counts')
    require(all(delta[k] == 0 for k in ('omitted', 'callbackThrew', 'omittedUnsupported', 'omittedClosed', 'omittedFailure')), 'Native callback/observation was omitted')
    require(r['sinkFailed'] is failed and r['flushFailed'] is (mode == 'WRITER_FAILURE'), 'Wrong failure classification')
    require(natural(r['workConsumed']) and natural(r['remainingWork']), 'Invalid work counts')
    require((r['workConsumed'] == r['remainingWork'] == 0) if mode == 'EXHAUSTED' else r['workConsumed'] > 0, 'Capture work did not match case')
    for k in ('cpuNanos', 'wallNanos', 'allocatedBytes', 'measuredFileOffset', 'measuredEndOffset', 'measuredFileBytes', 'measuredLineCount', 'fileBytes', 'controllerShallowBytes', 'stateShallowBytes', 'admissionCounterReachableBytes'):
        require(natural(r[k]), 'Invalid metric: ' + k)
    require(r['measuredEndOffset']-r['measuredFileOffset'] == r['measuredFileBytes'] and r['measuredEndOffset'] <= r['fileBytes'], 'Byte interval differs')
    require(r['measuredLineCount'] == (0 if mode == 'WRITER_FAILURE' else 2*n), 'Physical delivery differs')
    require((r['measuredFileBytes'] == 0) == (mode == 'WRITER_FAILURE'), 'Unexpected physical output loss')
    for p in PHASES:
        require(all(natural(r['phases'][p][k]) for k in ('calls', 'cpuNanos', 'allocatedBytes')), 'Invalid phase metric')
    calls = {p:r['phases'][p]['calls'] for p in PHASES}
    if not profiled: require(not any(calls.values()), 'Instrumented control')
    else:
        expected = dict(capture=n, contextFreeze=0 if mode == 'CAPTURE_FAILURE' else n,
                        eventFreeze=2*n+r['entryLocationRecords']+(n if mode == 'CAPTURE_FAILURE' else 0),
                        publication=2*n, serialization=2 if mode == 'WRITER_FAILURE' else 2*n+1, disclosureDelivery=0)
        require(all(calls[k] == v for k,v in expected.items()), 'Phase receipts differ')
        require((calls['canonicalOrdering'] == 0) if mode == 'EXHAUSTED' else calls['canonicalOrdering'] > 0, 'Ordering scope differs')
    require(r['gcCollectionsAfterWarmupThroughCleanup'] is None or natural(r['gcCollectionsAfterWarmupThroughCleanup']), 'Invalid GC count')
    require(r['heapPoolPeaksThroughCleanup'] and all(v is None or natural(v) for v in r['heapPoolPeaksThroughCleanup'].values()), 'Invalid heap pool declaration')


def validate_file(r, choices):
    data = Path(r['file']).read_bytes()
    require(len(data) == r['fileBytes'] and hashlib.sha256(data).hexdigest() == r['fileSha256'], 'Physical file bytes/hash differ')
    interval = data[r['measuredFileOffset']:r['measuredEndOffset']]
    require(len(interval) == r['measuredFileBytes'] and hashlib.sha256(interval).hexdigest() == r['measuredFileSha256'], 'Measured byte slice differs')
    rows = [json.loads(s) for s in interval.decode('utf-8').splitlines()]
    require(len(rows) == r['measuredLineCount'], 'Encoded line count differs')
    pending = set(); seen = set(); contexts = 0
    for index, row in enumerate(rows):
        require(row['type'] == 'AI_SUBORDINATE_CHOICE', 'Unexpected synthetic record')
        if index: require(row['eventIndex'] == rows[index-1]['eventIndex']+1, 'Event ordering changed')
        c = row['choice']; key = c['choiceId']
        if c['status'] == 'STARTED':
            require(key not in seen, 'Duplicate start'); seen.add(key); pending.add(key); contexts += 1
            choices.validate_input_context(c['inputContext'], c['chooserPlayerId'], set(range(2 if r['fixture'] == 'SMALL' else 4)), parameters_allowed=choices.parameter_boundary(c))
            choices.input_boundary(c['inputContext'], c['boundary'], c['stage'], c)
            choices.input_envelope(c['inputContext'], row)
        else:
            require(c['status'] == 'COMPLETED' and key in pending, 'Original callback result/lifecycle changed'); pending.remove(key)
    require(not pending and contexts == (0 if r['mode'] == 'WRITER_FAILURE' else r['iterations']), 'Delivered lifecycles differ')
    if r['mode'] in ('NUMBER', 'MAX_X', 'DAMAGE'):
        require('α'.encode() in interval and '🧪'.encode() in interval, 'Unicode path was not exercised')


def validate_matrix(rows, profiled, smoke=False):
    keys = [(r['fixture'],r['mode'],r['round']) for r in rows]
    rounds = {r['round'] for r in rows}
    require(rounds == set(range(len(rounds))) and len(rounds) >= (1 if smoke else 4), 'Incomplete rounds')
    require(len(keys) == len(set(keys)) and set(keys) == {(f,m,i) for f in FIXTURES for m in MODES for i in rounds}, 'Incomplete/duplicate pipeline matrix')
    for r in rows: validate_row(r, profiled)
    return set(keys)


def main():
    p = argparse.ArgumentParser(description=__doc__); p.add_argument('directory', type=Path); p.add_argument('--output', type=Path, required=True); p.add_argument('--smoke', action='store_true'); args = p.parse_args()
    here = Path(__file__).resolve().parent
    spec = importlib.util.spec_from_file_location('pipeline_ordinary_reader', here.parent/'export.py'); reader = importlib.util.module_from_spec(spec); spec.loader.exec_module(reader)
    groups = defaultdict(list); matrices = {}
    for label in ('control', 'profiled'):
        rows = json.loads((args.directory/label/'measurements.json').read_text())
        matrices[label] = validate_matrix(rows, label == 'profiled', args.smoke)
        for r in rows:
            validate_row(r, label == 'profiled'); validate_file(r, reader._choices); groups[r['fixture'],r['mode'],label].append(r)
    require(matrices['control'] == matrices['profiled'], 'Control/profile matrices differ')
    out = dict(scope='SYNTHETIC_PIPELINE_COSTS_NOT_PLAYED_GAMES', smoke=args.smoke, trainingReady=False, groups=[])
    for fixture in sorted(FIXTURES):
        for mode in sorted(MODES):
            both = [r for label in ('control','profiled') for r in groups[fixture,mode,label]]
            require(len({r['policySha256'] for r in both}) == 1, 'Represented policy differs across rounds/control')
            item = dict(fixture=fixture, mode=mode, status='EXPECTED_FAILURE' if mode.endswith('FAILURE') else 'EXHAUSTED' if mode=='EXHAUSTED' else 'HEALTHY', costs={})
            for label in ('control','profiled'):
                rows = groups[fixture,mode,label]
                item['costs'][label] = {k:dict(median=median(v), minimum=min(v), maximum=max(v), rounds=v) for k,v in
                    ((k,[r[k]/r['iterations'] for r in rows]) for k in ('cpuNanos','allocatedBytes','measuredFileBytes'))}
            out['groups'].append(item)
    args.output.write_text(json.dumps(out,indent=2)+'\n'); print('Validated real UTF-8 files, source/phase receipts and explicit failure scopes; played games: zero')


if __name__ == '__main__': main()
