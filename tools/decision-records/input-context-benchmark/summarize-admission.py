"""Validate isolated producer admission trials; never infer missing-view costs from traces."""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import re
from statistics import median

CONFIG = {'SMALL2': (2, 4, False, False), 'PUBLIC4': (4, 18, False, True), 'GROWING4': (4, 18, True, True)}
POLICIES = [('PREFIX', 1, 0), *(('STRIDE', 4, i) for i in range(4)), *(('HASH', d, i) for d in (4, 8) for i in (0, 1)), ('RESERVE', 1, 0)]
FAMILIES = ('DAMAGE', 'DRAW', 'NUMBER', 'ENTITY')
BOUNDARIES = dict(zip(FAMILIES, ('DAMAGE_CAN_PLAY_POLICY', 'DRAW_AMOUNT_TARGET_POLICY', 'CONTROLLER_NUMBER_RANGE', 'CONTROLLER_EFFECT_ENTITY')))
CATEGORIES = {'AVAILABLE', 'BUDGET', 'EXPERIMENT_SAMPLED', 'EXPERIMENT_RESERVED', 'RESERVE_ATTEMPT_FAILED'}
NORMAL_FIELDS = ('entered', 'prepared', 'callbackStarted', 'callbackReturned', 'startAttempts', 'startReturned', 'terminalAttempts', 'terminalReturned', 'finished')
ZERO_FIELDS = ('omitted', 'callbackThrew', 'observationFailures', 'omittedUnsupported', 'omittedClosed', 'omittedFailure')
QUALITY = ('rulesStateFacts', 'rulesStateProfiles', 'partialProfiles', 'representedCards', 'identityKnownCards', 'disclosedFacts', 'linkedFacts')
MASK = (1 << 64) - 1


def require(condition, message):
    if not condition:
        raise ValueError(message)


def natural(value):
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def mix(z):
    z = ((z ^ (z >> 30)) * 0xbf58476d1ce4e5b9) & MASK
    z = ((z ^ (z >> 27)) * 0x94d049bb133111eb) & MASK
    return z ^ (z >> 31)


def admits(policy, denominator, salt, ordinal):
    if policy == 'STRIDE':
        return (ordinal - 1) % denominator == salt
    if policy == 'HASH':
        # The controlled fixture chooses original player ID 1, declared by the probe construction.
        return (mix(ordinal ^ mix(salt + 1)) & (denominator - 1)) == 0
    return True


