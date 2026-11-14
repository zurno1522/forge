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


if __name__=='__main__':unittest.main()
