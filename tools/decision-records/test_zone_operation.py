import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_context_bindings import rows4
from test_entry_location import entry


def operation_fixture():
    rows=rows4(); choice=rows[2]['choice']; context=choice['inputContext']
    retained=copy.deepcopy(choice['options']['items'][0])
    choice.update(choiceType='ZONE_CHANGE_SELECTION_OPERATION', boundary='CONTROLLER_ZONE_CHANGE_SELECTIONS', entryLocation=entry('BEFORE_OPERATION'))
    choice['options']=dict(coverage='RECEIVED_ZONE_OPERATION_REQUEST', branch='ORDINARY_HIDDEN_ORIGIN', requestedIterations=-2,
        destination='Hand',origins=['Library','Graveyard','Library'],sourcePlayerId=0,deciderPlayerId=1)
    context.update(schemaVersion=9,profile='PERMITTED_CHOICE_VIEW_V9',timing='BEFORE_OPERATION')
    context['coverage']['prompt']='RECEIVED_ZONE_OPERATION_V1'
    context['policy']['prompt']=dict(kind='ZONE_CHANGE_OPERATION_REQUEST',requestedIterations=-2,destination='Hand',
        origins=['Library','Graveyard','Library'],sourcePlayer='p0',decider='p1')
    context['diagnostics']['optionBindings']=dict(coverage='UNSUPPORTED',items=[],truncated=False)
    rows[3]['choice']['selection']=dict(coverage='OBSERVED_RETAINED_CARD_PREFIX',items=[retained],selectedCount=1,truncated=False)
    return rows


class ZoneOperationTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.index=0

    def export(self,rows):
        self.index+=1; source=self.root/f'{self.index}.jsonl';out=self.root/f'out-{self.index}'
        source.write_text(''.join(json.dumps(r)+'\n' for r in rows))
        result=module.export([source],out)
        self.assertEqual(result['inputCoverageAudit'],module.export([source])['inputCoverageAudit'])
        return result,out

    def test_received_iterations_are_not_legal_bounds_and_retained_cards_are_not_returns_or_movement(self):
        rows=operation_fixture();result,out=self.export(rows)
        value=json.loads((out/'choices.jsonl').read_text())
        self.assertFalse(value['trainingReady']);self.assertEqual(value['start']['choice'],rows[2]['choice'])
        audit=result['inputCoverageAudit'];locations=audit['entryLocationAudit']
        self.assertEqual(locations['knownInputBoundaryInventoryExtensionV5'],{'CHOICE':{'CONTROLLER_ZONE_CHANGE_SELECTIONS':'BEFORE_OPERATION'}})
        self.assertNotIn('CONTROLLER_ZONE_CHANGE_SELECTIONS',audit['knownInputBoundaryInventoryV1'])
        self.assertNotIn('CONTROLLER_ZONE_CHANGE_SELECTIONS',locations['knownInputBoundaryInventoryV2']['CHOICE'])
        self.assertNotIn('entitySelectionHistory',result)
        self.assertTrue(any('V9' in text and 'retained' in text for text in result['limitations']))

    def test_nullable_roles_empty_origins_and_planning_have_explicit_meanings(self):
        for planning in (False,True):
            rows=operation_fixture();choice=rows[2]['choice'];context=choice['inputContext']
            choice['options'].update(destination='None',origins=[],sourcePlayerId=None,deciderPlayerId=None)
            context['policy']['prompt'].update(destination='None',origins=[],sourcePlayer=None,decider=None)
            if planning:
                choice['stage']='CANDIDATE_EVALUATION';context['stateBasis']=choice['entryLocation']['stateBasis']='PLANNING_WORKING_STATE'
            rows[3]['choice']['selection'].update(items=[],selectedCount=0)
            self.export(rows)
        result,_=self.export(rows4())
        self.assertNotIn('knownInputBoundaryInventoryExtensionV5',result['inputCoverageAudit'].get('entryLocationAudit',{}))

    def test_malformed_scope_arguments_roles_and_retained_prefixes_are_rejected(self):
        for error in ('profile','entry','timing','branch','iterations','boolean','origin-order','destination','source','decider','unknown-role',
                      'extra-options','pool-feature','history','coverage','player-result','count','prefix','extra-prompt','kind-coverage'):
            rows=operation_fixture();choice=rows[2]['choice'];context=choice['inputContext'];prompt=context['policy']['prompt'];result=rows[3]['choice']['selection']
            if error=='profile':context.update(schemaVersion=8,profile='PERMITTED_CHOICE_VIEW_V8')
            if error=='entry':choice.pop('entryLocation')
            if error=='timing':context['timing']='BEFORE_SELECTOR'
            if error=='branch':choice['options']['branch']='SAME_NAME_AS_EACH'
            if error=='iterations':prompt['requestedIterations']=7
            if error=='boolean':prompt['requestedIterations']=True
            if error=='origin-order':prompt['origins']=['Graveyard','Library','Library']
            if error=='destination':prompt['destination']='Exile'
            if error=='source':prompt['sourcePlayer']='p1'
            if error=='decider':prompt['decider']='p0'
            if error=='unknown-role':prompt['sourcePlayer']='p9'
            if error=='extra-options':choice['options']['items']=[]
            if error=='pool-feature':prompt['offered']=['c0']
            if error=='history':prompt['priorSelectionCount']=0
            if error=='coverage':result['coverage']='OBSERVED_RETURNED_ENTITY_PREFIX'
            if error=='player-result':result['items']=[dict(type='PLAYER',id=0)]
            if error=='count':result['selectedCount']=0
            if error=='prefix':result['truncated']=True
            if error=='extra-prompt':prompt['minimumPicks']=0
            if error=='kind-coverage':context['coverage']['prompt']='RECEIVED_ZONE_REQUEST_V1'
            with self.subTest(error=error),self.assertRaises(module.InvalidData):self.export(rows)

    def test_unavailable_retained_references_keep_the_known_count_and_legacy_reports_stay_unchanged(self):
        rows=operation_fixture();rows[3]['choice']['selection'].update(coverage='UNAVAILABLE',items=[],selectedCount=9,truncated=True)
        self.export(rows)
        rows=operation_fixture();context=rows[2]['choice']['inputContext'];context.update(status='PARTIAL')
        context['policy']['prompt']=None;context['coverage']['prompt']='UNAVAILABLE'
        context['omissions'].append(dict(component='PROMPT',reason='UNRESOLVED_BINDING'));self.export(rows)
