import copy
import json
import tempfile
import unittest
from pathlib import Path
from test_choice_events import choices_fixture
from test_export import module


def mode_fixture():
    rows = choices_fixture()
    start = rows[2]['choice']; end = rows[3]['choice']
    start.update(choiceType='MODE_SELECTION', boundary='CONTROLLER_MODES',
                 options=dict(coverage='OBSERVED_MODE_OPTIONS', items=[dict(abilityId=8, optionIndex=0, definitionIndex=1), dict(abilityId=9, optionIndex=1, definitionIndex=2)],
                              truncated=False, min=0, max=3, allowRepeat=True))
    end['selection'] = dict(available=True, modes=[dict(abilityId=8, optionIndex=0, definitionIndex=1)] * 2, selectedCount=2, truncated=False, accepted=None)
    return rows


class ModeChoiceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def write(self, rows, name='input.jsonl'):
        p = self.root / name
        p.write_text(''.join(json.dumps(r) + '\n' for r in rows))
        return p

    def test_repeated_and_unmapped_modes_are_retained_as_returned(self):
        for mapped in (True, False):
            rows = mode_fixture()
            if not mapped:
                rows[3]['choice']['selection']['modes'] = [dict(abilityId=99, optionIndex=None, definitionIndex=None)]
                rows[3]['choice']['selection']['selectedCount'] = 1
            out = self.root / str(mapped)
            summary = module.export([self.write(rows, str(mapped) + '.jsonl')], out)
            self.assertEqual(summary['standaloneChoiceTypes'], {'MODE_SELECTION': 1})
            self.assertEqual(summary['standaloneModeOptionLinks'], {'EXACT_CAPTURED_OPTION': 2} if mapped else {'UNMAPPED': 1})
            self.assertEqual(summary['standaloneRepeatedModeReferences'], 1 if mapped else 0)
            choice = json.loads((out / 'choices.jsonl').read_text())
            self.assertEqual(choice['terminal']['choice']['selection'], rows[3]['choice']['selection'])

    def test_mode_prefix_bounds_and_total_counts_remain_explicit(self):
        rows = mode_fixture()
        value = rows[3]['choice']['selection']
        value.update(modes=value['modes'] * 128, selectedCount=300, truncated=True)
        self.assertEqual(module.export([self.write(rows)])['standaloneChoiceRecords'], 1)

    def test_mode_identity_flags_and_private_fields_are_validated(self):
        for mutation in ('option-index', 'identity', 'bool-index', 'count', 'private', 'available', 'flags'):
            with self.subTest(mutation=mutation):
                rows = mode_fixture()
                start = rows[2]['choice']; value = rows[3]['choice']['selection']
                if mutation == 'option-index': value['modes'][0]['optionIndex'] = 9
                elif mutation == 'identity': value['modes'][0]['abilityId'] = 99
                elif mutation == 'bool-index': value['modes'][0]['optionIndex'] = True
                elif mutation == 'count': value['selectedCount'] = 0
                elif mutation == 'private': start['options']['items'][0]['description'] = 'hidden text'
                elif mutation == 'available': value['available'] = False
                elif mutation == 'flags': start['options']['allowRepeat'] = 1
                with self.assertRaises(module.InvalidData): module.export([self.write(rows, mutation + '.jsonl')])
