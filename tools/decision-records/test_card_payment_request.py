import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_mana_source_input import source_fixture
from test_received_parameters import receipt, card_ref, parameter_fixture


def card_fixture(kind='DISCARD',minimum=1,maximum=2):
    rows=source_fixture();c=rows[2]['choice'];ctx=c['inputContext'];ctx.update(schemaVersion=21,profile='PERMITTED_CHOICE_VIEW_V21')
    values=dict(paymentKind=kind,minimum=minimum,maximum=maximum)
    c.update(choiceType='PAYMENT_CARD_SELECTION',boundary='PAYMENT_DISCARD_POOL' if kind=='DISCARD' else 'PAYMENT_SACRIFICE_POOL',options=dict(coverage='OBSERVED_CARD_OPTIONS_NOT_ALL_LEGAL_PAYMENT_PLANS',cards=dict(items=[card_ref()],truncated=False),minimum=minimum,maximum=maximum,callbackParameters=receipt('CARD_PAYMENT_REQUEST',values)))
    ctx['policy']['receivedParameters']=dict(kind='CARD_PAYMENT_REQUEST',**values);rows[3]['choice']['selection']=dict(items=[card_ref()],truncated=False);return rows


def unavailable(ctx):
    ctx.update(status='UNAVAILABLE',policy=None,diagnostics=dict(playerBindings=[],cardBindings=[],stackBindings=[],optionBindings=dict(coverage='UNAVAILABLE',items=[],truncated=True)),omissions=[dict(component='CONTEXT',reason='BUDGET_EXHAUSTED')])
    ctx['coverage'].update(playerView='UNAVAILABLE',stack='UNAVAILABLE',disclosures='UNAVAILABLE',prompt='UNAVAILABLE',receivedParameters='UNAVAILABLE')


class CardPaymentRequestTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.index=0

    def export(self,rows):
        self.index+=1;path=self.root/f'{self.index}.jsonl';out=self.root/f'out-{self.index}';path.write_text(''.join(json.dumps(r)+'\n' for r in rows))
        result=module.export([path],out);self.assertEqual(result,module.export([path]));return result,out

    def test_actual_request_values_include_reversed_negative_and_extreme_direct_calls(self):
        for kind in ('DISCARD','SACRIFICE'):
            for minimum,maximum in ((0,0),(-1,2),(3,1),(-(1<<31),(1<<31)-1)):
                result,out=self.export(card_fixture(kind,minimum,maximum));self.assertFalse(json.loads((out/'choices.jsonl').read_text())['trainingReady'])
                self.assertTrue(any('V21' in text and 'request values' in text for text in result['limitations']))

    def test_planning_and_error_keep_the_original_outer_policy_semantics(self):
        rows=card_fixture();c=rows[2]['choice'];c['inputContext']['stateBasis']=c['entryLocation']['stateBasis']='PLANNING_WORKING_STATE';self.export(rows)
        rows[3]['choice'].update(status='ERROR',selection=None);self.export(rows)

    def test_debug_request_and_old_option_bounds_must_agree_even_when_view_is_unavailable(self):
        rows=card_fixture();c=rows[2]['choice'];unavailable(c['inputContext']);self.export(rows)
        c['options']['callbackParameters']['values']['minimum']=0
        with self.assertRaises(module.InvalidData):self.export(rows)
        rows=card_fixture();c=rows[2]['choice'];unavailable(c['inputContext']);c['options']['callbackParameters']['values']['paymentKind']='SACRIFICE'
        with self.assertRaises(module.InvalidData):self.export(rows)

    def test_family_profile_scalar_options_and_entry_agreement_are_strict(self):
        for error in ('profile','no-entry','no-receipt','wrong-kind','bad-kind','bool-min','huge-max','float-min','feature-mismatch','option-mismatch','raw-extra','feature-extra','domain-injection','wrong-timing'):
            rows=card_fixture();c=rows[2]['choice'];ctx=c['inputContext'];raw=c['options']['callbackParameters'];features=ctx['policy']['receivedParameters']
            if error=='profile':ctx.update(schemaVersion=20,profile='PERMITTED_CHOICE_VIEW_V20')
            if error=='no-entry':c.pop('entryLocation')
            if error=='no-receipt':c['options'].pop('callbackParameters')
            if error=='wrong-kind':raw['values']['paymentKind']=features['paymentKind']='SACRIFICE'
            if error=='bad-kind':raw['values']['paymentKind']=features['paymentKind']='EXILE'
            if error=='bool-min':raw['values']['minimum']=features['minimum']=True
            if error=='huge-max':raw['values']['maximum']=features['maximum']=1<<31
            if error=='float-min':raw['values']['minimum']=features['minimum']=1.0
            if error=='feature-mismatch':features['maximum']=1
            if error=='option-mismatch':c['options']['minimum']=0
            if error=='raw-extra':raw['values']['optional']=False
            if error=='feature-extra':features['selectedCard']='c0'
            if error=='domain-injection':ctx['diagnostics']['optionBindings']=dict(coverage='DIAGNOSTIC_PUBLIC_CURRENT_V1',items=[dict(optionIndex=0,entityRef='c0')],truncated=False)
            if error=='wrong-timing':ctx['timing']=c['entryLocation']['timing']='BEFORE_OPERATION';c['entryLocation']['kind']='OPERATION'
            with self.subTest(error=error),self.assertRaises(module.InvalidData):self.export(rows)

    def test_numeric_entity_mana_source_and_exile_cannot_claim_this_permission(self):
        for rows in (parameter_fixture('NONE',{}),parameter_fixture('NONE',{},'ENTITY'),source_fixture()):
            c=rows[2]['choice'];ctx=c['inputContext'];ctx.update(schemaVersion=21,profile='PERMITTED_CHOICE_VIEW_V21');c['options']['callbackParameters']=receipt('CARD_PAYMENT_REQUEST',dict(paymentKind='DISCARD',minimum=1,maximum=1));ctx['policy']['receivedParameters']=dict(kind='CARD_PAYMENT_REQUEST',paymentKind='DISCARD',minimum=1,maximum=1)
            with self.assertRaises(module.InvalidData):self.export(rows)
        rows=card_fixture();rows[2]['choice']['boundary']='PAYMENT_EXILE_POOL'
        with self.assertRaises(module.InvalidData):self.export(rows)

    def test_old_card_policy_remains_absent_without_new_profile_or_feature_inference(self):
        rows=card_fixture();c=rows[2]['choice'];ctx=c['inputContext'];ctx.update(schemaVersion=20,profile='PERMITTED_CHOICE_VIEW_V20');ctx['coverage']['receivedParameters']='UNSUPPORTED';ctx['policy']['receivedParameters']=None;c['options'].pop('callbackParameters')
        result,_=self.export(rows);self.assertFalse(any('V21' in text for text in result['limitations']))
