import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_received_parameters import parameter_fixture, receipt, card_ref
from test_choice_events import choices_fixture
from test_entry_location import entry


def mana_value(): return dict(source=card_ref(),player=dict(type='PLAYER',id=1),color=8,snow=False)


def mana_fixture(test=False, colors=-1, shard='GENERIC'):
    rows=parameter_fixture('NONE',{},'ENTITY');c=rows[2]['choice'];ctx=c['inputContext'];basis='PLANNING_WORKING_STATE' if test else 'RULES_STATE'
    ctx.update(schemaVersion=19,profile='PERMITTED_CHOICE_VIEW_V19',stateBasis=basis)
    ctx['diagnostics']['optionBindings']=dict(coverage='UNSUPPORTED',items=[],truncated=False)
    c.update(choiceType='PAYMENT_MANA_SELECTION',boundary='MANA_POOL_OPTIONS',stage='PAYMENT',entryLocation=entry())
    c['entryLocation']['stateBasis']=basis;values=dict(testPayment=test,shard=shard,colorsPaid=colors)
    c['options']=dict(coverage='OBSERVED_MANA_OPTIONS',mana=dict(items=[mana_value()],truncated=False),callbackParameters=receipt('MANA_POOL_REQUEST',values))
    ctx['policy']['receivedParameters']=dict(kind='MANA_POOL_REQUEST',**values);rows[3]['choice']['selection']=mana_value()
    return rows


class ManaPoolInputTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.index=0

    def export(self,rows):
        self.index+=1;path=self.root/f'{self.index}.jsonl';out=self.root/f'out-{self.index}';path.write_text(''.join(json.dumps(r)+'\n' for r in rows))
        result=module.export([path],out);self.assertEqual(result,module.export([path]));return result,out

    def test_real_and_test_requests_are_received_arguments_and_test_views_are_planning(self):
        for test in (False,True):
            for colors in (-128,-1,0,31,127):
                result,out=self.export(mana_fixture(test,colors));self.assertFalse(json.loads((out/'choices.jsonl').read_text())['trainingReady'])
                audit=result['inputCoverageAudit'];entry_audit=audit['entryLocationAudit']
                self.assertEqual(entry_audit['knownInputBoundaryInventoryExtensionV9'],{'CHOICE':{'MANA_POOL_OPTIONS':'BEFORE_SELECTOR'}})
                self.assertNotIn('MANA_POOL_OPTIONS',audit['knownInputBoundaryInventoryV1']);self.assertNotIn('MANA_POOL_OPTIONS',entry_audit['knownInputBoundaryInventoryV2']['CHOICE'])
                self.assertTrue(any('V19' in text and 'testPayment=true' in text for text in result['limitations']))

    def test_non_test_can_inherit_planning_and_error_without_becoming_paid(self):
        rows=mana_fixture();c=rows[2]['choice'];c['inputContext']['stateBasis']=c['entryLocation']['stateBasis']='PLANNING_WORKING_STATE';self.export(rows)
        rows[3]['choice'].update(status='ERROR',selection=None);self.export(rows)

    def test_unknown_null_shard_receipt_refuses_whole_component_without_inventing_none(self):
        rows=mana_fixture();c=rows[2]['choice'];ctx=c['inputContext'];c['options']['callbackParameters']=dict(schemaVersion=1,coverage='UNSUPPORTED',kind=None,values=None,reason='UNSUPPORTED')
        ctx['coverage']['receivedParameters']='UNAVAILABLE';ctx['policy']['receivedParameters']=None;ctx['status']='PARTIAL';ctx['omissions'].append(dict(component='PARAMETERS',reason='UNSUPPORTED'));self.export(rows)
        ctx['coverage']['receivedParameters']='RECEIVED_CALLBACK_PARAMETERS_V1';ctx['policy']['receivedParameters']=dict(kind='MANA_POOL_REQUEST',testPayment=False,shard='GENERIC',colorsPaid=-1)
        with self.assertRaises(module.InvalidData):self.export(rows)

    def test_strict_mode_basis_profile_entry_and_scalar_agreement(self):
        for error in ('profile','entry','operation','test-rules','test-entry-rules','int-bool','str-bool','bad-shard','null-shard','raw-shard','too-low','too-high','bool-byte','float-byte','mismatch','missing','extra','no-receipt','fake-none','unavailable-prefix','fake-binding'):
            rows=mana_fixture(True);c=rows[2]['choice'];ctx=c['inputContext'];raw=c['options']['callbackParameters'];features=ctx['policy']['receivedParameters']
            if error=='profile':ctx.update(schemaVersion=18,profile='PERMITTED_CHOICE_VIEW_V18')
            if error=='entry':c.pop('entryLocation')
            if error=='operation':c['entryLocation']['kind']='OPERATION';c['entryLocation']['timing']=ctx['timing']='BEFORE_OPERATION'
            if error=='test-rules':ctx['stateBasis']=c['entryLocation']['stateBasis']='RULES_STATE'
            if error=='test-entry-rules':c['entryLocation']['stateBasis']='RULES_STATE'
            if error=='int-bool':raw['values']['testPayment']=features['testPayment']=1
            if error=='str-bool':raw['values']['testPayment']=features['testPayment']='true'
            if error=='bad-shard':raw['values']['shard']=features['shard']='ANY_MANA'
            if error=='null-shard':raw['values']['shard']=features['shard']=None
            if error=='raw-shard':raw['values']['shard']=features['shard']={'name':'GENERIC'}
            if error in ('too-low','too-high','bool-byte','float-byte'):
                value={'too-low':-129,'too-high':128,'bool-byte':True,'float-byte':0.0}[error];raw['values']['colorsPaid']=features['colorsPaid']=value
            if error=='mismatch':features['testPayment']=False
            if error=='missing':raw['values'].pop('colorsPaid')
            if error=='extra':features['paid']=True
            if error=='no-receipt':c['options'].pop('callbackParameters')
            if error=='fake-none':raw.update(kind='NONE',values={});features.clear();features['kind']='NONE'
            if error=='unavailable-prefix':raw.update(coverage='UNAVAILABLE',values=None,reason='OUTSIDE_CAPTURED_PREFIX')
            if error=='fake-binding':ctx['diagnostics']['optionBindings']=dict(coverage='DIAGNOSTIC_PUBLIC_CURRENT_V1',items=[dict(optionIndex=0,entityRef='c0')],truncated=False)
            with self.subTest(error=error),self.assertRaises(module.InvalidData):self.export(rows)

    def test_new_mana_kind_cannot_expand_entity_numeric_or_trigger_permissions(self):
        from test_trigger_preparation_input import trigger_fixture
        for rows in (parameter_fixture('NONE',{}),parameter_fixture('NONE',{},'ENTITY'),trigger_fixture()):
            c=rows[2]['choice'];ctx=c['inputContext'];ctx.update(schemaVersion=19,profile='PERMITTED_CHOICE_VIEW_V19')
            c['options']['callbackParameters']=receipt('MANA_POOL_REQUEST',dict(testPayment=False,shard='GENERIC',colorsPaid=-1))
            ctx['policy']['receivedParameters']=dict(kind='MANA_POOL_REQUEST',testPayment=False,shard='GENERIC',colorsPaid=-1)
            with self.assertRaises(module.InvalidData):self.export(rows)
        rows=mana_fixture();c=rows[2]['choice'];c['options']['callbackParameters']=receipt('ENTITY_ATTACHMENT_ROLES',dict(entities=[]));c['inputContext']['policy']['receivedParameters']=dict(kind='ENTITY_ATTACHMENT_ROLES',entities=[])
        with self.assertRaises(module.InvalidData):self.export(rows)

    def test_legacy_unknown_callback_retains_absent_input_and_inventories(self):
        rows=choices_fixture();c=rows[2]['choice'];c.update(choiceType='PAYMENT_MANA_SELECTION',boundary='MANA_POOL_OPTIONS',stage='PAYMENT',options=dict(coverage='OBSERVED_MANA_OPTIONS',mana=dict(items=[mana_value()],truncated=False)))
        rows[3]['choice']['selection']=mana_value();result,_=self.export(rows)
        self.assertNotIn('entryLocationAudit',result['inputCoverageAudit']);self.assertFalse(any('V19' in text for text in result['limitations']))
