import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_zone_operation import operation_fixture
from test_zone_request_input import zone_fixture
from test_entry_location import entry


def spell_fixture():
    rows = operation_fixture(); parent = rows[2]['choice']; context = parent['inputContext']
    refs = [dict(type='SPELL_ABILITY', id=101, stackId=None), dict(type='SPELL_ABILITY', id=102, stackId=None)]
    parent.update(choiceType='EFFECT_MULTI_SPELL_ABILITY_SELECTION', boundary='CONTROLLER_EFFECT_SPELL_ABILITIES')
    parent['options'] = dict(coverage='OBSERVED_SPELL_ABILITY_OPTIONS_DIAGNOSTIC_ONLY', items=copy.deepcopy(refs), truncated=False, requestedSelections=0)
    context.update(schemaVersion=13, profile='PERMITTED_CHOICE_VIEW_V13')
    context['coverage']['prompt'] = 'RECEIVED_SPELL_OPERATION_V1'
    context['policy']['prompt'] = dict(kind='SPELL_ABILITY_OPERATION_REQUEST', requestedSelections=0)
    rows[3]['choice']['selection'] = dict(coverage='OBSERVED_RETURNED_SPELL_ABILITY_PREFIX', items=[copy.deepcopy(refs[0])], selectedCount=1, truncated=False)
    children = zone_fixture()[2:4]; child = children[0]['choice']; ctx = child['inputContext']
    child.update(choiceType='EFFECT_SPELL_ABILITY_SELECTION', boundary='CONTROLLER_EFFECT_SPELL_ABILITY',
        choiceId='spell-child-2', parentChoiceId=parent['choiceId'], entryLocation=entry())
    child['options'] = dict(coverage='OBSERVED_SPELL_ABILITY_OPTIONS_DIAGNOSTIC_ONLY', items=copy.deepcopy(refs), truncated=False)
    ctx.update(schemaVersion=13, profile='PERMITTED_CHOICE_VIEW_V13')
    ctx['coverage']['prompt'] = 'UNSUPPORTED'; ctx['policy']['prompt'] = None
    ctx['diagnostics']['optionBindings'] = dict(coverage='UNSUPPORTED', items=[], truncated=False)
    children[1]['choice'].update(choiceId=child['choiceId'], selection=dict(selected=copy.deepcopy(refs[0]), selectedOptionIndex=0, match='UNIQUE'))
    rows[3:3] = children
    for index, row in enumerate(rows[1:-1], 1): row['eventIndex'] = index
    return rows


class SpellAbilityChoiceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.index = 0

    def export(self, rows):
        self.index += 1; source = self.root / f'{self.index}.jsonl'; output = self.root / f'out-{self.index}'
        source.write_text(''.join(json.dumps(row) + '\n' for row in rows))
        result = module.export([source], output); self.assertEqual(result, module.export([source]))
        return result, output

    def test_actual_request_is_not_a_legal_result_bound_and_new_inventory_is_conditional(self):
        result, out = self.export(spell_fixture())
        values = [json.loads(line) for line in (out/'choices.jsonl').read_text().splitlines()]
        self.assertEqual(len(values), 2); self.assertTrue(all(not value['trainingReady'] for value in values))
        audit = result['inputCoverageAudit']['entryLocationAudit']
        self.assertEqual(audit['knownInputBoundaryInventoryExtensionV6'], {'CHOICE': {
            'CONTROLLER_EFFECT_SPELL_ABILITIES': 'BEFORE_OPERATION', 'CONTROLLER_EFFECT_SPELL_ABILITY': 'BEFORE_SELECTOR'}})
        self.assertNotIn('CONTROLLER_EFFECT_SPELL_ABILITIES', result['inputCoverageAudit']['knownInputBoundaryInventoryV1'])
        self.assertNotIn('CONTROLLER_EFFECT_SPELL_ABILITY', audit['knownInputBoundaryInventoryV2']['CHOICE'])
        self.assertTrue(any('V13' in text and 'do-while' in text for text in result['limitations']))
        old, _ = self.export(operation_fixture())
        self.assertNotIn('knownInputBoundaryInventoryExtensionV6', old['inputCoverageAudit']['entryLocationAudit'])
        self.assertFalse(any('V13' in text for text in old['limitations']))

    def test_planning_requests_signed_int_bounds_and_unavailable_single_prompt(self):
        for count in (-(1 << 31), -1, 0, 2, (1 << 31)-1):
            rows = spell_fixture(); parent = rows[2]['choice']; context = parent['inputContext']
            parent['options']['requestedSelections'] = context['policy']['prompt']['requestedSelections'] = count
            parent['stage'] = 'CANDIDATE_EVALUATION'; context['stateBasis'] = parent['entryLocation']['stateBasis'] = 'PLANNING_WORKING_STATE'
            with self.subTest(count=count): self.export(rows)

    def test_duplicates_outside_prefix_and_unavailable_results_keep_diagnostic_meanings(self):
        rows = spell_fixture(); child = rows[3]['choice']; ref = child['options']['items'][0]
        child['options']['items'] = [copy.deepcopy(ref), copy.deepcopy(ref)]
        rows[4]['choice']['selection'].update(selectedOptionIndex=None, match='AMBIGUOUS')
        rows[5]['choice']['selection'].update(items=[copy.deepcopy(ref), copy.deepcopy(ref)], selectedCount=2)
        self.export(rows)
        rows = spell_fixture(); child = rows[3]['choice']; child['options']['items'] = [copy.deepcopy(child['options']['items'][1])]
        child['options']['truncated'] = True; rows[4]['choice']['selection'].update(selectedOptionIndex=None, match='OUTSIDE_CAPTURED_POOL')
        self.export(rows)
        rows = spell_fixture(); terminal = rows[5]['choice']['selection']; ref = terminal['items'][0]
        terminal.update(items=[copy.deepcopy(ref) for _ in range(256)], selectedCount=257, truncated=True)
        self.export(rows)
        terminal.update(coverage='UNAVAILABLE', items=[], selectedCount=2, truncated=True); self.export(rows)

    def test_declined_and_error_callbacks_are_distinct_from_empty_returned_lists(self):
        for status in ('DECLINED', 'ERROR'):
            rows = spell_fixture(); rows[4]['choice'].update(status=status, selection=None)
            rows[5]['choice']['selection'].update(items=[], selectedCount=0)
            with self.subTest(status=status): self.export(rows)

    def test_strict_source_types_requests_and_reference_validation(self):
        errors = ('old-parent', 'old-child', 'missing-entry', 'wrong-timing', 'wrong-boundary', 'bad-version-type',
            'wrong-profile', 'wrong-count', 'large-count', 'bool-count', 'card-option', 'card-result', 'extra-params',
            'title', 'single-prompt', 'wrong-index', 'false-ambiguity', 'bad-result-coverage', 'bad-prefix',
            'unavailable-options', 'history', 'pool-feature')
        for error in errors:
            rows = spell_fixture(); parent, child = (rows[i]['choice'] for i in (2, 3)); ctx = parent['inputContext']
            result = rows[4]['choice']['selection']; final = rows[5]['choice']['selection']
            if error in ('old-parent', 'old-child'):
                context = (parent if error == 'old-parent' else child)['inputContext']; context.update(schemaVersion=12, profile='PERMITTED_CHOICE_VIEW_V12')
            if error == 'missing-entry': child.pop('entryLocation')
            if error == 'wrong-timing': child['entryLocation']['timing'] = 'BEFORE_OPERATION'
            if error == 'wrong-boundary': child['boundary'] = 'CONTROLLER_EFFECT_ENTITY'
            if error == 'bad-version-type': child['inputContext']['schemaVersion'] = '13'
            if error == 'wrong-profile': ctx['profile'] = 'PERMITTED_CHOICE_VIEW_V12'
            if error == 'wrong-count': ctx['policy']['prompt']['requestedSelections'] = 1
            if error == 'large-count': parent['options']['requestedSelections'] = ctx['policy']['prompt']['requestedSelections'] = 1 << 31
            if error == 'bool-count': parent['options']['requestedSelections'] = ctx['policy']['prompt']['requestedSelections'] = True
            if error == 'card-option': child['options']['items'][0] = dict(type='CARD', id=101, gameTimestamp=1)
            if error == 'card-result': result['selected'] = dict(type='CARD', id=101, gameTimestamp=1)
            if error == 'extra-params': child['options']['params'] = dict(Affected=1)
            if error == 'title': child['options']['title'] = 'Secret'
            if error == 'single-prompt':
                child['inputContext']['policy']['prompt'] = dict(kind='SPELL_ABILITY_OPERATION_REQUEST', requestedSelections=1)
                child['inputContext']['coverage']['prompt'] = 'RECEIVED_SPELL_OPERATION_V1'
            if error == 'wrong-index': result['selectedOptionIndex'] = 1
            if error == 'false-ambiguity': result.update(match='AMBIGUOUS', selectedOptionIndex=None)
            if error == 'bad-result-coverage': final['coverage'] = 'OBSERVED_RETURNED_ENTITY_PREFIX'
            if error == 'bad-prefix': final.update(selectedCount=0, truncated=True)
            if error == 'unavailable-options': child['options']['coverage'] = 'UNAVAILABLE'
            if error == 'history': child['options']['selectionSequence'] = dict(priorSelectionCount=0)
            if error == 'pool-feature': ctx['policy']['prompt']['offeredAbilities'] = [101]
            with self.subTest(error=error), self.assertRaises(module.InvalidData): self.export(rows)
