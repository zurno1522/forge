import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_choice_events import choices_fixture
from test_received_parameters import parameter_fixture, receipt
from test_entry_location import entry


def trigger_fixture(mandatory=True, accepted=True):
    rows = parameter_fixture('NONE', {}); start = rows[2]['choice']; context = start['inputContext']
    context.update(schemaVersion=17, profile='PERMITTED_CHOICE_VIEW_V17', timing='BEFORE_OPERATION')
    context['policy']['prompt'] = None; context['coverage']['prompt'] = 'UNSUPPORTED'
    context['policy']['receivedParameters'] = dict(kind='TRIGGER_REQUIREMENT', mandatory=mandatory)
    plan = dict(abilities=[dict(abilityId=start['abilityId'], targets=[], targetsTruncated=False)], abilitiesTruncated=False)
    start.update(choiceType='TRIGGER_PREPARATION_RESULT', boundary='TRIGGER_PREPARATION', stage='TRIGGER_PREPARATION', entryLocation=entry('BEFORE_OPERATION'),
        options=dict(coverage='PRIOR_TARGET_PLAN_ONLY', priorTargets=copy.deepcopy(plan), callbackParameters=receipt('TRIGGER_REQUIREMENT', dict(mandatory=mandatory))))
    result = copy.deepcopy(plan); result['abilities'][0]['targets'] = [dict(type='PLAYER', id=0)]
    rows[3]['choice']['selection'] = dict(accepted=accepted, targets=result)
    return rows


class TriggerPreparationInputTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup); self.root = Path(self.tmp.name); self.index = 0

    def export(self, rows):
        self.index += 1; path = self.root / f'{self.index}.jsonl'; output = self.root / f'out-{self.index}'
        path.write_text(''.join(json.dumps(row) + '\n' for row in rows))
        result = module.export([path], output); self.assertEqual(result, module.export([path])); return result, output

    def test_preparation_directive_and_acceptance_are_independent_operation_facts(self):
        for mandatory in (False, True):
            for accepted in (False, True):
                with self.subTest(mandatory=mandatory, accepted=accepted):
                    result, output = self.export(trigger_fixture(mandatory, accepted))
                    entry_audit = result['inputCoverageAudit']['entryLocationAudit']
                    self.assertEqual(entry_audit['knownInputBoundaryInventoryExtensionV7'], {'CHOICE': {'TRIGGER_PREPARATION':'BEFORE_OPERATION'}})
                    self.assertNotIn('TRIGGER_PREPARATION', result['inputCoverageAudit']['knownInputBoundaryInventoryV1'])
                    self.assertNotIn('TRIGGER_PREPARATION', entry_audit['knownInputBoundaryInventoryV2']['CHOICE'])
                    self.assertTrue(any('V17' in text and 'mandatory directive' in text for text in result['limitations']))
                    self.assertFalse(json.loads((output/'choices.jsonl').read_text())['trainingReady'])

    def test_unknown_compatibility_directive_is_not_inferred_from_acceptance(self):
        rows = trigger_fixture(); child = rows[2]['choice']; ctx = child['inputContext']
        child['options']['callbackParameters'] = dict(schemaVersion=1, coverage='UNSUPPORTED', kind=None, values=None, reason='UNSUPPORTED')
        ctx['policy']['receivedParameters'] = None; ctx['coverage']['receivedParameters'] = 'UNAVAILABLE'; ctx['status'] = 'PARTIAL'
        ctx['omissions'].append(dict(component='PARAMETERS', reason='UNSUPPORTED')); self.export(rows)
        ctx['policy']['receivedParameters'] = dict(kind='TRIGGER_REQUIREMENT', mandatory=True); ctx['coverage']['receivedParameters'] = 'RECEIVED_CALLBACK_PARAMETERS_V1'
        with self.assertRaises(module.InvalidData): self.export(rows)

    def test_planning_inputs_keep_their_operation_basis_and_error_lifecycle(self):
        rows = trigger_fixture(); child = rows[2]['choice']; ctx = child['inputContext']; ctx['stateBasis'] = child['entryLocation']['stateBasis'] = 'PLANNING_WORKING_STATE'
        self.export(rows)
        rows[3]['choice'].update(status='ERROR', selection=None); self.export(rows)

    def test_profile_stage_entry_boolean_and_role_injection_are_rejected(self):
        errors = ('old-profile','no-entry','selector-entry','wrong-stage','missing-receipt','missing-directive','bool-int','bool-string',
            'wrong-projection','extra-option','extra-feature','extra-diagnostic','raw-role','unknown-coverage','fake-none')
        for error in errors:
            rows = trigger_fixture(); child = rows[2]['choice']; ctx = child['inputContext']; raw = child['options']['callbackParameters']; value = ctx['policy']['receivedParameters']
            if error == 'old-profile': ctx.update(schemaVersion=16, profile='PERMITTED_CHOICE_VIEW_V16')
            if error == 'no-entry': child.pop('entryLocation')
            if error == 'selector-entry': child['entryLocation'] = entry()
            if error == 'wrong-stage': child['stage'] = 'CONTROLLER_CALLBACK'
            if error == 'missing-receipt': child['options'].pop('callbackParameters')
            if error == 'missing-directive': raw['values'].pop('mandatory')
            if error == 'bool-int': raw['values']['mandatory'] = value['mandatory'] = 1
            if error == 'bool-string': raw['values']['mandatory'] = value['mandatory'] = 'true'
            if error == 'wrong-projection': value['mandatory'] = False
            if error == 'extra-option': child['options']['isLegal'] = True
            if error == 'extra-feature': value['accepted'] = True
            if error == 'extra-diagnostic': raw['values']['title'] = 'private'
            if error == 'raw-role': value['sourceId'] = 8
            if error == 'unknown-coverage': ctx['coverage']['receivedParameters'] = 'COMPLETE'
            if error == 'fake-none': raw.update(kind='NONE', values={}); value.clear(); value['kind'] = 'NONE'
            with self.subTest(error=error), self.assertRaises(module.InvalidData): self.export(rows)

    def test_new_directive_never_expands_numeric_or_entity_family_permission(self):
        for family in ('NUMBER', 'ENTITY'):
            rows = parameter_fixture('NONE', {}, family); child = rows[2]['choice']; ctx = child['inputContext']; ctx.update(schemaVersion=17, profile='PERMITTED_CHOICE_VIEW_V17')
            child['options']['callbackParameters'] = receipt('TRIGGER_REQUIREMENT', dict(mandatory=True))
            ctx['policy']['receivedParameters'] = dict(kind='TRIGGER_REQUIREMENT', mandatory=True)
            with self.subTest(family=family), self.assertRaises(module.InvalidData): self.export(rows)
        rows = trigger_fixture(); child = rows[2]['choice']; child['options']['callbackParameters'] = receipt('VOTER', dict(voter=dict(type='PLAYER', id=0)))
        child['inputContext']['policy']['receivedParameters'] = dict(kind='VOTER', voter='p0')
        with self.assertRaises(module.InvalidData): self.export(rows)

    def test_legacy_preparation_absence_and_inventories_remain_unchanged(self):
        rows = choices_fixture(); rows[2]['choice'].update(choiceType='TRIGGER_PREPARATION_RESULT', boundary='TRIGGER_PREPARATION', stage='TRIGGER_PREPARATION')
        result, _ = self.export(rows)
        self.assertFalse(any('V17' in text for text in result['limitations']))
        self.assertNotIn('entryLocationAudit', result['inputCoverageAudit'])
        self.assertEqual(result['inputCoverageAudit']['totals']['contextStatuses']['ABSENT'], 2)
