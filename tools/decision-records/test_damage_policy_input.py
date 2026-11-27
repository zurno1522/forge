import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_draw_policy_input import draw_fixture
from test_card_payment_request import unavailable,card_fixture
from test_max_x_calculation_input import calculation_fixture
from test_number_choices import number_fixture
from test_received_parameters import receipt


def damage_fixture(operation='CAN_PLAY',mandatory=None):
    rows=draw_fixture();c=rows[2]['choice'];ctx=c['inputContext'];ctx.update(schemaVersion=28,profile='PERMITTED_CHOICE_VIEW_V28');mandatory=(False if mandatory is None else mandatory) if operation=='TRIGGER' else None
    c.update(choiceType='DAMAGE_POLICY_RESULT',boundary='DAMAGE_'+operation+'_POLICY',options=dict(coverage='DAMAGE_POLICY_NOT_LEGAL_DOMAIN',operation=operation,callbackParameters=receipt('DAMAGE_POLICY_REQUEST',dict(operation=operation,mandatory=mandatory))))
    ctx['policy']['receivedParameters']=dict(kind='DAMAGE_POLICY_REQUEST',operation=operation,mandatory=mandatory);rows[3]['choice']['selection']=dict(result=False) if operation=='LIFE_DRAIN' else dict(rating=100,decision='WillPlay');return rows


class DamagePolicyInputTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.index=0

    def export(self,rows):
        self.index+=1;p=self.root/f'{self.index}.jsonl';out=self.root/f'out-{self.index}';p.write_text(''.join(json.dumps(r)+'\n' for r in rows));result=module.export([p],out);self.assertEqual(result,module.export([p]));return result,out

    def test_native_operations_have_their_own_entries_and_branch_specific_return_shapes(self):
        for operation in ('CAN_PLAY','TRIGGER','LIFE_DRAIN'):
            result,out=self.export(damage_fixture(operation));self.assertFalse(json.loads((out/'choices.jsonl').read_text())['trainingReady']);audit=result['inputCoverageAudit'];self.assertEqual(audit['entryLocationAudit']['knownInputBoundaryInventoryExtensionV17'],{'CHOICE':{'DAMAGE_'+operation+'_POLICY':'BEFORE_OPERATION'}});self.assertNotIn('DAMAGE_'+operation+'_POLICY',audit['knownInputBoundaryInventoryV1']);self.assertTrue(any('V28' in t and 'Native rating/reason' in t for t in result['limitations']));self.assertTrue(any('V27 captures the native Draw' in t for t in result['limitations']))

    def test_received_trigger_flags_and_native_rejected_or_false_returns_are_not_legal_domain_claims(self):
        for mandatory in (False,True):
            for rating,decision in ((100,'WillPlay'),(0,'TargetingFailed'),(-3,'CantPlayAi')):
                rows=damage_fixture('TRIGGER',mandatory);rows[3]['choice']['selection']=dict(rating=rating,decision=decision);_,out=self.export(rows);row=json.loads((out/'choices.jsonl').read_text());self.assertEqual(row['status'],'COMPLETED');self.assertEqual(row['terminal']['choice']['selection'],dict(rating=rating,decision=decision))
        for result in (False,True):
            rows=damage_fixture('LIFE_DRAIN');rows[3]['choice']['selection']['result']=result;self.export(rows)

    def test_planning_and_original_errors_preserve_each_operation_lifecycle(self):
        for operation in ('CAN_PLAY','TRIGGER','LIFE_DRAIN'):
            rows=damage_fixture(operation);c=rows[2]['choice'];c['inputContext']['stateBasis']=c['entryLocation']['stateBasis']='PLANNING_WORKING_STATE';self.export(rows);rows[3]['choice'].update(status='ERROR',selection=None);self.export(rows)

    def test_profile_entry_operation_flag_family_and_result_permissions_are_strict(self):
        for error in ('profile','entry','selector','no-context','coverage','operation','raw-operation','unknown','null-trigger','int-trigger','invented-flag','missing','extra','family','type','boolean-rating','big-rating','unknown-decision','wrong-return','result-int','domain','targets','paid','no-receipt'):
            rows=damage_fixture('LIFE_DRAIN' if error in ('result-int',) else 'TRIGGER' if error in ('null-trigger','int-trigger') else 'CAN_PLAY');c=rows[2]['choice'];ctx=c['inputContext'];raw=c['options']['callbackParameters'];f=ctx['policy']['receivedParameters'];selected=rows[3]['choice']['selection']
            if error=='profile':ctx.update(schemaVersion=27,profile='PERMITTED_CHOICE_VIEW_V27')
            if error=='entry':c.pop('entryLocation')
            if error=='selector':ctx['timing']=c['entryLocation']['timing']='BEFORE_SELECTOR';c['entryLocation']['kind']='SELECTOR'
            if error=='no-context':c.pop('inputContext')
            if error=='coverage':c['options']['coverage']='HEURISTIC_BOUND_NOT_LEGAL_SET'
            if error=='operation':c['options']['operation']='TRIGGER'
            if error=='raw-operation':raw['values']['operation']=f['operation']='LIFE_DRAIN'
            if error=='unknown':c['options']['operation']=raw['values']['operation']=f['operation']='ARBITRARY_LOGIC'
            if error=='null-trigger':raw['values']['mandatory']=f['mandatory']=None
            if error=='int-trigger':raw['values']['mandatory']=f['mandatory']=1
            if error=='invented-flag':raw['values']['mandatory']=f['mandatory']=False
            if error=='missing':raw['values'].pop('mandatory')
            if error=='extra':f['damage']=5
            if error=='family':raw.update(kind='DRAW_POLICY_REQUEST',values=dict(mandatory=False));f.clear();f.update(kind='DRAW_POLICY_REQUEST',mandatory=False)
            if error=='type':c['choiceType']='DRAW_POLICY_RESULT'
            if error=='boolean-rating':selected['rating']=True
            if error=='big-rating':selected['rating']=1<<31
            if error=='unknown-decision':selected['decision']='AlwaysLegal'
            if error=='wrong-return':rows[3]['choice']['selection']=dict(result=True)
            if error=='result-int':selected['result']=1
            if error=='domain':c['options']['maximum']=5
            if error=='targets':selected['targets']=[]
            if error=='paid':selected['paid']=True
            if error=='no-receipt':c['options'].pop('callbackParameters')
            with self.subTest(error=error),self.assertRaises(module.InvalidData):self.export(rows)

    def test_unavailable_view_does_not_skip_raw_operation_and_flag_agreement(self):
        rows=damage_fixture();c=rows[2]['choice'];unavailable(c['inputContext']);self.export(rows);c['options']['callbackParameters']['values']['operation']='LIFE_DRAIN'
        with self.assertRaises(module.InvalidData):self.export(rows)

    def test_old_refinement_and_other_families_cannot_inherit_damage_scope_or_decision_vocabulary(self):
        rows=number_fixture(True);rows[2]['choice'].update(boundary='X_ANNOUNCEMENT_REFINEMENT',options=dict(coverage='OBSERVED_SELECTION_ONLY',minimum=None,maximum=None,previousValue=3));rows[3]['choice']['selection']['domainMatch']='UNAVAILABLE';result,_=self.export(rows);self.assertFalse(any('V28' in t for t in result['limitations']));self.assertNotIn('entryLocationAudit',result['inputCoverageAudit'])
        for rows in (rows,draw_fixture(),calculation_fixture(),card_fixture()):
            c=rows[2]['choice'];template=damage_fixture()[2]['choice'];c['inputContext']=copy.deepcopy(template['inputContext']);c['entryLocation']=copy.deepcopy(template['entryLocation']);c['options']['callbackParameters']=copy.deepcopy(template['options']['callbackParameters'])
            with self.assertRaises(module.InvalidData):self.export(rows)
