import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_mana_pool_input import mana_fixture
from test_received_parameters import parameter_fixture, receipt


def source_ref(identity=7): return dict(type='SPELL_ABILITY',id=identity,stackId=None)


def source_fixture(remaining=2, check=True, effect=False):
    rows=mana_fixture();c=rows[2]['choice'];ctx=c['inputContext'];ctx.update(schemaVersion=20,profile='PERMITTED_CHOICE_VIEW_V20')
    values=dict(shard='GENERIC',remainingManaValue=remaining,checkCosts=check,effect=effect)
    c.update(choiceType='PAYMENT_MANA_SOURCE_SELECTION',boundary='MANA_SOURCE_OPTIONS',options=dict(coverage='OBSERVED_MANA_SOURCE_OPTIONS_NOT_ALL_LEGAL_PAYMENT_PLANS',abilities=dict(items=[source_ref()],truncated=False),callbackParameters=receipt('MANA_SOURCE_REQUEST',values)))
    ctx['policy']['receivedParameters']=dict(kind='MANA_SOURCE_REQUEST',**values);rows[3]['choice']['selection']=source_ref();return rows


class ManaSourceInputTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.index=0

    def export(self,rows):
        self.index+=1;path=self.root/f'{self.index}.jsonl';out=self.root/f'out-{self.index}';path.write_text(''.join(json.dumps(r)+'\n' for r in rows))
        result=module.export([path],out);self.assertEqual(result,module.export([path]));return result,out

    def test_received_flags_and_native_values_do_not_claim_a_domain_or_payment(self):
        for remaining in (-(1<<31),0,1,(1<<31)-1):
            for check,effect in ((True,False),(False,True)):
                result,out=self.export(source_fixture(remaining,check,effect));self.assertFalse(json.loads((out/'choices.jsonl').read_text())['trainingReady'])
                audit=result['inputCoverageAudit'];entry=audit['entryLocationAudit'];self.assertEqual(entry['knownInputBoundaryInventoryExtensionV10'],{'CHOICE':{'MANA_SOURCE_OPTIONS':'BEFORE_SELECTOR'}})
                self.assertNotIn('MANA_SOURCE_OPTIONS',audit['knownInputBoundaryInventoryV1']);self.assertNotIn('MANA_SOURCE_OPTIONS',entry['knownInputBoundaryInventoryV2']['CHOICE'])
                self.assertTrue(any('V20' in text and 'proposal' in text for text in result['limitations']))

    def test_planning_decline_error_and_outside_prefix_proposals_remain_distinct(self):
        rows=source_fixture();c=rows[2]['choice'];c['inputContext']['stateBasis']=c['entryLocation']['stateBasis']='PLANNING_WORKING_STATE';self.export(rows)
        rows[3]['choice']['selection']=source_ref(99);self.export(rows)
        for status in ('DECLINED','ERROR'):
            rows[3]['choice'].update(status=status,selection=None);self.export(rows)

    def test_unknown_native_cost_refuses_component_without_erasing_the_current_view(self):
        rows=source_fixture();c=rows[2]['choice'];ctx=c['inputContext'];c['options']['callbackParameters']=dict(schemaVersion=1,coverage='UNSUPPORTED',kind=None,values=None,reason='UNSUPPORTED')
        ctx['coverage']['receivedParameters']='UNAVAILABLE';ctx['policy']['receivedParameters']=None;ctx['status']='PARTIAL';ctx['omissions'].append(dict(component='PARAMETERS',reason='UNSUPPORTED'));self.export(rows)
        ctx['coverage']['receivedParameters']='RECEIVED_CALLBACK_PARAMETERS_V1';ctx['policy']['receivedParameters']=dict(kind='MANA_SOURCE_REQUEST',shard='GENERIC',remainingManaValue=1,checkCosts=True,effect=False)
        with self.assertRaises(module.InvalidData):self.export(rows)

    def test_source_profile_entry_options_flags_and_selection_are_strict(self):
        errors=('profile','no-context','no-entry','operation','unknown-shard','null-shard','bool-amount','huge-amount','float-amount','int-check','str-effect','mismatch','missing','extra-feature','extra-option','wrong-pool','oversized-pool','no-receipt','paid','selected-card','wrong-family','fake-binding')
        for error in errors:
            rows=source_fixture();c=rows[2]['choice'];ctx=c['inputContext'];raw=c['options']['callbackParameters'];features=ctx['policy']['receivedParameters']
            if error=='profile':ctx.update(schemaVersion=19,profile='PERMITTED_CHOICE_VIEW_V19')
            if error=='no-context':c.pop('inputContext')
            if error=='no-entry':c.pop('entryLocation')
            if error=='operation':ctx['timing']=c['entryLocation']['timing']='BEFORE_OPERATION';c['entryLocation']['kind']='OPERATION'
            if error=='unknown-shard':features['shard']=raw['values']['shard']='FLEXIBLE'
            if error=='null-shard':features['shard']=raw['values']['shard']=None
            if error in ('bool-amount','huge-amount','float-amount'):
                value={'bool-amount':True,'huge-amount':1<<31,'float-amount':1.5}[error];features['remainingManaValue']=raw['values']['remainingManaValue']=value
            if error=='int-check':features['checkCosts']=raw['values']['checkCosts']=1
            if error=='str-effect':features['effect']=raw['values']['effect']='false'
            if error=='mismatch':features['remainingManaValue']=1
            if error=='missing':raw['values'].pop('effect')
            if error=='extra-feature':features['reservedSourceIds']=[7]
            if error=='extra-option':c['options']['legalSourceCount']=1
            if error=='wrong-pool':c['options']['abilities']['items'][0]=dict(type='PLAYER',id=0)
            if error=='oversized-pool':c['options']['abilities']['items']*=257
            if error=='no-receipt':c['options'].pop('callbackParameters')
            if error=='paid':rows[3]['choice']['selection']['paid']=True
            if error=='selected-card':rows[3]['choice']['selection']=dict(type='CARD',id=8,gameTimestamp=10)
            if error=='wrong-family':raw.update(kind='MANA_POOL_REQUEST',values=dict(shard='GENERIC',testPayment=False,colorsPaid=-1));features.clear();features.update(kind='MANA_POOL_REQUEST',shard='GENERIC',testPayment=False,colorsPaid=-1)
            if error=='fake-binding':ctx['diagnostics']['optionBindings']=dict(coverage='DIAGNOSTIC_PUBLIC_CURRENT_V1',items=[dict(optionIndex=0,entityRef='c0')],truncated=False)
            with self.subTest(error=error),self.assertRaises(module.InvalidData):self.export(rows)

    def test_source_parameters_cannot_expand_pool_entity_numeric_or_trigger_permission(self):
        from test_trigger_preparation_input import trigger_fixture
        for rows in (mana_fixture(),parameter_fixture('NONE',{}),parameter_fixture('NONE',{},'ENTITY'),trigger_fixture()):
            c=rows[2]['choice'];ctx=c['inputContext'];ctx.update(schemaVersion=20,profile='PERMITTED_CHOICE_VIEW_V20')
            c['options']['callbackParameters']=receipt('MANA_SOURCE_REQUEST',dict(shard='GREEN',remainingManaValue=1,checkCosts=True,effect=False));ctx['policy']['receivedParameters']=dict(kind='MANA_SOURCE_REQUEST',shard='GREEN',remainingManaValue=1,checkCosts=True,effect=False)
            with self.assertRaises(module.InvalidData):self.export(rows)

    def test_new_optional_inventory_and_limitations_do_not_upgrade_legacy_pool_inputs(self):
        result,_=self.export(mana_fixture());self.assertFalse(any('V20' in text for text in result['limitations']))
        self.assertNotIn('knownInputBoundaryInventoryExtensionV10',result['inputCoverageAudit']['entryLocationAudit'])
