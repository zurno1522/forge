"""Original-AI standalone callback counts; these are not unique gameplay decisions."""
from collections import Counter

FIELDS = ('entered', 'prepared', 'omitted', 'callbackStarted', 'callbackReturned', 'callbackThrew', 'inputUnavailable',
          'startAttempts', 'startReturned', 'terminalAttempts', 'terminalReturned', 'finished', 'observationFailures',
          'omittedUnsupported', 'omittedClosed', 'omittedFailure')
MAX_LONG = (1 << 63) - 1
SCOPE = 'ORIGINAL_AI_STANDALONE_CALLBACKS'


def require(ok, message):
    if not ok: raise ValueError('observation accounting: ' + message)


def integer(value):
    return isinstance(value, int) and not isinstance(value, bool) and 0 <= value <= MAX_LONG


def validate(metadata, finished, game_count, starts, terminals, boundaries):
    declared = metadata.get('observationAccountingSchemaVersion')
    value = finished.get('observationAccounting')
    if declared is None:
        require(value is None, 'undeclared capability')
        return
    require(integer(declared) and declared == 1, 'unsupported capability')
    require(metadata.get('choiceEventSchemaVersion') == 1, 'standalone protocol is not declared')
    require(isinstance(value, dict) and set(value) == {'schemaVersion', 'scope', 'cutoff', 'gamesAccounted', 'counts'}, 'invalid envelope')
    require(integer(value['schemaVersion']) and value['schemaVersion'] == 1 and value['scope'] == SCOPE
            and value['cutoff'] == 'GAME_CAPTURE_CLOSED', 'unsupported scope or version')
    require(integer(value['gamesAccounted']) and value['gamesAccounted'] <= game_count, 'invalid game count')
    counts = value['counts']
    require(isinstance(counts, dict) and set(counts) == {'closed', 'overflowed', 'scopeComplete', 'boundaries', 'collectorStarts', 'collectorTerminals'}, 'invalid counts')
    require(counts['closed'] is True and all(isinstance(counts[key], bool) for key in ('overflowed', 'scopeComplete')), 'unclosed or invalid ledger')
    require(integer(counts['collectorStarts']) and integer(counts['collectorTerminals']), 'invalid collector counts')
    cells = counts['boundaries']
    require(isinstance(cells, dict) and set(cells) <= boundaries | {'UNCLASSIFIED'}, 'unbounded or unknown boundary')
    totals = Counter()
    for row in cells.values():
        require(isinstance(row, dict) and set(row) == set(FIELDS) and all(integer(row[key]) for key in FIELDS), 'invalid cell')
        require(row['entered'] > 0, 'empty cell')
        totals.update(row)
        if counts['overflowed']: continue
        require(row['prepared'] + row['omitted'] <= row['entered'] and row['finished'] <= row['entered'], 'invalid capture balance')
        require(row['omitted'] == sum(row[key] for key in ('omittedUnsupported', 'omittedClosed', 'omittedFailure')), 'omission reasons do not reconcile')
        require(row['callbackReturned'] + row['callbackThrew'] <= row['callbackStarted'] <= row['entered'], 'invalid callback balance')
        require(row['startReturned'] <= row['startAttempts'] <= row['prepared']
                and row['terminalReturned'] <= row['terminalAttempts'] <= row['startReturned'], 'invalid publication balance')
        require(row['inputUnavailable'] <= row['entered'], 'invalid input omission count')
        if row['finished'] == row['entered']:
            require(row['prepared'] + row['omitted'] == row['entered']
                    and row['callbackReturned'] + row['callbackThrew'] == row['callbackStarted'], 'unreconciled completed invocations')
    if counts['scopeComplete']:
        require(value['gamesAccounted'] == game_count, 'complete scope has missing games')
        if not counts['overflowed']:
            require(counts['collectorStarts'] <= totals['startAttempts']
                    and counts['collectorTerminals'] <= totals['terminalAttempts'], 'collector observations exceed declared attempts')
    if value['gamesAccounted'] == game_count and not counts['overflowed']:
        require((counts['collectorStarts'], counts['collectorTerminals']) == (starts, terminals), 'collector counts differ from the stream')


def summarize(runs):
    totals = Counter(); receipts = Counter(); by_boundary = {}; present = 0; complete = True; overflow = False
    for run in runs:
        value = run['finished'].get('observationAccounting')
        if value is None: complete = False; continue
        present += 1; counts = value['counts']
        complete &= counts['scopeComplete'] and value['gamesAccounted'] == len(run['games'])
        overflow |= counts['overflowed']
        receipts.update({key: counts[key] for key in ('collectorStarts', 'collectorTerminals')})
        for name, row in counts['boundaries'].items():
            totals.update(row); by_boundary.setdefault(name, Counter()).update(row)
    if not present: return None
    exact = complete and not overflow
    missing = totals['entered'] - receipts['collectorStarts'] if exact else None
    return dict(schemaVersion=1, scope=SCOPE, runsPresent=present, runsAbsent=len(runs)-present,
                scopeComplete=complete, overflowed=overflow, counts=dict(totals), collector=dict(receipts),
                byBoundary={name: dict(row) for name, row in by_boundary.items()},
                invocationsInFlightAtCutoffs=None if overflow else totals['entered']-totals['finished'],
                callbacksInFlightAtCutoffs=None if overflow else totals['callbackStarted']-totals['callbackReturned']-totals['callbackThrew'],
                startsMissingAtCutoffs=missing, startRecordMissingFractionAtCutoffs=missing/totals['entered'] if exact and totals['entered'] else None,
                nestedInvocationCount=None, uninstrumentedDecisionCount=None)
