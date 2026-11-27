import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_card_payment_request import card_fixture
from test_received_parameters import receipt,card_ref


def pick_fixture(count=0,requested=2):
    rows=card_fixture('SACRIFICE');c=rows[2]['choice'];ctx=c['inputContext'];ctx.update(schemaVersion=22,profile='PERMITTED_CHOICE_VIEW_V22')
    raw=dict(requestedSelections=requested,priorSelectionCount=count,previousSelections=[copy.deepcopy(card_ref()) for _ in range(count)])
    c.update(choiceType='PAYMENT_CARD_PICK',boundary='PAYMENT_SACRIFICE_PICK',options=dict(coverage='OBSERVED_CARD_OPTIONS_NOT_ALL_LEGAL_PAYMENT_PLANS',cards=dict(items=[card_ref()],truncated=False),callbackParameters=receipt('SACRIFICE_PICK_REQUEST',raw)))
    ctx['policy']['receivedParameters']=dict(kind='SACRIFICE_PICK_REQUEST',requestedSelections=requested,priorSelectionCount=count,previousSelections=['c0']*count);rows[3]['choice']['selection']=card_ref();return rows


class SacrificePickInputTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.index=0

    def export(self,rows):
        self.index+=1;path=self.root/f'{self.index}.jsonl';out=self.root/f'out-{self.index}';path.write_text(''.join(json.dumps(r)+'\n' for r in rows));result=module.export([path],out);self.assertEqual(result,module.export([path]));return result,out

    def test_whole_public_prior_proposals_have_own_entry_and_no_paid_claim(self):
        for count in (0,1):
            result,out=self.export(pick_fixture(count));self.assertFalse(json.loads((out/'choices.jsonl').read_text())['trainingReady']);audit=result['inputCoverageAudit'];entry=audit['entryLocationAudit']
            self.assertEqual(entry['knownInputBoundaryInventoryExtensionV11'],{'CHOICE':{'PAYMENT_SACRIFICE_PICK':'BEFORE_SELECTOR'}});self.assertNotIn('PAYMENT_SACRIFICE_PICK',audit['knownInputBoundaryInventoryV1'])
            self.assertTrue(any('V22' in text and 'prior-proposal' in text for text in result['limitations']))

    def test_planning_decline_error_and_bounded_or_unknown_history_stay_explicit(self):
        rows=pick_fixture();c=rows[2]['choice'];ctx=c['inputContext'];ctx['stateBasis']=c['entryLocation']['stateBasis']='PLANNING_WORKING_STATE';self.export(rows)
        for status in ('DECLINED','ERROR'):
            rows[3]['choice'].update(status=status,selection=None);self.export(rows)
        for coverage in ('UNAVAILABLE','UNSUPPORTED'):
            rows=pick_fixture();c=rows[2]['choice'];ctx=c['inputContext'];raw=c['options']['callbackParameters'];raw.update(coverage=coverage,kind='SACRIFICE_PICK_REQUEST' if coverage=='UNAVAILABLE' else None,values=None,reason='OUTSIDE_CAPTURED_PREFIX' if coverage=='UNAVAILABLE' else 'UNSUPPORTED')
            ctx['coverage']['receivedParameters']='UNAVAILABLE';ctx['policy']['receivedParameters']=None;ctx['status']='PARTIAL';ctx['omissions'].append(dict(component='PARAMETERS',reason=raw['reason']));self.export(rows)

    def test_whole_sixty_four_history_is_allowed_without_prefix_or_duplicate_features(self):
        rows=pick_fixture(0,65);c=rows[2]['choice'];ctx=c['inputContext'];base_card=ctx['policy']['cards'][0];base_binding=ctx['diagnostics']['cardBindings'][0];raws=[];locals=[]
        for i in range(64):
            if i:
                card=copy.deepcopy(base_card);card['entityRef']='c'+str(i);binding=copy.deepcopy(base_binding);binding.update(entityRef=card['entityRef'],id=base_binding['id']+i);ctx['policy']['cards'].append(card);ctx['diagnostics']['cardBindings'].append(binding)
            raws.append(dict(type='CARD',id=base_binding['id']+i,gameTimestamp=base_binding['gameTimestamp']));locals.append('c'+str(i))
        c['options']['callbackParameters']['values'].update(priorSelectionCount=64,previousSelections=raws);ctx['policy']['receivedParameters'].update(priorSelectionCount=64,previousSelections=locals);self.export(rows)
        ctx['policy']['receivedParameters']['previousSelections'][1]='c0';c['options']['callbackParameters']['values']['previousSelections'][1]=copy.deepcopy(raws[0])
        with self.assertRaises(module.InvalidData):self.export(rows)

    def test_count_profile_bound_identity_and_return_permissions_are_strict(self):
        for error in ('profile','entry','count','requested','negative','too-many','private','stale','raw-player','feature-id','mismatch','extra','paid','prefix','wrong-family'):
            rows=pick_fixture(1);c=rows[2]['choice'];ctx=c['inputContext'];raw=c['options']['callbackParameters'];features=ctx['policy']['receivedParameters']
            if error=='profile':ctx.update(schemaVersion=21,profile='PERMITTED_CHOICE_VIEW_V21')
            if error=='entry':c.pop('entryLocation')
            if error=='count':raw['values']['priorSelectionCount']=features['priorSelectionCount']=0
            if error=='requested':raw['values']['requestedSelections']=features['requestedSelections']=1
            if error=='negative':raw['values']['priorSelectionCount']=features['priorSelectionCount']=-1
            if error=='too-many':raw['values'].update(requestedSelections=66,priorSelectionCount=65,previousSelections=[card_ref()]*65);features.update(requestedSelections=66,priorSelectionCount=65,previousSelections=['c0']*65)
            if error=='private':ctx['policy']['cards'][0]['zone']='Hand'
            if error=='stale':raw['values']['previousSelections'][0]['gameTimestamp']+=1
            if error=='raw-player':raw['values']['previousSelections'][0]=dict(type='PLAYER',id=0)
            if error=='feature-id':features['previousSelections'][0]=card_ref()
            if error=='mismatch':features['requestedSelections']=3
            if error=='extra':features['remainingCount']=1
            if error=='paid':rows[3]['choice']['selection']['paid']=True
            if error=='prefix':raw['values']['truncated']=False
            if error=='wrong-family':raw.update(kind='CARD_PAYMENT_REQUEST',values=dict(paymentKind='SACRIFICE',minimum=2,maximum=2));features.clear();features.update(kind='CARD_PAYMENT_REQUEST',paymentKind='SACRIFICE',minimum=2,maximum=2)
            with self.subTest(error=error),self.assertRaises(module.InvalidData):self.export(rows)

    def test_unresolved_prior_member_refuses_the_whole_feature_but_keeps_current_view(self):
        rows=pick_fixture(1);c=rows[2]['choice'];ctx=c['inputContext'];c['options']['callbackParameters']['values']['previousSelections'][0]['id']+=100
        ctx['policy']['receivedParameters']=None;ctx['coverage']['receivedParameters']='UNAVAILABLE';ctx['status']='PARTIAL';ctx['omissions'].append(dict(component='PARAMETERS',reason='UNRESOLVED_BINDING'));self.export(rows)
        c['options']['callbackParameters']['values'].update(priorSelectionCount=0,previousSelections=[])
        with self.assertRaises(module.InvalidData):self.export(rows)

    def test_parent_request_never_inherits_pick_history_and_legacy_inventory_stays_frozen(self):
        rows=card_fixture();result,_=self.export(rows);self.assertFalse(any('V22' in text for text in result['limitations']))
        c=rows[2]['choice'];ctx=c['inputContext'];ctx.update(schemaVersion=22,profile='PERMITTED_CHOICE_VIEW_V22');c['options']['callbackParameters']=receipt('SACRIFICE_PICK_REQUEST',dict(requestedSelections=2,priorSelectionCount=0,previousSelections=[]));ctx['policy']['receivedParameters']=dict(kind='SACRIFICE_PICK_REQUEST',requestedSelections=2,priorSelectionCount=0,previousSelections=[])
        with self.assertRaises(module.InvalidData):self.export(rows)
