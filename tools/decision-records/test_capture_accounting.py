import json
from pathlib import Path
import tempfile
import unittest

from test_export import module, fixture
from test_nested_input_context import nested_rows

FIELDS = ('entered', 'captured', 'unsupported', 'budget', 'interrupted', 'failed', 'finished', 'inputUnavailable')


def accounted(rows=None):
    rows = nested_rows() if rows is None else rows
    decision = next(r['decision'] for r in rows if r['type'] == 'AI_DECISION')
    counts = dict.fromkeys(FIELDS, 0); counts.update(entered=1, captured=1, finished=1)
    decision['targetCaptureAccounting'] = dict(schemaVersion=1, scope='RECORDING_PARENT_TARGET_CAPTURE_CALLS',
        cutoff='PARENT_CAPTURE_CLOSED', closed=True, overflowed=False, counts=counts)
    return rows, decision


class CaptureAccountingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.index = 0

    def export(self, rows):
        self.index += 1; source = self.root / f'{self.index}.jsonl'
        source.write_text(''.join(json.dumps(r)+'\n' for r in rows))
        result = module.export([source], self.root / f'out-{self.index}')
        self.assertEqual(result, module.export([source]))
        return result

    def test_counted_capture_omissions_are_distinct_from_missing_parents_and_callback_outcomes(self):
        rows, decision = accounted(); counts = decision['targetCaptureAccounting']['counts']
        counts.update(entered=4, unsupported=1, failed=1, budget=1, finished=4)
        decision['subordinateChoiceOmissions'] = dict(budget=1, interrupted=0)
        result = self.export(rows)['targetCaptureAccounting']
        self.assertEqual(result['knownMissingRowsAtParentCutoffs'], 3)
        self.assertEqual(result['missingRowFractionForRetainedParents'], .75)
        self.assertIsNone(result['gameWideNestedInvocationCount'])
        self.assertIsNone(result['callbackOutcomeCounts'])
        self.assertIsNone(result['missingParentInvocationCount'])

    def test_pending_eligibility_and_already_captured_rows_have_different_cutoff_counts(self):
        rows, decision = accounted(fixture(status='TIMEOUT'))
        decision['targetCaptureAccounting']['counts'].update(entered=1, captured=0, finished=0)
        result = self.export(rows)['targetCaptureAccounting']
        self.assertEqual(result['captureCallsPendingAtParentCutoffs'], 1)
        self.assertEqual(result['knownMissingRowsAtParentCutoffs'], 1)
        rows, decision = accounted()
        decision['targetCaptureAccounting']['counts']['finished'] = 0
        decision['subordinateChoices'][0].update(status='INCOMPLETE', selected=None, selectedOptionIndex=None)
        result = self.export(rows)['targetCaptureAccounting']
        self.assertEqual(result['captureCallsPendingAtParentCutoffs'], 1)
        self.assertEqual(result['knownMissingRowsAtParentCutoffs'], 0)

    def test_unavailable_view_is_a_retained_row_and_must_match_the_source_count(self):
        from test_nested_input_context import hide
        rows, decision = accounted(); hide(decision['subordinateChoices'][0])
        decision['targetCaptureAccounting']['counts']['inputUnavailable'] = 1
        result = self.export(rows)['targetCaptureAccounting']
        self.assertEqual(result['knownMissingRowsAtParentCutoffs'], 0)
        decision['targetCaptureAccounting']['counts']['inputUnavailable'] = 0
        with self.assertRaises(module.InvalidData): self.export(rows)

    def test_legacy_absence_and_saturation_cannot_establish_an_aggregate_fraction(self):
        self.assertNotIn('targetCaptureAccounting', self.export(nested_rows()))
        rows, decision = accounted(); decision['targetCaptureAccounting']['overflowed'] = True
        result = self.export(rows)['targetCaptureAccounting']
        self.assertIsNone(result['knownMissingRowsAtParentCutoffs'])
        self.assertIsNone(result['missingRowFractionForRetainedParents'])
        decision['targetCaptureAccounting']['overflowed'] = False
        aggregate = module._captures.Aggregate(); aggregate.observe(decision); aggregate.observe({})
        result = aggregate.summary()
        self.assertEqual(result['parentsAbsent'], 1)
        self.assertFalse(result['overflowed'])
        self.assertIsNone(result['missingRowFractionForRetainedParents'])

    def test_invalid_versions_bounds_balances_and_retained_row_counts_fail_closed(self):
        for error in ('version', 'scope', 'closed', 'negative', 'boolean', 'too-large', 'unknown-field', 'outcomes', 'finished', 'rows', 'budget', 'interrupted', 'exit-before-result', 'overflow-balance'):
            rows, decision = accounted(); value = decision['targetCaptureAccounting']; counts = value['counts']
            if error == 'version': value['schemaVersion'] = 2
            if error == 'scope': value['scope'] = 'ALL_GAMEPLAY_DECISIONS'
            if error == 'closed': value['closed'] = False
            if error == 'negative': counts['failed'] = -1
            if error == 'boolean': counts['entered'] = True
            if error == 'too-large': counts['entered'] = 1 << 63
            if error == 'unknown-field': counts['parentId'] = 99
            if error == 'outcomes': counts['failed'] = 1
            if error == 'finished': counts['finished'] = 2
            if error == 'rows': counts['captured'] = 0
            if error == 'budget': decision['subordinateChoiceOmissions'] = dict(budget=1, interrupted=0)
            if error == 'interrupted': decision['subordinateChoiceOmissions'] = dict(budget=0, interrupted=1)
            if error == 'exit-before-result': counts['finished'] = 0
            if error == 'overflow-balance': value['overflowed'] = True; counts['unsupported'] = 2
            with self.subTest(error=error), self.assertRaises(module.InvalidData): self.export(rows)
