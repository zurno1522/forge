import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_choice_events import choices_fixture
from test_trigger_preparation_input import trigger_fixture
from test_entry_location import entry


def copy_fixture():
    rows = trigger_fixture(); start = rows[2]['choice']; ctx = start['inputContext']
    start.update(choiceType='EFFECT_COPY_TARGET_RESULT', boundary='COPY_TARGETS', stage='EFFECT_COPY_TARGET_SELECTION')
    start['options'].pop('callbackParameters'); rows[3]['choice']['selection'].pop('accepted')
    ctx['coverage']['receivedParameters'] = 'UNSUPPORTED'; ctx['policy']['receivedParameters'] = None
    return rows


class CopyTargetInputTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup); self.root = Path(self.tmp.name); self.index = 0

    def export(self, rows):
        self.index += 1; path = self.root / f'{self.index}.jsonl'; output = self.root / f'out-{self.index}'
        path.write_text(''.join(json.dumps(row) + '\n' for row in rows))
        result = module.export([path], output); self.assertEqual(result, module.export([path])); return result, output

    def test_copy_operation_has_conditional_inventory_and_no_acceptance_inference(self):
        rows = copy_fixture(); result, output = self.export(rows)
        audit = result['inputCoverageAudit']; entries = audit['entryLocationAudit']
        self.assertEqual(entries['knownInputBoundaryInventoryExtensionV8'], {'CHOICE': {'COPY_TARGETS': 'BEFORE_OPERATION'}})
        self.assertNotIn('COPY_TARGETS', audit['knownInputBoundaryInventoryV1']); self.assertNotIn('COPY_TARGETS', entries['knownInputBoundaryInventoryV2']['CHOICE'])
        self.assertTrue(any('COPY_TARGETS captures' in text for text in result['limitations']))
        self.assertFalse(json.loads((output/'choices.jsonl').read_text())['trainingReady'])
        rows[3]['choice']['selection']['targets'] = copy.deepcopy(rows[2]['choice']['options']['priorTargets']); self.export(rows)

    def test_planning_and_error_outcomes_preserve_operation_facts(self):
        rows = copy_fixture(); c = rows[2]['choice']; c['inputContext']['stateBasis'] = c['entryLocation']['stateBasis'] = 'PLANNING_WORKING_STATE'; self.export(rows)
        rows[3]['choice'].update(status='ERROR', selection=None); self.export(rows)

    def test_copy_never_accepts_an_invented_domain_directive_or_acceptance(self):
        for error in ('profile','entry','selector','stage','timing','directive','receipt','accepted','pool','prompt'):
            rows = copy_fixture(); c = rows[2]['choice']; ctx = c['inputContext']
            if error == 'profile': ctx.update(schemaVersion=16, profile='PERMITTED_CHOICE_VIEW_V16')
            if error == 'entry': c.pop('entryLocation')
            if error == 'selector': c['entryLocation'] = entry()
            if error == 'stage': c['stage'] = 'CONTROLLER_CALLBACK'
            if error == 'timing': ctx['timing'] = 'AFTER_SELECTION_BEFORE_APPLICATION'
            if error == 'directive':
                ctx['coverage']['receivedParameters'] = 'RECEIVED_CALLBACK_PARAMETERS_V1'; ctx['policy']['receivedParameters'] = dict(kind='TRIGGER_REQUIREMENT', mandatory=False)
            if error == 'receipt': c['options']['callbackParameters'] = dict(schemaVersion=1, coverage='COMPLETE', kind='TRIGGER_REQUIREMENT', values=dict(mandatory=False), reason=None)
            if error == 'accepted': rows[3]['choice']['selection']['accepted'] = True
            if error == 'pool': c['options']['items'] = []
            if error == 'prompt':
                ctx['coverage']['prompt'] = 'CONTROLLER_REQUEST_V1'; ctx['policy']['prompt'] = dict(kind='NUMBER_RANGE', minimum=0, maximum=3)
            with self.subTest(error=error), self.assertRaises(module.InvalidData): self.export(rows)

    def test_legacy_copy_absence_is_unknown_without_inventory_changes(self):
        rows = choices_fixture(); rows[2]['choice'].update(choiceType='EFFECT_COPY_TARGET_RESULT', boundary='COPY_TARGETS', stage='EFFECT_COPY_TARGET_SELECTION')
        rows[3]['choice']['selection'].pop('accepted')
        result, _ = self.export(rows); self.assertNotIn('entryLocationAudit', result['inputCoverageAudit'])
        self.assertFalse(any('COPY_TARGETS captures' in text for text in result['limitations']))
        self.assertEqual(result['inputCoverageAudit']['totals']['contextStatuses']['ABSENT'], 2)
