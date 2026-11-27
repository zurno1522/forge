import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module as exporter
from test_card_payment_request import unavailable, card_fixture

here = Path(__file__).with_name('input-context-benchmark')
spec = importlib.util.spec_from_file_location('admission_measurements', here / 'summarize-admission.py')
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)
CASES = json.loads((here / 'fixtures/admission-cases.json').read_text())['cases']


def case(name='forward-600-PREFIX-1-0'):
    return copy.deepcopy(CASES[name])


class AdmissionMeasurementTests(unittest.TestCase):
    def test_native_fixture_rows_validate_and_reserve_funding_has_a_fixed_total(self):
        for row in CASES.values():
            with self.subTest(policy=row['policy'], cycles=row['cycles']):
                summary.validate_row(row)
        short = case('forward-60-RESERVE-1-0'); long = case('forward-600-RESERVE-1-0')
        self.assertEqual((short['funded'], short['held']), (32000, 32000))
        self.assertEqual((long['funded'], long['held']), (64000, 0))
        self.assertEqual(long['captureWork'] + long['disclosureWork'] + long['remaining'], 64000)

    def test_stride_offsets_admit_different_nested_slots_without_hiding_the_missing_families(self):
        for offset, family in enumerate(('DAMAGE', 'DRAW', 'NUMBER', 'ENTITY')):
            row = case(f'forward-600-STRIDE-4-{offset}')
            admitted = {k.split(':')[1] for k in row['groups'] if not k.endswith(':EXPERIMENT_SAMPLED')}
            self.assertEqual(admitted, {family})
            self.assertEqual(sum(v['records'] for k, v in row['groups'].items() if k.endswith(':EXPERIMENT_SAMPLED')), 1800)
        self.assertEqual(summary.mix(0), 0)
        self.assertEqual(summary.mix(1), 0x5692161d100b05e5)
        self.assertEqual(summary.mix(2), 0xdbd238973a2b148a)
        self.assertFalse(all(summary.admits('HASH', 4, 0, n) == summary.admits('HASH', 4, 1, n) for n in range(1, 65)))
        self.assertTrue(all(not summary.admits('HASH', 8, 0, n) or summary.admits('HASH', 4, 0, n) for n in range(1, 65)))

    def test_wrong_admission_rules_and_uncharged_reservation_failures_are_refused(self):
        row = case('forward-600-STRIDE-4-0'); row['salt'] = 1
        with self.assertRaisesRegex(ValueError, 'Admission/slot'): summary.validate_row(row)
        row = case('forward-600-HASH-4-0'); row['salt'] = 1
        with self.assertRaises(ValueError): summary.validate_row(row)
        row = case('forward-600-RESERVE-1-0')
        key = next(k for k in row['groups'] if k.endswith(':RESERVE_ATTEMPT_FAILED'))
        row['groups'][key]['work'] = 0
        with self.assertRaisesRegex(ValueError, 'Uncharged'): summary.validate_row(row)

    def test_capture_disclosure_remaining_and_player_share_debits_must_reconcile(self):
        for field in ('captureWork', 'disclosureWork', 'remaining', 'funded', 'held', 'share', 'inactiveRemaining', 'skipped-work'):
            row = case('forward-600-HASH-8-0')
            if field == 'inactiveRemaining': row[field][0] -= 1
            elif field == 'skipped-work':
                key = next(k for k in row['groups'] if k.endswith(':EXPERIMENT_SAMPLED')); row['groups'][key]['work'] = 1
            else: row[field] += 1
            with self.subTest(field=field), self.assertRaises(ValueError): summary.validate_row(row)

    def test_missing_lifecycles_bad_source_scope_and_unavailable_underreporting_are_refused(self):
        for field in ('callbacks', 'checksum', 'starts', 'terminals', 'gateCalls', 'scopeComplete', 'overflowed', 'inputUnavailable', 'collectorStarts'):
            row = case()
            if field in ('scopeComplete', 'overflowed'): row['accounting'][field] = not row['accounting'][field]
            elif field == 'inputUnavailable': row['accounting']['boundaries']['DAMAGE_CAN_PLAY_POLICY'][field] -= 1
            elif field == 'collectorStarts': row['accounting'][field] = 1
            else: row[field] -= 1
            with self.subTest(field=field), self.assertRaises(ValueError): summary.validate_row(row)

    def test_healthy_and_unavailable_metrics_cannot_be_merged_or_lost_to_make_a_cheaper_claim(self):
        for field in ('negative', 'boolean', 'missing-family', 'tiny-cpu', 'tiny-allocation', 'profile', 'fixture'):
            row = case(); key = next(iter(row['groups']))
            if field == 'negative': row['groups'][key]['entryCpuNanos'] = -1
            if field == 'boolean': row['captureWork'] = True
            if field == 'missing-family': row['groups'].pop(key)
            if field == 'tiny-cpu': row['cpuNanos'] = 1
            if field == 'tiny-allocation': row['allocatedBytes'] = 1
            if field == 'profile': row['profile'] = 'PERMITTED_CHOICE_VIEW_V99'
            if field == 'fixture': row['players'] = 2
            with self.subTest(field=field), self.assertRaises(ValueError): summary.validate_row(row)

    def test_profile_quality_cannot_turn_truncated_or_unavailable_inputs_into_complete_state(self):
        for defect in ('zone-total', 'known-cards', 'facts', 'partial', 'unavailable-cards', 'omission-count', 'public-bindings'):
            row = case(); available = next(v for k,v in row['groups'].items() if k.endswith(':AVAILABLE') and v['partialProfiles'])
            if defect == 'zone-total': available['representedCards'] += 1
            if defect == 'known-cards': available['identityKnownCards'] = available['representedCards'] + 1
            if defect == 'facts': available['disclosedFacts'] = 32 * available['records'] + 1
            if defect == 'partial': available['partialProfiles'] = 0
            if defect == 'unavailable-cards':
                missing = next(v for k,v in row['groups'].items() if k.endswith(':BUDGET'))
                missing.update(representedCards=1, representedZones={'Hand':1})
            if defect == 'public-bindings': available['linkedFacts'] = 0
            if defect == 'omission-count': available['omissionReasons']['OUTSIDE_CAPTURED_PREFIX'] = available['records'] + 1
            with self.subTest(defect=defect), self.assertRaises(ValueError): summary.validate_row(row)

    def test_overlay_control_requires_the_same_policy_and_debits_and_full_matrix_requires_repetition(self):
        control = case('production-600-PREFIX-1-0'); overlay = case(); summary.compare_prefix(control, overlay)
        for field in ('policy', 'debit'):
            changed = copy.deepcopy(overlay)
            if field == 'policy': changed['policyHashes'][next(iter(changed['policyHashes']))] = '0' * 64
            else: changed['disclosureWork'] += 1
            with self.subTest(field=field), self.assertRaises(ValueError): summary.compare_prefix(control, changed)
        with self.assertRaisesRegex(ValueError, 'rounds'): summary.validate_trials([overlay], True)
        partial = [dict(copy.deepcopy(overlay), round=i) for i in range(3)]
        with self.assertRaisesRegex(ValueError, 'matrix'): summary.validate_trials(partial, True)
        with self.assertRaisesRegex(ValueError, 'matrix'): summary.validate_trials(partial + [partial[0]], True)

    def test_experimental_omission_reasons_cannot_be_exported_as_ordinary_training_records(self):
        for reason in ('EXPERIMENT_SAMPLED', 'EXPERIMENT_RESERVED'):
            rows = card_fixture(); ctx = rows[2]['choice']['inputContext']; unavailable(ctx)
            with tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / 'trace.jsonl'; path.write_text(''.join(json.dumps(r) + '\n' for r in rows)); exporter.export([path])
                ctx['omissions'][0]['reason'] = reason; path.write_text(''.join(json.dumps(r) + '\n' for r in rows))
                with self.subTest(reason=reason), self.assertRaises(exporter.InvalidData): exporter.export([path])
