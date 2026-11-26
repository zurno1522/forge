import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_context_bindings import rows4
from test_prompt_input_context import rows3
from test_entry_location import entry
from test_entity_selection_history import history_fixture


def upgrade(rows):
    def walk(value):
        if isinstance(value, dict):
            if str(value.get('profile', '')).startswith('PERMITTED_CHOICE_VIEW_V'):
                value.update(schemaVersion=16, profile='PERMITTED_CHOICE_VIEW_V16')
                value['coverage']['receivedParameters'] = 'UNAVAILABLE' if value['policy'] is None else 'UNSUPPORTED'
                if value['policy'] is not None: value['policy']['receivedParameters'] = None
            for child in value.values(): walk(child)
        elif isinstance(value, list):
            for child in value: walk(child)
    walk(rows)
    for row in rows:
        choice = row.get('choice', {})
        if choice.get('status') == 'STARTED' and choice.get('boundary') in ('CONTROLLER_EFFECT_ENTITY','CONTROLLER_NUMBER_RANGE'):
            choice['options']['callbackParameters'] = dict(schemaVersion=1, coverage='COMPLETE', kind='NONE', values={}, reason=None)
            choice['inputContext']['coverage']['receivedParameters'] = 'RECEIVED_CALLBACK_PARAMETERS_V1'
            choice['inputContext']['policy']['receivedParameters'] = dict(kind='NONE')
    return rows


def receipt(kind, values): return dict(schemaVersion=1, coverage='COMPLETE', kind=kind, values=values, reason=None)


def parameter_fixture(kind='VOTER', values=None, family='NUMBER'):
    rows = rows3() if family == 'NUMBER' else rows4()
    if family == 'NUMBER':
        rows[2]['choice']['inputContext'] = copy.deepcopy(rows4()[2]['choice']['inputContext'])
        rows[2]['choice']['inputContext']['policy']['prompt'] = dict(kind='NUMBER_RANGE', minimum=0, maximum=3)
        rows[2]['choice']['inputContext']['coverage']['prompt'] = 'CONTROLLER_REQUEST_V1'
        rows[2]['choice']['inputContext']['diagnostics']['optionBindings'] = dict(coverage='UNSUPPORTED', items=[], truncated=False)
    start = rows[2]['choice']; start['entryLocation'] = entry(); upgrade(rows)
    values = dict(voter=dict(type='PLAYER', id=0)) if values is None else copy.deepcopy(values)
    start['options']['callbackParameters'] = receipt(kind, values)
    policy = start['inputContext']['policy']; diagnostic = start['inputContext']['diagnostics']
    by_raw = [(dict(type='PLAYER', id=b['id']), b['entityRef']) for b in diagnostic['playerBindings']]
    by_raw += [(dict(type='CARD', id=b['id'], gameTimestamp=b['gameTimestamp']), b['entityRef']) for b in diagnostic['cardBindings']]
    policy['receivedParameters'] = dict(kind=kind, **{key:next(local for raw, local in by_raw if raw == value) if isinstance(value, dict) else value for key, value in values.items()})
    return rows


def card_ref():
    binding = rows4()[2]['choice']['inputContext']['diagnostics']['cardBindings'][0]
    return dict(type='CARD', id=binding['id'], gameTimestamp=binding['gameTimestamp'])


class ReceivedParametersTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup); self.root = Path(self.tmp.name); self.index = 0

    def export(self, rows):
        self.index += 1; source = self.root / f'{self.index}.jsonl'; output = self.root / f'out-{self.index}'
        source.write_text(''.join(json.dumps(row) + '\n' for row in rows))
        result = module.export([source], output); self.assertEqual(result, module.export([source])); return result, output

    def test_all_audited_shapes_preserve_roles_signed_scalars_and_separate_capability(self):
        cases = [('NUMBER','NONE',{}), ('NUMBER','VOTER',dict(voter=dict(type='PLAYER', id=0))),
            ('NUMBER','COUNTER_AMOUNT',dict(target=card_ref(), counterType='-1/-1')),
            ('NUMBER','COUNTER_TRANSFER_AMOUNT',dict(source=card_ref(), target=dict(type='PLAYER', id=0), counterType='charge')),
            ('ENTITY','ENTITY_TARGET_ROLE',dict(target=dict(type='PLAYER', id=0))),
            ('ENTITY','ENTITY_CARD_ROLE',dict(role='Attach', entity=card_ref())),
            ('ENTITY','ENTITY_MANA_VALUE_LIMIT',dict(manaValueLimit=-(1 << 31))),
            ('ENTITY','ENTITY_COUNTER_REQUEST',dict(counterType='loyalty', amount=(1 << 31)-1, target=card_ref())),
            ('ENTITY','ENTITY_COUNTER_REQUEST',dict(counterType='charge', amount=None, target=None))]
        for family, kind, values in cases:
            with self.subTest(kind=kind):
                result, output = self.export(parameter_fixture(kind, values, family))
                self.assertEqual(result['inputCoverageAudit']['totals']['components']['receivedParameters:RECEIVED_CALLBACK_PARAMETERS_V1'], 1)
                self.assertTrue(any('V16' in text and 'Role collections remain unsupported' in text for text in result['limitations']))
                self.assertTrue(all(not json.loads(line)['trainingReady'] for line in (output/'choices.jsonl').read_text().splitlines()))

    def test_unsupported_parameters_leave_numeric_domain_and_owned_history_independently_valid(self):
        for history in (False, True):
            rows = upgrade(history_fixture()) if history else parameter_fixture(); child = rows[3 if history else 2]['choice']; ctx = child['inputContext']
            old_prompt = copy.deepcopy(ctx['policy']['prompt'])
            child['options']['callbackParameters'] = dict(schemaVersion=1, coverage='UNSUPPORTED', kind=None, values=None, reason='UNSUPPORTED')
            ctx['policy']['receivedParameters'] = None; ctx['coverage']['receivedParameters'] = 'UNAVAILABLE'; ctx['status'] = 'PARTIAL'
            ctx['omissions'].append(dict(component='PARAMETERS', reason='UNSUPPORTED'))
            self.export(rows); self.assertEqual(ctx['policy']['prompt'], old_prompt)

    def test_unresolved_roles_and_overlong_counter_names_are_explicit_refusals(self):
        for reason in ('UNRESOLVED_BINDING', 'OUTSIDE_CAPTURED_PREFIX'):
            rows = parameter_fixture('COUNTER_AMOUNT', dict(target=card_ref(), counterType='charge')); child = rows[2]['choice']; ctx = child['inputContext']
            if reason == 'OUTSIDE_CAPTURED_PREFIX': child['options']['callbackParameters'].update(coverage='UNAVAILABLE', values=None, reason=reason)
            ctx['policy']['receivedParameters'] = None; ctx['coverage']['receivedParameters'] = 'UNAVAILABLE'; ctx['status'] = 'PARTIAL'; ctx['omissions'].append(dict(component='PARAMETERS', reason=reason))
            with self.subTest(reason=reason): self.export(rows)

    def test_planning_parameters_do_not_grant_numeric_prompt_permission(self):
        rows = parameter_fixture(); child = rows[2]['choice']; ctx = child['inputContext']; prompt = ctx['policy']['prompt']
        child['stage'] = 'CANDIDATE_EVALUATION'; child['entryLocation']['stateBasis'] = ctx['stateBasis'] = 'PLANNING_WORKING_STATE'
        ctx['policy']['prompt'] = None; ctx['coverage']['prompt'] = 'UNAVAILABLE'; ctx['status'] = 'PARTIAL'; ctx['omissions'].append(dict(component='PROMPT', reason='UNSUPPORTED'))
        self.export(rows)
        ctx['policy']['prompt'] = prompt; ctx['coverage']['prompt'] = 'CONTROLLER_REQUEST_V1'
        with self.assertRaises(module.InvalidData): self.export(rows)

    def test_shape_profile_boundary_and_feature_injections_are_rejected(self):
        errors = ('old-profile','missing-capability','missing-policy-field','missing-receipt','bool-version','extra-receipt','unknown-shape',
            'wrong-family','extra-value','long-counter-name','raw-id-role','foreign-role',
            'mismatched-role','missing-role','extra-feature','wrong-coverage','silent-absent','unsupported-features','missing-entry',
            'wrong-boundary','old-wire','list-role','none-fallback')
        for error in errors:
            rows = parameter_fixture(); child = rows[2]['choice']; ctx = child['inputContext']; raw = child['options']['callbackParameters']; projected = ctx['policy']['receivedParameters']
            if error == 'old-profile': ctx.update(schemaVersion=15, profile='PERMITTED_CHOICE_VIEW_V15')
            if error == 'missing-capability': ctx['coverage'].pop('receivedParameters')
            if error == 'missing-policy-field': ctx['policy'].pop('receivedParameters')
            if error == 'missing-receipt': child['options'].pop('callbackParameters')
            if error == 'bool-version': raw['schemaVersion'] = True
            if error == 'extra-receipt': raw['title'] = 'secret'
            if error == 'unknown-shape': raw['kind'] = 'UNRECOGNIZED'
            if error == 'wrong-family': raw.update(kind='ENTITY_MANA_VALUE_LIMIT', values=dict(manaValueLimit=1))
            if error == 'extra-value': raw['values']['internal'] = 'private'
            if error == 'long-counter-name': raw.update(kind='COUNTER_AMOUNT', values=dict(target=card_ref(), counterType='x'*257))
            if error == 'raw-id-role': projected['voter'] = 0
            if error == 'foreign-role': projected['voter'] = 'p99'
            if error == 'mismatched-role': projected['voter'] = 'p1'
            if error == 'missing-role': projected['voter'] = None
            if error == 'extra-feature': projected['options'] = []
            if error == 'wrong-coverage': ctx['coverage']['receivedParameters'] = 'CONTROLLER_REQUEST_V1'
            if error == 'silent-absent': ctx['coverage']['receivedParameters'] = 'UNSUPPORTED'; ctx['policy']['receivedParameters'] = None
            if error == 'unsupported-features': raw.update(coverage='UNSUPPORTED', kind=None, values=None, reason='UNSUPPORTED')
            if error == 'missing-entry': child.pop('entryLocation')
            if error == 'wrong-boundary': child['boundary'] = 'CONTROLLER_NUMBER_OPTIONS'
            if error == 'old-wire': ctx['coverage'].pop('receivedParameters'); ctx['policy'].pop('receivedParameters')
            if error == 'list-role': projected['voter'] = ['p0']
            if error == 'none-fallback': projected.clear(); projected['kind'] = 'NONE'
            with self.subTest(error=error), self.assertRaises(module.InvalidData): self.export(rows)

    def test_private_stale_and_changed_scalars_cannot_become_received_features(self):
        for error in ('private','stale','scalar','nullable'):
            rows = parameter_fixture('ENTITY_CARD_ROLE', dict(role='Partner', entity=card_ref()), 'ENTITY'); child = rows[2]['choice']; ctx = child['inputContext']
            if error == 'private':
                ctx['policy']['cards'][0]['zone'] = 'Hand'; ctx['diagnostics']['optionBindings'] = dict(coverage='UNSUPPORTED', items=[], truncated=False)
            if error == 'stale': child['options']['callbackParameters']['values']['entity']['gameTimestamp'] += 1
            if error == 'scalar': ctx['policy']['receivedParameters']['role'] = 'Attacker'
            if error == 'nullable': child['options']['callbackParameters']['values']['entity'] = None
            with self.subTest(error=error), self.assertRaises(module.InvalidData): self.export(rows)
        for value in (True, 1 << 31, '2'):
            rows = parameter_fixture('ENTITY_MANA_VALUE_LIMIT', dict(manaValueLimit=2), 'ENTITY'); child = rows[2]['choice']
            child['options']['callbackParameters']['values']['manaValueLimit'] = child['inputContext']['policy']['receivedParameters']['manaValueLimit'] = value
            with self.subTest(value=value), self.assertRaises(module.InvalidData): self.export(rows)

    def test_parent_context_cannot_borrow_a_child_parameter_capability(self):
        rows = upgrade(history_fixture()); parent, child = (rows[i]['choice'] for i in (2, 3))
        parent['inputContext']['policy']['receivedParameters'] = dict(kind='NONE'); parent['inputContext']['coverage']['receivedParameters'] = 'RECEIVED_CALLBACK_PARAMETERS_V1'
        with self.assertRaises(module.InvalidData): self.export(rows)

    def test_v15_wire_and_absence_do_not_acquire_new_capability(self):
        result, _ = self.export(rows3()); self.assertFalse(any('V16' in text for text in result['limitations']))
