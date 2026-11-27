import copy
import importlib.util
from pathlib import Path
import unittest

path = Path(__file__).with_name('input-context-benchmark') / 'summarize-availability.py'
spec = importlib.util.spec_from_file_location('availability_measurements', path)
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)


def fixture():
    a = dict(records=4, contextStatuses={'PARTIAL': 1, 'CAPTURED': 1, 'UNAVAILABLE': 1, 'ABSENT': 1},
             omissionReasons={'BUDGET_EXHAUSTED': 1, 'UNSUPPORTED': 2}, knownUnrecordedCaptures={'NESTED_TARGET:budget': 4})
    b = dict(records=2, contextStatuses={'UNAVAILABLE': 1, 'ABSENT': 1},
             omissionReasons={'BUDGET_EXHAUSTED': 1, 'UNSUPPORTED': 1}, knownUnrecordedCaptures={})
    total = dict(records=6, contextStatuses={'PARTIAL': 1, 'CAPTURED': 1, 'UNAVAILABLE': 2, 'ABSENT': 2},
                 omissionReasons={'BUDGET_EXHAUSTED': 2, 'UNSUPPORTED': 3}, knownUnrecordedCaptures={'NESTED_TARGET:budget': 4})
    return dict(inputCoverageAudit=dict(schemaVersion=1, scope='OBSERVED_RECORDS_ONLY', totals=total,
        byTurn=[dict(runId='run-a', game=1, playerId=0, turn=12, turnBasis='ENTRY_LOCATION', counts=a),
                dict(runId='run-a', game=1, playerId=0, turn=25, turnBasis='PARENT_CONTAINER', counts=b)],
        byBoundary=[dict(owner='CHOICE', boundary='BOUNDARY_A', counts=copy.deepcopy(a)),
                    dict(owner='NESTED_TARGET', boundary='BOUNDARY_B', counts=copy.deepcopy(b))]))


