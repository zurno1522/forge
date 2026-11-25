import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
import test_zone_variants as variants


def moving_fixture(branch):
    rows = variants.variant_fixture(branch)
    for i in (2, 3):
        choice = rows[i]['choice']; context = choice['inputContext']
        context.update(schemaVersion=12, profile='PERMITTED_CHOICE_VIEW_V12')
        zones = dict(destination='None' if branch == 'DRAFT' else 'Exile', origins=[] if branch == 'DRAFT' else ['Library'])
        choice['options'].update(copy.deepcopy(zones))
        context['policy']['prompt'].update(copy.deepcopy(zones))
        if branch == 'DRAFT': context['policy']['prompt']['sourcePlayer'] = 'p1'
    if branch == 'DRAFT': rows[2]['choice']['options']['sourcePlayerId'] = 1
    return rows


class MovingZoneChoiceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.index = 0

    def export(self, rows):
        self.index += 1; source = self.root / f'{self.index}.jsonl'
        source.write_text(''.join(json.dumps(row) + '\n' for row in rows))
        result = module.export([source], self.root / f'out-{self.index}')
        self.assertEqual(result, module.export([source])); return result

    def test_moving_branches_allow_return_history_without_final_retention(self):
        for branch in ('HEIST', 'DRAFT'):
            with self.subTest(branch=branch):
                rows = moving_fixture(branch); result = self.export(rows)
                self.assertEqual(result['zoneSelectionProgress']['counts']['records'], 1)
                self.assertTrue(any('V12' in text and 'rejected' in text for text in result['limitations']))
                self.assertFalse(any('Heist/Draft remain unsupported' in text for text in result['limitations']))

    def test_legacy_profiles_refuse_moving_branches_even_with_unavailable_prompts(self):
        for branch in ('HEIST', 'DRAFT'):
            for version in (10, 11):
                for unavailable in (False, True):
                    rows = moving_fixture(branch)
                    for i in (2, 3):
                        context = rows[i]['choice']['inputContext']
                        context.update(schemaVersion=version, profile=f'PERMITTED_CHOICE_VIEW_V{version}')
                        if version == 10: context['policy']['prompt'].pop('branch')
                        if unavailable:
                            context['policy']['prompt'] = None; context['coverage']['prompt'] = 'UNAVAILABLE'
                            context['status'] = 'PARTIAL'; context['omissions'].append(dict(component='PROMPT', reason='UNSUPPORTED'))
                    with self.subTest(branch=branch, version=version, unavailable=unavailable), self.assertRaises(module.InvalidData): self.export(rows)

    def test_branch_zone_and_role_constraints_are_checked_independently_of_progress(self):
        for branch in ('HEIST', 'DRAFT'):
            for error in ('destination', 'origins', 'decider', 'missing-source', 'source' if branch == 'DRAFT' else 'child-branch'):
                rows = moving_fixture(branch); parent, child = (rows[i]['choice'] for i in (2, 3))
                if error == 'destination':
                    for choice in (parent, child):
                        choice['options']['destination'] = choice['inputContext']['policy']['prompt']['destination'] = 'Hand'
                if error == 'origins':
                    for choice in (parent, child):
                        choice['options']['origins'] = choice['inputContext']['policy']['prompt']['origins'] = ['Graveyard']
                if error == 'decider':
                    parent['options']['deciderPlayerId'] = 0; parent['inputContext']['policy']['prompt']['decider'] = 'p0'
                if error == 'missing-source':
                    parent['options']['sourcePlayerId'] = None; parent['inputContext']['policy']['prompt']['sourcePlayer'] = None
                if error == 'source':
                    parent['options']['sourcePlayerId'] = 0; parent['inputContext']['policy']['prompt']['sourcePlayer'] = 'p0'
                if error == 'child-branch': child['inputContext']['policy']['prompt']['branch'] = 'ORDINARY_HIDDEN_ORIGIN'
                with self.subTest(branch=branch, error=error), self.assertRaises(module.InvalidData): self.export(rows)
