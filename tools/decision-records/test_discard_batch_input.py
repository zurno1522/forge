import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_card_payment_request import card_fixture,unavailable
from test_received_parameters import receipt,card_ref


def batch_fixture(branch='POLICY'):
    rows=card_fixture();c=rows[2]['choice'];ctx=c['inputContext'];ctx.update(schemaVersion=23,profile='PERMITTED_CHOICE_VIEW_V23',timing='BEFORE_OPERATION')
    c['entryLocation'].update(kind='OPERATION',timing='BEFORE_OPERATION');values=dict(branch=branch,minimum=1,maximum=2,manaValue=2 if branch=='MANA_VALUE' else None);supplied=branch in ('POLICY','COMBO')
    c.update(choiceType='PAYMENT_CARD_BATCH',boundary='PAYMENT_DISCARD_BATCH',options=dict(coverage='DISCARD_OPERATION_NOT_ALL_LEGAL_PLANS',branch=branch,poolCoverage='RECEIVED_CARD_POOL' if supplied else 'NO_RECEIVED_CARD_POOL',cards=dict(items=[card_ref()],truncated=False) if supplied else None,callbackParameters=receipt('DISCARD_BATCH_REQUEST',values)))
    ctx['policy']['receivedParameters']=dict(kind='DISCARD_BATCH_REQUEST',**values);return rows


class DiscardBatchInputTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.index=0

    def export(self,rows):
        self.index+=1;path=self.root/f'{self.index}.jsonl';out=self.root/f'out-{self.index}';path.write_text(''.join(json.dumps(r)+'\n' for r in rows));result=module.export([path],out);self.assertEqual(result,module.export([path]));return result,out

    def test_native_branch_scopes_distinguish_supplied_pools_from_internal_hand_gathering(self):
        for branch in ('POLICY','COMBO','SHAPESHIFTER','MANA_VALUE','UNCASTABLE_EXCESS'):
            result,out=self.export(batch_fixture(branch));self.assertFalse(json.loads((out/'choices.jsonl').read_text())['trainingReady'])
            audit=result['inputCoverageAudit'];self.assertEqual(audit['entryLocationAudit']['knownInputBoundaryInventoryExtensionV12'],{'CHOICE':{'PAYMENT_DISCARD_BATCH':'BEFORE_OPERATION'}});self.assertNotIn('PAYMENT_DISCARD_BATCH',audit['knownInputBoundaryInventoryV1'])
            self.assertTrue(any('V23' in text and 'forced aliases' in text for text in result['limitations']))

    def test_null_combo_fallthrough_and_errors_are_operation_outcomes_not_whole_cost_failures(self):
        rows=batch_fixture('COMBO');c=rows[2]['choice'];c['inputContext']['stateBasis']=c['entryLocation']['stateBasis']='PLANNING_WORKING_STATE'
        for status in ('DECLINED','ERROR'):
            rows[3]['choice'].update(status=status,selection=None);self.export(rows)

    def test_branch_consistency_is_checked_when_no_current_view_is_available(self):
        rows=batch_fixture();c=rows[2]['choice'];unavailable(c['inputContext']);self.export(rows);c['options']['callbackParameters']['values']['branch']='COMBO'
        with self.assertRaises(module.InvalidData):self.export(rows)

    def test_profile_kind_pool_shape_and_received_scalar_permissions_are_strict(self):
        for error in ('profile','entry','selector','no-context','bad-branch','branch-mismatch','invented-pool','lost-pool','false-coverage','extra','missing','bool-bound','big-bound','mana-extra','mana-missing','mana-bool','no-receipt','binding','paid'):
            rows=batch_fixture('MANA_VALUE' if error in ('mana-missing','mana-bool','invented-pool') else 'POLICY');c=rows[2]['choice'];ctx=c['inputContext'];raw=c['options']['callbackParameters'];features=ctx['policy']['receivedParameters']
            if error=='profile':ctx.update(schemaVersion=22,profile='PERMITTED_CHOICE_VIEW_V22')
            if error=='entry':c.pop('entryLocation')
            if error=='selector':ctx['timing']=c['entryLocation']['timing']='BEFORE_SELECTOR';c['entryLocation']['kind']='SELECTOR'
            if error=='no-context':c.pop('inputContext')
            if error=='bad-branch':c['options']['branch']=raw['values']['branch']=features['branch']='ARBITRARY_LOGIC'
            if error=='branch-mismatch':raw['values']['branch']=features['branch']='COMBO'
            if error=='invented-pool':c['options']['cards']=dict(items=[card_ref()],truncated=False)
            if error=='lost-pool':c['options']['cards']=None
            if error=='false-coverage':c['options']['poolCoverage']='NO_RECEIVED_CARD_POOL'
            if error=='extra':features['legalCardCount']=1
            if error=='missing':raw['values'].pop('manaValue')
            if error=='bool-bound':raw['values']['minimum']=features['minimum']=True
            if error=='big-bound':raw['values']['maximum']=features['maximum']=1<<31
            if error=='mana-extra':raw['values']['manaValue']=features['manaValue']=2
            if error=='mana-missing':raw['values']['manaValue']=features['manaValue']=None
            if error=='mana-bool':raw['values']['manaValue']=features['manaValue']=True
            if error=='no-receipt':c['options'].pop('callbackParameters')
            if error=='binding':ctx['diagnostics']['optionBindings']=dict(coverage='DIAGNOSTIC_PUBLIC_CURRENT_V1',items=[dict(optionIndex=0,entityRef='c0')],truncated=False)
            if error=='paid':rows[3]['choice']['selection']['paid']=True
            with self.subTest(error=error),self.assertRaises(module.InvalidData):self.export(rows)

    def test_parent_card_and_other_existing_families_cannot_inherit_batch_permission(self):
        from test_mana_source_input import source_fixture
        for rows in (card_fixture(),source_fixture()):
            c=rows[2]['choice'];ctx=c['inputContext'];ctx.update(schemaVersion=23,profile='PERMITTED_CHOICE_VIEW_V23');c['options']['callbackParameters']=receipt('DISCARD_BATCH_REQUEST',dict(branch='POLICY',minimum=1,maximum=2,manaValue=None));ctx['policy']['receivedParameters']=dict(kind='DISCARD_BATCH_REQUEST',branch='POLICY',minimum=1,maximum=2,manaValue=None)
            with self.assertRaises(module.InvalidData):self.export(rows)

    def test_old_policy_request_keeps_its_own_timing_and_inventory(self):
        result,_=self.export(card_fixture());self.assertFalse(any('V23' in text for text in result['limitations']));self.assertNotIn('knownInputBoundaryInventoryExtensionV12',result['inputCoverageAudit']['entryLocationAudit'])
