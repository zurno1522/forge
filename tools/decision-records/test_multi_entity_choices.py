import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_context_bindings import rows4
from test_entry_location import entry


def multi_fixture():
    rows = rows4(); choice = rows[2]['choice']; context = choice['inputContext']
    offered = choice['options']['items'] + [dict(type='PLAYER', id=0)]
    choice.update(choiceType='EFFECT_MULTI_ENTITY_SELECTION', boundary='CONTROLLER_EFFECT_ENTITIES',
                  entryLocation=entry('BEFORE_OPERATION'),
                  options=dict(coverage='OBSERVED_EFFECT_OPTIONS_DIAGNOSTIC_ONLY', items=offered,
                               truncated=False, min=0, max=2, relatedPlayerId=0))
    context.update(schemaVersion=6, profile='PERMITTED_CHOICE_VIEW_V6', timing='BEFORE_OPERATION')
    context['policy']['prompt'] = dict(kind='ENTITY_BOUNDS', minimumPicks=0, maximumPicks=2, relatedPlayer='p0')
    context['coverage']['prompt'] = 'RECEIVED_ENTITY_BOUNDS_V1'
    context['diagnostics']['optionBindings'] = dict(coverage='UNSUPPORTED', items=[], truncated=False)
    rows[3]['choice']['selection'] = dict(coverage='OBSERVED_RETURNED_ENTITY_PREFIX', items=copy.deepcopy(offered), selectedCount=2, truncated=False)
    return rows


class MultiEntityChoiceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.index = 0

    def export(self, rows):
        self.index += 1; source = self.root/f'{self.index}.jsonl'; out = self.root/f'out-{self.index}'
        source.write_text(''.join(json.dumps(r)+'\n' for r in rows))
        result = module.export([source], out)
        self.assertEqual(result['inputCoverageAudit'], module.export([source])['inputCoverageAudit'])
        return result, out

    def test_outer_operation_has_its_own_entry_constraints_and_conditional_inventory_without_legal_domain_permission(self):
        rows = multi_fixture(); result, out = self.export(rows)
        audit = result['inputCoverageAudit']; location = audit['entryLocationAudit']
        self.assertEqual(location['kinds'], {'OPERATION': 1})
        self.assertEqual(location['knownInputBoundaryInventoryExtensionV4'], {'CHOICE': {'CONTROLLER_EFFECT_ENTITIES': 'BEFORE_OPERATION'}})
        self.assertNotIn('CONTROLLER_EFFECT_ENTITIES', audit['knownInputBoundaryInventoryV1'])
        self.assertNotIn('CONTROLLER_EFFECT_ENTITIES', location['knownInputBoundaryInventoryV2']['CHOICE'])
        self.assertNotIn('knownInputBoundaryInventoryExtensionV3', location)
        choice = json.loads((out/'choices.jsonl').read_text())
        self.assertFalse(choice['trainingReady']); self.assertEqual(choice['start']['choice'], rows[2]['choice'])
        self.assertTrue(any('V6' in s and 'prior-selection' in s for s in result['limitations']))
        legacy, _ = self.export(rows4())
        self.assertNotIn('entryLocationAudit', legacy['inputCoverageAudit'])

    def test_received_bounds_roles_planning_and_extreme_values_are_not_normalized(self):
        for basis in ('RULES_STATE', 'PLANNING_WORKING_STATE'):
            for minimum, maximum in ((0, 2), (9, 3), (-2147483648, 2147483647)):
                rows = multi_fixture(); choice = rows[2]['choice']; context = choice['inputContext']
                choice['options'].update(min=minimum, max=maximum)
                context['policy']['prompt'].update(minimumPicks=minimum, maximumPicks=maximum)
                choice['entryLocation']['stateBasis'] = context['stateBasis'] = basis
                self.export(rows)

    def test_inner_callbacks_remain_separate_explicit_children_and_are_not_counted_as_unique_decisions(self):
        rows = multi_fixture(); outer = rows[2]['choice']; children = []
        for i, ref in enumerate(outer['options']['items']):
            child = rows4()[2:4]; start, end = child[0]['choice'], child[1]['choice']
            start.update(choiceId=f'child-{i}', parentChoiceId=outer['choiceId'])
            end['choiceId'] = start['choiceId']
            start['options']['items'] = [copy.deepcopy(ref)]
            start['inputContext']['diagnostics']['optionBindings'] = dict(coverage='UNSUPPORTED', items=[], truncated=False)
            end['selection']['selected'] = copy.deepcopy(ref)
            children += child
        rows[3:3] = children
        for i, row in enumerate(rows[1:-1], 1): row['eventIndex'] = i
        result, out = self.export(rows)
        self.assertEqual(result['standaloneChoiceTypes'], {'EFFECT_MULTI_ENTITY_SELECTION': 1, 'EFFECT_ENTITY_SELECTION': 2})
        values = [json.loads(s) for s in (out/'choices.jsonl').read_text().splitlines()]
        self.assertEqual(sum(v['start']['choice']['parentChoiceId'] == outer['choiceId'] for v in values), 2)
        self.assertIsNone(result['inputCoverageAudit']['unobservedDecisionCount'])

    def test_empty_declined_duplicate_and_truncated_results_preserve_actual_returned_coverage(self):
        for kind in ('empty', 'declined', 'duplicate-prefix', 'unavailable-prefix'):
            rows = multi_fixture(); terminal = rows[3]['choice']; value = terminal['selection']
            if kind == 'empty': value.update(items=[], selectedCount=0)
            if kind == 'declined': terminal.update(status='DECLINED', selection=None)
            if kind == 'duplicate-prefix': value.update(items=[value['items'][0]]*256, selectedCount=300, truncated=True)
            if kind == 'unavailable-prefix': value.update(coverage='UNAVAILABLE', items=[], selectedCount=2, truncated=True)
            _, out = self.export(rows)
            self.assertEqual(json.loads((out/'choices.jsonl').read_text())['terminal']['choice'], terminal)

    def test_unavailable_outer_input_preserves_received_diagnostics_and_does_not_invent_history(self):
        rows = multi_fixture(); context = rows[2]['choice']['inputContext']
        context.update(status='UNAVAILABLE', policy=None, omissions=[dict(component='CONTEXT', reason='BUDGET_EXHAUSTED')])
        context['coverage'].update(playerView='UNAVAILABLE', stack='UNAVAILABLE', disclosures='UNAVAILABLE', prompt='UNAVAILABLE')
        context['diagnostics'] = dict(playerBindings=[], cardBindings=[], stackBindings=[], optionBindings=dict(coverage='UNAVAILABLE', items=[], truncated=True))
        result, out = self.export(rows)
        self.assertEqual(result['inputCoverageAudit']['entryLocationAudit']['kinds'], {'OPERATION': 1})
        choice = json.loads((out/'choices.jsonl').read_text())
        self.assertIsNone(choice['start']['choice']['inputContext']['policy'])
        self.assertEqual(choice['terminal']['choice']['selection']['selectedCount'], 2)

    def test_entity_bounds_permission_does_not_apply_to_the_old_atomic_selector(self):
        rows = rows4(); context = rows[2]['choice']['inputContext']
        context.update(schemaVersion=6, profile='PERMITTED_CHOICE_VIEW_V6')
        context['policy']['prompt'] = dict(kind='ENTITY_BOUNDS', minimumPicks=0, maximumPicks=2, relatedPlayer=None)
        context['coverage']['prompt'] = 'RECEIVED_ENTITY_BOUNDS_V1'
        with self.assertRaises(module.InvalidData): self.export(rows)

    def test_invalid_profile_boundary_role_arguments_history_claims_and_prefixes_are_rejected(self):
        for error in ('v5', 'missing-entry', 'selector', 'minimum', 'maximum', 'role', 'int-overflow', 'bool-bound', 'coverage',
                      'pool-links', 'prior-picks', 'count', 'truncated', 'raw-id', 'foreign-type'):
            rows = multi_fixture(); choice = rows[2]['choice']; context = choice['inputContext']; value = rows[3]['choice']['selection']
            if error == 'v5': context.update(schemaVersion=5, profile='PERMITTED_CHOICE_VIEW_V5')
            if error == 'missing-entry': choice.pop('entryLocation')
            if error == 'selector': context['timing'] = 'BEFORE_SELECTOR'; choice['entryLocation'].update(timing='BEFORE_SELECTOR', kind='SELECTOR')
            if error == 'minimum': context['policy']['prompt']['minimumPicks'] = 1
            if error == 'maximum': context['policy']['prompt']['maximumPicks'] = 3
            if error == 'role': context['policy']['prompt']['relatedPlayer'] = 'p1'
            if error == 'int-overflow': choice['options']['max'] = context['policy']['prompt']['maximumPicks'] = 1 << 40
            if error == 'bool-bound': context['policy']['prompt']['minimumPicks'] = False
            if error == 'coverage': context['coverage']['prompt'] = 'CONTROLLER_REQUEST_V1'
            if error == 'pool-links': context['diagnostics']['optionBindings'] = dict(coverage='DIAGNOSTIC_PUBLIC_CURRENT_V1', items=[], truncated=False)
            if error == 'prior-picks': context['policy']['prompt']['previousSelections'] = []
            if error == 'count': value['selectedCount'] = 0
            if error == 'truncated': value['truncated'] = True
            if error == 'raw-id': context['policy']['prompt']['relatedPlayerId'] = 0
            if error == 'foreign-type': value['items'][0] = dict(type='SPELL_ABILITY', id=7, stackId=None)
            with self.subTest(error=error), self.assertRaises(module.InvalidData): self.export(rows)
