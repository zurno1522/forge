import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

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


class ExportTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def write(self, rows, name='input.jsonl'):
        path=self.root/name
        path.write_text(''.join(json.dumps(r)+'\n' for r in rows))
        return path

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



if __name__=='__main__':unittest.main()