def validate_row(row):
    require(row.get('profile') == 'PERMITTED_CHOICE_VIEW_V28', 'Unexpected producer profile')
    workload = row.get('workload'); require(workload in CONFIG, 'Unknown fixture')
    require(tuple(row.get(k) for k in ('players', 'initialBoardPerPlayer', 'growsAtTurn31', 'disclosures')) == CONFIG[workload], 'Fixture dimensions changed')
    require(type(row.get('overlay')) is bool and row.get('cycles') in (60, 600) and natural(row.get('round')), 'Invalid trial declaration')
    require(type(row.get('growsAtTurn31')) is bool and type(row.get('disclosures')) is bool, 'Invalid fixture flags')
    require(natural(row.get('denominator')) and natural(row.get('salt')), 'Invalid admission scalars')
    policy = (row.get('policy'), row.get('denominator'), row.get('salt'))
    require(policy in POLICIES, 'Unknown admission declaration')
    require(row['overlay'] or policy == ('PREFIX', 1, 0), 'Production control uses a candidate')
    for k in ('share', 'funded', 'held', 'remaining', 'captureWork', 'disclosureWork', 'callbacks', 'checksum', 'starts', 'terminals', 'gateCalls', 'cpuNanos', 'allocatedBytes', 'writerCharacters'):
        require(natural(row.get(k)), 'Invalid metric: ' + k)
    n = row['cycles'] * 4; share = 256000 // row['players']
    require(row['share'] == share and row['funded'] + row['held'] == share, 'Allowance changed')
    held = share // 2 if policy[0] == 'RESERVE' and row['cycles'] == 60 else 0
    require(row['held'] == held and row['captureWork'] + row['disclosureWork'] + row['remaining'] == row['funded'], 'Work/funding does not reconcile')
    require(row['inactiveRemaining'] == [share] * (row['players'] - 1), 'Player shares borrowed')
    require(all(row[k] == n for k in ('callbacks', 'starts', 'terminals')) and row['checksum'] == row['cycles'] * 44, 'Native callbacks changed')
    require(row['gateCalls'] == (n if row['overlay'] else 0), 'Overlay gate count changed')
    require(row['writerCharacters'] > 0 and row['cpuNanos'] > 0 and row['allocatedBytes'] > 0, 'Missing cost evidence')
    require(row['disclosures'] or row['disclosureWork'] == 0, 'Unexpected disclosure debit')
    seen = defaultdict(Counter); groups = row.get('groups'); require(isinstance(groups, dict), 'Missing strata')
    work = cpu = allocation = 0; failures = 0
    for key, metrics in groups.items():
        parts = key.split(':'); require(len(parts) == 3 and parts[0].isdigit(), 'Invalid stratum')
        window, family, category = int(parts[0]), parts[1], parts[2]
        require(window < row['cycles'] // 100 + (1 if row['cycles'] % 100 else 0) and family in FAMILIES and category in CATEGORIES, 'Unknown stratum')
        require(set(metrics) == {'records', 'work', 'entryCpuNanos', 'entryAllocatedBytes', 'representedZones', 'omissionReasons', *QUALITY}
                and all(natural(metrics[k]) for k in ('records', 'work', 'entryCpuNanos', 'entryAllocatedBytes', *QUALITY)) and metrics['records'] > 0, 'Invalid stratum metrics')
        for field in ('representedZones', 'omissionReasons'):
            require(isinstance(metrics[field], dict) and all(isinstance(k,str) and natural(v) for k,v in metrics[field].items()), 'Invalid profile-quality map')
        require(sum(metrics['representedZones'].values()) == metrics['representedCards'] and metrics['identityKnownCards'] <= metrics['representedCards'] <= 128*metrics['records'], 'Invalid represented-card evidence')
        require(metrics['linkedFacts'] <= metrics['disclosedFacts'] <= 32*metrics['records'], 'Invalid disclosure evidence')
        require(all(v <= metrics['records'] for v in metrics['omissionReasons'].values()), 'Invalid omission denominator')
        if category == 'AVAILABLE':
            require(metrics['rulesStateProfiles'] <= metrics['records'] and metrics['rulesStateFacts'] <= min(metrics['disclosedFacts'],32*metrics['rulesStateProfiles'])
                    and metrics['disclosedFacts']-metrics['rulesStateFacts'] <= 32*(metrics['records']-metrics['rulesStateProfiles']), 'Invalid state-basis evidence')
            if row['disclosures'] and (not row['growsAtTurn31'] or window < 3):
                require(metrics['linkedFacts'] == metrics['rulesStateFacts'], 'Public proof fixture lost represented bindings')
            require(max(metrics['omissionReasons'].values(),default=0) <= metrics['partialProfiles'] <= min(metrics['records'],sum(metrics['omissionReasons'].values())), 'Partial profiles and omissions differ')
        else:
            require(not any(metrics[k] for k in QUALITY) and not metrics['representedZones'], 'Unavailable input invented represented state')
        if category in ('EXPERIMENT_SAMPLED', 'EXPERIMENT_RESERVED'):
            require(metrics['work'] == 0, 'Skipped traversal spent work')
        if category == 'AVAILABLE':
            require(metrics['work'] >= metrics['records'], 'Available capture without work')
        if category in ('EXPERIMENT_RESERVED', 'RESERVE_ATTEMPT_FAILED'):
            require(policy[0] == 'RESERVE' and window < 3, 'Reservation escaped its horizon')
        if category == 'RESERVE_ATTEMPT_FAILED':
            failures += metrics['records']; require(metrics['work'] > 0, 'Uncharged failed reservation capture')
        seen[window, family][category] += metrics['records']; work += metrics['work']; cpu += metrics['entryCpuNanos']; allocation += metrics['entryAllocatedBytes']
    require(failures <= 1, 'Repeated early-tranche failures')
    require(work == row['captureWork'] and cpu <= row['cpuNanos'] and allocation <= row['allocatedBytes'], 'Stratum costs do not reconcile')
    expected = defaultdict(Counter)
    for cycle in range(row['cycles']):
        for slot, family in enumerate(FAMILIES):
            expected[cycle // 100, family]['all'] += 1
            expected[cycle // 100, family]['sampled'] += not admits(*policy, cycle * 4 + slot + 1)
    require(set(seen) == set(expected), 'Missing turn/family stratum')
    for key, counts in expected.items():
        require(sum(seen[key].values()) == counts['all'] and seen[key]['EXPERIMENT_SAMPLED'] == counts['sampled'], 'Admission/slot counts differ from declared rule')
    account = row.get('accounting', {})
    require(account.get('scopeComplete') is True and account.get('overflowed') is False and account.get('closed') is False, 'Incomplete source accounting')
    require(all(natural(account.get(k)) for k in ('collectorStarts','collectorTerminals')) and account.get('collectorStarts') == account.get('collectorTerminals') == 0, 'Unexpected collector in emission-only probe')
    require(set(account.get('boundaries', {})) == set(BOUNDARIES.values()), 'Wrong source boundaries')
    for family, boundary in BOUNDARIES.items():
        c = account['boundaries'][boundary]
        require(set(c) == set(NORMAL_FIELDS + ZERO_FIELDS + ('inputUnavailable',)) and all(natural(v) for v in c.values()), 'Invalid source counters')
        unavailable = sum(v for (window, f), counts in seen.items() if f == family for category, v in counts.items() if category != 'AVAILABLE')
        require(all(c[k] == row['cycles'] for k in NORMAL_FIELDS) and all(c[k] == 0 for k in ZERO_FIELDS) and c['inputUnavailable'] == unavailable, 'Source/lifecycle counts differ')
    hashes = row.get('policyHashes'); require(isinstance(hashes, dict), 'Missing policy hashes')
    available_families = {family for (window, family), counts in seen.items() if counts['AVAILABLE']}
    require({k.split(':')[0] for k in hashes} == available_families and all(isinstance(v, str) and re.fullmatch('[0-9a-f]{64}', v) for v in hashes.values()), 'Invalid available-policy evidence')
    return row


def structural(row):
    return {k: ({g: {m: v for m, v in values.items() if m not in ('entryCpuNanos', 'entryAllocatedBytes')} for g, values in value.items()} if k == 'groups' else value)
            for k, value in row.items() if k not in ('overlay', 'gateCalls', 'round', 'cpuNanos', 'allocatedBytes', 'writerCharacters')}


def compare_prefix(production, overlay):
    require(structural(production) == structural(overlay), 'Overlay prefix differs from production')


def validate_trials(rows, overlay):
    require(isinstance(rows, list) and rows, 'Missing trials')
    for row in rows:
        validate_row(row); require(row['overlay'] == overlay, 'Wrong JVM declaration')
    rounds = {r['round'] for r in rows}
    require(len(rounds) >= 3 and rounds == set(range(len(rounds))), 'Missing measured rounds')
    keys = [(r['workload'], r['cycles'], r['policy'], r['denominator'], r['salt'], r['round']) for r in rows]
    expected = {(w, c, *p, i) for w in CONFIG for c in (60, 600) for p in (POLICIES if overlay else POLICIES[:1]) for i in rounds}
    require(len(keys) == len(set(keys)) and set(keys) == expected, 'Missing or duplicate experiment matrix')


def distribution(values):
    return dict(median=median(values), minimum=min(values), maximum=max(values), rounds=values)


def summarize(production, forward, reverse):
    validate_trials(production, False); validate_trials(forward, True); validate_trials(reverse, True)
    require({r['round'] for r in production} == {r['round'] for r in forward} == {r['round'] for r in reverse}, 'Different repetition counts')
    controls = {(r['workload'], r['cycles']): r for r in production}
    for r in production + [r for r in forward + reverse if r['policy'] == 'PREFIX']:
        compare_prefix(controls[r['workload'], r['cycles']], r)
    trials = defaultdict(list)
    for row in forward + reverse:
        trials[row['workload'], row['cycles'], row['policy'], row['denominator'], row['salt']].append(row)
    results = []
    for (workload, cycles, policy, denominator, salt), rows in trials.items():
        reference = structural(rows[0]); require(all(structural(r) == reference for r in rows), 'Non-deterministic fixture/admission evidence')
        windows = defaultdict(Counter); qualities = defaultdict(Counter); zones = defaultdict(Counter); reasons = defaultdict(Counter); entry = defaultdict(list)
        for key, metrics in rows[0]['groups'].items():
            window, family, category = key.split(':'); window=int(window); windows[window][category] += metrics['records']
            if category == 'AVAILABLE':
                qualities[window].update({k:metrics[k] for k in QUALITY}); zones[window].update(metrics['representedZones']); reasons[window].update(metrics['omissionReasons'])
        for category in CATEGORIES:
            values = []
            for r in rows:
                selected = [v for k, v in r['groups'].items() if k.endswith(':' + category)]
                n = sum(v['records'] for v in selected)
                if n:
                    values.append(dict(n=n, cpu=sum(v['entryCpuNanos'] for v in selected)/n, allocation=sum(v['entryAllocatedBytes'] for v in selected)/n))
            if values:
                entry[category] = dict(recordsPerTrial=values[0]['n'], entryCpuMicros=distribution([v['cpu']/1000 for v in values]), entryAllocatedBytes=distribution([v['allocation'] for v in values]))
        results.append(dict(workload=workload, cycles=cycles, policy=policy, denominator=denominator, salt=salt,
                            captureWork=rows[0]['captureWork'], disclosureWork=rows[0]['disclosureWork'], remaining=rows[0]['remaining'], held=rows[0]['held'],
                            windows=[dict(turnFrom=i*10+1, turnThrough=i*10+10, counts=dict(v), availableProfileQuality=dict(qualities[i]), representedZones=dict(zones[i]), availableProfileOmissions=dict(reasons[i])) for i,v in sorted(windows.items())],
                            wholeRunCpuMillis=distribution([r['cpuNanos']/1e6 for r in rows]), wholeRunAllocatedBytes=distribution([r['allocatedBytes'] for r in rows]),
                            writerCharacters=distribution([r['writerCharacters'] for r in rows]), entrySpans=dict(entry)))
    return dict(schemaVersion=1, scope='CONTROLLED_DEVELOPMENT_ADMISSION_EXPERIMENT', profile='PERMITTED_CHOICE_VIEW_V28',
                prefixMatchesProduction=True, originalCallbacksAndRngCheckedByProbe=True, results=results, trainingReady=False,
                limitations=['Controlled repeating callbacks and explicit fixture turns are not production family frequencies or game-turn distributions.',
                             'Experimental omission reasons are not ordinary supported V28 trace records; no learning exports are produced.',
                             'Only current-thread CPU/allocation is measured; metric reads and validation/aggregate bookkeeping perturb execution.',
                             'Entry spans run through START serialization, excluding callbacks/terminals; whole-run spans include those stages, disclosure refresh and clock progression. Initial fixture/disclosure setup and board creation are excluded.',
                             'Character counts use the production streaming writer, without UTF-8 conversion or physical I/O. The collector does not participate.',
                             'Profile-quality totals count represented occurrences and overlapping omission reasons, not complete states or quality scores.',
                             'Policy hashes sample represented inputs; callback assertions do not establish all native gameplay behavior, cancellation or concurrency.',
                             'A finite allowance cannot guarantee sustained input coverage. No independent unbiased inclusion probability or production default is selected.'])


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('directory', type=Path); parser.add_argument('--output', type=Path, required=True); args=parser.parse_args()
    meta=json.loads((args.directory/'metadata.json').read_text()); require(not meta['smoke'], 'Smoke run is not an assessment')
    rows=[json.loads((args.directory/(name+'.json')).read_text()) for name in ('production','forward','reverse')]
    result=summarize(*rows); args.output.write_text(json.dumps(result,indent=2)+'\n')


if __name__ == '__main__':
    main()
