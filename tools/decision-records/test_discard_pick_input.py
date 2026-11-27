import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_card_payment_request import card_fixture, unavailable
from test_received_parameters import receipt,card_ref


def discard_pick_fixture(count=0,strategy='PREFERENCE'):
    rows=card_fixture();c=rows[2]['choice'];ctx=c['inputContext'];timing='BEFORE_SELECTOR' if strategy=='PREFERENCE' else 'BEFORE_OPERATION'
    ctx.update(schemaVersion=24,profile='PERMITTED_CHOICE_VIEW_V24',timing=timing);c['entryLocation'].update(kind='SELECTOR' if strategy=='PREFERENCE' else 'OPERATION',timing=timing)
    values=dict(strategy=strategy,effectiveMinimum=2,maximum=2,priorSelectionCount=count,previousSelections=[card_ref() for _ in range(count)])
    c.update(choiceType='PAYMENT_CARD_PICK',boundary='PAYMENT_DISCARD_'+strategy,options=dict(coverage='OBSERVED_CARD_OPTIONS_NOT_ALL_LEGAL_PAYMENT_PLANS',strategy=strategy,cards=dict(items=[card_ref()],truncated=False),callbackParameters=receipt('DISCARD_PICK_REQUEST',values)))
    ctx['policy']['receivedParameters']=dict(kind='DISCARD_PICK_REQUEST',**copy.deepcopy(values));ctx['policy']['receivedParameters']['previousSelections']=['c0']*count;rows[3]['choice']['selection']=card_ref();return rows


class DiscardPickInputTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.index=0

    def export(self,rows):
        self.index+=1;p=self.root/f'{self.index}.jsonl';out=self.root/f'out-{self.index}';p.write_text(''.join(json.dumps(r)+'\n' for r in rows));result=module.export([p],out);self.assertEqual(result,module.export([p]));return result,out

    def test_preference_and_fallback_have_separate_native_entry_kinds_and_typed_progress(self):
        for strategy,timing in (('PREFERENCE','BEFORE_SELECTOR'),('FALLBACK','BEFORE_OPERATION')):
            result,out=self.export(discard_pick_fixture(1,strategy));self.assertFalse(json.loads((out/'choices.jsonl').read_text())['trainingReady']);audit=result['inputCoverageAudit']
            self.assertEqual(audit['entryLocationAudit']['knownInputBoundaryInventoryExtensionV13'],{'CHOICE':{'PAYMENT_DISCARD_'+strategy:timing}});self.assertNotIn('PAYMENT_DISCARD_'+strategy,audit['knownInputBoundaryInventoryV1']);self.assertTrue(any('V24' in t and 'prior-proposal' in t for t in result['limitations']));self.assertTrue(any('identical permitted hand presentations' in t for t in result['limitations']));self.assertFalse(any('Private history/domain permission is unchanged' in t for t in result['limitations']))

    def test_current_chooser_owned_visible_hand_permission_is_specific_to_discard_history(self):
        rows=discard_pick_fixture(1);ctx=rows[2]['choice']['inputContext'];card=ctx['policy']['cards'][0];card.update(zone='Hand',owner=ctx['policy']['chooser'],controller=ctx['policy']['chooser'],identityKnown=True);self.export(rows)
        for error in ('opponent-owner','opponent-control','unknown-identity','library','stale','foreign'):
            bad=copy.deepcopy(rows);c=bad[2]['choice'];v=c['inputContext']['policy']['cards'][0]
            if error=='opponent-owner':v['owner']='p1' if v['owner']=='p0' else 'p0'
            if error=='opponent-control':v['controller']='p1' if v['controller']=='p0' else 'p0'
            if error=='unknown-identity':v['identityKnown']=False;v['name']=None
            if error=='library':v['zone']='Library'
            if error=='stale':c['options']['callbackParameters']['values']['previousSelections'][0]['gameTimestamp']+=1
            if error=='foreign':c['options']['callbackParameters']['values']['previousSelections'][0]['id']+=100
            with self.subTest(error=error),self.assertRaises(module.InvalidData):self.export(bad)
        from test_sacrifice_pick_input import pick_fixture
        old=pick_fixture(1);old[2]['choice']['inputContext'].update(schemaVersion=24,profile='PERMITTED_CHOICE_VIEW_V24');old[2]['choice']['inputContext']['policy']['cards'][0].update(zone='Hand')
        with self.assertRaises(module.InvalidData):self.export(old)

    def test_identical_private_hand_presentations_cannot_be_disambiguated_by_ids_or_hidden_order(self):
        rows=discard_pick_fixture(1);c=rows[2]['choice'];ctx=c['inputContext'];card=ctx['policy']['cards'][0];card.update(zone='Hand',owner=ctx['policy']['chooser'],controller=ctx['policy']['chooser'],identityKnown=True)
        other=copy.deepcopy(card);other['entityRef']='c1';ctx['policy']['cards'].append(other);binding=copy.deepcopy(ctx['diagnostics']['cardBindings'][0]);binding.update(entityRef='c1',id=binding['id']+1);ctx['diagnostics']['cardBindings'].append(binding)
        with self.assertRaises(module.InvalidData):self.export(rows)
        ctx['policy']['receivedParameters']=None;ctx['coverage']['receivedParameters']='UNAVAILABLE';ctx['status']='PARTIAL';ctx['omissions'].append(dict(component='PARAMETERS',reason='UNRESOLVED_BINDING'));self.export(rows)

    def test_probe_decline_and_planning_error_remain_local_operation_outcomes(self):
        for strategy in ('PREFERENCE','FALLBACK'):
            rows=discard_pick_fixture(0,strategy);c=rows[2]['choice'];c['inputContext']['stateBasis']=c['entryLocation']['stateBasis']='PLANNING_WORKING_STATE'
            for status in ('DECLINED','ERROR'):
                rows[3]['choice'].update(status=status,selection=None);self.export(rows)

    def test_whole_history_sixty_four_is_allowed_but_prefixes_and_duplicate_members_are_refused(self):
        rows=discard_pick_fixture();c=rows[2]['choice'];ctx=c['inputContext'];card=ctx['policy']['cards'][0];binding=ctx['diagnostics']['cardBindings'][0];raws=[];refs=[]
        for i in range(64):
            if i:
                new=copy.deepcopy(card);new['entityRef']='c'+str(i);b=copy.deepcopy(binding);b.update(entityRef=new['entityRef'],id=binding['id']+i);ctx['policy']['cards'].append(new);ctx['diagnostics']['cardBindings'].append(b)
            raws.append(dict(type='CARD',id=binding['id']+i,gameTimestamp=binding['gameTimestamp']));refs.append('c'+str(i))
        raw=c['options']['callbackParameters']['values'];feature=ctx['policy']['receivedParameters'];raw.update(effectiveMinimum=65,maximum=65,priorSelectionCount=64,previousSelections=raws);feature.update(effectiveMinimum=65,maximum=65,priorSelectionCount=64,previousSelections=refs);self.export(rows)
        feature['previousSelections'][1]='c0';raw['previousSelections'][1]=copy.deepcopy(raws[0])
        with self.assertRaises(module.InvalidData):self.export(rows)

    def test_profile_count_strategy_timing_and_identity_checks_are_strict(self):
        for error in ('profile','entry','timing','strategy','boundary','count','minimum','negative','big','bool','max-big','extra','paid','prefix','wrong-family','binding','receipt-order'):
            rows=discard_pick_fixture(1);c=rows[2]['choice'];ctx=c['inputContext'];raw=c['options']['callbackParameters'];v=raw['values'];f=ctx['policy']['receivedParameters']
            if error=='profile':ctx.update(schemaVersion=23,profile='PERMITTED_CHOICE_VIEW_V23')
            if error=='entry':c.pop('entryLocation')
            if error=='timing':ctx['timing']=c['entryLocation']['timing']='BEFORE_OPERATION';c['entryLocation']['kind']='OPERATION'
            if error=='strategy':v['strategy']=f['strategy']='FALLBACK'
            if error=='boundary':c['boundary']='PAYMENT_SACRIFICE_PICK'
            if error=='count':v['priorSelectionCount']=f['priorSelectionCount']=0
            if error=='minimum':v['effectiveMinimum']=f['effectiveMinimum']=1
            if error=='negative':v['priorSelectionCount']=f['priorSelectionCount']=-1
            if error=='big':v['effectiveMinimum']=f['effectiveMinimum']=1<<31
            if error=='bool':v['priorSelectionCount']=f['priorSelectionCount']=True
            if error=='max-big':v['maximum']=f['maximum']=1<<31
            if error=='extra':f['legalCount']=1
            if error=='paid':rows[3]['choice']['selection']['paid']=True
            if error=='prefix':v['truncated']=False
            if error=='wrong-family':raw.update(kind='SACRIFICE_PICK_REQUEST',values=dict(requestedSelections=2,priorSelectionCount=1,previousSelections=[card_ref()]));f.clear();f.update(kind='SACRIFICE_PICK_REQUEST',requestedSelections=2,priorSelectionCount=1,previousSelections=['c0'])
            if error=='binding':ctx['diagnostics']['optionBindings']=dict(coverage='DIAGNOSTIC_PUBLIC_CURRENT_V1',items=[dict(optionIndex=0,entityRef='c0')],truncated=False)
            if error=='receipt-order':v['previousSelections'][0]['id']+=100
            with self.subTest(error=error),self.assertRaises(module.InvalidData):self.export(rows)

    def test_unavailable_views_do_not_skip_strategy_validation_or_reconstruct_missing_history(self):
        rows=discard_pick_fixture();c=rows[2]['choice'];unavailable(c['inputContext']);self.export(rows);c['options']['callbackParameters']['values']['strategy']='FALLBACK'
        with self.assertRaises(module.InvalidData):self.export(rows)
        for coverage in ('UNAVAILABLE','UNSUPPORTED'):
            rows=discard_pick_fixture();c=rows[2]['choice'];ctx=c['inputContext'];raw=c['options']['callbackParameters'];raw.update(coverage=coverage,kind='DISCARD_PICK_REQUEST' if coverage=='UNAVAILABLE' else None,values=None,reason='OUTSIDE_CAPTURED_PREFIX' if coverage=='UNAVAILABLE' else 'UNSUPPORTED');ctx['policy']['receivedParameters']=None;ctx['coverage']['receivedParameters']='UNAVAILABLE';ctx['status']='PARTIAL';ctx['omissions'].append(dict(component='PARAMETERS',reason=raw['reason']));self.export(rows)

    def test_other_operations_cannot_inherit_discard_history_and_legacy_inventory_is_frozen(self):
        from test_discard_batch_input import batch_fixture
        for rows in (card_fixture(),batch_fixture()):
            c=rows[2]['choice'];ctx=c['inputContext'];ctx.update(schemaVersion=24,profile='PERMITTED_CHOICE_VIEW_V24');c['options']['callbackParameters']=receipt('DISCARD_PICK_REQUEST',dict(strategy='PREFERENCE',effectiveMinimum=1,maximum=1,priorSelectionCount=0,previousSelections=[]));ctx['policy']['receivedParameters']=dict(kind='DISCARD_PICK_REQUEST',strategy='PREFERENCE',effectiveMinimum=1,maximum=1,priorSelectionCount=0,previousSelections=[])
            with self.assertRaises(module.InvalidData):self.export(rows)
        result,_=self.export(card_fixture());self.assertFalse(any('V24' in t for t in result['limitations']));self.assertNotIn('knownInputBoundaryInventoryExtensionV13',result['inputCoverageAudit']['entryLocationAudit'])
