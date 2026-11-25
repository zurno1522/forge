import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
import test_zone_pick_progress as progress


def variant_fixture(branch):
    rows = progress.progress_fixture()
    for index in (2, 3):
        context = rows[index]['choice']['inputContext']
        context.update(schemaVersion=11, profile='PERMITTED_CHOICE_VIEW_V11')
        context['policy']['prompt']['branch'] = branch
    rows[2]['choice']['options']['branch'] = branch
    return rows


class ZoneVariantTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.index = 0

    def export(self, rows):
        self.index += 1
        source = self.root / f'{self.index}.jsonl'
        source.write_text(''.join(json.dumps(row) + '\n' for row in rows))
        result = module.export([source], self.root / f'out-{self.index}')
        self.assertEqual(result, module.export([source]))
        return result

    def test_v11_named_ordinary_and_per_type_ownership_preserve_partial_readiness(self):
        for branch in ('ORDINARY_HIDDEN_ORIGIN', 'SAME_NAME_HIDDEN_ORIGIN', 'EACH_TYPE_HIDDEN_ORIGIN'):
            with self.subTest(branch=branch):
                result = self.export(variant_fixture(branch))
                self.assertEqual(result['zoneSelectionProgress']['counts']['records'], 1)
                self.assertTrue(any('V11' in text and 'across-type' in text for text in result['limitations']))

    def test_per_type_initial_retention_is_not_reconstructed_as_prior_returns(self):
        rows = variant_fixture('EACH_TYPE_HIDDEN_ORIGIN')
        child = rows[3]['choice']
        child['options']['zoneSelectionProgress'].update(attemptIndex=0, loopPosition=0,
            priorNonNullReturnCount=0, retainedCount=4, priorReturns=[])
        child['inputContext']['policy']['prompt'].update(attemptIndex=0, loopPosition=0,
            priorNonNullReturnCount=0, retainedCount=4, previousReturns=[])
        self.export(rows)

    def test_invented_missing_or_mismatched_branches_are_rejected(self):
        for error in ('parent-v10', 'child-v10', 'missing-parent-prompt', 'missing-child-prompt',
                      'unknown', 'wrong-parent', 'wrong-child', 'invented-name'):
            rows = variant_fixture('SAME_NAME_HIDDEN_ORIGIN')
            parent, child = (rows[i]['choice'] for i in (2, 3))
            if error in ('parent-v10', 'child-v10'):
                context = (parent if error == 'parent-v10' else child)['inputContext']
                context.update(schemaVersion=10, profile='PERMITTED_CHOICE_VIEW_V10')
                context['policy']['prompt'].pop('branch')
            if error == 'missing-parent-prompt': parent['inputContext']['policy']['prompt'].pop('branch')
            if error == 'missing-child-prompt': child['inputContext']['policy']['prompt'].pop('branch')
            if error == 'unknown': parent['options']['branch'] = 'SOME_OTHER_LOOP'
            if error == 'wrong-parent': parent['inputContext']['policy']['prompt']['branch'] = 'ORDINARY_HIDDEN_ORIGIN'
            if error == 'wrong-child': child['inputContext']['policy']['prompt']['branch'] = 'EACH_TYPE_HIDDEN_ORIGIN'
            if error == 'invented-name': child['inputContext']['policy']['prompt']['namedCard'] = 'c0'
            with self.subTest(error=error), self.assertRaises(module.InvalidData): self.export(rows)

    def test_legacy_profiles_cannot_sneak_in_branch_features(self):
        rows = progress.progress_fixture()
        for index in (2, 3):
            changed = copy.deepcopy(rows)
            changed[index]['choice']['inputContext']['policy']['prompt']['branch'] = 'ORDINARY_HIDDEN_ORIGIN'
            with self.subTest(index=index), self.assertRaises(module.InvalidData): self.export(changed)
