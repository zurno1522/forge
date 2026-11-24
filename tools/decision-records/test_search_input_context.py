import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_search_target_applications import search_fixture
from test_context_bindings import rows4
from test_entry_location import entry


def search_rows(operation='ROLLOUT_TARGET_OVERRIDE'):
    rows = search_fixture(operation); choice = rows[2]['decision']['searchTargetApplications'][0]
    context = copy.deepcopy(rows4()[2]['choice']['inputContext'])
    timing = 'BEFORE_OPERATION' if operation == 'HEURISTIC_REVALIDATION' else 'AFTER_SELECTION_BEFORE_APPLICATION'
    context.update(timing=timing, stateBasis='PLANNING_WORKING_STATE')
    context['perspective'].update(chooserPlayerId=0, controllingPlayerId=0)
    context['policy'].update(chooser='p0', turn=7, phase='MAIN2')
    context['diagnostics']['optionBindings'] = dict(coverage='UNSUPPORTED', items=[], truncated=False)
    location = entry(timing); location.update(stateBasis='PLANNING_WORKING_STATE', turn=6, phase='MAIN1')
    choice.update(inputContext=context, entryLocation=location)
    return rows


def hide(rows):
    choice = rows[2]['decision']['searchTargetApplications'][0]
    choice.update(operation='MASKED', optionCoverage='MASKED', requestedTarget=None, beforeTargets=None,
                  afterTargets=None, changedAbilities=[], status='MASKED')
    rows[4]['targetChoiceProvenance']['abilities'][0]['targetChoices'] = [None]
    context = choice['inputContext']
    context.update(status='UNAVAILABLE', timing='UNESTABLISHED', stateBasis='UNESTABLISHED', policy=None,
                   diagnostics=dict(playerBindings=[], cardBindings=[], stackBindings=[], optionBindings=dict(coverage='UNAVAILABLE', items=[], truncated=True)),
                   omissions=[dict(component='CONTEXT', reason='HIDDEN')])
    context['coverage'].update(playerView='UNAVAILABLE', disclosures='UNAVAILABLE', stack='UNAVAILABLE', prompt='UNAVAILABLE')
    choice['entryLocation'].update(status='UNAVAILABLE', kind='UNESTABLISHED', timing='UNESTABLISHED', stateBasis='UNESTABLISHED',
                                   turn=None, phase=None, turnPlayerId=None, reason='HIDDEN')


class SearchInputContextTests(unittest.TestCase):
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

    def test_search_owns_its_entry_clock_and_context_without_changing_legacy_report_claims(self):
        result, output = self.export(search_rows())
        self.assertEqual(result['inputContextOwnerStatuses'], {'SEARCH_OPERATION:CAPTURED': 1, 'DECISION:ABSENT': 1})
        self.assertTrue(any(row['turnBasis'] == 'ENTRY_LOCATION' and row['turn'] == 6 and row['playerId'] == 0
                            for row in result['inputCoverageAudit']['byTurn']))
        saved = json.loads((output/'dataset.jsonl').read_text())
        self.assertEqual(saved['decision']['searchTargetApplications'][0]['inputContext']['policy']['turn'], 7)
        self.assertFalse(saved['trainingReady'])
        self.assertIsNone(result['inputCoverageAudit']['unobservedDecisionCount'])
        self.assertEqual(result['inputCoverageAudit']['entryLocationAudit']['knownInputBoundaryInventoryV2']['SEARCH_OPERATION']['GUARD_REAIM'],
                         'AFTER_SELECTION_BEFORE_APPLICATION')
        legacy, _ = self.export(search_fixture())
        self.assertNotIn('inputContextRecords', legacy)
        self.assertNotIn('entryLocationAudit', legacy['inputCoverageAudit'])
        self.assertTrue(any(row['turnBasis'] == 'PARENT_CONTAINER' for row in legacy['inputCoverageAudit']['byTurn']))

    def test_revalidation_and_precomputed_applications_use_different_entry_kinds(self):
        for operation in ('HEURISTIC_REVALIDATION', 'RESTORE_APPROVED_TARGET', 'ROLLOUT_TARGET_OVERRIDE', 'DISCOVERED_TARGET', 'GUARD_REAIM'):
            with self.subTest(operation=operation):
                result, _ = self.export(search_rows(operation))
                kind = 'OPERATION' if operation == 'HEURISTIC_REVALIDATION' else 'APPLICATION'
                self.assertEqual(result['inputCoverageAudit']['entryLocationAudit']['kinds'], {kind: 1})

    def test_masked_search_has_no_view_clock_or_operation_kind(self):
        rows = search_rows(); hide(rows)
        result, _ = self.export(rows)
        self.assertEqual(result['inputContextStatuses'], {'UNAVAILABLE': 1, 'ABSENT': 1})
        self.assertTrue(any(row['turnBasis'] == 'ENTRY_LOCATION_UNAVAILABLE' and row['turn'] is None
                            for row in result['inputCoverageAudit']['byTurn']))

    def test_invalid_search_context_claims_are_rejected(self):
        for error in ('rules', 'selector', 'revalidation', 'missing-entry', 'player', 'prompt', 'pool',
                      'masked-clock', 'masked-operation-kind', 'hidden-public', 'null-input', 'null-entry'):
            rows = search_rows(); choice = rows[2]['decision']['searchTargetApplications'][0]
            context = choice['inputContext']; location = choice['entryLocation']
            if error == 'rules': context['stateBasis'] = location['stateBasis'] = 'RULES_STATE'
            if error == 'selector':
                context['timing'] = location['timing'] = 'BEFORE_SELECTOR'; location['kind'] = 'SELECTOR'
            if error == 'revalidation': choice['operation'] = 'HEURISTIC_REVALIDATION'; choice['requestedTarget'] = None
            if error == 'missing-entry': choice.pop('entryLocation')
            if error == 'player': location['turnPlayerId'] = 99
            if error == 'prompt':
                context['coverage']['prompt'] = 'CONTROLLER_REQUEST_V1'
                context['policy']['prompt'] = dict(kind='NUMBER_RANGE', minimum=0, maximum=1)
            if error == 'pool': context['diagnostics']['optionBindings'] = dict(coverage='DIAGNOSTIC_PUBLIC_CURRENT_V1', items=[], truncated=False)
            if error == 'masked-clock':
                saved = copy.deepcopy(location); hide(rows); choice['entryLocation'] = saved
            if error == 'masked-operation-kind': hide(rows); context['timing'] = 'AFTER_SELECTION_BEFORE_APPLICATION'
            if error == 'hidden-public':
                hide(rows); choice['operation'] = 'ROLLOUT_TARGET_OVERRIDE'
            if error == 'null-input': choice['inputContext'] = None
            if error == 'null-entry': choice['entryLocation'] = None
            with self.subTest(error=error), self.assertRaises(module.InvalidData): self.export(rows)
