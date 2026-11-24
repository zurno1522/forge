import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module, fixture
from test_search_input_context import search_rows, hide
from test_capture_accounting import FIELDS


def accounted(rows=None):
    rows = search_rows() if rows is None else rows
    decision = next(row['decision'] for row in rows if row['type'] == 'AI_DECISION')
    counts = dict.fromkeys(FIELDS, 0); counts.update(entered=1, captured=1, finished=1)
    decision['searchCaptureAccounting'] = dict(schemaVersion=1, scope='RECORDING_PARENT_SEARCH_CAPTURE_CALLS',
        cutoff='PARENT_CAPTURE_CLOSED', closed=True, overflowed=False, counts=counts)
    return rows, decision


class SearchCaptureAccountingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.index = 0

    def export(self, rows):
        self.index += 1; source = self.root / f'{self.index}.jsonl'
        source.write_text(''.join(json.dumps(row)+'\n' for row in rows))
        result = module.export([source], self.root / f'out-{self.index}')
        self.assertEqual(result, module.export([source]))
        return result

    def test_counts_include_unsupported_and_failed_capture_attempts_without_claiming_callback_outcomes(self):
        rows, decision = accounted(); counts = decision['searchCaptureAccounting']['counts']
        counts.update(entered=4, unsupported=1, failed=1, budget=1, finished=4)
        decision['searchTargetApplicationOmissions']['budget'] = 1
        summary = self.export(rows)['searchCaptureAccounting']
        self.assertEqual(summary['knownMissingRowsAtParentCutoffs'], 3)
        self.assertEqual(summary['missingRowFractionForRetainedParents'], .75)
        for field in ('gameWideNestedInvocationCount', 'callbackOutcomeCounts', 'missingParentInvocationCount'):
            self.assertIsNone(summary[field])
        # Every host can be unsupported: no search-record declaration is required for absent rows.
        rows, decision = accounted(fixture())
        decision['searchCaptureAccounting']['counts'].update(captured=0, unsupported=1)
        self.assertEqual(self.export(rows)['searchCaptureAccounting']['knownMissingRowsAtParentCutoffs'], 1)

    def test_unavailable_input_and_result_interruption_are_retained_rows(self):
        rows, decision = accounted(); hide(rows)
        decision['searchCaptureAccounting']['counts']['inputUnavailable'] = 1
        summary = self.export(rows)['searchCaptureAccounting']
        self.assertEqual(summary['knownMissingRowsAtParentCutoffs'], 0)
        rows, decision = accounted(); operation = decision['searchTargetApplications'][0]
        operation.update(status='CAPTURE_INTERRUPTED', afterTargets=None, changedAbilities=[])
        decision['searchTargetApplicationOmissions']['interrupted'] = 1
        rows[4]['targetChoiceProvenance']['abilities'][0]['targetChoices'] = [None]
        summary = self.export(rows)['searchCaptureAccounting']
        self.assertEqual(summary['knownMissingRowsAtParentCutoffs'], 0)
        self.assertEqual(summary['counts']['interrupted'], 0)
        decision['searchTargetApplicationOmissions']['interrupted'] = 2
        with self.assertRaises(module.InvalidData): self.export(rows)

    def test_pending_capture_is_distinct_from_a_callback_with_a_retained_incomplete_row(self):
        rows, decision = accounted(fixture(status='TIMEOUT'))
        decision['searchCaptureAccounting']['counts'].update(captured=0, finished=0)
        summary = self.export(rows)['searchCaptureAccounting']
        self.assertEqual(summary['captureCallsPendingAtParentCutoffs'], 1)
        self.assertEqual(summary['knownMissingRowsAtParentCutoffs'], 1)
        rows, decision = accounted(); operation = decision['searchTargetApplications'][0]
        operation.update(status='INCOMPLETE', afterTargets=None, changedAbilities=[], candidateActionId=None)
        decision.update(status='AI_TIMEOUT', selection='PASS_PRIORITY', selectedActions=[])
        decision['subordinateChoices'][0]['candidateActionId'] = None
        rows = [row for row in rows if row['type'] not in ('AI_ACTION_EXECUTION','STACK_ADDED','STACK_RESOLVED','STACK_REMOVED')]
        for i, row in enumerate(rows[1:-1], 1): row['eventIndex'] = i
        summary = self.export(rows)['searchCaptureAccounting']
        self.assertEqual(summary['captureCallsPendingAtParentCutoffs'], 0)
        self.assertEqual(summary['knownMissingRowsAtParentCutoffs'], 0)
        decision['searchCaptureAccounting']['counts']['finished'] = 0
        self.assertEqual(self.export(rows)['searchCaptureAccounting']['captureCallsPendingAtParentCutoffs'], 1)

    def test_legacy_mixed_zero_and_overflow_cohorts_cannot_supply_an_exact_fraction(self):
        self.assertNotIn('searchCaptureAccounting', self.export(search_rows()))
        rows, decision = accounted(fixture()); decision['searchCaptureAccounting']['counts'] = dict.fromkeys(FIELDS, 0)
        self.assertIsNone(self.export(rows)['searchCaptureAccounting']['missingRowFractionForRetainedParents'])
        rows, decision = accounted(); decision['searchCaptureAccounting']['overflowed'] = True
        summary = self.export(rows)['searchCaptureAccounting']
        self.assertIsNone(summary['knownMissingRowsAtParentCutoffs'])
        self.assertIsNone(summary['captureCallsPendingAtParentCutoffs'])
        self.assertIsNone(summary['missingRowFractionForRetainedParents'])
        decision['searchCaptureAccounting']['overflowed'] = False
        aggregate = module._captures.Aggregate('search'); aggregate.observe(decision); aggregate.observe({})
        self.assertEqual(aggregate.summary()['parentsAbsent'], 1)
        self.assertIsNone(aggregate.summary()['missingRowFractionForRetainedParents'])

    def test_malformed_and_cross_family_counts_fail_closed(self):
        for error in ('version', 'scope', 'closed', 'boolean', 'overflow', 'negative', 'bounds', 'rows', 'views',
                      'balance', 'finished', 'result-before-exit', 'budget', 'unknown-field', 'saturated-row-mismatch'):
            rows, decision = accounted(); value = decision['searchCaptureAccounting']; counts = value['counts']
            if error == 'version': value['schemaVersion'] = 2
            if error == 'scope': value['scope'] = 'RECORDING_PARENT_TARGET_CAPTURE_CALLS'
            if error == 'closed': value['closed'] = False
            if error == 'boolean': counts['entered'] = True
            if error == 'overflow': value['overflowed'] = 1
            if error == 'negative': counts['failed'] = -1
            if error == 'bounds': counts['entered'] = 1 << 63
            if error == 'rows': counts['captured'] = 0
            if error == 'views': counts['inputUnavailable'] = 1
            if error == 'balance': counts['failed'] = 1
            if error == 'finished': counts['finished'] = 2
            if error == 'result-before-exit': counts['finished'] = 0
            if error == 'budget': decision['searchTargetApplicationOmissions']['budget'] = 1
            if error == 'unknown-field': counts['callbackReturned'] = 1
            if error == 'saturated-row-mismatch': value['overflowed'] = True; counts['captured'] = 0
            with self.subTest(error=error), self.assertRaises(module.InvalidData): self.export(rows)
