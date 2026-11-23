import copy
import json
import tempfile
import unittest
from pathlib import Path

from test_export import fixture, module, target_pool_fixture
from test_choice_events import choices_fixture
from test_input_context import input_context
from test_search_target_applications import search_fixture


class CoverageAuditTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def export(self, rows, name='case', output=True):
        path = self.root / (name + '.jsonl')
        path.write_text(''.join(json.dumps(row) + '\n' for row in rows))
        return module.export([path], self.root / name if output else None)

    def test_legacy_absence_has_an_explicit_observed_denominator_and_unknown_unobserved_count(self):
        result = self.export(fixture())
        audit = result['inputCoverageAudit']
        self.assertEqual(audit['observedOwners'], {'DECISION': 1})
        self.assertEqual(audit['totals']['records'], 1)
        self.assertEqual(audit['totals']['contextStatuses'], {'ABSENT': 1})
        self.assertEqual(audit['totals']['components'], {})
        self.assertEqual(audit['totals']['optionBindings'], {'NO_CONTEXT': 1})
        self.assertIsNone(audit['unobservedDecisionCount'])
        self.assertIsNone(audit['completeLearningExampleCount'])
        self.assertNotIn('inputContextRecords', result)
        self.assertIn('Unobserved decision count', (self.root / 'case/coverage.md').read_text())
        dataset = json.loads((self.root / 'case/dataset.jsonl').read_text())
        self.assertNotIn('inputContext', dataset['decision'])
        self.assertEqual(len(list((self.root / 'case').iterdir())), 6)

    def test_known_missing_inputs_are_grouped_by_boundary_player_and_turn_without_double_counting_terminals(self):
        rows = choices_fixture()
        base_start, base_end = copy.deepcopy(rows[2]), copy.deepcopy(rows[3])
        pairs = []
        for index, status in enumerate(('CAPTURED', 'PARTIAL', 'UNAVAILABLE', 'ABSENT')):
            start, end = copy.deepcopy(base_start), copy.deepcopy(base_end)
            start['choice']['choiceId'] = end['choice']['choiceId'] = 'choice-' + str(index)
            start.update(turn=index + 1, phase='MAIN1')
            if status != 'ABSENT':
                c = input_context(); c['status'] = status; c['policy']['turn'] = index + 1
                if status == 'PARTIAL':
                    c['omissions'] = [dict(component=component, reason='OUTSIDE_CAPTURED_PREFIX')
                                      for component in ('ENTITIES', 'MANA')]
                elif status == 'UNAVAILABLE':
                    c.update(policy=None, diagnostics=dict(playerBindings=[], cardBindings=[]),
                             omissions=[dict(component='CONTEXT', reason='BUDGET_EXHAUSTED')])
                    c['coverage'].update(playerView='UNAVAILABLE', disclosures='UNAVAILABLE')
                start['choice']['inputContext'] = c
            pairs.extend([start, end])
        rows[2:4] = pairs
        for i, row in enumerate(rows[1:-1], 1): row['eventIndex'] = i
        audit = self.export(rows)['inputCoverageAudit']
        self.assertEqual(audit['observedOwners'], {'DECISION': 1, 'CHOICE': 4})
        self.assertEqual(audit['totals']['records'], 5)
        self.assertEqual(audit['totals']['contextStatuses'], {'ABSENT': 2, 'CAPTURED': 1, 'PARTIAL': 1, 'UNAVAILABLE': 1})
        self.assertEqual(audit['totals']['omissionReasons'], {'OUTSIDE_CAPTURED_PREFIX': 1, 'BUDGET_EXHAUSTED': 1})
        self.assertEqual(audit['totals']['omissionComponents']['MANA:OUTSIDE_CAPTURED_PREFIX'], 1)
        self.assertEqual(audit['totals']['components']['stack:UNSUPPORTED'], 3)
        self.assertEqual(audit['totals']['optionBindings'], {'NO_CONTEXT': 2, 'DIAGNOSTIC_ONLY_V1': 3})
        boundary = next(row for row in audit['byBoundary'] if row['owner'] == 'CHOICE')
        self.assertEqual(boundary['counts']['records'], 4)
        exhausted = next(row for row in audit['byTurn'] if row['turn'] == 3)
        self.assertEqual(exhausted['playerId'], 1)
        self.assertEqual(exhausted['counts']['omissionReasons'], {'BUDGET_EXHAUSTED': 1})

    def test_nested_and_search_observations_are_counted_with_container_timing_not_as_fresh_inputs(self):
        for name, rows, owner in [('nested', target_pool_fixture(), 'NESTED_TARGET'),
                                  ('search', search_fixture(), 'SEARCH_OPERATION')]:
            audit = self.export(rows, name)['inputCoverageAudit']
            self.assertEqual(audit['observedOwners'][owner], 1)
            group = next(row for row in audit['byBoundary'] if row['owner'] == owner)
            self.assertEqual(group['counts']['contextStatuses'], {'ABSENT': 1})
            self.assertEqual(group['counts']['inputBoundaries'], {'NO_ESTABLISHED_INPUT_BOUNDARY_V1': 1})
            turn = next(row for row in audit['byTurn'] if row['turnBasis'] == 'PARENT_CONTAINER')
            self.assertEqual(turn['turn'], 1)

    def test_unknown_choice_turn_is_not_filled_from_the_parent_or_context(self):
        rows = choices_fixture()
        rows[2]['choice']['inputContext'] = input_context()
        audit = self.export(rows)['inputCoverageAudit']
        group = next(row for row in audit['byTurn'] if row['turnBasis'] == 'CHOICE_START_EVENT')
        self.assertIsNone(group['turn'])
        self.assertEqual(group['counts']['contextStatuses'], {'CAPTURED': 1})

    def test_disk_streaming_and_in_memory_aggregation_agree_and_turns_do_not_merge_across_runs(self):
        rows = choices_fixture()
        disk = self.export(rows, 'first')['inputCoverageAudit']
        run = module.load_run(self.root / 'first.jsonl')
        memory = module.make_coverage([run], list(module.dataset_rows(run)))['inputCoverageAudit']
        self.assertEqual(disk, memory)
        other = copy.deepcopy(rows); other[0]['runId'] = 'other-run'
        self.export(other, 'other', output=False)
        result = module.export([self.root / 'first.jsonl', self.root / 'other.jsonl'])['inputCoverageAudit']
        self.assertEqual(result['totals']['records'], 4)
        self.assertEqual(len(result['byTurn']), 4)
        self.assertEqual({row['runId'] for row in result['byTurn']}, {'example', 'other-run'})

    def test_announcement_is_an_operation_in_the_audit_not_a_claim_of_selection(self):
        rows = choices_fixture()
        rows[2]['choice'].update(choiceType='NUMBER_SELECTION', boundary='CONTROLLER_ANNOUNCEMENT',
                                options=dict(coverage='OBSERVED_RANGE', minimum=0, maximum=2), inputContext=input_context())
        rows[3]['choice']['selection'] = dict(value=1, optionIndex=None, domainMatch='WITHIN_OBSERVED_DOMAIN')
        audit = self.export(rows)['inputCoverageAudit']
        self.assertEqual(audit['totals']['timings'], {'BEFORE_OPERATION': 1})
        self.assertEqual(audit['totals']['inputBoundaries'], {'KNOWN_INPUT_BOUNDARY_V1': 2})

    def test_masked_source_has_hidden_unavailable_context_without_claiming_an_audited_selector(self):
        rows = choices_fixture()
        c = input_context(timing='BEFORE_SELECTOR')
        c.update(status='UNAVAILABLE', policy=None, diagnostics=dict(playerBindings=[], cardBindings=[]),
                 omissions=[dict(component='CONTEXT', reason='HIDDEN')])
        c['coverage'].update(playerView='UNAVAILABLE', disclosures='UNAVAILABLE')
        rows[2]['choice'].update(choiceType='MASKED_CHOICE', boundary='MASKED_CHOICE', options=dict(coverage='MASKED'), inputContext=c)
        rows[3]['choice']['selection'] = None
        audit = self.export(rows)['inputCoverageAudit']
        self.assertEqual(audit['totals']['omissionReasons'], {'HIDDEN': 1})
        group = next(row for row in audit['byBoundary'] if row['owner'] == 'CHOICE')
        self.assertEqual(group['counts']['inputBoundaries'], {'NO_ESTABLISHED_INPUT_BOUNDARY_V1': 1})
        c['status'] = 'CAPTURED'
        with self.assertRaises(module.InvalidData): self.export(rows, 'unmasked')

    def test_parent_reported_lost_captures_stay_separate_from_observed_records_and_unknown_metadata(self):
        rows = target_pool_fixture()
        d = next(row['decision'] for row in rows if row['type'] == 'AI_DECISION')
        d['subordinateChoiceOmissions'] = dict(budget=5, interrupted=2)
        audit = self.export(rows)['inputCoverageAudit']
        self.assertEqual(audit['totals']['records'], 2)
        self.assertEqual(audit['totals']['knownUnrecordedCaptures'], {'NESTED_TARGET:budget': 5, 'NESTED_TARGET:interrupted': 2})
        self.assertEqual(audit['totals']['captureOmissionMetadata'], {'NESTED_TARGET:DECLARED': 1, 'SEARCH_OPERATION:UNAVAILABLE': 1})
        baseline = next(row for row in audit['byTurn'] if row['turnBasis'] == 'PARENT_BASELINE')
        self.assertEqual(baseline['counts']['knownUnrecordedCaptures']['NESTED_TARGET:budget'], 5)

    def test_incomplete_timeout_choice_is_still_an_observation_not_a_completed_learning_example(self):
        rows = choices_fixture()
        rows.pop(3)  # No terminal selection arrived before termination.
        for row in rows:
            if row['type'] == 'GAME_EXECUTION_FINISHED': row['status'] = 'TIMEOUT'
            if row['type'] == 'RUN_FINISHED': row.update(status='TIMEOUT', gamesNormal=0, gamesTimedOut=1)
        for index, row in enumerate(rows[1:-1], 1): row['eventIndex'] = index
        rows[2]['choice']['inputContext'] = input_context()
        result = self.export(rows)
        audit = result['inputCoverageAudit']
        self.assertEqual(audit['totals']['records'], 2)
        self.assertEqual(audit['totals']['contextStatuses'], {'ABSENT': 1, 'CAPTURED': 1})
        self.assertEqual(result['standaloneChoiceStatuses'], {'INCOMPLETE': 1})
        self.assertEqual(result['censoredStandaloneChoices'], 1)
        self.assertIsNone(audit['completeLearningExampleCount'])
