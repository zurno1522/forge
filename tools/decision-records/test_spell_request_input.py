import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_spell_ability_choices import spell_fixture


def request_fixture(affected=True):
    rows = spell_fixture()
    for i in (2, 3): rows[i]['choice']['inputContext'].update(schemaVersion=14, profile='PERMITTED_CHOICE_VIEW_V14')
    child = rows[3]['choice']; context = child['inputContext']
    child['options']['spellRequest'] = dict(schemaVersion=1, coverage='COMPLETE', affectedPlayerId=0 if affected else None)
    context['coverage']['prompt'] = 'RECEIVED_SPELL_REQUEST_V1'
    context['policy']['prompt'] = dict(kind='SPELL_ABILITY_REQUEST', affectedPlayer='p0' if affected else None)
    return rows


class SpellRequestInputTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.index = 0

    def export(self, rows):
        self.index += 1; source = self.root / f'{self.index}.jsonl'; out = self.root / f'out-{self.index}'
        source.write_text(''.join(json.dumps(row) + '\n' for row in rows))
        result = module.export([source], out); self.assertEqual(result, module.export([source])); return result, out

    def test_current_roles_and_known_no_parameter_requests_have_their_own_permission(self):
        for affected in (False, True):
            with self.subTest(affected=affected):
                result, out = self.export(request_fixture(affected))
                values = [json.loads(line) for line in (out/'choices.jsonl').read_text().splitlines()]
                self.assertTrue(all(not value['trainingReady'] for value in values))
                self.assertTrue(any('V14' in text and 'exact Player identity' in text for text in result['limitations']))
                self.assertFalse(any('single prompt and complete legal domain remain unknown' in text for text in result['limitations']))

    def test_unsupported_parameters_and_unresolved_roles_are_not_silently_empty(self):
        for unsupported in (False, True):
            rows = request_fixture(); child = rows[3]['choice']; ctx = child['inputContext']
            if unsupported: child['options']['spellRequest'].update(coverage='UNSUPPORTED', affectedPlayerId=None)
            ctx['policy']['prompt'] = None; ctx['coverage']['prompt'] = 'UNAVAILABLE'; ctx['status'] = 'PARTIAL'
            ctx['omissions'].append(dict(component='PROMPT', reason='UNSUPPORTED' if unsupported else 'UNRESOLVED_BINDING'))
            with self.subTest(unsupported=unsupported): self.export(rows)

    def test_planning_roles_keep_working_state_semantics(self):
        rows = request_fixture(); child = rows[3]['choice']; child['stage'] = 'CANDIDATE_EVALUATION'
        child['entryLocation']['stateBasis'] = child['inputContext']['stateBasis'] = 'PLANNING_WORKING_STATE'
        self.export(rows)

    def test_receipt_profile_role_and_feature_injection_are_rejected(self):
        errors = ('missing-receipt', 'old-profile', 'component-version', 'bool-version', 'extra-component', 'unknown-coverage',
            'bool-id', 'string-id', 'wrong-role', 'unknown-role', 'empty-role', 'unsupported-role', 'unsupported-prompt',
            'wrong-coverage', 'extra-prompt', 'raw-feature-id', 'wrong-boundary', 'missing-entry', 'internal-kind')
        for error in errors:
            rows = request_fixture(); child = rows[3]['choice']; ctx = child['inputContext']; receipt = child['options']['spellRequest']; prompt = ctx['policy']['prompt']
            if error == 'missing-receipt': child['options'].pop('spellRequest')
            if error == 'old-profile': ctx.update(schemaVersion=13, profile='PERMITTED_CHOICE_VIEW_V13')
            if error == 'component-version': receipt['schemaVersion'] = 2
            if error == 'bool-version': receipt['schemaVersion'] = True
            if error == 'extra-component': receipt['privateName'] = 'secret'
            if error == 'unknown-coverage': receipt['coverage'] = 'NONE'
            if error == 'bool-id': receipt['affectedPlayerId'] = True
            if error == 'string-id': receipt['affectedPlayerId'] = '0'
            if error == 'wrong-role': prompt['affectedPlayer'] = 'p1'
            if error == 'unknown-role': prompt['affectedPlayer'] = 'p99'
            if error == 'empty-role': prompt['affectedPlayer'] = None
            if error == 'unsupported-role': receipt['coverage'] = 'UNSUPPORTED'
            if error == 'unsupported-prompt': receipt.update(coverage='UNSUPPORTED', affectedPlayerId=None); prompt['affectedPlayer'] = None
            if error == 'wrong-coverage': ctx['coverage']['prompt'] = 'CONTROLLER_REQUEST_V1'
            if error == 'extra-prompt': prompt['minimumPicks'] = 1
            if error == 'raw-feature-id': prompt['affectedPlayer'] = 0
            if error == 'wrong-boundary': child['boundary'] = 'CONTROLLER_EFFECT_ENTITY'
            if error == 'missing-entry': child.pop('entryLocation')
            if error == 'internal-kind': prompt['kind'] = 'UNSUPPORTED_REQUEST'
            with self.subTest(error=error), self.assertRaises(module.InvalidData): self.export(rows)

    def test_v13_records_keep_their_unsupported_prompt_and_old_wire(self):
        result, _ = self.export(spell_fixture())
        self.assertFalse(any('V14' in text for text in result['limitations']))
        rows = request_fixture(False); child = rows[3]['choice']; child['inputContext'].update(schemaVersion=13, profile='PERMITTED_CHOICE_VIEW_V13')
        child['inputContext']['policy']['prompt'] = None; child['inputContext']['coverage']['prompt'] = 'UNSUPPORTED'
        with self.assertRaises(module.InvalidData): self.export(rows)
