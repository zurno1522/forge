import copy
import json
import tempfile
import unittest
from pathlib import Path
from test_choice_events import choices_fixture
from test_export import module


def payment_fixture():
    rows = choices_fixture()
    rows[2]['choice'].update(choiceType='PAYMENT_DECISION', boundary='COST_VISITOR', stage='PAYMENT',
        options=dict(coverage='PAYMENT_VISITOR_INPUT_NOT_ALTERNATIVES', costKind='CostSacrifice', effect=False))
    rows[3]['choice']['selection'] = dict(decisionCount=0, cards=dict(items=[dict(type='CARD', id=51, gameTimestamp=22)], truncated=False),
        mana=None, players=None, abilities=None, colorMask=None, unrecordedDetails=[])
    return rows


class PaymentChoiceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def export(self, rows, name='input'):
        source = self.root / (name + '.jsonl')
        source.write_text(''.join(json.dumps(r) + '\n' for r in rows))
        out = self.root / name
        summary = module.export([source], out)
        return summary, [json.loads(s) for s in (out / 'choices.jsonl').read_text().splitlines()]

    def test_selected_cards_are_not_conflated_with_decision_count_or_actual_payment(self):
        summary, choices = self.export(payment_fixture())
        value = choices[0]['terminal']['choice']['selection']
        self.assertEqual(value['decisionCount'], 0)
        self.assertEqual(len(value['cards']['items']), 1)
        self.assertNotIn('paid', value)
        self.assertEqual(summary['standalonePaymentCostKinds'], {'CostSacrifice': 1})
        rows = payment_fixture()
        rows[3]['choice'].update(status='DECLINED', selection=None)
        _, choices = self.export(rows, 'declined')
        self.assertEqual(choices[0]['status'], 'DECLINED')

    def test_nested_card_pool_and_mana_routine_have_separate_typed_results(self):
        for kind in ('PAYMENT_CARD_SELECTION', 'PAYMENT_MANA', 'PAYMENT_MANA_SELECTION'):
            rows = payment_fixture()
            start, terminal = copy.deepcopy(rows[2]), copy.deepcopy(rows[3])
            start['choice'].update(choiceId='nested', parentChoiceId=rows[2]['choice']['choiceId'], choiceType=kind)
            terminal['choice']['choiceId'] = 'nested'
            if kind == 'PAYMENT_CARD_SELECTION':
                start['choice'].update(boundary='PAYMENT_SACRIFICE_POOL', options=dict(coverage='OBSERVED_CARD_OPTIONS_NOT_ALL_LEGAL_PAYMENT_PLANS',
                    cards=copy.deepcopy(terminal['choice']['selection']['cards']), minimum=1, maximum=1))
                terminal['choice']['selection'] = copy.deepcopy(start['choice']['options']['cards'])
            elif kind == 'PAYMENT_MANA':
                start['choice'].update(boundary='AUTO_MANA_PAYMENT', options=dict(coverage='OBSERVED_REQUIREMENT_NOT_ALTERNATIVE_PLANS', remainingManaValue=2, effect=False))
                terminal['choice']['selection'] = dict(paymentRoutineReturned=True, costSatisfied=False, manaAbilities=dict(items=[], truncated=False))
            else:
                mana = dict(source=dict(type='CARD', id=4, gameTimestamp=22), player=dict(type='PLAYER', id=1), color=8, snow=False)
                start['choice'].update(boundary='MANA_POOL_OPTIONS', options=dict(coverage='OBSERVED_MANA_OPTIONS', mana=dict(items=[mana], truncated=False)))
                terminal['choice']['selection'] = mana
            rows[3:3] = [start, terminal]
            for i, row in enumerate(rows[1:-1], 1): row['eventIndex'] = i
            summary, choices = self.export(rows, kind)
            self.assertEqual(summary['standaloneChoiceRecords'], 2)
            self.assertEqual(choices[1]['start']['choice']['parentChoiceId'], choices[0]['choiceId'])

    def test_private_or_untyped_payment_data_is_rejected(self):
        for mutation in ('prompt', 'count', 'color', 'kind', 'identity', 'unavailable', 'paid'):
            rows = payment_fixture()
            start, result = rows[2]['choice'], rows[3]['choice']['selection']
            if mutation == 'prompt': start['options']['prompt'] = 'secret'
            elif mutation == 'count': result['decisionCount'] = True
            elif mutation == 'color': result['colorMask'] = 32
            elif mutation == 'kind': start['options']['costKind'] = 'UnknownScriptCost'
            elif mutation == 'identity': result['cards']['items'][0]['type'] = 'PLAYER'
            elif mutation == 'unavailable': result['unrecordedDetails'] = ['secret type text']
            elif mutation == 'paid': result['paid'] = True
            with self.subTest(mutation=mutation), self.assertRaises(module.InvalidData): self.export(rows, mutation)

    def test_truncated_results_and_unsupported_details_remain_explicit(self):
        rows = payment_fixture()
        result = rows[3]['choice']['selection']
        result['cards']['truncated'] = True
        result['unrecordedDetails'] = ['TYPE_TEXT', 'COUNTER_TABLE']
        summary, choices = self.export(rows)
        self.assertEqual(summary['standalonePaymentUnrecordedDetails'], {'TYPE_TEXT': 1, 'COUNTER_TABLE': 1})
        self.assertTrue(choices[0]['terminal']['choice']['selection']['cards']['truncated'])
