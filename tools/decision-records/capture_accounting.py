"""Capture-method calls in retained parent records; no game-wide callback denominator."""
from collections import Counter

FAMILIES = {
    'target': ('targetCaptureAccounting', 'RECORDING_PARENT_TARGET_CAPTURE_CALLS', 'subordinateChoices', 'subordinateChoiceOmissions'),
    'search': ('searchCaptureAccounting', 'RECORDING_PARENT_SEARCH_CAPTURE_CALLS', 'searchTargetApplications', 'searchTargetApplicationOmissions'),
}
FIELDS = ('entered', 'captured', 'unsupported', 'budget', 'interrupted', 'failed', 'finished', 'inputUnavailable')


def require(ok, message):
    if not ok: raise ValueError('capture accounting: ' + message)


def integer(value):
    return isinstance(value, int) and not isinstance(value, bool) and 0 <= value <= (1 << 63)-1


def validate(decision):
    for family in FAMILIES: validate_family(decision, family)


def validate_family(decision, family):
    field, scope, rows_field, omissions_field = FAMILIES[family]
    if field not in decision: return
    value = decision[field]
    require(isinstance(value, dict) and set(value) == {'schemaVersion', 'scope', 'cutoff', 'closed', 'overflowed', 'counts'}, 'invalid envelope')
    require(integer(value['schemaVersion']) and value['schemaVersion'] == 1 and value['scope'] == scope
            and value['cutoff'] == 'PARENT_CAPTURE_CLOSED', 'unsupported scope/version')
    require(value['closed'] is True and isinstance(value['overflowed'], bool), 'unclosed ledger')
    c = value['counts']
    require(isinstance(c, dict) and set(c) == set(FIELDS) and all(integer(c[k]) for k in FIELDS), 'invalid counts')
    rows = decision.get(rows_field, [])
    require(isinstance(rows, list) and len(rows) <= 128 and c['captured'] == len(rows), 'captured count differs from retained rows')
    unavailable = sum(isinstance(row, dict) and isinstance(row.get('inputContext'), dict)
                      and row['inputContext'].get('status') == 'UNAVAILABLE' for row in rows)
    require(c['inputUnavailable'] == unavailable, 'unavailable count differs from retained rows')
    terminal_rows = sum(isinstance(row, dict) and row.get('status') != 'INCOMPLETE' for row in rows)
    require(terminal_rows <= c['finished'], 'selection result precedes capture-method exit')
    require(all(c[key] <= c['entered'] for key in FIELDS if key != 'entered'), 'counter exceeds entered calls')
    if value['overflowed']: return
    outcomes = sum(c[k] for k in ('captured', 'unsupported', 'budget', 'interrupted', 'failed'))
    require(outcomes <= c['entered'] and c['finished'] <= c['entered'], 'invalid capture balance')
    if c['finished'] == c['entered']: require(outcomes == c['entered'], 'unreconciled exited calls')
    omissions = decision.get(omissions_field)
    if isinstance(omissions, dict):
        for name in ('budget', 'interrupted'):
            require(integer(omissions.get(name)), 'invalid legacy omission count')
        require(omissions['budget'] == c['budget'], 'budget omissions differ')
        # Search legacy metadata also counts result-stage interruption on an already retained row.
        result_interruptions = sum(isinstance(row, dict) and row.get('status') == 'CAPTURE_INTERRUPTED' for row in rows) if family == 'search' else 0
        require(omissions['interrupted'] <= c['interrupted'] + c['entered'] - c['finished'] + result_interruptions,
                'interruption omissions exceed declared capture work and retained result interruptions')


class Aggregate:
    def __init__(self, family='target'):
        self.field, self.scope, _, _ = FAMILIES[family]
        self.present = self.absent = 0
        self.overflow = False
        self.counts = Counter()

    def observe(self, decision):
        value = decision.get(self.field)
        if value is None: self.absent += 1; return
        self.present += 1; self.overflow |= value['overflowed']; self.counts.update(value['counts'])

    def summary(self):
        if not self.present: return None
        c = self.counts
        missing = None if self.overflow else c['entered'] - c['captured']
        return dict(schemaVersion=1, scope=self.scope, population='RETAINED_PARENT_RECORDS',
                    parentsPresent=self.present, parentsAbsent=self.absent, overflowed=self.overflow, counts=dict(c),
                    knownMissingRowsAtParentCutoffs=missing,
                    captureCallsPendingAtParentCutoffs=None if self.overflow else c['entered']-c['finished'],
                    missingRowFractionForRetainedParents=missing/c['entered'] if not self.absent and not self.overflow and c['entered'] else None,
                    gameWideNestedInvocationCount=None, callbackOutcomeCounts=None, missingParentInvocationCount=None)
