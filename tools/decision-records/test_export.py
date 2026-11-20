import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import hashlib
import sqlite3

spec = importlib.util.spec_from_file_location('decision_export', Path(__file__).with_name('export.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def fixture(status='NORMAL', draw=False):
    meta = dict(type='RUN_STARTED', schemaVersion=1, runId='example', seed=123, pilotMode='HEURISTIC',
                participants=[dict(participantId=i+1, deckName=f'Deck {i}', deck={'Main':[{'name':'Island','count':60}]}) for i in range(2)])
    players = [dict(id=i, life=40, handCount=0, libraryCount=60, cards=[], floatingManaColors=[]) for i in range(2)]
    decision = dict(decisionSchemaVersion=1, decisionId='0:1', playerId=0, decisionType='MAIN_PHASE_PLAY_SELECTION',
                    candidateCoverage='OBSERVED_EVALUATIONS_NOT_ALL_LEGAL_ACTIONS', observationCoverage='PARTIAL_PLAYER_VIEW_V1',
                    status='COMPLETED', elapsedNanos=2, selection='PASS_PRIORITY', selectedActions=[], candidates=[],
                    observation=dict(turn=1, phase='MAIN1', turnPlayerId=0, players=players))
    outcome = dict(endReason='Draw' if draw else 'AllOpponentsLost', draw=draw,
                   winners=[] if draw else [dict(participantId=1, player=dict(id=0))])
    rows=[meta,
          dict(type='GAME_STARTED', players=[dict(participantId=i+1,player=dict(id=i)) for i in range(2)]),
          dict(type='AI_DECISION',turn=1,phase='MAIN1',decision=decision),
          dict(type='GAME_OUTCOME',**outcome),dict(type='GAME_FINISHED'),
          dict(type='GAME_EXECUTION_FINISHED',status=status),
          dict(type='RUN_FINISHED',status=status,gamesAttempted=1,gamesNormal=int(status=='NORMAL'),gamesTimedOut=int(status=='TIMEOUT'),gamesFailed=int(status=='EXCEPTION'))]
    for index,row in enumerate(rows[1:-1],1): row.update(game=1,eventIndex=index)
    return rows


def linked_fixture(result='SUCCEEDED', game_status='NORMAL'):
    rows = fixture(game_status)
    action = dict(actionId='0:1:0', abilityId=7, sourceId=8, kind='SPELL')
    rows[2]['decision'].update(actionExecutionSchemaVersion=1, selection='PLAY', selectedActions=[action])
    identity = dict(action, executionSchemaVersion=1, decisionId='0:1', playerId=0)
    executions = []
    if result != 'ABANDONED':
        executions.append(dict(type='AI_ACTION_EXECUTION', execution=dict(identity, status='STARTED', reason='EXECUTION_REQUESTED')))
    if result is not None:
        execution = dict(identity, status=result, reason='STACKED' if result == 'SUCCEEDED' else 'TEST_RESULT')
        if result == 'SUCCEEDED':
            execution.update(executedAbilityId=77, stackId=12)
        executions.append(dict(type='AI_ACTION_EXECUTION', execution=execution))
    rows[3:3] = executions
    for i, row in enumerate(rows[1:-1], 1): row.update(game=1, eventIndex=i)
    return rows


def engine_fixture(ending='resolved', filtered=True):
    rows = linked_fixture()
    rows[0]['actionEventSchemaVersion'] = 1
    if filtered:
        rows[0]['streamKind'] = 'AI_DECISIONS'
    identity = dict(decisionId='0:1', actionId='0:1:0', stackId=12, abilityId=77)
    rows.insert(4, dict(type='STACK_ADDED', **identity))
    if ending == 'resolved':
        events = [dict(type='STACK_RESOLVED', fizzled=False, **identity), dict(type='STACK_REMOVED', **identity)]
    elif ending == 'countered':
        events = [dict(type='STACK_REMOVED', **identity), dict(type='STACK_COUNTERED', **identity)]
    else:
        events = [dict(type='STACK_CLEARED', decisionId=None, actionId=None,
                       clearedActions=[{k: v for k, v in identity.items() if k != 'abilityId'}])]
    rows[6:6] = events
    for i, row in enumerate(rows[1:-1], 1): row.update(game=1, eventIndex=i)
    return rows


def target_fixture(filtered=True):
    rows = engine_fixture(filtered=filtered)
    rows[4]['finalizedTargets'] = dict(schemaVersion=1, abilities=[
        dict(abilityId=77, usesTargeting=True, targets=[
            dict(type='PLAYER', id=1, dividedAmount=1),
            dict(type='CARD', id=(1 << 41) + 8, gameTimestamp=(1 << 50) + 3, dividedAmount=2),
            dict(type='SPELL_ABILITY', id=(1 << 42) + 9, stackId=(1 << 43) + 10)]),
        dict(abilityId=78, usesTargeting=True, targets=[dict(type='CARD', id=(1 << 41) + 8, gameTimestamp=(1 << 50) + 3)])])
    return rows


def payment_fixture():
    rows = engine_fixture()
    rows[4]['finalizedPayments'] = dict(
        schemaVersion=1, coverage='ENGINE_PAYMENT_FACTS_V1',
        mana=[dict(color='B', playerId=0, source=dict(id=(1 << 41) + 8, gameTimestamp=(1 << 50) + 3), snow=False)],
        lastLifePayment=2, phyrexianLifePaid=4, optionalCosts=['Kicker1'], alternativeCost='Flashback',
        keywordAmounts=[dict(keyword='MULTIKICKER', amount=2)], withoutManaCost=False,
        cardPayments=[dict(type='Sacrificed', intrinsic=True, cards=[dict(id=(1 << 41) + 9, gameTimestamp=(1 << 50) + 4)])],
        unrecognizedCardPaymentGroups=1)
    return rows


def target_pool_fixture():
    rows = engine_fixture()
    d = rows[2]['decision']
    d['candidates'] = [dict(abilityId=7, sourceId=8, kind='SPELL', assessment='WillPlay')]
    options = [dict(type='CARD', id=(1 << 41) + index, gameTimestamp=(1 << 51) + index) for index in (1, 2)]
    choice = dict(choiceSchemaVersion=1, choiceId='opaque-choice', choiceType='TARGET_POOL_SELECTION',
                  stage='CANDIDATE_EVALUATION', chooserPlayerId=0, candidateAbilityId=7,
                  abilityId=7, sourceId=8, boundary='DESTROY_REMOVAL_POOL',
                  optionCoverage='OBSERVED_HEURISTIC_POOL_NOT_ALL_LEGAL_TARGET_SETS',
                  priorTargets=[], options=options, status='SELECTED', selected=copy.deepcopy(options[1]),
                  selectedOptionIndex=1, candidateIndex=0, candidateActionId='0:1:0')
    d.update(subordinateChoiceSchemaVersion=1, subordinateChoiceCoverage='OBSERVED_HEURISTIC_TARGET_POOLS_V1',
             subordinateChoices=[choice])
    return rows


def target_provenance_fixture():
    rows = target_pool_fixture()
    decision = rows[2]['decision']
    decision['targetChoiceProvenanceSchemaVersion'] = 1
    choice = decision['subordinateChoices'][0]
    choice['purpose'] = 'TARGET_RECOMMENDATION'
    rows[4]['finalizedTargets'] = dict(schemaVersion=1, abilities=[dict(abilityId=rows[4]['abilityId'], usesTargeting=True,
                                                       targets=[copy.deepcopy(choice['selected'])])])
    rows[4]['targetChoiceProvenance'] = dict(schemaVersion=1, coverage='EXPLICIT_TARGET_ADDITIONS_V1', abilitiesTruncated=False,
            abilities=[dict(abilityId=rows[4]['abilityId'], targetChoices=[dict(choiceId=choice['choiceId'], chooserPlayerId=0,
                           stage='CANDIDATE_EVALUATION')], targetsTruncated=False)])
    return rows


class ExportTests(unittest.TestCase):
    def test_draw_addition_boundary_and_timing_are_retained_and_validated(self):
        rows = target_pool_fixture()
        choice = rows[2]['decision']['subordinateChoices'][0]
        choice.update(boundary='DRAW_PLAYER_POOL', poolSnapshotTiming='AT_TARGET_ADDITION')
        choice['options'] = [dict(type='PLAYER', id=i) for i in (0, 1)]
        choice['selected'] = copy.deepcopy(choice['options'][1])
        source = self.write(rows)
        self.assertEqual(module.export([source])['subordinateChoiceBoundaries'], {'DRAW_PLAYER_POOL': 1})
        choice['poolSnapshotTiming'] = 'BEFORE_DECISION'
        with self.assertRaises(module.InvalidData): module.export([self.write(rows, 'invalid-timing.jsonl')])

    def test_player_burn_addition_is_a_recommendation_and_can_supply_a_committed_origin(self):
        rows = target_provenance_fixture()
        choice = rows[2]['decision']['subordinateChoices'][0]
        choice.update(boundary='DAMAGE_PLAYER_POOL', poolSnapshotTiming='AT_TARGET_ADDITION')
        choice['options'] = [dict(type='PLAYER', id=i) for i in (0, 1)]
        choice['selected'] = copy.deepcopy(choice['options'][1])
        rows[4]['finalizedTargets']['abilities'][0]['targets'] = [copy.deepcopy(choice['selected'])]
        summary = module.export([self.write(rows)])
        self.assertEqual(summary['subordinateChoicePurposes'], {'TARGET_RECOMMENDATION': 1})
        self.assertEqual(summary['committedTargetChoiceOrigins'], {'EXPLICIT_EVALUATION_ADDITION': 1})

    def test_attachment_boundaries_preserve_typed_pools_and_reject_mismatched_reference_kinds(self):
        for kind in ('CARD', 'PLAYER'):
            rows = target_pool_fixture()
            choice = rows[2]['decision']['subordinateChoices'][0]
            choice['boundary'] = 'ATTACH_' + kind + '_POOL'
            if kind == 'PLAYER':
                choice['options'] = [dict(type='PLAYER', id=i) for i in (0, 1)]
                choice['selected'] = copy.deepcopy(choice['options'][1])
            self.assertEqual(module.export([self.write(rows, kind + '.jsonl')])['subordinateChoiceRecords'], 1)
            choice['options'][0] = dict(type='PLAYER' if kind == 'CARD' else 'CARD', id=0, **({'gameTimestamp': 1} if kind == 'PLAYER' else {}))
            with self.assertRaises(module.InvalidData): module.export([self.write(rows, kind + '-wrong.jsonl')])

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def write(self, rows, name='input.jsonl'):
        path=self.root/name
        path.write_text(''.join(json.dumps(r)+'\n' for r in rows))
        return path

    def test_explicit_target_origin_survives_export_and_spooling_without_inferred_origins(self):
        rows = target_provenance_fixture()
        # A second equal recommendation exists, but the explicit origin identifies the first invocation.
        duplicate = copy.deepcopy(rows[2]['decision']['subordinateChoices'][0])
        duplicate['choiceId'] = 'later-equal-recommendation'
        rows[2]['decision']['subordinateChoices'].append(duplicate)
        source = self.write(rows)
        out = self.root / 'provenance'
        summary = module.export([source], out)
        self.assertEqual(summary['committedTargetChoiceOrigins'], {'EXPLICIT_EVALUATION_ADDITION': 1})
        exported = json.loads((out / 'dataset.jsonl').read_text())
        provenance = exported['actionExecutions'][0]['engineEvents'][0]['targetChoiceProvenance']
        self.assertEqual(provenance, rows[4]['targetChoiceProvenance'])
        self.assertEqual(provenance['abilities'][0]['targetChoices'][0]['choiceId'], 'opaque-choice')
        self.assertFalse(exported['trainingReady'])
        self.assertIn('Unknown origins are not inferred', (out / 'coverage.md').read_text())
        self.assertEqual(module.make_coverage([module.load_run(source)], [exported])['committedTargetChoiceOrigins'],
                         summary['committedTargetChoiceOrigins'])

    def test_matching_target_without_an_explicit_origin_remains_unknown_and_legacy_is_unavailable(self):
        for variant in ('unknown', 'unavailable', 'legacy'):
            rows = target_provenance_fixture()
            if variant == 'unknown': rows[4]['targetChoiceProvenance']['abilities'][0]['targetChoices'][0] = None
            elif variant == 'unavailable': rows[4]['targetChoiceProvenance'] = None
            else:
                del rows[2]['decision']['targetChoiceProvenanceSchemaVersion']
                del rows[4]['targetChoiceProvenance']
            summary = module.export([self.write(rows, variant + '.jsonl')])
            if variant == 'legacy': self.assertNotIn('committedTargetChoiceOrigins', summary)
            else:
                self.assertEqual(summary['committedTargetChoiceOrigins'], {'UNKNOWN': 1} if variant == 'unknown' else {})
                self.assertEqual(summary['targetChoiceProvenanceSnapshots'], {'OBSERVED' if variant == 'unknown' else 'UNAVAILABLE': 1})

    def test_target_origin_rejects_fabricated_links_identity_mismatches_and_private_fields(self):
        for mutation in ('choice', 'action', 'chooser', 'stage', 'target', 'ability', 'slot', 'schema', 'bool-schema',
                         'undeclared', 'missing-targets', 'event', 'private', 'truncation', 'group-truncation', 'preference'):
            with self.subTest(mutation=mutation):
                rows = target_provenance_fixture()
                snapshot = rows[4]['targetChoiceProvenance']; group = snapshot['abilities'][0]; origin = group['targetChoices'][0]
                if mutation == 'choice': origin['choiceId'] = 'invented'
                elif mutation == 'action': rows[2]['decision']['subordinateChoices'][0]['candidateActionId'] = None
                elif mutation == 'chooser': origin['chooserPlayerId'] = True
                elif mutation == 'stage': origin['stage'] = 'EXECUTION'
                elif mutation == 'target': rows[4]['finalizedTargets']['abilities'][0]['targets'][0]['gameTimestamp'] += 1
                elif mutation == 'ability': group['abilityId'] += 1
                elif mutation == 'slot': group['targetChoices'] = []
                elif mutation == 'schema': snapshot['schemaVersion'] = 2
                elif mutation == 'bool-schema': snapshot['schemaVersion'] = True
                elif mutation == 'undeclared': del rows[2]['decision']['targetChoiceProvenanceSchemaVersion']
                elif mutation == 'missing-targets': rows[4]['finalizedTargets'] = None
                elif mutation == 'event': rows[6]['targetChoiceProvenance'] = rows[4].pop('targetChoiceProvenance')
                elif mutation == 'private': origin['prompt'] = 'hidden information'
                elif mutation == 'truncation': group['targetsTruncated'] = True
                elif mutation == 'group-truncation': snapshot['abilitiesTruncated'] = True
                elif mutation == 'preference':
                    choice = rows[2]['decision']['subordinateChoices'][0]
                    choice['boundary'] = 'DAMAGE_OPPONENT_POOL'; choice['purpose'] = 'OPPONENT_PREFERENCE'
                with self.assertRaises(module.InvalidData):
                    module.export([self.write(rows, mutation + '.jsonl')])

    def test_target_provenance_bounds_have_explicit_unknown_and_truncated_coverage(self):
        rows = target_provenance_fixture()
        target = rows[4]['finalizedTargets']['abilities'][0]['targets'][0]
        rows[4]['finalizedTargets']['abilities'][0]['targets'] = [copy.deepcopy(target) for _ in range(257)]
        group = rows[4]['targetChoiceProvenance']['abilities'][0]
        group.update(targetChoices=[group['targetChoices'][0]] + [None] * 255, targetsTruncated=True)
        summary = module.export([self.write(rows)])
        self.assertEqual(summary['committedTargetChoiceOrigins'], {'EXPLICIT_EVALUATION_ADDITION': 1, 'UNKNOWN': 255, 'TRUNCATED': 1})

    def test_examples_distinguish_unknown_origins_from_explicit_links_on_equal_target_recommendations(self):
        inputs = []
        for index in range(3):
            rows = target_provenance_fixture()
            rows[0]['runId'] = 'provenance-' + str(index)
            if index < 2: rows[4]['targetChoiceProvenance']['abilities'][0]['targetChoices'][0] = None
            inputs.append(self.write(rows, 'provenance-' + str(index) + '.jsonl'))
        out = self.root / 'provenance-examples'
        module.export(inputs, out, examples=2)
        examples = [json.loads(line) for line in (out / 'examples.jsonl').read_text().splitlines()]
        self.assertEqual([row['runId'] for row in examples], ['provenance-0', 'provenance-2'])

    def test_target_pool_records_export_with_explicit_partial_coverage(self):
        source = self.write(target_pool_fixture())
        out = self.root / 'out'
        summary = module.export([source], out)
        self.assertEqual(summary['subordinateChoiceRecords'], 1)
        self.assertEqual(summary['subordinateChoiceStatuses'], {'SELECTED': 1})
        self.assertEqual(summary['targetPoolSizes'], {2: 1})
        self.assertEqual(summary['targetChoiceCandidateLinks'], {'EXACT_SELECTED_CANDIDATE': 1})
        row = json.loads((out / 'dataset.jsonl').read_text())
        choice = row['decision']['subordinateChoices'][0]
        self.assertEqual(choice['selected']['id'], (1 << 41) + 2)
        self.assertEqual(choice['selected']['gameTimestamp'], (1 << 51) + 2)
        self.assertEqual(choice['candidateActionId'], row['decision']['selectedActions'][0]['actionId'])
        self.assertFalse(row['trainingReady'])
        self.assertIn('not complete legal target sets', (out / 'coverage.md').read_text())

    def test_pool_presence_coverage_does_not_equate_recommendations_with_committed_targets(self):
        for linked in (True, False):
            rows = target_pool_fixture()
            rows[4]['finalizedTargets'] = target_fixture()[4]['finalizedTargets']
            rows[2]['decision']['subordinateChoices'][0]['purpose'] = 'TARGET_RECOMMENDATION'
            if not linked:
                rows[2]['decision']['subordinateChoices'][0]['candidateActionId'] = None
            summary = module.export([self.write(rows, f'presence-{linked}.jsonl')])
            self.assertEqual(summary['declaredSelectedTargetedActions'], 1)
            self.assertEqual(summary['selectedTargetedActionsWithPoolRecord'], int(linked))
            self.assertEqual(summary['selectedTargetedActionsWithTargetRecommendation'], int(linked))

    def test_examples_include_observed_choices_instead_of_only_duplicate_empty_groups(self):
        inputs = []
        for index in range(3):
            rows = fixture()
            rows[0]['runId'] = f'choice-example-{index}'
            d = rows[2]['decision']
            d.update(subordinateChoiceSchemaVersion=1, subordinateChoiceCoverage='OBSERVED_HEURISTIC_TARGET_POOLS_V1', subordinateChoices=[])
            if index == 2:
                richer = target_pool_fixture()[2]['decision']
                d['candidates'] = richer['candidates']
                d['subordinateChoices'] = richer['subordinateChoices']
                d['subordinateChoices'][0]['candidateActionId'] = None
            inputs.append(self.write(rows, f'choice-example-{index}.jsonl'))
        out = self.root / 'choice-examples'
        module.export(inputs, out, examples=2)
        rows = [json.loads(line) for line in (out / 'examples.jsonl').read_text().splitlines()]
        self.assertEqual([row['runId'] for row in rows], ['choice-example-0', 'choice-example-2'])
        self.assertTrue(rows[1]['decision']['subordinateChoices'])

    def test_truncated_pools_and_declared_omissions_preserve_the_actual_returned_identity(self):
        rows = target_pool_fixture()
        d = rows[2]['decision']
        d['subordinateChoiceOmissions'] = dict(budget=3, interrupted=1)
        choice = d['subordinateChoices'][0]
        choice.update(optionsTruncated=True, priorTargetsTruncated=False, status='OUTSIDE_OBSERVED_POOL', selectedOptionIndex=None)
        choice['selected']['id'] += 100
        source = self.write(rows)
        out = self.root / 'out'
        summary = module.export([source], out)
        self.assertEqual(summary['subordinateChoicesOmittedByBudget'], 3)
        self.assertEqual(summary['subordinateChoicesOmittedDuringCapture'], 1)
        self.assertEqual(summary['targetPoolCaptureCompleteness'], {'TRUNCATED': 1})
        self.assertEqual(summary['priorTargetsCaptureCompleteness'], {'OBSERVED_FULL_LIST': 1})
        record = json.loads((out / 'dataset.jsonl').read_text())['decision']['subordinateChoices'][0]
        self.assertEqual(record['selected'], choice['selected'])
        self.assertIsNone(record['selectedOptionIndex'])

    def test_missing_budget_metadata_is_unavailable_and_malformed_metadata_is_rejected(self):
        summary = module.export([self.write(target_pool_fixture())])
        self.assertEqual(summary['targetChoiceCaptureBudgetCoverage'], {'UNAVAILABLE': 1})
        self.assertEqual(summary['targetPoolCaptureCompleteness'], {'UNAVAILABLE': 1})
        for index, omissions in enumerate((dict(budget=-1, interrupted=0), dict(budget=True, interrupted=0),
                                           dict(budget=1), dict(budget=0, interrupted=0, detail='raw text'))):
            rows = target_pool_fixture()
            rows[2]['decision']['subordinateChoiceOmissions'] = omissions
            with self.assertRaises(module.InvalidData):
                module.load_run(self.write(rows, f'omission-{index}.jsonl'))
        rows = target_pool_fixture()
        rows[2]['decision']['subordinateChoices'][0]['optionsTruncated'] = 1
        with self.assertRaises(module.InvalidData):
            module.load_run(self.write(rows, 'invalid-truncation.jsonl'))

    def test_target_pool_identity_status_and_visibility_contradictions_are_rejected(self):
        mutations = [
            lambda d, c: d.pop('subordinateChoiceSchemaVersion'),
            lambda d, c: d.update(subordinateChoiceSchemaVersion=True),
            lambda d, c: d.update(subordinateChoiceCoverage='ALL_LEGAL_TARGETS'),
            lambda d, c: d['subordinateChoices'].append(copy.deepcopy(c)),
            lambda d, c: c.update(choiceSchemaVersion=True),
            lambda d, c: c.update(chooserPlayerId=99),
            lambda d, c: c.update(candidateIndex=99),
            lambda d, c: c.update(candidateAbilityId=99),
            lambda d, c: c.update(candidateActionId='unknown-action'),
            lambda d, c: c.update(sourceId=99),
            lambda d, c: c.update(selectedOptionIndex=0),
            lambda d, c: c.update(selectedOptionIndex=True),
            lambda d, c: c.update(status='DECLINED'),
            lambda d, c: c.update(stage='EXECUTION'),
            lambda d, c: c.update(optionCoverage='ALL_LEGAL_TARGETS'),
            lambda d, c: c.update(boundary='a raw card-script description'),
            lambda d, c: c.update(purpose='OPPONENT_PREFERENCE'),
            lambda d, c: c['options'][0].update(name='Privileged identity'),
            lambda d, c: c['options'][0].update(gameTimestamp=True),
            lambda d, c: c['priorTargets'].append(dict(type='PLAYER', id=True)),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                rows = target_pool_fixture()
                d = rows[2]['decision']
                mutate(d, d['subordinateChoices'][0])
                with self.assertRaises(module.InvalidData):
                    module.export([self.write(rows, f'bad-choice-{index}.jsonl')], self.root / f'bad-out-{index}')

    def test_explicit_uncertain_pool_outcomes_and_interrupted_assessments_are_preserved(self):
        for status in ('DECLINED', 'ERROR', 'INCOMPLETE', 'OUTSIDE_OBSERVED_POOL', 'AMBIGUOUS_MATCH'):
            with self.subTest(status=status):
                rows = target_pool_fixture()
                choice = rows[2]['decision']['subordinateChoices'][0]
                choice.update(status=status, selectedOptionIndex=None, candidateIndex=None, candidateActionId=None)
                if status in ('DECLINED', 'ERROR', 'INCOMPLETE'):
                    choice['selected'] = None
                elif status == 'OUTSIDE_OBSERVED_POOL':
                    choice['selected']['id'] += 100
                else:
                    choice['options'].append(copy.deepcopy(choice['selected']))
                run = module.load_run(self.write(rows, status + '.jsonl'))
                data = list(module.dataset_rows(run))
                self.assertEqual(data[0]['decision']['subordinateChoices'][0], choice)
                coverage = module.make_coverage([run], data)
                self.assertEqual(coverage['targetChoiceCandidateLinks'], {'NO_EXACT_SELECTED_ACTION_LINK': 1})
                self.assertEqual(coverage['targetChoiceAssessmentLinks'], {'MISSING_ASSESSMENT': 1})

    def test_unselected_candidate_choice_does_not_invent_an_action_link(self):
        rows = fixture()
        d = rows[2]['decision']
        with_choice = target_pool_fixture()[2]['decision']
        d.update({key: copy.deepcopy(with_choice[key]) for key in ('candidates', 'subordinateChoiceSchemaVersion',
                    'subordinateChoiceCoverage', 'subordinateChoices')})
        d['subordinateChoices'][0]['candidateActionId'] = None
        data = list(module.dataset_rows(module.load_run(self.write(rows))))
        self.assertEqual(data[0]['actionExecutions'], [])
        self.assertEqual(data[0]['decision']['subordinateChoices'][0]['status'], 'SELECTED')
        self.assertIsNone(data[0]['decision']['subordinateChoices'][0]['candidateActionId'])

    def test_masked_source_requires_masked_choice_boundary(self):
        rows = target_pool_fixture()
        d = rows[2]['decision']
        d['observation']['players'][0]['cards'] = [dict(id=8, zone='Hand', identityKnown=False)]
        with self.assertRaises(module.InvalidData):
            module.load_run(self.write(rows))
        d['subordinateChoices'][0]['boundary'] = 'MASKED_TARGET_POOL'
        module.load_run(self.write(rows, 'masked.jsonl'))

    def test_legacy_output_does_not_claim_subordinate_choice_coverage(self):
        out = self.root / 'legacy-out'
        summary = module.export([self.write(fixture())], out)
        self.assertNotIn('subordinateChoiceRecords', summary)
        self.assertNotIn('subordinateChoiceCoverage', summary)
        self.assertNotIn('Subordinate target-pool selections', (out / 'coverage.md').read_text())

    def test_spooled_export_preserves_distinct_examples_then_fills_in_source_order(self):
        inputs = []
        for index, rows in enumerate((fixture(), fixture(), fixture('TIMEOUT'),
                                      linked_fixture('FAILED'), linked_fixture('ABANDONED', 'EXCEPTION'))):
            rows[0]['runId'] = 'run' + str(index)
            inputs.append(self.write(rows, f'run{index}.jsonl'))
        out = self.root / 'out'
        module.export(inputs, out, examples=5)
        examples = [json.loads(line) for line in (out / 'examples.jsonl').read_text().splitlines()]
        self.assertEqual([row['runId'] for row in examples], ['run0', 'run2', 'run3', 'run4', 'run1'])

    def test_spooled_games_materialize_in_game_order_instead_of_creation_order(self):
        first, second = fixture(), fixture()
        for row in second[1:-1]:
            row['game'] = 2
        first[2]['decision']['decisionId'] = 'first'
        second[2]['decision']['decisionId'] = 'second'
        finish = dict(first[-1], gamesAttempted=2, gamesNormal=2)
        source = self.write([first[0]] + second[1:-1] + first[1:-1] + [finish])
        out = self.root / 'out'
        module.export([source], out)
        rows = [json.loads(line) for line in (out / 'dataset.jsonl').read_text().splitlines()]
        self.assertEqual([(row['game'], row['decisionId']) for row in rows], [(1, 'first'), (2, 'second')])

    def test_nullable_legacy_pilot_metadata_still_forms_one_coverage_group(self):
        rows = fixture()
        rows[0]['pilotMode'] = None
        extra = copy.deepcopy(rows[2])
        extra['decision']['decisionId'] = 'another'
        rows.insert(3, extra)
        for index, row in enumerate(rows[1:-1], 1):
            row.update(game=1, eventIndex=index)
        out = self.root / 'out'
        summary = module.export([self.write(rows)], out)
        self.assertEqual(summary['pilotModes'], {None: 2})
        self.assertEqual(len(summary['groups']), 1)
        self.assertEqual(summary['groups'][0]['decisions'], 2)
        self.assertIsNone(summary['groups'][0]['pilotMode'])

    def test_late_invalid_input_publishes_nothing_and_discards_spool(self):
        valid = self.write(fixture(), 'valid.jsonl')
        invalid = self.write(fixture(), 'invalid.jsonl')
        invalid.write_text(invalid.read_text() + '{}\n')
        directories = []
        original = module.Spool
        class TrackedSpool(original):
            def __enter__(inner):
                result = super().__enter__()
                directories.append(Path(inner.directory.name))
                return result
        out = self.root / 'out'
        with mock.patch.object(module, 'Spool', TrackedSpool):
            with self.assertRaises(module.InvalidData):
                module.export([valid, invalid], out)
        self.assertFalse(out.exists())
        self.assertTrue(directories)
        self.assertTrue(all(not directory.exists() for directory in directories))

    def test_sqlite_disk_exhaustion_publishes_nothing_and_discards_spool(self):
        rows = fixture()
        rows[0]['participants'][0]['deck']['Main'][0]['name'] = 'Large fixture ' * 20000
        source = self.write(rows)
        directories = []
        original = module.Spool
        class LimitedSpool(original):
            def __enter__(inner):
                result = super().__enter__()
                directories.append(Path(inner.directory.name))
                inner.db.execute('PRAGMA max_page_count=16')
                return result
        out = self.root / 'out'
        with mock.patch.object(module, 'Spool', LimitedSpool):
            with self.assertRaisesRegex(sqlite3.OperationalError, 'full'):
                module.export([source], out)
        self.assertFalse(out.exists())
        self.assertTrue(all(not directory.exists() for directory in directories))

    def test_failure_after_staging_dataset_does_not_publish_partial_export(self):
        source = self.write(fixture())
        out = self.root / 'out'
        original = module.write_export
        def failed_write(stage, *args):
            original(stage, *args)
            self.assertTrue((stage / 'dataset.jsonl').exists())
            raise OSError('simulated output failure')
        with mock.patch.object(module, 'write_export', failed_write):
            with self.assertRaisesRegex(OSError, 'output failure'):
                module.export([source], out)
        self.assertFalse(out.exists())
        self.assertFalse(list(self.root.glob('.forge-export-*')))

    def test_publication_failure_rolls_back_own_files_and_preserves_racing_file(self):
        source = self.write(fixture())
        out = self.root / 'out'
        original = module.os.link
        def raced_link(src, dest):
            if Path(dest).name == 'examples.jsonl':
                Path(dest).write_text('external file')
            return original(src, dest)
        with mock.patch.object(module.os, 'link', raced_link):
            with self.assertRaises(FileExistsError):
                module.export([source], out)
        self.assertEqual((out / 'examples.jsonl').read_text(), 'external file')
        self.assertFalse((out / 'dataset.jsonl').exists())
        self.assertEqual(list(out.iterdir()), [out / 'examples.jsonl'])

    def test_materialization_uses_validated_spool_when_source_changes(self):
        source = self.write(fixture())
        source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
        original = module.Spool
        class ChangedSourceSpool(original):
            def add_run(inner, run):
                super().add_run(run)
                source.write_text('changed externally after validation')
        out = self.root / 'out'
        with mock.patch.object(module, 'Spool', ChangedSourceSpool):
            module.export([source], out)
        row = json.loads((out / 'dataset.jsonl').read_text())
        self.assertEqual(row['outcomeLabel'], 'WIN')
        manifest = json.loads((out / 'manifest.json').read_text())
        self.assertEqual(manifest['inputs'][0]['sourceSha256'], source_hash)

    def test_disk_backed_action_state_rejects_late_contradiction_and_duplicate_decision(self):
        cases = []
        rows = engine_fixture()
        rows[4], rows[5] = rows[5], rows[4]
        rows[4]['execution']['executedAbilityId'] = 99
        cases.append(rows)
        rows = fixture()
        rows.insert(3, copy.deepcopy(rows[2]))
        cases.append(rows)
        for index, rows in enumerate(cases):
            for event_index, row in enumerate(rows[1:-1], 1):
                row.update(game=1, eventIndex=event_index)
            out = self.root / f'out{index}'
            with self.assertRaises(module.InvalidData):
                module.export([self.write(rows, f'invalid{index}.jsonl')], out)
            self.assertFalse(out.exists())

    def test_export_self_contained_metadata_and_outcome(self):
        source=self.write(fixture())
        out=self.root/'out'
        summary=module.export([source],out)
        self.assertEqual(summary['outcomeEligibleDecisions'],1)
        row=json.loads((out/'dataset.jsonl').read_text())
        self.assertEqual(row['outcomeLabel'],'WIN')
        self.assertFalse(row['trainingReady'])
        self.assertEqual(row['seed'],123)
        self.assertEqual(len(row['deckSha256']),64)
        self.assertIn('coverage and data-integrity', (out/'coverage.md').read_text())
        self.assertEqual(json.loads((out/'validation.json').read_text())['valid'],True)
        manifest=json.loads((out/'manifest.json').read_text())
        self.assertEqual(manifest['games'][0]['gameStarted']['players'][0]['participantId'],1)
        self.assertFalse(manifest['games'][0]['administrativeOutcome'])
        with self.assertRaises(module.InvalidData): module.export([source],out)

    def test_timeouts_and_errors_never_receive_draw_or_loss_reward(self):
        for status in ('TIMEOUT','EXCEPTION'):
            run=module.load_run(self.write(fixture(status,draw=True)))
            row=list(module.dataset_rows(run))[0]
            self.assertFalse(row['outcomeEligible'])
            self.assertIsNone(row['outcomeLabel'])
        # Actual normal-completion draws remain legitimate outcome labels.
        row=list(module.dataset_rows(module.load_run(self.write(fixture(draw=True)))))[0]
        self.assertEqual(row['outcomeLabel'],'DRAW')

    def test_timeout_during_setup_retains_administrative_result_without_game_start(self):
        rows = fixture('TIMEOUT', draw=True)
        rows = [r for r in rows if r['type'] not in ('GAME_STARTED', 'AI_DECISION')]
        for i, row in enumerate(rows[1:-1], 1): row.update(game=1, eventIndex=i)
        path = self.write(rows)
        run = module.load_run(path)
        self.assertEqual(list(module.dataset_rows(run)), [])
        summary = module.export([path], self.root / 'pregame-timeout')
        self.assertEqual(summary['executionStatuses'], {'TIMEOUT': 1})
        self.assertEqual(summary['outcomeEligibleDecisions'], 0)
        manifest = json.loads((self.root / 'pregame-timeout/manifest.json').read_text())
        self.assertIsNone(manifest['games'][0]['gameStarted'])
        self.assertTrue(manifest['games'][0]['administrativeOutcome'])
        rows[-2]['status'] = rows[-1]['status'] = 'NORMAL'
        rows[-1].update(gamesNormal=1, gamesTimedOut=0)
        with self.assertRaises(module.InvalidData): module.load_run(self.write(rows))

    def test_interrupted_decision_is_censored_even_in_winning_game(self):
        rows=fixture();rows[2]['decision']['status']='EVALUATION_TIMEOUT'
        row=list(module.dataset_rows(module.load_run(self.write(rows))))[0]
        self.assertIsNone(row['outcomeLabel'])

    def test_late_decision_during_cancellation_is_retained_but_censored(self):
        rows=fixture('TIMEOUT',draw=True)
        decision=rows.pop(2)
        rows.insert(4,decision)
        for i,row in enumerate(rows[1:-1],1):row['eventIndex']=i
        run=module.load_run(self.write(rows))
        row=list(module.dataset_rows(run))[0]
        self.assertTrue(row['postOutcome'])
        self.assertIsNone(row['outcomeLabel'])
        self.assertEqual(module.make_coverage([run],[row])['decisionsAfterOutcome'],1)

    def test_land_shortlist_without_ability_id_is_an_option_not_a_rejection(self):
        rows=fixture()
        action=dict(kind='LAND',sourceId=7,abilityId=18,sourceName='Island')
        option=dict(kind='LAND',sourceId=7,sourceName='Island',assessment='LAND_OPTION',assessmentStage='LAND_SHORTLIST',heuristicDefault=True,searchOffered=False)
        rows[2]['decision'].update(selection='PLAY',selectedActions=[action],candidates=[option])
        run=module.load_run(self.write(rows))
        dataset=list(module.dataset_rows(run))
        summary=module.make_coverage([run],dataset)
        self.assertEqual(summary['selectedActionsWithoutAssessment'],0)
        self.assertEqual(summary['rejectedCards'],{})
        del option['assessmentStage']
        with self.assertRaises(module.InvalidData):module.load_run(self.write(rows))

    def test_specific_rejection_details_are_validated_and_reported(self):
        rows=fixture()
        candidate=dict(kind='SPELL',sourceId=7,abilityId=18,assessment='CantPlayAi',assessmentDetail=dict(reason='CARD_AI_RATING_TOO_LOW',handler='PermanentCreatureAi',handlerAssessment='WillPlay',handlerRating=0))
        rows[2]['decision']['candidates']=[candidate]
        run=module.load_run(self.write(rows));data=list(module.dataset_rows(run))
        summary=module.make_coverage([run],data)
        self.assertEqual(summary['assessmentDetails']['CARD_AI_RATING_TOO_LOW'],1)
        candidate['assessmentDetail']['handlerRating']='bad'
        with self.assertRaises(module.InvalidData):module.load_run(self.write(rows))

    def test_rejects_hidden_hand_library_and_masked_identity(self):
        for zone,owner,known,name in [('Hand',1,True,True),('Library',0,True,True),('Exile',1,False,True)]:
            rows=fixture()
            card=dict(id=8,zone=zone,identityKnown=known)
            if name: card['name']='Secret'
            rows[2]['decision']['observation']['players'][owner]['cards']=[card]
            with self.assertRaises(module.InvalidData): module.load_run(self.write(rows))

    def test_masked_source_must_not_leak_card_handler_information(self):
        rows=fixture()
        rows[2]['decision']['observation']['players'][1]['cards']=[dict(id=7,zone='Exile',identityKnown=False)]
        rows[2]['decision']['candidates']=[dict(kind='SPELL',sourceId=7,abilityId=18,assessment='CantPlayAi',assessmentDetail=dict(reason='CARD_AI_DECLINED',handler='CounterAi'))]
        with self.assertRaises(module.InvalidData):module.load_run(self.write(rows))
        del rows[2]['decision']['candidates'][0]['assessmentDetail']['handler']
        module.load_run(self.write(rows))

    def test_rejects_incomplete_file_before_creating_outputs(self):
        out=self.root/'out'
        with self.assertRaises(module.InvalidData): module.export([self.write(fixture()[:-1])],out)
        self.assertFalse(out.exists())

    def test_rejects_duplicate_ids_bad_status_and_game_order(self):
        cases=[]
        rows=fixture();rows.insert(3,copy.deepcopy(rows[2]));rows[3]['eventIndex']=3
        for i,r in enumerate(rows[4:-1],4):r['eventIndex']=i
        cases.append(rows)
        rows=fixture();rows[-1]['gamesNormal']=0;cases.append(rows)
        rows=fixture();rows[-2]['eventIndex']=2;cases.append(rows)
        rows=fixture();rows[3]['winners'][0]['participantId']=2;cases.append(rows)
        rows=fixture();rows[2]['decision']['observation']['phase']='MAIN2';cases.append(rows)
        rows=fixture();rows[2]['decision']['decisionSchemaVersion']=2;cases.append(rows)
        for rows in cases:
            with self.assertRaises(module.InvalidData): module.load_run(self.write(rows))

    def test_accepts_shared_indices_only_when_declared(self):
        rows=fixture();rows[0].update(streamKind='AI_DECISIONS',eventIndexMode='SHARED_WITH_GAME_TRACE')
        for r in rows[1:-1]:r['eventIndex']*=3
        module.load_run(self.write(rows))
        del rows[0]['eventIndexMode']
        with self.assertRaises(module.InvalidData): module.load_run(self.write(rows))

    def test_rejects_unknown_stream_metadata(self):
        for field,value in [('eventIndexMode','GUESS'),('streamKind','GUESS'),('runId',[])]:
            rows=fixture();rows[0][field]=value
            with self.assertRaises(module.InvalidData): module.load_run(self.write(rows))

    def test_refuses_two_streams_of_same_run(self):
        paths=[self.write(fixture(),'a.jsonl'),self.write(fixture(),'b.jsonl')]
        with self.assertRaises(module.InvalidData): module.export(paths)

    def test_unknown_legacy_metadata_stays_unknown(self):
        rows=fixture();del rows[0]['runId'];del rows[0]['pilotMode']
        run=module.load_run(self.write(rows))
        self.assertTrue(run['metadata']['runId'].startswith('legacy-'))
        self.assertEqual(run['metadata']['pilotMode'],'UNKNOWN')
        self.assertEqual(run['metadata']['engineRevision'],'UNKNOWN')

    def test_action_execution_joins_through_changed_ability_ids(self):
        rows=linked_fixture()
        rows[0]['streamKind']='AI_DECISIONS'
        run=module.load_run(self.write(rows))
        dataset=list(module.dataset_rows(run))
        execution=dataset[0]['actionExecutions'][0]
        self.assertEqual(execution['status'],'SUCCEEDED')
        self.assertEqual(execution['events'][-1]['execution']['executedAbilityId'],77)
        self.assertEqual(module.make_coverage([run],dataset)['actionExecutionStatuses'],{'SUCCEEDED':1})
        self.assertEqual(dataset[0]['outcomeLabel'],'WIN')

    def test_abandoned_errors_and_unknown_executions_are_censored(self):
        for status in ('ABANDONED','ERROR','UNOBSERVED'):
            row=list(module.dataset_rows(module.load_run(self.write(linked_fixture(status)))))[0]
            self.assertEqual(row['actionExecutions'][0]['status'],status)
            self.assertIsNone(row['outcomeLabel'])
        # A definitively failed attempt remains observable; WIN still means eventual game outcome only.
        row=list(module.dataset_rows(module.load_run(self.write(linked_fixture('FAILED')))))[0]
        self.assertEqual(row['actionExecutions'][0]['status'],'FAILED')

    def test_incomplete_execution_is_accepted_only_for_abnormal_games(self):
        with self.assertRaises(module.InvalidData): module.load_run(self.write(linked_fixture(None)))
        row=list(module.dataset_rows(module.load_run(self.write(linked_fixture(None,'TIMEOUT')))))[0]
        self.assertEqual(row['actionExecutions'][0]['status'],'INCOMPLETE')
        self.assertIsNone(row['outcomeLabel'])

    def test_legacy_action_execution_is_unavailable_not_guessed(self):
        rows=linked_fixture()
        rows=[r for r in rows if r['type']!='AI_ACTION_EXECUTION']
        del rows[2]['decision']['actionExecutionSchemaVersion']
        del rows[2]['decision']['selectedActions'][0]['actionId']
        for i,row in enumerate(rows[1:-1],1):row['eventIndex']=i
        row=list(module.dataset_rows(module.load_run(self.write(rows))))[0]
        self.assertEqual(row['actionExecutions'][0]['status'],'UNAVAILABLE')

    def test_rejects_orphan_duplicate_and_mismatched_execution(self):
        for mutation in ('orphan','duplicate','no_start','wrong_player','wrong_source','missing_stack','hidden_detail'):
            rows=linked_fixture()
            if mutation=='orphan': rows[3]['execution']['actionId']='other'
            if mutation=='duplicate': rows.insert(5,copy.deepcopy(rows[4]))
            if mutation=='no_start': rows.pop(3)
            if mutation=='wrong_player': rows[4]['execution']['playerId']=1
            if mutation=='wrong_source': rows[4]['execution']['sourceId']=19
            if mutation=='missing_stack': del rows[4]['execution']['stackId']
            if mutation=='hidden_detail': rows[4]['execution']['sourceName']='Secret'
            for i,row in enumerate(rows[1:-1],1):row['eventIndex']=i
            with self.subTest(mutation=mutation):
                with self.assertRaises(module.InvalidData): module.load_run(self.write(rows))

    def test_execution_after_outcome_is_retained_in_order_and_censored(self):
        rows=linked_fixture()
        result=rows.pop(4)
        rows.insert(5,result)
        for i,row in enumerate(rows[1:-1],1):row['eventIndex']=i
        row=list(module.dataset_rows(module.load_run(self.write(rows))))[0]
        self.assertTrue(row['actionExecutions'][0]['events'][-1]['postOutcome'])
        self.assertIsNone(row['outcomeLabel'])

    def test_execution_export_omits_unrelated_diagnostic_envelope_fields(self):
        rows=linked_fixture()
        rows[3]['diagnosticEngineState']={'opponentHand':['Secret Card']}
        row=list(module.dataset_rows(module.load_run(self.write(rows))))[0]
        self.assertNotIn('Secret Card',json.dumps(row))
        self.assertEqual(row['actionExecutions'][0]['events'][0]['eventIndex'],3)

    def test_full_width_engine_ids_and_event_indices_survive_export(self):
        rows=linked_fixture()
        rows[0].update(streamKind='AI_DECISIONS',eventIndexMode='SHARED_WITH_GAME_TRACE')
        identity=dict(abilityId=(1<<40)+7,sourceId=(1<<41)+8)
        rows[2]['decision']['selectedActions'][0].update(identity)
        for row in rows[1:-1]:
            row['eventIndex']+=(1<<33)
            if row['type']=='AI_ACTION_EXECUTION':row['execution'].update(identity)
        rows[4]['execution'].update(executedAbilityId=(1<<42)+9,stackId=(1<<43)+10)
        out=self.root/'wide-ids'
        module.export([self.write(rows)],out)
        exported=json.loads((out/'dataset.jsonl').read_text())
        self.assertEqual(exported['sourceEventIndex'],(1<<33)+2)
        execution=exported['actionExecutions'][0]['events'][-1]['execution']
        self.assertEqual(execution['abilityId'],(1<<40)+7)
        self.assertEqual(execution['executedAbilityId'],(1<<42)+9)
        self.assertEqual(execution['stackId'],(1<<43)+10)

    def test_linked_resolution_retains_observations_without_changing_execution_status(self):
        for ending, types in (('resolved', ['STACK_ADDED', 'STACK_RESOLVED', 'STACK_REMOVED']),
                              ('countered', ['STACK_ADDED', 'STACK_REMOVED', 'STACK_COUNTERED']),
                              ('cleared', ['STACK_ADDED', 'STACK_CLEARED'])):
            with self.subTest(ending=ending):
                run = module.load_run(self.write(engine_fixture(ending)))
                dataset = list(module.dataset_rows(run))
                action = dataset[0]['actionExecutions'][0]
                self.assertEqual(action['status'], 'SUCCEEDED')
                self.assertEqual(action['engineEventCoverage'], 'LINKED_EVENTS_V1')
                self.assertEqual([e['type'] for e in action['engineEvents']], types)
                self.assertEqual(action['engineEvents'][-1]['actionId'], '0:1:0')
                self.assertEqual(action['engineEvents'][-1]['stackId'], 12)
                self.assertEqual(dataset[0]['outcomeLabel'], 'WIN')
                self.assertEqual(module.make_coverage([run], dataset)['linkedEngineEvents'], dict.fromkeys(types, 1))

    def test_full_and_filtered_events_export_identically_without_privileged_fields(self):
        filtered = engine_fixture()
        full = copy.deepcopy(filtered)
        full[0]['streamKind'] = 'FULL_GAME_TRACE'
        full[4].update(source={'name': 'Secret card'}, choices={'secret': 'Secret card'}, targets=['Secret card'])
        full.insert(7, dict(type='STACK_ADDED', decisionId=None, actionId=None, source={'name': 'Secret card'}))
        for i, row in enumerate(full[1:-1], 1): row.update(game=1, eventIndex=i)
        # Shared indices refer to the original full trace, including its unlinked event.
        filtered[0]['eventIndexMode'] = 'SHARED_WITH_GAME_TRACE'
        for row in filtered[1:-1]:
            if row['eventIndex'] >= 7: row['eventIndex'] += 1
        full_rows = list(module.dataset_rows(module.load_run(self.write(full, 'full.jsonl'))))
        decision_rows = list(module.dataset_rows(module.load_run(self.write(filtered, 'filtered.jsonl'))))
        self.assertEqual(full_rows, decision_rows)
        self.assertNotIn('Secret card', json.dumps(full_rows))

    def test_missing_resolution_does_not_invent_a_failure_or_resolved_result(self):
        rows = linked_fixture()
        rows[0]['actionEventSchemaVersion'] = 1
        action = list(module.dataset_rows(module.load_run(self.write(rows))))[0]['actionExecutions'][0]
        self.assertEqual(action['status'], 'SUCCEEDED')
        self.assertEqual(action['engineEvents'], [])
        legacy = list(module.dataset_rows(module.load_run(self.write(linked_fixture()))))[0]['actionExecutions'][0]
        self.assertEqual(legacy['engineEventCoverage'], 'UNAVAILABLE')

    def test_fizzled_resolution_is_retained_as_an_engine_observation(self):
        rows = engine_fixture()
        rows[6]['fizzled'] = True
        action = list(module.dataset_rows(module.load_run(self.write(rows))))[0]['actionExecutions'][0]
        self.assertTrue(action['engineEvents'][1]['fizzled'])
        self.assertEqual(action['status'], 'SUCCEEDED')

    def test_linked_land_event_has_no_stack_identity(self):
        rows = linked_fixture()
        rows[0].update(actionEventSchemaVersion=1, streamKind='AI_DECISIONS')
        rows[2]['decision']['selectedActions'][0]['kind'] = 'LAND'
        for row in rows:
            if row['type'] == 'AI_ACTION_EXECUTION': row['execution']['kind'] = 'LAND'
        terminal = rows[4]['execution']
        terminal['reason'] = 'LAND_PLAYED'
        del terminal['executedAbilityId'], terminal['stackId']
        rows.insert(4, dict(type='LAND_PLAYED', decisionId='0:1', actionId='0:1:0'))
        for i, row in enumerate(rows[1:-1], 1): row.update(game=1, eventIndex=i)
        action = list(module.dataset_rows(module.load_run(self.write(rows))))[0]['actionExecutions'][0]
        self.assertEqual(action['engineEvents'][0]['type'], 'LAND_PLAYED')
        self.assertNotIn('stackId', action['engineEvents'][0])

    def test_invalid_engine_references_are_rejected(self):
        for mutation in ('orphan', 'wrong_decision', 'wrong_stack', 'wrong_ability', 'execution_mismatch',
                         'duplicate', 'before_start', 'hidden_detail', 'single_clear', 'bad_clear', 'missing_fizzled'):
            rows = engine_fixture('cleared' if mutation in ('single_clear', 'bad_clear') else 'resolved')
            if mutation == 'orphan': rows[4]['actionId'] = 'other'
            if mutation == 'wrong_decision': rows[4]['decisionId'] = 'other'
            if mutation == 'wrong_stack': rows[6]['stackId'] = 99
            if mutation == 'wrong_ability': rows[6]['abilityId'] = 99
            if mutation == 'execution_mismatch': rows[5]['execution']['executedAbilityId'] = 99
            if mutation == 'duplicate': rows.insert(7, copy.deepcopy(rows[6]))
            if mutation == 'before_start': rows[3], rows[4] = rows[4], rows[3]
            if mutation == 'hidden_detail': rows[4]['source'] = {'name': 'Secret card'}
            if mutation == 'single_clear': rows[6]['actionId'] = '0:1:0'
            if mutation == 'bad_clear': rows[6]['clearedActions'][0]['stackId'] = 99
            if mutation == 'missing_fizzled': del rows[6]['fizzled']
            for i, row in enumerate(rows[1:-1], 1): row.update(game=1, eventIndex=i)
            with self.subTest(mutation=mutation):
                with self.assertRaises(module.InvalidData): module.load_run(self.write(rows))

    def test_post_outcome_engine_event_is_retained_and_censored(self):
        rows = engine_fixture()
        resolved = rows.pop(6)
        rows.insert(9, resolved)
        for i, row in enumerate(rows[1:-1], 1): row.update(game=1, eventIndex=i)
        dataset = list(module.dataset_rows(module.load_run(self.write(rows))))[0]
        self.assertTrue(dataset['actionExecutions'][0]['engineEvents'][-1]['postOutcome'])
        self.assertIsNone(dataset['outcomeLabel'])

    def test_linked_engine_events_preserve_long_ids(self):
        rows = engine_fixture()
        for row in rows:
            if row['type'].startswith('STACK_'):
                row['stackId'] += 1 << 40
                row['abilityId'] += 1 << 41
            if row['type'] == 'AI_ACTION_EXECUTION' and row['execution']['status'] == 'SUCCEEDED':
                row['execution']['stackId'] += 1 << 40
                row['execution']['executedAbilityId'] += 1 << 41
        action = list(module.dataset_rows(module.load_run(self.write(rows))))[0]['actionExecutions'][0]
        self.assertEqual(action['engineEvents'][-1]['stackId'], (1 << 40) + 12)
        self.assertEqual(action['engineEvents'][-1]['abilityId'], (1 << 41) + 77)

    def test_finalized_targets_preserve_groups_order_allocations_and_long_ids(self):
        rows = target_fixture()
        run = module.load_run(self.write(rows))
        dataset = list(module.dataset_rows(run))
        snapshot = dataset[0]['actionExecutions'][0]['engineEvents'][0]['finalizedTargets']
        self.assertEqual(snapshot, rows[4]['finalizedTargets'])
        self.assertEqual([t['type'] for t in snapshot['abilities'][0]['targets']], ['PLAYER', 'CARD', 'SPELL_ABILITY'])
        self.assertEqual(snapshot['abilities'][0]['targets'][1]['gameTimestamp'], (1 << 50) + 3)
        summary = module.make_coverage([run], dataset)
        self.assertEqual(summary['finalizedTargetSnapshots'], {'OBSERVED': 1})
        self.assertEqual(summary['finalizedTargetTypes'], {'PLAYER': 1, 'CARD': 2, 'SPELL_ABILITY': 1})
        out = self.root / 'target-export'
        module.export([self.write(rows)], out)
        exported = json.loads((out / 'dataset.jsonl').read_text())
        self.assertEqual(exported['actionExecutions'][0]['engineEvents'][0]['finalizedTargets'], snapshot)
        self.assertIn('Finalized target snapshots', (out / 'coverage.md').read_text())
        self.assertEqual(exported['outcomeLabel'], 'WIN')
        self.assertFalse(exported['trainingReady'])

    def test_target_snapshots_export_equally_from_full_and_filtered_streams(self):
        filtered = target_fixture()
        full = copy.deepcopy(filtered)
        full[0]['streamKind'] = 'FULL_GAME_TRACE'
        full[4].update(targets=[dict(card=dict(name='Secret target identity'))], targetDescription='Secret target identity')
        full_rows = list(module.dataset_rows(module.load_run(self.write(full, 'full.jsonl'))))
        filtered_rows = list(module.dataset_rows(module.load_run(self.write(filtered, 'filtered.jsonl'))))
        self.assertEqual(full_rows, filtered_rows)
        self.assertNotIn('Secret target identity', json.dumps(full_rows))

    def test_target_snapshot_unavailable_is_distinct_from_observed_empty_targets(self):
        for state in ('legacy', 'unavailable', 'untargeted', 'optional_zero'):
            rows = engine_fixture()
            if state == 'unavailable': rows[4]['finalizedTargets'] = None
            if state in ('untargeted', 'optional_zero'):
                rows[4]['finalizedTargets'] = dict(schemaVersion=1, abilities=[
                    dict(abilityId=77, usesTargeting=state == 'optional_zero', targets=[])])
            run = module.load_run(self.write(rows))
            dataset = list(module.dataset_rows(run))
            coverage = module.make_coverage([run], dataset)
            with self.subTest(state=state):
                self.assertEqual(coverage['finalizedTargetSnapshots'],
                                 {'OBSERVED': 1} if state in ('untargeted', 'optional_zero') else {'UNAVAILABLE': 1})
                self.assertEqual(coverage['finalizedTargetTypes'], {})

    def test_targeted_ability_without_live_stack_entry_retains_null_stack_id(self):
        rows = target_fixture()
        rows[4]['finalizedTargets']['abilities'][0]['targets'][2]['stackId'] = None
        event = list(module.dataset_rows(module.load_run(self.write(rows))))[0]['actionExecutions'][0]['engineEvents'][0]
        self.assertIsNone(event['finalizedTargets']['abilities'][0]['targets'][2]['stackId'])

    def test_invalid_or_privileged_target_snapshots_are_rejected(self):
        mutations = ('bad_version', 'boolean_version', 'missing_groups', 'empty_groups', 'wrong_root', 'bad_group_flag',
                     'extra_snapshot_field', 'extra_group_field', 'extra_target_field', 'missing_card_timestamp',
                     'bad_card_timestamp', 'bad_target_id', 'unknown_type', 'missing_stack_id', 'bad_stack_id',
                     'negative_allocation', 'boolean_allocation', 'wrong_event', 'invalid_group', 'invalid_target')
        for filtered in (True, False):
            for mutation in mutations:
                rows = target_fixture(filtered=filtered)
                snapshot = rows[4]['finalizedTargets']
                group = snapshot['abilities'][0]
                target = group['targets'][1]
                if mutation == 'bad_version': snapshot['schemaVersion'] = 2
                if mutation == 'boolean_version': snapshot['schemaVersion'] = True
                if mutation == 'missing_groups': del snapshot['abilities']
                if mutation == 'empty_groups': snapshot['abilities'] = []
                if mutation == 'wrong_root': group['abilityId'] = 999
                if mutation == 'bad_group_flag': group['usesTargeting'] = 1
                if mutation == 'extra_snapshot_field': snapshot['sourceName'] = 'Secret card'
                if mutation == 'extra_group_field': group['description'] = 'Secret card'
                if mutation == 'extra_target_field': target['name'] = 'Secret card'
                if mutation == 'missing_card_timestamp': del target['gameTimestamp']
                if mutation == 'bad_card_timestamp': target['gameTimestamp'] = 1.5
                if mutation == 'bad_target_id': target['id'] = True
                if mutation == 'unknown_type': target['type'] = 'UNKNOWN'
                if mutation == 'missing_stack_id': del group['targets'][2]['stackId']
                if mutation == 'bad_stack_id': group['targets'][2]['stackId'] = '12'
                if mutation == 'negative_allocation': target['dividedAmount'] = -1
                if mutation == 'boolean_allocation': target['dividedAmount'] = True
                if mutation == 'wrong_event': rows[6]['finalizedTargets'] = rows[4].pop('finalizedTargets')
                if mutation == 'invalid_group': snapshot['abilities'][0] = None
                if mutation == 'invalid_target': group['targets'][1] = None
                with self.subTest(filtered=filtered, mutation=mutation):
                    with self.assertRaises(module.InvalidData): module.load_run(self.write(rows))


    def test_finalized_modes_keep_committed_order_repeats_and_ambiguous_indices(self):
        rows = engine_fixture()
        rows[4]['finalizedModes'] = dict(schemaVersion=1, modal=True, modes=[
            dict(abilityId=(1 << 42) + 1, optionIndex=2), dict(abilityId=(1 << 42) + 2, optionIndex=2),
            dict(abilityId=(1 << 42) + 3, optionIndex=None)])
        run = module.load_run(self.write(rows))
        dataset = list(module.dataset_rows(run))
        self.assertEqual(dataset[0]['actionExecutions'][0]['engineEvents'][0]['finalizedModes'], rows[4]['finalizedModes'])
        summary = module.make_coverage([run], dataset)
        self.assertEqual(summary['finalizedModeSnapshots'], {'OBSERVED': 1})
        self.assertEqual(summary['selectedModeCounts'], {3: 1})
        full = copy.deepcopy(rows)
        full[0]['streamKind'] = 'FULL_GAME_TRACE'
        full[4]['choices'] = dict(modes=[dict(description='Secret script description')])
        self.assertEqual(list(module.dataset_rows(module.load_run(self.write(full)))), dataset)
        self.assertNotIn('Secret script description', json.dumps(dataset))

    def test_nonmodal_and_unavailable_modes_are_distinct(self):
        for snapshot in (None, dict(schemaVersion=1, modal=False, modes=[]), dict(schemaVersion=1, modal=True, modes=[])):
            rows = engine_fixture()
            rows[4]['finalizedModes'] = snapshot
            run = module.load_run(self.write(rows))
            data = list(module.dataset_rows(run))
            self.assertEqual(data[0]['actionExecutions'][0]['engineEvents'][0]['finalizedModes'], snapshot)
            self.assertEqual(module.make_coverage([run], data)['finalizedModeSnapshots'], {'UNAVAILABLE' if snapshot is None else 'OBSERVED': 1})

    def test_invalid_or_privileged_mode_data_is_rejected(self):
        for mutation in ('version', 'nonmodal', 'private', 'negative_index', 'wrong_event'):
            rows = engine_fixture()
            snapshot = dict(schemaVersion=1, modal=True, modes=[dict(abilityId=78, optionIndex=0)])
            rows[4]['finalizedModes'] = snapshot
            if mutation == 'version': snapshot['schemaVersion'] = True
            if mutation == 'nonmodal': snapshot['modal'] = False
            if mutation == 'private': snapshot['modes'][0]['description'] = 'Secret card'
            if mutation == 'negative_index': snapshot['modes'][0]['optionIndex'] = -1
            if mutation == 'wrong_event': rows[6]['finalizedModes'] = rows[4].pop('finalizedModes')
            with self.subTest(mutation=mutation):
                with self.assertRaises(module.InvalidData): module.load_run(self.write(rows))


    def test_announced_x_zero_and_unannounced_are_not_inferred_from_other_fields(self):
        for value in (None, 0, 2):
            rows = engine_fixture()
            rows[4]['finalizedX'] = dict(schemaVersion=1, announcedValue=value)
            rows[0]['streamKind'] = 'FULL_GAME_TRACE'
            rows[4]['manaSpent'] = [{'color': 'G'}] * 4
            run = module.load_run(self.write(rows))
            dataset = list(module.dataset_rows(run))
            event = dataset[0]['actionExecutions'][0]['engineEvents'][0]
            self.assertEqual(event['finalizedX']['announcedValue'], value)
            self.assertNotIn('manaSpent', event)
            summary = module.make_coverage([run], dataset)
            self.assertEqual(summary['announcedXValues'], {} if value is None else {value: 1})
            filtered = copy.deepcopy(rows)
            filtered[0]['streamKind'] = 'AI_DECISIONS'
            del filtered[4]['manaSpent']
            self.assertEqual(list(module.dataset_rows(module.load_run(self.write(filtered)))), dataset)

    def test_invalid_or_privileged_x_data_is_rejected(self):
        for mutation in ('negative', 'boolean', 'description', 'wrong_event'):
            rows = engine_fixture()
            snapshot = dict(schemaVersion=1, announcedValue=2)
            rows[4]['finalizedX'] = snapshot
            if mutation == 'negative': snapshot['announcedValue'] = -1
            if mutation == 'boolean': snapshot['announcedValue'] = False
            if mutation == 'description': snapshot['description'] = 'Secret card'
            if mutation == 'wrong_event': rows[6]['finalizedX'] = rows[4].pop('finalizedX')
            with self.subTest(mutation=mutation):
                with self.assertRaises(module.InvalidData): module.load_run(self.write(rows))


    def test_payment_facts_survive_both_exports_without_inventing_a_life_total(self):
        rows = payment_fixture()
        run = module.load_run(self.write(rows))
        dataset = list(module.dataset_rows(run))
        payment = dataset[0]['actionExecutions'][0]['engineEvents'][0]['finalizedPayments']
        self.assertEqual(payment, rows[4]['finalizedPayments'])
        self.assertEqual(payment['lastLifePayment'], 2)
        self.assertEqual(payment['phyrexianLifePaid'], 4)
        self.assertNotIn('totalLifePaid', payment)
        summary = module.make_coverage([run], dataset)
        self.assertEqual(summary['optionalCostSelections'], {'Kicker1': 1})
        self.assertEqual(summary['alternativeCostSelections'], {'Flashback': 1})
        self.assertEqual(summary['unrecognizedCardPaymentGroups'], 1)
        full = copy.deepcopy(rows)
        full[0]['streamKind'] = 'FULL_GAME_TRACE'
        full[4]['manaSpent'] = [dict(source=dict(name='Secret payment source'))]
        self.assertEqual(list(module.dataset_rows(module.load_run(self.write(full)))), dataset)
        self.assertNotIn('Secret payment source', json.dumps(dataset))

    def test_payment_snapshot_may_be_unavailable_or_have_unknown_mana_provenance(self):
        rows = payment_fixture()
        rows[4]['finalizedPayments'] = None
        run = module.load_run(self.write(rows))
        self.assertEqual(module.make_coverage([run], list(module.dataset_rows(run)))['finalizedPaymentSnapshots'], {'UNAVAILABLE': 1})
        rows = payment_fixture()
        rows[4]['finalizedPayments']['mana'][0].update(source=None, playerId=None, snow=None)
        data = list(module.dataset_rows(module.load_run(self.write(rows))))
        self.assertIsNone(data[0]['actionExecutions'][0]['engineEvents'][0]['finalizedPayments']['mana'][0]['source'])

    def test_invalid_and_private_payment_payloads_are_rejected(self):
        for mutation in ('schema', 'coverage', 'private_source', 'private_card', 'private_cost_type', 'negative_amount',
                         'life_total', 'invalid_color', 'invalid_source_id', 'private_root', 'wrong_event', 'duplicate_optional'):
            rows = payment_fixture()
            payment = rows[4]['finalizedPayments']
            if mutation == 'schema': payment['schemaVersion'] = 2
            if mutation == 'coverage': payment['coverage'] = 'COMPLETE_PAYMENTS'
            if mutation == 'private_source': payment['mana'][0]['source']['name'] = 'Secret source'
            if mutation == 'private_card': payment['cardPayments'][0]['cards'][0]['name'] = 'Secret card'
            if mutation == 'private_cost_type': payment['cardPayments'][0]['type'] = 'Secret payment label'
            if mutation == 'negative_amount': payment['keywordAmounts'][0]['amount'] = -1
            if mutation == 'life_total': payment['totalLifePaid'] = 6
            if mutation == 'invalid_color': payment['mana'][0]['color'] = 'X'
            if mutation == 'invalid_source_id': payment['mana'][0]['source']['id'] = True
            if mutation == 'private_root': payment['description'] = 'Secret card'
            if mutation == 'duplicate_optional': payment['optionalCosts'] *= 2
            if mutation == 'wrong_event': rows[6]['finalizedPayments'] = rows[4].pop('finalizedPayments')
            with self.subTest(mutation=mutation):
                with self.assertRaises(module.InvalidData): module.load_run(self.write(rows))


    def test_exception_after_selection_can_export_without_normal_engine_finish(self):
        for outcome_present in (True, False):
            rows = linked_fixture('ABANDONED', 'EXCEPTION')
            rows = [r for r in rows if r['type'] != 'GAME_FINISHED' and (outcome_present or r['type'] != 'GAME_OUTCOME')]
            for index, row in enumerate(rows[1:-1], 1): row.update(game=1, eventIndex=index)
            dataset = list(module.dataset_rows(module.load_run(self.write(rows))))
            self.assertEqual(dataset[0]['executionStatus'], 'EXCEPTION')
            self.assertFalse(dataset[0]['outcomeEligible'])
            self.assertIsNone(dataset[0]['outcomeLabel'])
        rows = [r for r in fixture() if r['type'] != 'GAME_FINISHED']
        for index, row in enumerate(rows[1:-1], 1): row.update(game=1, eventIndex=index)
        with self.assertRaises(module.InvalidData): module.load_run(self.write(rows))


    def test_actual_stack_identity_is_checked_even_when_terminal_arrives_first(self):
        for matches in (True, False):
            rows = engine_fixture()
            if not matches: rows[5]['execution']['executedAbilityId'] = 99
            rows[4], rows[5] = rows[5], rows[4]
            for index, row in enumerate(rows[1:-1], 1): row.update(game=1, eventIndex=index)
            if matches:
                self.assertEqual(list(module.dataset_rows(module.load_run(self.write(rows))))[0]['actionExecutions'][0]['status'], 'SUCCEEDED')
            else:
                with self.assertRaises(module.InvalidData): module.load_run(self.write(rows))


if __name__=='__main__':unittest.main()
