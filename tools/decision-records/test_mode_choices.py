import copy
import json
import tempfile
import unittest
from pathlib import Path
from test_choice_events import choices_fixture
from test_export import module
from test_context_bindings import rows4
from test_entry_location import entry, target_rows


def mode_fixture():
    rows = choices_fixture()
    start = rows[2]['choice']; end = rows[3]['choice']
    start.update(choiceType='MODE_SELECTION', boundary='CONTROLLER_MODES',
                 options=dict(coverage='OBSERVED_MODE_OPTIONS', items=[dict(abilityId=8, optionIndex=0, definitionIndex=1), dict(abilityId=9, optionIndex=1, definitionIndex=2)],
                              truncated=False, min=0, max=3, allowRepeat=True))
    end['selection'] = dict(available=True, modes=[dict(abilityId=8, optionIndex=0, definitionIndex=1)] * 2, selectedCount=2, truncated=False, accepted=None)
    return rows


def mode_input_fixture(boundary='CONTROLLER_MODES'):
    rows = mode_fixture(); choice = rows[2]['choice']
    timing = 'BEFORE_OPERATION' if boundary == 'CONTROLLER_MODES' else 'BEFORE_SELECTOR'
    choice.update(boundary=boundary, stage='CONTROLLER_CALLBACK', entryLocation=entry(timing),
                  inputContext=rows4()[2]['choice']['inputContext'])
    choice['entryLocation']['stateBasis'] = 'PLANNING_WORKING_STATE'
    context = choice['inputContext']
    context.update(timing=timing, stateBasis='PLANNING_WORKING_STATE')
    context['diagnostics']['optionBindings'] = dict(coverage='UNSUPPORTED', items=[], truncated=False)
    return rows


class ModeChoiceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def write(self, rows, name='input.jsonl'):
        p = self.root / name
        p.write_text(''.join(json.dumps(r) + '\n' for r in rows))
        return p

    def test_repeated_and_unmapped_modes_are_retained_as_returned(self):
        for mapped in (True, False):
            rows = mode_fixture()
            if not mapped:
                rows[3]['choice']['selection']['modes'] = [dict(abilityId=99, optionIndex=None, definitionIndex=None)]
                rows[3]['choice']['selection']['selectedCount'] = 1
            out = self.root / str(mapped)
            summary = module.export([self.write(rows, str(mapped) + '.jsonl')], out)
            self.assertEqual(summary['standaloneChoiceTypes'], {'MODE_SELECTION': 1})
            self.assertEqual(summary['standaloneModeOptionLinks'], {'EXACT_CAPTURED_OPTION': 2} if mapped else {'UNMAPPED': 1})
            self.assertEqual(summary['standaloneRepeatedModeReferences'], 1 if mapped else 0)
            choice = json.loads((out / 'choices.jsonl').read_text())
            self.assertEqual(choice['terminal']['choice']['selection'], rows[3]['choice']['selection'])

    def test_mode_prefix_bounds_and_total_counts_remain_explicit(self):
        rows = mode_fixture()
        value = rows[3]['choice']['selection']
        value.update(modes=value['modes'] * 128, selectedCount=300, truncated=True)
        self.assertEqual(module.export([self.write(rows)])['standaloneChoiceRecords'], 1)

    def test_unavailable_mode_plan_is_not_counted_as_a_captured_empty_plan(self):
        for available in (False, True):
            rows = mode_fixture()
            rows[3]['choice']['selection'].update(available=available, modes=[], selectedCount=0 if available else None,
                                                 truncated=False, accepted=available)
            summary = module.export([self.write(rows, str(available) + '.jsonl')])
            self.assertEqual(summary['standaloneModePlanCoverage'], {'OBSERVED' if available else 'UNAVAILABLE': 1})
            self.assertEqual(summary['standaloneModePlanCapture'], {'FULL_LIST' if available else 'UNAVAILABLE': 1})

    def test_mode_identity_flags_and_private_fields_are_validated(self):
        for mutation in ('option-index', 'identity', 'bool-index', 'count', 'private', 'available', 'flags'):
            with self.subTest(mutation=mutation):
                rows = mode_fixture()
                start = rows[2]['choice']; value = rows[3]['choice']['selection']
                if mutation == 'option-index': value['modes'][0]['optionIndex'] = 9
                elif mutation == 'identity': value['modes'][0]['abilityId'] = 99
                elif mutation == 'bool-index': value['modes'][0]['optionIndex'] = True
                elif mutation == 'count': value['selectedCount'] = 0
                elif mutation == 'private': start['options']['items'][0]['description'] = 'hidden text'
                elif mutation == 'available': value['available'] = False
                elif mutation == 'flags': start['options']['allowRepeat'] = 1
                with self.assertRaises(module.InvalidData): module.export([self.write(rows, mutation + '.jsonl')])

    def test_mode_inputs_keep_selector_and_operation_distinct_without_changing_legacy_inventories(self):
        legacy = module.export([self.write(target_rows(), 'legacy.jsonl')])['inputCoverageAudit']['entryLocationAudit']
        self.assertNotIn('knownInputBoundaryInventoryExtensionV3', legacy)
        for boundary, kind, timing in (('CHARM_MODE_HEURISTIC', 'SELECTOR', 'BEFORE_SELECTOR'),
                                        ('CONTROLLER_MODES', 'OPERATION', 'BEFORE_OPERATION')):
            rows = mode_input_fixture(boundary); out = self.root / boundary
            result = module.export([self.write(rows, boundary+'.jsonl')], out)
            self.assertEqual(result['inputCoverageAudit'], module.export([self.write(rows)])['inputCoverageAudit'])
            audit = result['inputCoverageAudit']; location = audit['entryLocationAudit']
            self.assertEqual(location['kinds'], {kind: 1})
            self.assertEqual(location['knownInputBoundaryInventoryV2'], legacy['knownInputBoundaryInventoryV2'])
            self.assertNotIn(boundary, audit['knownInputBoundaryInventoryV1'])
            self.assertEqual(location['knownInputBoundaryInventoryExtensionV3'], {'CHOICE': {boundary: timing}})
            self.assertEqual(next(r for r in audit['byTurn'] if r['turnBasis'] == 'ENTRY_LOCATION')['turn'], 0)
            exported = json.loads((out/'choices.jsonl').read_text())
            self.assertFalse(exported['trainingReady'])
            self.assertEqual(exported['start']['choice'], rows[2]['choice'])
        old = module.export([self.write(mode_fixture(), 'old-mode.jsonl')])
        self.assertNotIn('entryLocationAudit', old['inputCoverageAudit'])

    def test_modes_reject_invented_selector_timing_committed_state_prompts_and_domain_links(self):
        for error in ('timing', 'basis', 'missing-entry', 'prompt', 'links', 'entry-basis'):
            rows = mode_input_fixture(); choice = rows[2]['choice']; context = choice['inputContext']
            if error == 'timing':
                context['timing'] = 'BEFORE_SELECTOR'
                choice['entryLocation'].update(kind='SELECTOR', timing='BEFORE_SELECTOR')
            if error == 'basis':
                context['stateBasis'] = choice['entryLocation']['stateBasis'] = 'RULES_STATE'
            if error == 'missing-entry': choice.pop('entryLocation')
            if error == 'prompt':
                context.update(stateBasis='RULES_STATE')
                choice['entryLocation']['stateBasis'] = 'RULES_STATE'
                context['coverage']['prompt'] = 'CONTROLLER_REQUEST_V1'
                context['policy']['prompt'] = dict(kind='NUMBER_RANGE', minimum=0, maximum=3)
            if error == 'links':
                context['diagnostics']['optionBindings'] = dict(coverage='DIAGNOSTIC_PUBLIC_CURRENT_V1', items=[], truncated=False)
            if error == 'entry-basis': choice['entryLocation']['stateBasis'] = 'RULES_STATE'
            with self.subTest(error=error), self.assertRaises(module.InvalidData): module.export([self.write(rows)])

    def test_entry_only_and_unavailable_mode_views_do_not_invent_a_fresh_selector_or_clock(self):
        rows = mode_input_fixture(); choice = rows[2]['choice']; choice.pop('inputContext')
        result = module.export([self.write(rows)])
        self.assertEqual(result['inputCoverageAudit']['totals']['contextStatuses'], {'ABSENT': 2})
        context = mode_input_fixture()[2]['choice']['inputContext']; choice['inputContext'] = context
        context.update(status='UNAVAILABLE', policy=None, omissions=[dict(component='CONTEXT', reason='BUDGET_EXHAUSTED')])
        context['coverage'].update(playerView='UNAVAILABLE', stack='UNAVAILABLE', disclosures='UNAVAILABLE', prompt='UNAVAILABLE')
        context['diagnostics'] = dict(playerBindings=[], cardBindings=[], stackBindings=[], optionBindings=dict(coverage='UNAVAILABLE', items=[], truncated=True))
        result = module.export([self.write(rows)])
        self.assertEqual(result['inputCoverageAudit']['entryLocationAudit']['kinds'], {'OPERATION': 1})
