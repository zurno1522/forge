"""Development-only availability marginals from an exported, validated coverage audit."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

STATUSES = {'ABSENT', 'CAPTURED', 'PARTIAL', 'UNAVAILABLE'}
BASES = {'ENTRY_LOCATION', 'ENTRY_LOCATION_UNAVAILABLE', 'PARENT_BASELINE', 'CHOICE_START_EVENT', 'PARENT_CONTAINER'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def count(value):
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def check_counts(value):
    require(isinstance(value, dict) and count(value.get('records')), 'Invalid record count')
    for key in ('contextStatuses', 'omissionReasons', 'knownUnrecordedCaptures'):
        require(isinstance(value.get(key), dict) and all(isinstance(k, str) and count(v) for k, v in value[key].items()), 'Invalid count map: ' + key)
    statuses = value['contextStatuses']
    require(set(statuses) <= STATUSES and sum(statuses.values()) == value['records'], 'Status counts do not partition records')
    present = value['records'] - statuses.get('ABSENT', 0)
    require(all(v <= present for v in value['omissionReasons'].values()), 'Reason count exceeds present contexts')


def merge(rows):
    result = dict(records=0, contextStatuses=Counter(), omissionReasons=Counter(), knownUnrecordedCaptures=Counter())
    for row in rows:
        result['records'] += row['records']
        for key in ('contextStatuses', 'omissionReasons', 'knownUnrecordedCaptures'):
            result[key].update(row[key])
    return result


def normalized(value):
    return {k: v if k == 'records' else {a: b for a, b in v.items() if b} for k, v in value.items()
            if k in ('records', 'contextStatuses', 'omissionReasons', 'knownUnrecordedCaptures')}


def measures(value):
    statuses = value['contextStatuses']; records = value['records']
    present = records - statuses.get('ABSENT', 0)
    available = statuses.get('CAPTURED', 0) + statuses.get('PARTIAL', 0)
    budget = value['omissionReasons'].get('BUDGET_EXHAUSTED', 0)
    return dict(observedRecords=records, presentContexts=present, profileViewsAvailable=available,
                contextStatuses=dict(statuses), budgetAffectedRecords=budget,
                profileViewFractionOfObservedRecords=available / records if records else None,
                budgetReasonFractionOfPresentContexts=budget / present if present else None,
                omissionReasons=dict(value['omissionReasons']),
                knownUnrecordedParentCaptures=dict(value['knownUnrecordedCaptures']))


def summarize(coverage, window=10):
    require(count(window) and window > 0, 'Turn window must be a positive integer')
    audit = coverage.get('inputCoverageAudit', {})
    require(count(audit.get('schemaVersion')) and audit['schemaVersion'] == 1 and audit.get('scope') == 'OBSERVED_RECORDS_ONLY', 'Unsupported audit scope')
    check_counts(audit.get('totals'))
    turns = audit.get('byTurn'); boundaries = audit.get('byBoundary')
    require(isinstance(turns, list) and isinstance(boundaries, list), 'Missing audit marginals')
    players, windows, seen = {}, {}, set()
    for row in turns:
        check_counts(row['counts'])
        require(isinstance(row.get('runId'), str) and count(row.get('game')), 'Invalid run/game identity')
        require(row.get('playerId') is None or count(row['playerId']), 'Invalid chooser identity')
        require(row.get('turn') is None or count(row['turn']), 'Invalid turn')
        require(row.get('turnBasis') in BASES, 'Unknown turn basis')
        require((row['turnBasis'] != 'ENTRY_LOCATION_UNAVAILABLE') or row['turn'] is None, 'Unavailable entry has a turn')
        identity = (row['runId'], row['game'], row['playerId'])
        key = (*identity, row['turnBasis'], row['turn'])
        require(key not in seen, 'Duplicate turn marginal'); seen.add(key)
        players.setdefault(identity, []).append(row)
        first = None if row['turn'] is None else row['turn'] // window * window
        windows.setdefault((*identity, row['turnBasis'], first), []).append(row['counts'])
    boundary_keys = set()
    for row in boundaries:
        check_counts(row['counts'])
        key = (row['owner'], row['boundary'])
        require(key not in boundary_keys, 'Duplicate boundary marginal'); boundary_keys.add(key)
    for rows in (turns, boundaries):
        require(normalized(merge(r['counts'] for r in rows)) == normalized(audit['totals']), 'Audit marginals do not reconcile')
    by_player = []
    for (run, game, player), rows in players.items():
        firsts = {}; unknown = 0
        for row in rows:
            budget = row['counts']['omissionReasons'].get('BUDGET_EXHAUSTED', 0)
            if not budget:
                continue
            if row['turn'] is None:
                unknown += budget
            else:
                basis = row['turnBasis']; firsts[basis] = min(firsts.get(basis, row['turn']), row['turn'])
        by_player.append(dict(runId=run, game=game, playerId=player,
                              measures=measures(merge(r['counts'] for r in rows)),
                              earliestObservedBudgetTurnByBasis=firsts, unknownTurnBudgetAffectedRecords=unknown))
    by_window = [dict(runId=run, game=game, playerId=player, turnBasis=basis, turnFrom=first,
                      turnThrough=None if first is None else first + window - 1, measures=measures(merge(rows)))
                 for (run, game, player, basis, first), rows in windows.items()]
    return dict(schemaVersion=1, scope='OBSERVED_RECORD_AVAILABILITY_MARGINALS', turnWindow=window,
                totals=measures(audit['totals']), byPlayer=by_player, byTurnWindow=by_window,
                byBoundary=[dict(owner=r['owner'], boundary=r['boundary'], measures=measures(r['counts'])) for r in boundaries],
                sourceInvocationAccounting=coverage.get('observationAccounting'),
                runExecutions=coverage.get('runsSummary'), trainingReady=False,
                limitations=[
                    'Captured/partial means the named partial profile is available, not a complete decision input.',
                    'Observed records overlap; unavailable input, absent context and missing lifecycle delivery are distinct.',
                    'Parent-container turns are not independent selector clocks. Earliest observed budget turns are not exact exhaustion times.',
                    'Player and boundary marginals do not provide their joint distribution. Null turn/player identities stay unknown.',
                    'Known parent capture omissions have a separate denominator. Uninstrumented decision totals remain unknown.',
                    'No sampling policy, per-context work debits, counterfactual late-view costs, CPU overhead or inclusion probabilities can be inferred from these counts.'
                ])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('coverage', type=Path)
    parser.add_argument('--turn-window', type=int, default=10)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    raw = args.coverage.read_bytes()
    result = summarize(json.loads(raw), args.turn_window)
    result['source'] = dict(path=str(args.coverage), sha256=hashlib.sha256(raw).hexdigest())
    args.output.write_text(json.dumps(result, indent=2) + '\n')


if __name__ == '__main__':
    main()
