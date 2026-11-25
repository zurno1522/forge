import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_mode_choices import mode_input_fixture
from test_prompt_input_context import rows3


def mode_bounds_fixture(boundary='CONTROLLER_MODES'):
    rows = mode_input_fixture(boundary); context = rows[2]['choice']['inputContext']
    context.update(schemaVersion=5, profile='PERMITTED_CHOICE_VIEW_V5')
    context['policy']['prompt'] = dict(kind='MODE_BOUNDS', minimumPicks=0, maximumPicks=3, allowRepeat=True)
    context['coverage']['prompt'] = 'RECEIVED_MODE_BOUNDS_V1'
    return rows


class ModePromptInputTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.index = 0

    def export(self, rows):
        self.index += 1; source = self.root / f'{self.index}.jsonl'
        source.write_text(''.join(json.dumps(r)+'\n' for r in rows))
        out = self.root / f'out-{self.index}'
        result = module.export([source], out)
        self.assertEqual(result['inputCoverageAudit'], module.export([source])['inputCoverageAudit'])
        return result, out

    def test_both_planning_boundaries_keep_exact_received_bounds_and_unready_examples(self):
        for boundary in ('CONTROLLER_MODES', 'CHARM_MODE_HEURISTIC'):
            for minimum, maximum, repeat in ((0, 3, True), (9, 3, False), (-2147483648, 2147483647, True)):
                rows = mode_bounds_fixture(boundary); choice = rows[2]['choice']
                choice['options'].update(min=minimum, max=maximum, allowRepeat=repeat)
                prompt = choice['inputContext']['policy']['prompt']
                prompt.update(minimumPicks=minimum, maximumPicks=maximum, allowRepeat=repeat)
                result, out = self.export(rows)
                self.assertEqual(result['inputContextProfiles'], {'PERMITTED_CHOICE_VIEW_V5': 1})
                row = json.loads((out/'choices.jsonl').read_text())
                self.assertFalse(row['trainingReady'])
                self.assertEqual(row['start']['choice']['inputContext']['policy']['prompt'], prompt)
                self.assertTrue(any('V5' in text and 'bounds' in text for text in result['limitations']))

    def test_bad_profile_scope_bounds_types_and_diagnostic_argument_disagreements_are_rejected(self):
        for error in ('v4', 'minimum', 'maximum', 'repeat', 'bool-bound', 'int-repeat', 'out-of-int',
                      'raw-id', 'offered-modes', 'coverage', 'missing-entry', 'committed', 'numeric-boundary', 'parent'):
            rows = mode_bounds_fixture(); choice = rows[2]['choice']; context = choice['inputContext']; prompt = context['policy']['prompt']
            if error == 'v4': context.update(schemaVersion=4, profile='PERMITTED_CHOICE_VIEW_V4')
            if error == 'minimum': prompt['minimumPicks'] = 1
            if error == 'maximum': prompt['maximumPicks'] = 4
            if error == 'repeat': prompt['allowRepeat'] = False
            if error == 'bool-bound': prompt['minimumPicks'] = False
            if error == 'int-repeat': prompt['allowRepeat'] = 1
            if error == 'out-of-int': prompt['maximumPicks'] = choice['options']['max'] = 1 << 40
            if error == 'raw-id': prompt['abilityId'] = 8
            if error == 'offered-modes': prompt['items'] = []
            if error == 'coverage': context['coverage']['prompt'] = 'CONTROLLER_REQUEST_V1'
            if error == 'missing-entry': choice.pop('entryLocation')
            if error == 'committed': context['stateBasis'] = choice['entryLocation']['stateBasis'] = 'RULES_STATE'
            if error == 'numeric-boundary':
                numeric = rows3(); context.update(stateBasis='RULES_STATE', timing='BEFORE_SELECTOR')
                numeric[2]['choice']['inputContext'] = context; rows = numeric
            if error == 'parent':
                rows = rows[:2] + rows[4:]
                context.update(stateBasis='RULES_STATE', timing='BEFORE_OPERATION')
                next(r for r in rows if r['type'] == 'AI_DECISION')['decision']['inputContext'] = context
                for i, r in enumerate(rows[1:-1], 1): r['eventIndex'] = i
            with self.subTest(error=error), self.assertRaises(module.InvalidData): self.export(rows)

    def test_v5_inherits_rules_numeric_permission_without_granting_planning_numeric_permission(self):
        rows = rows3(); context = rows[2]['choice']['inputContext']
        context.update(schemaVersion=5, profile='PERMITTED_CHOICE_VIEW_V5')
        context['diagnostics']['optionBindings'] = dict(coverage='UNSUPPORTED', items=[], truncated=False)
        self.export(rows)
        context['stateBasis'] = 'PLANNING_WORKING_STATE'
        with self.assertRaises(module.InvalidData): self.export(rows)

    def test_legacy_v4_mode_inputs_keep_their_absent_prompt_and_version(self):
        rows = mode_input_fixture()
        result, _ = self.export(rows)
        self.assertEqual(result['inputContextProfiles'], {'PERMITTED_CHOICE_VIEW_V4': 1})
        self.assertFalse(any('V5' in text for text in result['limitations']))
