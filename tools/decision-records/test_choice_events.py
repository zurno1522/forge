import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from test_export import fixture, engine_fixture, module


def choices_fixture(parent=False, action=False):
    rows = engine_fixture() if action else fixture()
    rows[0]['choiceEventSchemaVersion'] = 1
    plan = dict(abilities=[dict(abilityId=7, targets=[], targetsTruncated=False)], abilitiesTruncated=False)
    start = dict(choiceSchemaVersion=1, choiceId='opaque choice', parentChoiceId=None, status='STARTED', choiceType='TARGET_SELECTION_RESULT',
                 boundary='CONTROLLER_TARGETS', stage='DELEGATED_TARGET_SELECTION', chooserPlayerId=1, chooserKind='AI',
                 abilityId=7, sourceId=8, decisionId='0:1' if parent or action else None,
                 actionId='0:1:0' if action else None, candidateAbilityId=7 if parent else None,
                 assessmentId=None,
                 resolvingAbilityId=None, options=dict(coverage='PRIOR_TARGET_PLAN_ONLY', priorTargets=plan))
    result = copy.deepcopy(plan)
    result['abilities'][0]['targets'] = [dict(type='PLAYER', id=0)]
    end = dict(choiceSchemaVersion=1, choiceId=start['choiceId'], status='COMPLETED', elapsedNanos=10,
               selection=dict(accepted=True, targets=result))
    position = 4 if action else 2
    if parent:
        rows[2]['decision']['candidates'] = [dict(abilityId=7, sourceId=8, kind='SPELL', assessment='WillPlay')]
    rows[position:position] = [dict(type='AI_SUBORDINATE_CHOICE', choice=start), dict(type='AI_SUBORDINATE_CHOICE', choice=end)]
    for i, row in enumerate(rows[1:-1], 1): row.update(game=1, eventIndex=i)
    return rows


class StandaloneChoiceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def write(self, rows, name='input.jsonl'):
        path = self.root / name
        path.write_text(''.join(json.dumps(r) + '\n' for r in rows))
        return path

    def test_explicit_assessment_identity_is_validated_without_matching_repeated_values(self):
        rows = choices_fixture(parent=True)
        decision = next(r['decision'] for r in rows if r['type'] == 'AI_DECISION')
        decision['candidates'][0]['assessmentId'] = 'assessment-first'
        decision['candidates'].append(dict(decision['candidates'][0], assessmentId='assessment-second'))
        rows[2]['choice']['assessmentId'] = 'assessment-first'
        self.assertEqual(module.export([self.write(rows)])['standaloneChoiceRecords'], 1)
        rows[2]['choice']['assessmentId'] = 'invented'
        with self.assertRaises(module.InvalidData): module.export([self.write(rows, 'invalid-assessment.jsonl')])

    def test_setup_choices_are_retained_but_censored(self):
        rows = choices_fixture()
        rows[1:4] = [rows[2], rows[3], rows[1]]
        for i, r in enumerate(rows[1:-1], 1): r['eventIndex'] = i
        out = self.root / 'out'
        module.export([self.write(rows)], out)
        self.assertTrue(json.loads((out / 'choices.jsonl').read_text())['censored'])

    def test_nested_choices_keep_explicit_causality_and_validate_completion_order(self):
        rows = choices_fixture()
        outer_start, outer_end = copy.deepcopy(rows[2]), copy.deepcopy(rows[3])
        inner_start, inner_end = copy.deepcopy(outer_start), copy.deepcopy(outer_end)
        inner_start['choice'].update(choiceId='inner', parentChoiceId=outer_start['choice']['choiceId'])
        inner_end['choice']['choiceId'] = 'inner'
        rows[2:4] = [outer_start, inner_start, inner_end, outer_end]
        for i, r in enumerate(rows[1:-1], 1): r['eventIndex'] = i
        self.assertEqual(module.export([self.write(rows)])['standaloneChoiceRecords'], 2)
        rows[4], rows[5] = rows[5], rows[4]
        for i, r in enumerate(rows[1:-1], 1): r['eventIndex'] = i
        with self.assertRaises(module.InvalidData): module.export([self.write(rows, 'wrong-order.jsonl')])

    def test_execution_pool_results_preserve_unique_outside_and_ambiguous_returns(self):
        for kind in ('UNIQUE', 'OUTSIDE_CAPTURED_POOL', 'AMBIGUOUS'):
            rows = choices_fixture()
            start, end = rows[2]['choice'], rows[3]['choice']
            ref = dict(type='CARD', id=1 << 41, gameTimestamp=1 << 50)
            items = [ref, copy.deepcopy(ref)] if kind == 'AMBIGUOUS' else [ref]
            start.update(choiceType='TARGET_POOL_SELECTION', boundary='DAMAGE_KILLABLES', options=dict(
                coverage='OBSERVED_HEURISTIC_POOL_NOT_ALL_LEGAL_TARGET_SETS', items=items,
                truncated=kind == 'OUTSIDE_CAPTURED_POOL', priorTargets=[], priorTargetsTruncated=False,
                poolSnapshotTiming='BEFORE_CHOOSER', purpose='TARGET_RECOMMENDATION'))
            selected = dict(ref, id=99) if kind == 'OUTSIDE_CAPTURED_POOL' else ref
            end['selection'] = dict(selected=selected, selectedOptionIndex=0 if kind == 'UNIQUE' else None, match=kind)
            self.assertEqual(module.export([self.write(rows, kind + '.jsonl')])['standaloneChoiceRecords'], 1)
            end['selection']['selectedOptionIndex'] = True
            with self.assertRaises(module.InvalidData): module.export([self.write(rows, kind + '-bad.jsonl')])

    def test_unlinked_choices_export_without_parent_decisions_and_preserve_actual_actor(self):
        rows = choices_fixture()
        rows = [r for r in rows if r['type'] != 'AI_DECISION']
        for i, r in enumerate(rows[1:-1], 1): r['eventIndex'] = i
        out = self.root / 'out'
        summary = module.export([self.write(rows)], out)
        self.assertEqual(summary['decisions'], 0)
        self.assertEqual(summary['standaloneChoiceRecords'], 1)
        choice = json.loads((out / 'choices.jsonl').read_text())
        self.assertEqual(choice['start']['choice']['chooserPlayerId'], 1)
        self.assertIsNone(choice['start']['choice']['actionId'])
        self.assertFalse(choice['trainingReady'])
        self.assertEqual((out / 'dataset.jsonl').read_text(), '')
        self.assertIn('choices.jsonl', (out / 'coverage.md').read_text())

    def test_future_parent_and_actual_started_action_links_validate_in_spool_and_memory(self):
        for parent, action in ((True, False), (False, True)):
            rows = choices_fixture(parent, action)
            path = self.write(rows, str(parent) + '.jsonl')
            run = module.load_run(path)
            self.assertEqual(len(list(module._choices.rows([run]))), 1)
            summary = module.export([path])
            self.assertEqual(summary['standaloneChoiceStages'], {'DELEGATED_TARGET_SELECTION': 1})

    def test_incomplete_evaluation_is_censored_even_when_game_finishes_normally(self):
        rows = choices_fixture(parent=True)
        rows = [r for r in rows if not (r['type'] == 'AI_SUBORDINATE_CHOICE' and r['choice']['status'] != 'STARTED')]
        next(r for r in rows if r['type'] == 'AI_DECISION')['decision']['status'] = 'AI_TIMEOUT'
        for i, r in enumerate(rows[1:-1], 1): r['eventIndex'] = i
        out = self.root / 'out'
        summary = module.export([self.write(rows)], out)
        choice = json.loads((out / 'choices.jsonl').read_text())
        self.assertEqual(choice['status'], 'INCOMPLETE')
        self.assertTrue(choice['censored'])
        self.assertEqual(summary['censoredStandaloneChoices'], 1)

    def test_trigger_preparation_preserves_accepted_plan_without_claiming_target_alternatives(self):
        rows = choices_fixture()
        rows[2]['choice'].update(choiceType='TRIGGER_PREPARATION_RESULT', boundary='TRIGGER_PREPARATION', stage='TRIGGER_PREPARATION')
        self.assertEqual(module.export([self.write(rows)])['standaloneChoiceTypes'], {'TRIGGER_PREPARATION_RESULT': 1})
        del rows[3]['choice']['selection']['accepted']
        with self.assertRaises(module.InvalidData): module.export([self.write(rows, 'bad-trigger.jsonl')])

    def test_invalid_choice_lifecycles_context_and_private_data_are_rejected(self):
        for mutation in ('duplicate-start', 'terminal-first', 'duplicate-end', 'missing-end', 'undeclared', 'schema', 'actor',
                         'stage', 'boundary', 'parent', 'action', 'candidate', 'private', 'private-target', 'target-root', 'negative-time'):
            with self.subTest(mutation=mutation):
                rows = choices_fixture(parent=True)
                start, end = rows[2]['choice'], rows[3]['choice']
                if mutation == 'duplicate-start': rows.insert(3, copy.deepcopy(rows[2]))
                elif mutation == 'terminal-first': rows[2], rows[3] = rows[3], rows[2]
                elif mutation == 'duplicate-end': rows.insert(4, copy.deepcopy(rows[3]))
                elif mutation == 'missing-end': del rows[3]
                elif mutation == 'undeclared': del rows[0]['choiceEventSchemaVersion']
                elif mutation == 'schema': start['choiceSchemaVersion'] = True
                elif mutation == 'actor': start['chooserPlayerId'] = 999
                elif mutation == 'stage': start['stage'] = 'GUESSED'
                elif mutation == 'boundary': start['boundary'] = 'COPY_TARGETS'
                elif mutation == 'parent': start['decisionId'] = 'invented'
                elif mutation == 'action': start['actionId'] = 'invented'
                elif mutation == 'candidate': start['candidateAbilityId'] = 99
                elif mutation == 'private': start['options']['prompt'] = 'private card text'
                elif mutation == 'private-target': end['selection']['targets']['abilities'][0]['targets'][0]['name'] = 'private'
                elif mutation == 'target-root': end['selection']['targets']['abilities'][0]['abilityId'] = 999
                elif mutation == 'negative-time': end['elapsedNanos'] = -1
                for i, r in enumerate(rows[1:-1], 1): r['eventIndex'] = i
                with self.assertRaises(module.InvalidData): module.export([self.write(rows, mutation + '.jsonl')])

    def test_optional_seventh_output_obeys_exclusive_publication_and_rollback(self):
        path = self.write(choices_fixture())
        out = self.root / 'out'
        real_link = module.os.link
        def racing_link(source, target):
            if Path(target).name == 'choices.jsonl': Path(target).write_text('external')
            return real_link(source, target)
        with mock.patch.object(module.os, 'link', side_effect=racing_link):
            with self.assertRaises(FileExistsError): module.export([path], out)
        self.assertEqual([p.name for p in out.iterdir()], ['choices.jsonl'])
        self.assertEqual((out / 'choices.jsonl').read_text(), 'external')

    def test_declared_empty_choice_output_differs_from_legacy_without_inventing_records(self):
        rows = fixture(); rows[0]['choiceEventSchemaVersion'] = 1
        out = self.root / 'out'
        summary = module.export([self.write(rows)], out)
        self.assertEqual((out / 'choices.jsonl').read_text(), '')
        self.assertEqual(summary['standaloneChoiceRecords'], 0)
