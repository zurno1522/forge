import copy
import json
import importlib.util
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_damage_policy_input import damage_fixture
from test_card_payment_request import unavailable
from test_search_input_context import search_rows, hide


def receipt(status='ADMITTED', ordinal=1):
    known = status in ('ADMITTED', 'COUNTER_OVERFLOW')
    return dict(schemaVersion=1, policy='FIXED_SHARE_PREFIX_V1' if known else None,
                denominator=1 if known else None, observationSeed=None,
                ordinal=ordinal if status == 'ADMITTED' else None, status=status)


def rows29():
    rows = damage_fixture()
    rows[2]['choice']['inputContext'].update(schemaVersion=29, profile='PERMITTED_CHOICE_VIEW_V29', admission=receipt())
    return rows


class InputAdmissionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.index = 0

    def export(self, rows, output=False):
        self.index += 1; path = self.root / f'{self.index}.jsonl'
        path.write_text(''.join(json.dumps(r) + '\n' for r in rows))
        out = self.root / str(self.index)
        result = module.export([path], out if output else None)
        if output: self.assertEqual(result, module.export([path]))
        return result, out

    def test_admitted_receipt_survives_export_outside_unchanged_features_and_is_observed_only(self):
        rows = rows29(); original = copy.deepcopy(damage_fixture()[2]['choice']['inputContext']['policy'])
        result, out = self.export(rows, True)
        exported = json.loads((out / 'choices.jsonl').read_text())['start']['choice']['inputContext']
        self.assertEqual(exported['admission'], receipt()); self.assertEqual(exported['policy'], original)
        self.assertNotIn('admission', exported['policy'])
        self.assertFalse(result['trainingReady'])
        a = result['inputCoverageAudit']['admissionAudit']
        self.assertEqual(a, dict(schemaVersion=1, scope='OBSERVED_V29_CONTEXT_REFERENCES_ONLY', records=1,
                                 statuses={'ADMITTED': 1}, policies={'FIXED_SHARE_PREFIX_V1': 1}))
        self.assertIn('not successful captures', (out / 'coverage.md').read_text())

    def test_exhausted_selected_gate_and_last_signed_long_are_valid_without_capture_claim(self):
        rows = rows29(); ctx = rows[2]['choice']['inputContext']; unavailable(ctx)
        ctx['admission'] = receipt(ordinal=(1 << 63) - 1)
        result, _ = self.export(rows)
        self.assertEqual(result['inputCoverageAudit']['admissionAudit']['statuses'], {'ADMITTED': 1})
        self.assertEqual(result['inputCoverageAudit']['totals']['contextStatuses']['UNAVAILABLE'], 1)

    def test_known_bypass_unknown_failure_and_saturated_counter_have_distinct_exact_receipts(self):
        for status, component, reason in [('BYPASSED', 'CONTEXT', 'HIDDEN'), ('BYPASSED', 'CONTEXT', 'INTERRUPTED'),
                                          ('BYPASSED', 'PERSPECTIVE', 'UNSUPPORTED'),
                                          ('UNESTABLISHED', 'CONTEXT', 'CAPTURE_FAILED'),
                                          ('COUNTER_OVERFLOW', 'CONTEXT', 'COUNTER_OVERFLOW')]:
            rows = rows29(); ctx = rows[2]['choice']['inputContext']; unavailable(ctx)
            ctx.update(admission=receipt(status), omissions=[dict(component=component, reason=reason)])
            if reason == 'HIDDEN':
                rows = search_rows(); hide(rows); ctx = rows[2]['decision']['searchTargetApplications'][0]['inputContext']
                ctx.update(schemaVersion=29, profile='PERMITTED_CHOICE_VIEW_V29', admission=receipt(status))
                ctx['coverage']['receivedParameters'] = 'UNAVAILABLE'
            with self.subTest(status=status, reason=reason): self.export(rows)

    def test_missing_extra_unknown_boolean_and_out_of_range_admission_values_are_rejected(self):
        for error in ('missing', 'extra', 'bool-version', 'unknown-version', 'policy', 'denominator', 'bool-denominator',
                      'seed', 'zero', 'negative', 'overflow', 'bool-ordinal', 'float-ordinal', 'null-ordinal', 'status'):
            rows = rows29(); ctx = rows[2]['choice']['inputContext']; a = ctx['admission']
            if error == 'missing': ctx.pop('admission')
            elif error == 'extra': a['work'] = 0
            else:
                key, value = {'bool-version': ('schemaVersion', True), 'unknown-version': ('schemaVersion', 2),
                    'policy': ('policy', 'HASH_THINNED_V1'), 'denominator': ('denominator', 4),
                    'bool-denominator': ('denominator', True), 'seed': ('observationSeed', 0), 'zero': ('ordinal', 0),
                    'negative': ('ordinal', -1), 'overflow': ('ordinal', 1 << 63), 'bool-ordinal': ('ordinal', True),
                    'float-ordinal': ('ordinal', 1.0), 'null-ordinal': ('ordinal', None), 'status': ('status', 'SAMPLED_OUT')}[error]
                a[key] = value
            with self.subTest(error=error), self.assertRaises(module.InvalidData): self.export(rows)

    def test_unavailable_early_return_still_validates_admission_and_no_state_is_inferred(self):
        for status, reason in [('ADMITTED', 'CAPTURE_FAILED'), ('BYPASSED', 'BUDGET_EXHAUSTED'),
                               ('UNESTABLISHED', 'INTERRUPTED'), ('COUNTER_OVERFLOW', 'BUDGET_EXHAUSTED')]:
            rows = rows29(); ctx = rows[2]['choice']['inputContext']; unavailable(ctx)
            ctx.update(admission=receipt(status), omissions=[dict(component='CONTEXT', reason=reason)])
            with self.subTest(status=status, reason=reason), self.assertRaises(module.InvalidData): self.export(rows)
        for status in ('BYPASSED', 'UNESTABLISHED', 'COUNTER_OVERFLOW'):
            rows = rows29(); rows[2]['choice']['inputContext']['admission'] = receipt(status)
            with self.subTest(status=status), self.assertRaises(module.InvalidData): self.export(rows)

    def test_v28_and_earlier_do_not_gain_new_metadata_or_sampling_permission(self):
        rows = damage_fixture(); result, _ = self.export(rows)
        self.assertNotIn('admissionAudit', result['inputCoverageAudit'])
        self.assertNotIn('admissionStatuses', result['inputCoverageAudit']['totals'])
        rows[2]['choice']['inputContext']['admission'] = receipt()
        with self.assertRaises(module.InvalidData): self.export(rows)
        for reason in ('ADMISSION_SAMPLED', 'EXPERIMENT_SAMPLED', 'EXPERIMENT_RESERVED'):
            for new in (False, True):
                rows = rows29() if new else damage_fixture(); ctx = rows[2]['choice']['inputContext']; unavailable(ctx)
                ctx['omissions'][0]['reason'] = reason
                with self.subTest(reason=reason, new=new), self.assertRaises(module.InvalidData): self.export(rows)

    def test_gaps_repeated_views_and_reverse_publication_are_not_source_loss_estimates(self):
        rows = rows29(); start, end = rows[2:4]; pairs = []
        for i, ordinal in enumerate((9, 2, 2, (1 << 63) - 1)):
            a, b = copy.deepcopy(start), copy.deepcopy(end)
            a['choice']['choiceId'] = b['choice']['choiceId'] = 'admission-' + str(i)
            a['choice']['inputContext']['admission']['ordinal'] = ordinal; pairs.extend((a, b))
        rows[2:4] = pairs
        for i, row in enumerate(rows[1:-1], 1): row['eventIndex'] = i
        result, _ = self.export(rows, True)
        self.assertEqual(result['inputCoverageAudit']['admissionAudit']['records'], 4)
        self.assertIsNone(result['inputCoverageAudit']['unobservedDecisionCount'])

    def test_native_compatibility_comparison_removes_metadata_but_keeps_features_results_and_delivery_counts(self):
        path = Path(__file__).with_name('input-context-benchmark') / 'run-prefix-compatibility.py'
        spec = importlib.util.spec_from_file_location('prefix_comparison', path)
        comparison = importlib.util.module_from_spec(spec); spec.loader.exec_module(comparison)
        old = damage_fixture()[2]; new = rows29()[2]
        self.assertEqual(comparison.semantic(old), comparison.semantic(new))
        changed = copy.deepcopy(new); changed['choice']['inputContext']['policy']['receivedParameters']['mandatory'] = True
        self.assertNotEqual(comparison.semantic(old), comparison.semantic(changed))
        for value in (dict(selection=dict(value=2)), dict(counts=dict(inputUnavailable=1)), dict(counts=dict(failed=1))):
            self.assertNotEqual(comparison.semantic(value), comparison.semantic({}))
