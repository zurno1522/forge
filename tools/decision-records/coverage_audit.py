"""Coverage of observed records, never an estimate of uninstrumented decisions."""
from collections import Counter


def counts():
    return dict(records=0, contextStatuses=Counter(), inputBoundaries=Counter(),
                timings=Counter(), stateBases=Counter(), components=Counter(), omissionReasons=Counter(),
                omissionComponents=Counter(), optionBindings=Counter(),
                captureOmissionMetadata=Counter(), knownUnrecordedCaptures=Counter())


def add(count, context, audited, parent_omissions=None):
    count['records'] += 1
    count['inputBoundaries']['KNOWN_INPUT_BOUNDARY_V1' if audited else 'NO_ESTABLISHED_INPUT_BOUNDARY_V1'] += 1
    count['contextStatuses']['ABSENT' if context is None else context['status']] += 1
    count['optionBindings']['NO_CONTEXT' if context is None else 'DIAGNOSTIC_ONLY_V' + str(context['schemaVersion'])] += 1
    for kind, metadata in (parent_omissions or {}).items():
        count['captureOmissionMetadata'][kind + (':UNAVAILABLE' if metadata is None else ':DECLARED')] += 1
        if metadata is not None:
            for reason, value in metadata.items():
                count['knownUnrecordedCaptures'][kind + ':' + reason] += value
    if context is None:
        return
    count['timings'][context['timing']] += 1
    count['stateBases'][context['stateBasis']] += 1
    for component, value in context['coverage'].items():
        count['components'][component + ':' + value] += 1
    # Reasons count affected records; component/reason pairs count declarations.
    count['omissionReasons'].update(sorted({item['reason'] for item in context['omissions']}))
    count['omissionComponents'].update(sorted({item['component'] + ':' + item['reason'] for item in context['omissions']}))


class CoverageAudit:
    def __init__(self, boundary_groups, turn_groups, audited_boundaries):
        self.boundaries = boundary_groups
        self.turns = turn_groups
        self.audited = audited_boundaries
        self.total = counts()
        self.owners = Counter()

    def observe(self, owner, boundary, context, *, run_id, game, turn, player, turn_basis, parent_omissions=None):
        audited = owner in ('DECISION', 'CHOICE') and boundary in self.audited
        self.owners[owner] += 1
        add(self.total, context, audited, parent_omissions)
        self._group(self.boundaries, (owner, boundary), dict(owner=owner, boundary=boundary), context, audited, parent_omissions)
        key = (run_id, game, turn, player, turn_basis)
        self._group(self.turns, key, dict(runId=run_id, game=game, turn=turn, playerId=player,
                                        turnBasis=turn_basis), context, audited, parent_omissions)

    @staticmethod
    def _group(store, key, identity, context, audited, parent_omissions):
        row = store.get(key)
        if row is None:
            row = dict(identity, counts=counts())
        add(row['counts'], context, audited, parent_omissions)
        store[key] = row

    def summary(self, sequence):
        return dict(schemaVersion=1, scope='OBSERVED_RECORDS_ONLY',
                    knownInputBoundaryInventoryV1=dict(self.audited),
                    definitions=dict(
                        records='Parent decisions, standalone STARTED choices, nested target records and search operations; overlapping observations, not unique gameplay decisions. Terminals are not counted again. Includes incomplete, censored and post-outcome records; no learning eligibility claim.',
                        contextStatuses='One status per observed record; ABSENT means no inputContext. CAPTURED refers only to its named partial profile.',
                        inputBoundaries='Current version-1 audited boundary inventory for parent/standalone owners, not proof that a legacy producer captured it or every branch selected an option. A missing group means no observed record, not no choices.',
                        components='Declarations per present context, including unsupported stack/history/options. Missing contexts supply no component declarations.',
                        omissionReasons='Records carrying each explicit reason; one record can have multiple reasons. Component/reason pairs are also deduplicated within a record.',
                        optionBindings='Supported profiles have diagnostic options only; no model-facing selectable-option bindings are established. This does not describe diagnostic joins or target provenance.',
                        knownUnrecordedCaptures='Explicit parent target/search capture-omission counts, separate from observed-record denominators. Missing declarations mean unknown, not zero. Actual missing selector boundaries/times are unknown; attribution is to the parent container only.',
                        byTurn='Parent baseline or choice-start turn where available; nested/search records use their parent container, not an independently captured selector time. Null turn means unknown.'),
                    observedOwners=dict(self.owners), totals=self.total,
                    unobservedDecisionCount=None, completeLearningExampleCount=None,
                    byBoundary=sequence('audit_boundaries', self.boundaries.values()),
                    byTurn=sequence('audit_turns', self.turns.values()))


def markdown(audit):
    yield '\n## Observed input coverage audit\n\n'
    yield (f"Denominator: {audit['totals']['records']} observed records across parent decisions, standalone starts, "
           'nested target records and search operations. These overlap; they are not unique gameplay decisions. '
           'Unobserved decision count and complete learning-example count are unknown.\n\n')
    yield ('CAPTURED means the named partial profile was captured. Components are declarations per present context. '
           'Omission reasons count affected records and can overlap. Option bindings remain diagnostic-only. '
           'A known input boundary is the current audit inventory, not proof of fresh selection on every branch.\n\n')
    columns = '| Records | Absent | Captured | Partial | Unavailable | Unsupported reason | Truncated reason | Budget reason | Interrupted reason |\n'
    def values(row):
        c = row['counts']; s = c['contextStatuses']; reasons = c['omissionReasons']
        return ' | '.join(str(value) for value in (c['records'], *(s.get(k, 0) for k in ('ABSENT', 'CAPTURED', 'PARTIAL', 'UNAVAILABLE')),
                         *(reasons.get(k, 0) for k in ('UNSUPPORTED', 'OUTSIDE_CAPTURED_PREFIX', 'BUDGET_EXHAUSTED', 'INTERRUPTED'))))
    yield '| Owner | Boundary ' + columns
    yield '| --- | --- |' + ' ---: |' * 9 + '\n'
    for row in audit['byBoundary']:
        yield f"| {row['owner']} | {row['boundary']} | {values(row)} |\n"
    yield '\n| Run | Game | Turn | Player | Turn basis ' + columns
    yield '| --- | ---: | ---: | ---: | --- |' + ' ---: |' * 9 + '\n'
    for row in audit['byTurn']:
        run = str(row['runId']).replace('|', '\\|').replace('\n', ' ')
        turn = 'UNKNOWN' if row['turn'] is None else row['turn']
        yield f"| {run} | {row['game']} | {turn} | {row['playerId']} | {row['turnBasis']} | {values(row)} |\n"
    yield ('\nNested/search turn groups describe the parent container. Detailed component and omission counts are in coverage.json. '
           'Explicit parent target/search capture omissions are counted separately as knownUnrecordedCaptures, '
           'with captureOmissionMetadata distinguishing declarations from unavailable counts; lost selector boundaries/times are unknown.\n')
