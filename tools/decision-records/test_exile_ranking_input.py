import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_discard_batch_input import batch_fixture
from test_card_payment_request import card_fixture,unavailable
from test_received_parameters import receipt


def exile_fixture(amount=1,effect=False):
    rows=batch_fixture();c=rows[2]['choice'];ctx=c['inputContext'];ctx.update(schemaVersion=25,profile='PERMITTED_CHOICE_VIEW_V25')
    c.update(boundary='PAYMENT_EXILE_RANKING',options=dict(coverage='EXILE_RANKING_NOT_ALL_LEGAL_PLANS',cards=c['options']['cards'],callbackParameters=receipt('EXILE_RANKING_REQUEST',dict(requestedSelections=amount,effect=effect))))
    ctx['policy']['receivedParameters']=dict(kind='EXILE_RANKING_REQUEST',requestedSelections=amount,effect=effect);return rows


def legacy_application():
    rows=card_fixture();c=rows[2]['choice'];c['boundary']='PAYMENT_EXILE_POOL';c.pop('inputContext');c.pop('entryLocation');c['options'].pop('callbackParameters');return rows


class ExileRankingInputTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.index=0

    def export(self,rows):
        self.index+=1;p=self.root/f'{self.index}.jsonl';out=self.root/f'out-{self.index}';p.write_text(''.join(json.dumps(r)+'\n' for r in rows));result=module.export([p],out);self.assertEqual(result,module.export([p]));return result,out

    def test_own_pre_ranking_entry_has_scalar_request_and_no_legal_domain_or_readiness_claim(self):
        result,out=self.export(exile_fixture());self.assertFalse(json.loads((out/'choices.jsonl').read_text())['trainingReady']);audit=result['inputCoverageAudit'];self.assertEqual(audit['entryLocationAudit']['knownInputBoundaryInventoryExtensionV14'],{'CHOICE':{'PAYMENT_EXILE_RANKING':'BEFORE_OPERATION'}});self.assertNotIn('PAYMENT_EXILE_RANKING',audit['knownInputBoundaryInventoryV1']);self.assertTrue(any('V25' in t and 'legacy PAYMENT_EXILE_POOL' in t for t in result['limitations']));self.assertTrue(any('V24 captures' in t for t in result['limitations']));self.assertFalse(any('Private history/domain permission is unchanged' in t for t in result['limitations']))

    def test_signed_native_counts_and_effect_flag_are_not_invented_legal_bounds(self):
        for amount in (-(1<<31),-1,0,1,(1<<31)-1):
            for effect in (False,True):self.export(exile_fixture(amount,effect))

    def test_decline_error_and_planning_are_operation_scoped(self):
        rows=exile_fixture();c=rows[2]['choice'];c['inputContext']['stateBasis']=c['entryLocation']['stateBasis']='PLANNING_WORKING_STATE'
        for status in ('DECLINED','ERROR'):
            rows[3]['choice'].update(status=status,selection=None);self.export(rows)

    def test_profile_entry_family_shape_and_scalar_agreement_are_strict(self):
        for error in ('profile','entry','selector','no-context','coverage','branch','missing','extra','boolean-count','big-count','effect-int','mismatch','family','type','binding','paid','no-receipt'):
            rows=exile_fixture();c=rows[2]['choice'];ctx=c['inputContext'];raw=c['options']['callbackParameters'];v=raw['values'];f=ctx['policy']['receivedParameters']
            if error=='profile':ctx.update(schemaVersion=24,profile='PERMITTED_CHOICE_VIEW_V24')
            if error=='entry':c.pop('entryLocation')
            if error=='selector':ctx['timing']=c['entryLocation']['timing']='BEFORE_SELECTOR';c['entryLocation']['kind']='SELECTOR'
            if error=='no-context':c.pop('inputContext')
            if error=='coverage':c['options']['coverage']='OBSERVED_CARD_OPTIONS_NOT_ALL_LEGAL_PAYMENT_PLANS'
            if error=='branch':c['options']['branch']='CRAFT'
            if error=='missing':v.pop('effect')
            if error=='extra':f['craft']=True
            if error=='boolean-count':v['requestedSelections']=f['requestedSelections']=True
            if error=='big-count':v['requestedSelections']=f['requestedSelections']=1<<31
            if error=='effect-int':v['effect']=f['effect']=1
            if error=='mismatch':f['effect']=True
            if error=='family':raw.update(kind='CARD_PAYMENT_REQUEST',values=dict(paymentKind='EXILE',minimum=1,maximum=1));f.clear();f.update(kind='CARD_PAYMENT_REQUEST',paymentKind='EXILE',minimum=1,maximum=1)
            if error=='type':c['choiceType']='PAYMENT_CARD_SELECTION'
            if error=='binding':ctx['diagnostics']['optionBindings']=dict(coverage='DIAGNOSTIC_PUBLIC_CURRENT_V1',items=[dict(optionIndex=0,entityRef='c0')],truncated=False)
            if error=='paid':rows[3]['choice']['selection']['paid']=True
            if error=='no-receipt':c['options'].pop('callbackParameters')
            with self.subTest(error=error),self.assertRaises(module.InvalidData):self.export(rows)

    def test_unavailable_view_still_requires_a_valid_received_request(self):
        rows=exile_fixture();c=rows[2]['choice'];unavailable(c['inputContext']);self.export(rows);c['options']['callbackParameters']['values']['effect']='CRAFT'
        with self.assertRaises(module.InvalidData):self.export(rows)

    def test_existing_application_and_other_families_cannot_be_upgraded_to_the_new_input(self):
        rows=legacy_application();result,_=self.export(rows);self.assertFalse(any('V25' in t for t in result['limitations']));self.assertNotIn('entryLocationAudit',result['inputCoverageAudit'])
        for rows in (card_fixture(),batch_fixture(),legacy_application()):
            template=exile_fixture()[2]['choice'];c=rows[2]['choice'];c['inputContext']=copy.deepcopy(template['inputContext']);c['entryLocation']=copy.deepcopy(template['entryLocation']);c['options']['callbackParameters']=copy.deepcopy(template['options']['callbackParameters'])
            with self.assertRaises(module.InvalidData):self.export(rows)