class AvailabilityMeasurementTests(unittest.TestCase):
    def test_input_availability_uses_observed_and_present_denominators_without_counting_parent_omissions(self):
        result = summary.summarize(fixture()); m = result['totals']
        self.assertEqual(m['observedRecords'], 6)
        self.assertEqual(m['presentContexts'], 4)
        self.assertEqual(m['profileViewsAvailable'], 2)
        self.assertEqual(m['profileViewFractionOfObservedRecords'], 1 / 3)
        self.assertEqual(m['budgetReasonFractionOfPresentContexts'], .5)
        self.assertEqual(m['knownUnrecordedParentCaptures'], {'NESTED_TARGET:budget': 4})
        self.assertIsNone(result['sourceInvocationAccounting'])
        self.assertFalse(result['trainingReady'])

    def test_independent_and_parent_clocks_remain_separate_and_windows_do_not_invent_exhaustion_times(self):
        result = summary.summarize(fixture())
        self.assertEqual(result['byPlayer'][0]['earliestObservedBudgetTurnByBasis'], {'ENTRY_LOCATION': 12, 'PARENT_CONTAINER': 25})
        self.assertEqual([(r['turnBasis'], r['turnFrom'], r['turnThrough']) for r in result['byTurnWindow']],
                         [('ENTRY_LOCATION', 10, 19), ('PARENT_CONTAINER', 20, 29)])
        self.assertNotIn('byPlayerBoundary', result)
        source = fixture(); source['inputCoverageAudit']['byTurn'][0]['turnBasis'] = 'CHOICE_START_EVENT'
        self.assertEqual(summary.summarize(source)['byTurnWindow'][0]['turnBasis'], 'CHOICE_START_EVENT')

    def test_unknown_entry_clock_and_unknown_chooser_do_not_become_zero_or_known_turns(self):
        source = fixture(); row = source['inputCoverageAudit']['byTurn'][1]
        row.update(turn=None, turnBasis='ENTRY_LOCATION_UNAVAILABLE', playerId=None)
        result = summary.summarize(source)
        unknown = next(r for r in result['byPlayer'] if r['playerId'] is None)
        self.assertEqual(unknown['earliestObservedBudgetTurnByBasis'], {})
        self.assertEqual(unknown['unknownTurnBudgetAffectedRecords'], 1)
        self.assertIsNone(result['byTurnWindow'][1]['turnFrom'])
        self.assertIsNone(result['byTurnWindow'][1]['turnThrough'])

    def test_reused_player_ids_in_different_runs_and_games_are_not_merged(self):
        source = fixture(); audit = source['inputCoverageAudit']; original = copy.deepcopy(audit['byTurn'])
        for run, game in [('run-a', 2), ('run-b', 1)]:
            for r in copy.deepcopy(original):
                r.update(runId=run, game=game); audit['byTurn'].append(r)
        for c in [audit['totals'], *(r['counts'] for r in audit['byBoundary'])]:
            c['records'] *= 3
            for key in ('contextStatuses', 'omissionReasons', 'knownUnrecordedCaptures'):
                c[key] = {k: v * 3 for k, v in c[key].items()}
        result = summary.summarize(source)
        self.assertEqual([(r['runId'], r['game'], r['playerId']) for r in result['byPlayer']], [('run-a', 1, 0), ('run-a', 2, 0), ('run-b', 1, 0)])

    def test_empty_and_absent_only_audits_have_unknown_rates_when_denominator_is_zero(self):
        source = fixture(); audit = source['inputCoverageAudit']
        for c in [audit['totals'], *(r['counts'] for r in audit['byTurn'] + audit['byBoundary'])]:
            c['contextStatuses'] = {'ABSENT': c['records']}; c['omissionReasons'] = {}; c['knownUnrecordedCaptures'] = {}
        self.assertIsNone(summary.summarize(source)['totals']['budgetReasonFractionOfPresentContexts'])
        audit.update(byTurn=[], byBoundary=[], totals=dict(records=0, contextStatuses={}, omissionReasons={}, knownUnrecordedCaptures={}))
        result = summary.summarize(source)
        self.assertIsNone(result['totals']['profileViewFractionOfObservedRecords'])
        self.assertEqual(result['byPlayer'], [])

    def test_bad_counts_duplicate_marginals_and_unreconciled_totals_are_rejected(self):
        for defect in ('negative', 'boolean', 'status', 'reason', 'turn-total', 'boundary-total', 'duplicate-turn', 'duplicate-boundary', 'known-omission'):
            source = fixture(); audit = source['inputCoverageAudit']; c = audit['byTurn'][0]['counts']
            if defect == 'negative': c['omissionReasons']['UNSUPPORTED'] = -1
            if defect == 'boolean': c['records'] = True
            if defect == 'status': c['contextStatuses']['PARTIAL'] += 1
            if defect == 'reason': c['omissionReasons']['UNSUPPORTED'] = 4
            if defect == 'turn-total': audit['byTurn'].pop()
            if defect == 'boundary-total': audit['byBoundary'].pop()
            if defect == 'duplicate-turn': audit['byTurn'].append(copy.deepcopy(audit['byTurn'][0]))
            if defect == 'duplicate-boundary': audit['byBoundary'].append(copy.deepcopy(audit['byBoundary'][0]))
            if defect == 'known-omission': c['knownUnrecordedCaptures']['NESTED_TARGET:budget'] -= 1
            with self.subTest(defect=defect), self.assertRaises(ValueError): summary.summarize(source)

    def test_invalid_clocks_identities_scope_and_window_are_rejected(self):
        for defect in ('clock', 'unavailable-clock', 'bool-turn', 'bool-player', 'bool-game', 'run', 'scope'):
            source = fixture(); row = source['inputCoverageAudit']['byTurn'][0]
            if defect == 'clock': row['turnBasis'] = 'GUESSED'
            if defect == 'unavailable-clock': row['turnBasis'] = 'ENTRY_LOCATION_UNAVAILABLE'
            if defect == 'bool-turn': row['turn'] = True
            if defect == 'bool-player': row['playerId'] = False
            if defect == 'bool-game': row['game'] = True
            if defect == 'run': row['runId'] = None
            if defect == 'scope': source['inputCoverageAudit']['scope'] = 'ALL_DECISIONS'
            with self.subTest(defect=defect), self.assertRaises(ValueError): summary.summarize(source)
        for window in (0, -1, True, 1.5):
            with self.subTest(window=window), self.assertRaises(ValueError): summary.summarize(fixture(), window)
