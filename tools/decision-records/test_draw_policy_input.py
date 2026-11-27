import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_max_x_calculation_input import calculation_fixture
from test_card_payment_request import unavailable,card_fixture
from test_number_choices import number_fixture
from test_trigger_preparation_input import trigger_fixture
from test_received_parameters import receipt


def draw_fixture(mandatory=False,result=True):
    rows=calculation_fixture();c=rows[2]['choice'];ctx=c['inputContext'];ctx.update(schemaVersion=27,profile='PERMITTED_CHOICE_VIEW_V27');c.update(choiceType='DRAW_POLICY_RESULT',boundary='DRAW_AMOUNT_TARGET_POLICY',options=dict(coverage='DRAW_POLICY_NOT_LEGAL_DOMAIN',callbackParameters=receipt('DRAW_POLICY_REQUEST',dict(mandatory=mandatory))))
    ctx['policy']['receivedParameters']=dict(kind='DRAW_POLICY_REQUEST',mandatory=mandatory);rows[3]['choice']['selection']=dict(result=result);return rows


class DrawPolicyInputTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.index=0

    def export(self,rows):
        self.index+=1;p=self.root/f'{self.index}.jsonl';out=self.root/f'out-{self.index}';p.write_text(''.join(json.dumps(r)+'\n' for r in rows));result=module.export([p],out);self.assertEqual(result,module.export([p]));return result,out

    def test_helper_operation_has_own_entry_and_no_invented_numeric_or_target_domain(self):
        result,out=self.export(draw_fixture());self.assertFalse(json.loads((out/'choices.jsonl').read_text())['trainingReady']);audit=result['inputCoverageAudit'];self.assertEqual(audit['entryLocationAudit']['knownInputBoundaryInventoryExtensionV16'],{'CHOICE':{'DRAW_AMOUNT_TARGET_POLICY':'BEFORE_OPERATION'}});self.assertNotIn('DRAW_AMOUNT_TARGET_POLICY',audit['knownInputBoundaryInventoryV1']);self.assertTrue(any('V27' in t and 'including fixed draws' in t for t in result['limitations']));self.assertTrue(any('V26 captures maximum-X' in t for t in result['limitations']))

    def test_false_and_true_native_returns_are_completed_results_for_both_received_flags(self):
        for mandatory in (False,True):
            for returned in (False,True):
                rows=draw_fixture(mandatory,returned);result,out=self.export(rows);choice=json.loads((out/'choices.jsonl').read_text());self.assertEqual(choice['status'],'COMPLETED');self.assertEqual(choice['terminal']['choice']['selection'],dict(result=returned))

    def test_planning_and_original_error_keep_the_operation_lifecycle(self):
        rows=draw_fixture();c=rows[2]['choice'];c['inputContext']['stateBasis']=c['entryLocation']['stateBasis']='PLANNING_WORKING_STATE';self.export(rows);rows[3]['choice'].update(status='ERROR',selection=None);self.export(rows)

    def test_profile_entry_scalar_family_boolean_and_no_domain_shapes_are_strict(self):
        for error in ('profile','entry','selector','no-context','coverage','bound','targets','previous','extra','missing','flag-int','mismatch','trigger-kind','type','value','result-int','numeric-return','paid','can-play','no-receipt'):
            rows=draw_fixture();c=rows[2]['choice'];ctx=c['inputContext'];raw=c['options']['callbackParameters'];f=ctx['policy']['receivedParameters'];selected=rows[3]['choice']['selection']
            if error=='profile':ctx.update(schemaVersion=26,profile='PERMITTED_CHOICE_VIEW_V26')
            if error=='entry':c.pop('entryLocation')
            if error=='selector':ctx['timing']=c['entryLocation']['timing']='BEFORE_SELECTOR';c['entryLocation']['kind']='SELECTOR'
            if error=='no-context':c.pop('inputContext')
            if error=='coverage':c['options']['coverage']='HEURISTIC_BOUND_NOT_LEGAL_SET'
            if error=='bound':c['options']['maximum']=3
            if error=='targets':c['options']['players']=[]
            if error=='previous':c['options']['previousValue']=2
            if error=='extra':f['effect']=False
            if error=='missing':raw['values'].pop('mandatory')
            if error=='flag-int':raw['values']['mandatory']=f['mandatory']=1
            if error=='mismatch':f['mandatory']=True
            if error=='trigger-kind':raw['kind']=f['kind']='TRIGGER_REQUIREMENT'
            if error=='type':c['choiceType']='X_SELECTION'
            if error=='value':selected['value']=3
            if error=='result-int':selected['result']=1
            if error=='numeric-return':rows[3]['choice']['selection']=dict(value=1,optionIndex=None,domainMatch='UNAVAILABLE')
            if error=='paid':selected['paid']=True
            if error=='can-play':selected['canPlayAI']=True
            if error=='no-receipt':c['options'].pop('callbackParameters')
            with self.subTest(error=error),self.assertRaises(module.InvalidData):self.export(rows)

    def test_unavailable_current_view_still_requires_valid_native_arguments_and_result(self):
        rows=draw_fixture();c=rows[2]['choice'];unavailable(c['inputContext']);self.export(rows);c['options']['callbackParameters']['values']['mandatory']='MANDATORY'
        with self.assertRaises(module.InvalidData):self.export(rows)

    def test_old_refinements_and_other_families_do_not_inherit_draw_helper_permission(self):
        rows=number_fixture(True);rows[2]['choice'].update(boundary='X_ANNOUNCEMENT_REFINEMENT',options=dict(coverage='OBSERVED_SELECTION_ONLY',minimum=None,maximum=None,previousValue=3));rows[3]['choice']['selection']['domainMatch']='UNAVAILABLE';result,_=self.export(rows);self.assertFalse(any('V27' in t for t in result['limitations']));self.assertNotIn('entryLocationAudit',result['inputCoverageAudit'])
        for rows in (rows,calculation_fixture(),card_fixture(),trigger_fixture()):
            c=rows[2]['choice'];template=draw_fixture()[2]['choice'];c['inputContext']=copy.deepcopy(template['inputContext']);c['entryLocation']=copy.deepcopy(template['entryLocation']);c['options']['callbackParameters']=copy.deepcopy(template['options']['callbackParameters'])
            with self.assertRaises(module.InvalidData):self.export(rows)
