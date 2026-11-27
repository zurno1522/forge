import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_exile_ranking_input import exile_fixture
from test_card_payment_request import unavailable,card_fixture
from test_number_choices import number_fixture
from test_received_parameters import receipt


def calculation_fixture(effect=False,value=2):
    rows=exile_fixture();c=rows[2]['choice'];ctx=c['inputContext'];ctx.update(schemaVersion=26,profile='PERMITTED_CHOICE_VIEW_V26');c.update(choiceType='X_SELECTION',boundary='MAX_X_CALCULATION',options=dict(coverage='COMPUTATION_NOT_LEGAL_DOMAIN',callbackParameters=receipt('MAX_X_CALCULATION_REQUEST',dict(effect=effect))))
    ctx['policy']['receivedParameters']=dict(kind='MAX_X_CALCULATION_REQUEST',effect=effect);rows[3]['choice']['selection']=dict(value=value,optionIndex=None,domainMatch='UNAVAILABLE');return rows


class MaxXCalculationInputTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.index=0

    def export(self,rows):
        self.index+=1;p=self.root/f'{self.index}.jsonl';out=self.root/f'out-{self.index}';p.write_text(''.join(json.dumps(r)+'\n' for r in rows));result=module.export([p],out);self.assertEqual(result,module.export([p]));return result,out

    def test_own_calculation_entry_does_not_invent_an_entry_time_domain(self):
        result,out=self.export(calculation_fixture());self.assertFalse(json.loads((out/'choices.jsonl').read_text())['trainingReady']);audit=result['inputCoverageAudit'];self.assertEqual(audit['entryLocationAudit']['knownInputBoundaryInventoryExtensionV15'],{'CHOICE':{'MAX_X_CALCULATION':'BEFORE_OPERATION'}});self.assertNotIn('MAX_X_CALCULATION',audit['knownInputBoundaryInventoryV1']);self.assertTrue(any('V26' in t and 'Guarded/cached returns' in t for t in result['limitations']));self.assertTrue(any('V25 captures exile' in t for t in result['limitations']))

    def test_native_signed_and_zero_returns_keep_no_domain_or_paid_claim(self):
        for value in (-(1<<31),-2,0,2,(1<<31)-1):
            for effect in (False,True):self.export(calculation_fixture(effect,value))

    def test_planning_and_original_error_are_operation_scoped(self):
        rows=calculation_fixture();c=rows[2]['choice'];c['inputContext']['stateBasis']=c['entryLocation']['stateBasis']='PLANNING_WORKING_STATE';self.export(rows);rows[3]['choice'].update(status='ERROR',selection=None);self.export(rows)

    def test_profile_entry_family_scalar_result_and_no_domain_contracts_are_strict(self):
        for error in ('profile','entry','selector','no-context','coverage','bound','previous','extra','missing','bool-flag','mismatch','family','type','index','domain','big-return','boolean-return','paid','no-receipt'):
            rows=calculation_fixture();c=rows[2]['choice'];ctx=c['inputContext'];raw=c['options']['callbackParameters'];f=ctx['policy']['receivedParameters'];selected=rows[3]['choice']['selection']
            if error=='profile':ctx.update(schemaVersion=25,profile='PERMITTED_CHOICE_VIEW_V25')
            if error=='entry':c.pop('entryLocation')
            if error=='selector':ctx['timing']=c['entryLocation']['timing']='BEFORE_SELECTOR';c['entryLocation']['kind']='SELECTOR'
            if error=='no-context':c.pop('inputContext')
            if error=='coverage':c['options']['coverage']='HEURISTIC_BOUND_NOT_LEGAL_SET'
            if error=='bound':c['options']['maximum']=3
            if error=='previous':c['options']['previousValue']=1
            if error=='extra':f['requestedMaximum']=3
            if error=='missing':raw['values'].pop('effect')
            if error=='bool-flag':raw['values']['effect']=f['effect']=1
            if error=='mismatch':f['effect']=True
            if error=='family':raw.update(kind='EXILE_RANKING_REQUEST',values=dict(requestedSelections=1,effect=False));f.clear();f.update(kind='EXILE_RANKING_REQUEST',requestedSelections=1,effect=False)
            if error=='type':c['choiceType']='NUMBER_SELECTION'
            if error=='index':selected['optionIndex']=0
            if error=='domain':selected['domainMatch']='WITHIN_OBSERVED_DOMAIN'
            if error=='big-return':selected['value']=1<<31
            if error=='boolean-return':selected['value']=False
            if error=='paid':selected['paid']=True
            if error=='no-receipt':c['options'].pop('callbackParameters')
            with self.subTest(error=error),self.assertRaises(module.InvalidData):self.export(rows)

    def test_unavailable_current_views_do_not_skip_received_scalar_checks(self):
        rows=calculation_fixture();c=rows[2]['choice'];unavailable(c['inputContext']);self.export(rows);c['options']['callbackParameters']['values']['effect']='ACTUAL'
        with self.assertRaises(module.InvalidData):self.export(rows)

    def test_old_assignments_and_other_families_retain_their_own_permission_and_timing(self):
        rows=number_fixture(True);result,_=self.export(rows);self.assertFalse(any('V26' in t for t in result['limitations']));self.assertNotIn('entryLocationAudit',result['inputCoverageAudit'])
        for rows in (number_fixture(True),number_fixture(),exile_fixture(),card_fixture()):
            c=rows[2]['choice'];template=calculation_fixture()[2]['choice'];c['inputContext']=copy.deepcopy(template['inputContext']);c['entryLocation']=copy.deepcopy(template['entryLocation']);c['options']['callbackParameters']=copy.deepcopy(template['options']['callbackParameters'])
            with self.assertRaises(module.InvalidData):self.export(rows)
        rows=number_fixture(True);rows[2]['choice'].update(boundary='X_ANNOUNCEMENT_REFINEMENT',options=dict(coverage='OBSERVED_SELECTION_ONLY',minimum=None,maximum=None,previousValue=3));rows[3]['choice']['selection']['domainMatch']='UNAVAILABLE';self.export(rows)
