import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module, target_pool_fixture
from test_context_bindings import rows4
from test_entry_location import entry


def nested_rows(application=False):
    rows = target_pool_fixture(); choice = rows[2]['decision']['subordinateChoices'][0]
    context = copy.deepcopy(rows4()[2]['choice']['inputContext'])
    context['stateBasis'] = 'PLANNING_WORKING_STATE'
    context['perspective'].update(chooserPlayerId=0, controllingPlayerId=0)
    context['policy'].update(chooser='p0', turn=7, phase='MAIN2')
    context['diagnostics']['cardBindings'][0].update(id=choice['options'][0]['id'], gameTimestamp=choice['options'][0]['gameTimestamp'])
    context['diagnostics']['optionBindings']['items'] = [dict(optionIndex=0, entityRef='c0'), dict(optionIndex=1, entityRef=None)]
    timing = 'AFTER_SELECTION_BEFORE_APPLICATION' if application else 'BEFORE_SELECTOR'
    context['timing'] = timing
    location = entry(timing); location.update(stateBasis='PLANNING_WORKING_STATE', turn=7, phase='MAIN2')
    choice.update(inputContext=context, entryLocation=location, optionsTruncated=False, priorTargetsTruncated=False)
    if application: choice['poolSnapshotTiming'] = 'AT_TARGET_ADDITION'
    return rows


def hide(choice):
    context = choice['inputContext']
    context.update(status='UNAVAILABLE', policy=None,
                   diagnostics=dict(playerBindings=[], cardBindings=[], stackBindings=[], optionBindings=dict(coverage='UNAVAILABLE', items=[], truncated=True)),
                   omissions=[dict(component='CONTEXT', reason='HIDDEN')])
    context['coverage'].update(playerView='UNAVAILABLE', disclosures='UNAVAILABLE', stack='UNAVAILABLE', prompt='UNAVAILABLE')
    choice.update(boundary='MASKED_TARGET_POOL', purpose='MASKED_TARGET_PLANNING')
    choice['entryLocation'].update(status='UNAVAILABLE', kind='UNESTABLISHED', timing='UNESTABLISHED', stateBasis='UNESTABLISHED',
                                   turn=None, phase=None, turnPlayerId=None, reason='HIDDEN')


class NestedInputContextTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.index = 0

    def export(self, rows):
        self.index += 1; source = self.root / f'{self.index}.jsonl'
        source.write_text(''.join(json.dumps(row)+'\n' for row in rows))
        output = self.root / f'out-{self.index}'
        result = module.export([source], output)
        self.assertEqual(result, module.export([source]))
        return result, output

    def test_nested_clock_and_input_remain_independent_of_the_parent_container(self):
        result, output = self.export(nested_rows())
        self.assertEqual(result['inputContextOwnerStatuses'], {'NESTED_TARGET:CAPTURED': 1, 'DECISION:ABSENT': 1})
        groups = result['inputCoverageAudit']['byTurn']
        self.assertTrue(any(r['turnBasis'] == 'ENTRY_LOCATION' and r['turn'] == 7 and r['playerId'] == 0 for r in groups))
        record = json.loads((output/'dataset.jsonl').read_text())
        self.assertEqual(record['decision']['subordinateChoices'][0]['inputContext']['policy']['turn'], 7)
        self.assertFalse(record['trainingReady'])
        self.assertEqual(result['inputContextBindings']['publicOptionLinks'], 1)
        self.assertIsNone(result['inputCoverageAudit']['unobservedDecisionCount'])
        legacy, _ = self.export(target_pool_fixture())
        self.assertNotIn('entryLocationAudit', legacy['inputCoverageAudit'])
        self.assertTrue(any(r['turnBasis'] == 'PARENT_CONTAINER' for r in legacy['inputCoverageAudit']['byTurn']))

    def test_precomputed_application_keeps_its_legacy_timing(self):
        result, _ = self.export(nested_rows(application=True))
        self.assertEqual(result['inputContextTimings'], {'AFTER_SELECTION_BEFORE_APPLICATION': 1})
        self.assertEqual(result['inputCoverageAudit']['entryLocationAudit']['kinds'], {'APPLICATION': 1})

    def test_masked_nested_source_retains_no_view_or_entry_clock(self):
        rows = nested_rows(); hide(rows[2]['decision']['subordinateChoices'][0])
        result, _ = self.export(rows)
        self.assertEqual(result['inputContextStatuses'], {'UNAVAILABLE': 1, 'ABSENT': 1})
        self.assertTrue(any(r['turnBasis'] == 'ENTRY_LOCATION_UNAVAILABLE' and r['turn'] is None
                            for r in result['inputCoverageAudit']['byTurn']))

    def test_false_basis_timing_identity_and_missing_ownership_are_rejected(self):
        for error in ('rules', 'application', 'missing-entry', 'stale', 'prompt', 'player', 'masked-clock'):
            rows = nested_rows(); choice = rows[2]['decision']['subordinateChoices'][0]
            context = choice['inputContext']
            if error == 'rules':
                context['stateBasis'] = choice['entryLocation']['stateBasis'] = 'RULES_STATE'
            if error == 'application': choice['poolSnapshotTiming'] = 'AT_TARGET_ADDITION'
            if error == 'missing-entry': choice.pop('entryLocation')
            if error == 'stale': choice['options'][0]['gameTimestamp'] += 1
            if error == 'prompt':
                context['coverage']['prompt'] = 'CONTROLLER_REQUEST_V1'
                context['policy']['prompt'] = dict(kind='NUMBER_RANGE', minimum=0, maximum=1)
            if error == 'player': choice['entryLocation']['turnPlayerId'] = 99
            if error == 'masked-clock':
                location = copy.deepcopy(choice['entryLocation']); hide(choice); choice['entryLocation'] = location
            with self.subTest(error=error), self.assertRaises(module.InvalidData): self.export(rows)
