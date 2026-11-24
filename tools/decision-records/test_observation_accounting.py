import json
from pathlib import Path
import tempfile
import unittest

from test_export import module, fixture
from test_choice_events import choices_fixture

FIELDS = ('entered', 'prepared', 'omitted', 'callbackStarted', 'callbackReturned', 'callbackThrew', 'inputUnavailable',
          'startAttempts', 'startReturned', 'terminalAttempts', 'terminalReturned', 'finished', 'observationFailures',
          'omittedUnsupported', 'omittedClosed', 'omittedFailure')


def with_accounting(rows=None):
    rows = choices_fixture() if rows is None else rows
    rows[0].update(choiceEventSchemaVersion=1, observationAccountingSchemaVersion=1)
    row = dict.fromkeys(FIELDS, 0)
    for key in ('entered', 'prepared', 'callbackStarted', 'callbackReturned', 'startAttempts', 'startReturned', 'terminalAttempts', 'terminalReturned', 'finished'):
        row[key] = 1
    rows[-1]['observationAccounting'] = dict(schemaVersion=1, scope='ORIGINAL_AI_STANDALONE_CALLBACKS', cutoff='GAME_CAPTURE_CLOSED',
        gamesAccounted=1, counts=dict(closed=True, overflowed=False, scopeComplete=True,
            collectorStarts=1, collectorTerminals=1, boundaries={'CONTROLLER_TARGETS': row}))
    return rows


class ObservationAccountingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.index = 0

    def export(self, rows):
        self.index += 1; source = self.root / f'{self.index}.jsonl'
        source.write_text(''.join(json.dumps(row)+'\n' for row in rows))
        return module.export([source], self.root / f'out-{self.index}')

    def test_retained_unavailable_input_and_callback_error_are_not_lost_records(self):
        rows = with_accounting(); cell = rows[-1]['observationAccounting']['counts']['boundaries']['CONTROLLER_TARGETS']
        cell['inputUnavailable'] = 1
        summary = self.export(rows)['observationAccounting']
        self.assertEqual(summary['startsMissingAtCutoffs'], 0)
        self.assertEqual(summary['startRecordMissingFractionAtCutoffs'], 0)
        self.assertIsNone(summary['nestedInvocationCount'])
        self.assertIsNone(summary['uninstrumentedDecisionCount'])
        cell.update(callbackReturned=0, callbackThrew=1)
        rows[3]['choice'].update(status='ERROR', selection=None)
        self.assertEqual(self.export(rows)['observationAccounting']['counts']['callbackThrew'], 1)

    def test_cutoff_pending_capture_and_callback_are_not_completed_or_repaired(self):
        rows = with_accounting(fixture(status='TIMEOUT')); counts = rows[-1]['observationAccounting']['counts']
        cell = dict.fromkeys(FIELDS, 0); cell['entered'] = 1
        counts.update(collectorStarts=0, collectorTerminals=0, boundaries={'UNCLASSIFIED': cell})
        summary = self.export(rows)['observationAccounting']
        self.assertEqual(summary['invocationsInFlightAtCutoffs'], 1)
        self.assertEqual(summary['startsMissingAtCutoffs'], 1)
        self.assertEqual(summary['startRecordMissingFractionAtCutoffs'], 1)
        rows = with_accounting(); rows.pop(3)
        next(row for row in rows if row['type'] == 'GAME_EXECUTION_FINISHED')['status'] = 'TIMEOUT'
        rows[-1].update(status='TIMEOUT', gamesNormal=0, gamesTimedOut=1)
        for index, record in enumerate(rows[1:-1], 1): record['eventIndex'] = index
        counts = rows[-1]['observationAccounting']['counts']; counts['collectorTerminals'] = 0
        cell = counts['boundaries']['CONTROLLER_TARGETS']
        cell.update(callbackReturned=0, terminalAttempts=0, terminalReturned=0, finished=0)
        summary = self.export(rows)['observationAccounting']
        self.assertEqual(summary['callbacksInFlightAtCutoffs'], 1)
        self.assertEqual(summary['startsMissingAtCutoffs'], 0)

    def test_omitted_record_has_a_separate_callback_and_reconciled_reason(self):
        rows = with_accounting(fixture()); counts = rows[-1]['observationAccounting']['counts']
        cell = dict.fromkeys(FIELDS, 0)
        cell.update(entered=1, omitted=1, omittedUnsupported=1, callbackStarted=1, callbackReturned=1, finished=1)
        counts.update(collectorStarts=0, collectorTerminals=0, boundaries={'UNCLASSIFIED': cell})
        summary = self.export(rows)['observationAccounting']
        self.assertEqual(summary['counts']['omitted'], 1)
        self.assertEqual(summary['counts']['callbackReturned'], 1)
        self.assertEqual(summary['byBoundary']['UNCLASSIFIED']['omittedUnsupported'], 1)

    def test_partial_scope_overflow_and_legacy_absence_do_not_claim_exact_missing_fractions(self):
        for mode in ('partial', 'overflow'):
            rows = with_accounting(); counts = rows[-1]['observationAccounting']['counts']
            if mode == 'partial': counts['scopeComplete'] = False
            else: counts['overflowed'] = True
            summary = self.export(rows)['observationAccounting']
            self.assertIsNone(summary['startsMissingAtCutoffs'])
            self.assertIsNone(summary['startRecordMissingFractionAtCutoffs'])
        self.assertNotIn('observationAccounting', self.export(choices_fixture()))

    def test_publication_return_is_not_a_collector_receipt(self):
        rows = with_accounting(fixture()); counts = rows[-1]['observationAccounting']['counts']
        counts.update(collectorStarts=0, collectorTerminals=0)
        summary = self.export(rows)['observationAccounting']
        self.assertEqual(summary['counts']['startReturned'], 1)
        self.assertEqual(summary['collector']['collectorStarts'], 0)
        self.assertEqual(summary['startsMissingAtCutoffs'], 1)

    def test_malformed_versions_balances_registry_and_receipts_fail_closed(self):
        for mutation in ('version', 'declaration', 'closed', 'bool', 'negative', 'callback-balance', 'omission-balance', 'capture-balance', 'receipt', 'boundary', 'games', 'publication'):
            rows = with_accounting(); accounting = rows[-1]['observationAccounting']; counts = accounting['counts']; cell = counts['boundaries']['CONTROLLER_TARGETS']
            if mutation == 'version': accounting['schemaVersion'] = 2
            if mutation == 'declaration': rows[0].pop('observationAccountingSchemaVersion')
            if mutation == 'closed': counts['closed'] = False
            if mutation == 'bool': cell['entered'] = True
            if mutation == 'negative': cell['entered'] = -1
            if mutation == 'callback-balance': cell['callbackReturned'] = 2
            if mutation == 'omission-balance': cell.update(omitted=1, prepared=0)
            if mutation == 'capture-balance': cell['prepared'] = 2
            if mutation == 'receipt': counts['collectorStarts'] = 0
            if mutation == 'boundary': counts['boundaries']['unbounded private name'] = counts['boundaries'].pop('CONTROLLER_TARGETS')
            if mutation == 'games': accounting['gamesAccounted'] = 0
            if mutation == 'publication': cell['terminalAttempts'] = 2
            with self.subTest(mutation=mutation), self.assertRaises(module.InvalidData): self.export(rows)
