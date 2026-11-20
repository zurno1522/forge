import json
import tempfile
import unittest
from pathlib import Path
from test_choice_events import choices_fixture
from test_export import module


def number_fixture(x=False):
    rows = choices_fixture()
    start = rows[2]['choice']
    start.update(choiceType='X_SELECTION' if x else 'NUMBER_SELECTION', boundary='MAX_X_HEURISTIC' if x else 'CONTROLLER_NUMBER_RANGE',
                 options=dict(coverage='HEURISTIC_BOUND_NOT_LEGAL_SET' if x else 'OBSERVED_RANGE', minimum=0, maximum=3))
    if x: start['options']['previousValue'] = None
    rows[3]['choice']['selection'] = dict(value=0, optionIndex=None, domainMatch='WITHIN_OBSERVED_DOMAIN')
    return rows


class NumberChoiceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def write(self, rows, name='input.jsonl'):
        p = self.root / name
        p.write_text(''.join(json.dumps(r) + '\n' for r in rows))
        return p

    def test_zero_x_unknown_alternatives_and_observed_ranges_are_kept_distinct(self):
        for x, unknown in ((True, False), (True, True), (False, False)):
            rows = number_fixture(x)
            if unknown:
                rows[2]['choice']['options'].update(coverage='OBSERVED_SELECTION_ONLY', minimum=None, maximum=None, previousValue=3)
                rows[3]['choice']['selection']['domainMatch'] = 'UNAVAILABLE'
            out = self.root / str(x) / str(unknown)
            summary = module.export([self.write(rows)], out)
            choice = json.loads((out / 'choices.jsonl').read_text())
            self.assertEqual(choice['terminal']['choice']['selection']['value'], 0)
            self.assertEqual(summary['standaloneChoiceTypes'], {'X_SELECTION' if x else 'NUMBER_SELECTION': 1})
            self.assertEqual(summary['standaloneNumericValueStates'], {'ZERO': 1})

    def test_discrete_outside_prefix_and_duplicate_values_never_create_an_index(self):
        for duplicate in (True, False):
            rows = number_fixture()
            rows[2]['choice'].update(boundary='CONTROLLER_NUMBER_OPTIONS', options=dict(coverage='OBSERVED_DISCRETE_OPTIONS',
                items=[1, 1] if duplicate else list(range(256)), truncated=not duplicate))
            rows[3]['choice']['selection'].update(value=1 if duplicate else 299, domainMatch='AMBIGUOUS_CAPTURED_OPTIONS' if duplicate else 'OUTSIDE_CAPTURED_OPTIONS')
            self.assertEqual(module.export([self.write(rows)])['standaloneChoiceRecords'], 1)
            rows[3]['choice']['selection']['optionIndex'] = 0
            with self.assertRaises(module.InvalidData): module.export([self.write(rows, 'bad.jsonl')])

    def test_invalid_numeric_shapes_and_evaluated_x_are_rejected_without_legality_inference(self):
        for mutation in ('bool-value', 'bool-bound', 'index', 'match', 'private', 'boundary', 'unknown-bound', 'candidate-x'):
            rows = number_fixture(True)
            if mutation == 'bool-value': rows[3]['choice']['selection']['value'] = True
            elif mutation == 'bool-bound': rows[2]['choice']['options']['maximum'] = True
            elif mutation == 'index': rows[3]['choice']['selection']['optionIndex'] = 0
            elif mutation == 'match': rows[3]['choice']['selection']['domainMatch'] = 'GUESSED'
            elif mutation == 'private': rows[2]['choice']['options']['prompt'] = 'secret'
            elif mutation == 'boundary': rows[2]['choice']['boundary'] = 'CONTROLLER_MODES'
            elif mutation == 'unknown-bound': rows[2]['choice']['options']['coverage'] = 'OBSERVED_SELECTION_ONLY'
            elif mutation == 'candidate-x':
                d = next(r['decision'] for r in rows if r['type'] == 'AI_DECISION')
                d['candidates'] = [dict(abilityId=7, sourceId=8, kind='SPELL', assessment='WillPlay', evaluatedX=True)]
            with self.subTest(mutation=mutation), self.assertRaises(module.InvalidData): module.export([self.write(rows, mutation + '.jsonl')])
        rows = number_fixture()
        rows[3]['choice']['selection'].update(value=4, domainMatch='OUTSIDE_OBSERVED_DOMAIN')
        self.assertEqual(module.export([self.write(rows, 'outside.jsonl')])['standaloneChoiceRecords'], 1)
