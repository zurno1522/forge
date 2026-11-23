import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module, fixture
from test_number_choices import number_fixture
from test_stack_input_context import context2
from test_input_context import input_context


def context3(chooser=1, prompt=None, coverage='CONTROLLER_REQUEST_V1'):
    context = context2(chooser=chooser, empty=True)
    context.update(schemaVersion=3, profile='PERMITTED_CHOICE_VIEW_V3', timing='BEFORE_SELECTOR')
    context['coverage']['prompt'] = coverage
    context['policy']['prompt'] = prompt if prompt is not None else dict(kind='NUMBER_RANGE', minimum=0, maximum=3)
    if coverage != 'CONTROLLER_REQUEST_V1': context['policy']['prompt'] = None
    return context


def rows3():
    rows = number_fixture()
    rows[2]['choice']['inputContext'] = context3()
    return rows


class PromptInputContextTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.index = 0

    def export(self, rows):
        self.index += 1
        path = self.root / f'{self.index}.jsonl'
        path.write_text(''.join(json.dumps(row) + '\n' for row in rows))
        output = self.root / f'export-{self.index}'
        summary = module.export([path], output)
        return summary, output

    def test_versions_preserve_their_shapes_and_new_request_data_is_not_training_ready(self):
        for context in (input_context(timing='BEFORE_SELECTOR'), context2(empty=True), context3()):
            context['timing'] = 'BEFORE_SELECTOR'
            rows = rows3(); rows[2]['choice']['inputContext'] = context
            summary, output = self.export(rows)
            row = json.loads((output / 'choices.jsonl').read_text())
            self.assertEqual(row['start']['choice']['inputContext'], context)
            self.assertFalse(row['trainingReady'])
            self.assertEqual(summary['inputContextProfiles'], {context['profile']: 1})
        self.assertEqual(summary['inputCoverageAudit']['totals']['components']['prompt:CONTROLLER_REQUEST_V1'], 1)
        self.assertEqual(summary['inputCoverageAudit']['totals']['optionBindings']['DIAGNOSTIC_ONLY_V3'], 1)

    def test_discrete_domain_preserves_duplicate_values_and_order_without_inventing_indices(self):
        rows = rows3(); start = rows[2]['choice']; terminal = rows[3]['choice']
        start.update(boundary='CONTROLLER_NUMBER_OPTIONS', options=dict(coverage='OBSERVED_DISCRETE_OPTIONS', items=[7, 7, -2], truncated=False))
        start['inputContext']['policy']['prompt'] = dict(kind='NUMBER_OPTIONS', values=[7, 7, -2], relatedPlayer='p0')
        terminal['selection'].update(value=7, domainMatch='AMBIGUOUS_CAPTURED_OPTIONS')
        self.export(rows)
        for mutation in ('order', 'membership', 'truncated', 'oversized'):
            bad = copy.deepcopy(rows); prompt = bad[2]['choice']['inputContext']['policy']['prompt']
            if mutation == 'order': prompt['values'] = [-2, 7, 7]
            if mutation == 'membership': prompt['values'] = [7, 7]
            if mutation == 'truncated': bad[2]['choice']['options']['truncated'] = True
            if mutation == 'oversized': prompt['values'] = [7] * 65
            with self.subTest(mutation=mutation), self.assertRaises(module.InvalidData): self.export(bad)

    def test_entity_optional_constraints_and_player_binding_match_the_actual_request(self):
        rows = rows3(); start = rows[2]['choice']
        start.update(choiceType='EFFECT_ENTITY_SELECTION', boundary='CONTROLLER_EFFECT_ENTITY',
                     options=dict(coverage='OBSERVED_EFFECT_OPTIONS_DIAGNOSTIC_ONLY', items=[dict(type='PLAYER', id=0)],
                                  truncated=False, optional=True, relatedPlayerId=0))
        start['inputContext']['policy']['prompt'] = dict(kind='SINGLE_ENTITY', minimumPicks=0, maximumPicks=1, optional=True, relatedPlayer='p0')
        rows[3]['choice']['selection'] = dict(selected=dict(type='PLAYER', id=0), selectedOptionIndex=0, match='UNIQUE')
        self.export(rows)
        for mutation in ('player', 'minimum', 'maximum', 'optional', 'raw-id', 'pool'):
            bad = copy.deepcopy(rows); prompt = bad[2]['choice']['inputContext']['policy']['prompt']
            if mutation == 'player': prompt['relatedPlayer'] = 'p1'
            if mutation == 'minimum': prompt['minimumPicks'] = 1
            if mutation == 'maximum': prompt['maximumPicks'] = 2
            if mutation == 'optional': prompt.update(optional=False, minimumPicks=1)
            if mutation == 'raw-id': prompt['relatedPlayerId'] = 0
            if mutation == 'pool': prompt['items'] = [dict(type='PLAYER', id=0)]
            with self.subTest(mutation=mutation), self.assertRaises(module.InvalidData): self.export(bad)

    def test_bounds_are_observed_arguments_including_reversed_or_extreme_values(self):
        for minimum, maximum in ((-2147483648, 2147483647), (9, 3)):
            rows = rows3()
            rows[2]['choice']['options'].update(minimum=minimum, maximum=maximum)
            rows[2]['choice']['inputContext']['policy']['prompt'].update(minimum=minimum, maximum=maximum)
            rows[3]['choice']['selection'].update(domainMatch='OUTSIDE_OBSERVED_DOMAIN' if minimum > 0 else 'WITHIN_OBSERVED_DOMAIN')
            self.export(rows)
        rows = rows3(); rows[2]['choice']['inputContext']['policy']['prompt']['maximum'] = 4
        with self.assertRaises(module.InvalidData): self.export(rows)

    def test_schema_coverage_state_and_boundary_claims_fail_closed(self):
        mutations = {
            'old-version': lambda c: c.update(schemaVersion=2, profile='PERMITTED_CHOICE_VIEW_V2'),
            'unknown-version': lambda c: c.update(schemaVersion=4),
            'wrong-profile': lambda c: c.update(profile='PERMITTED_CHOICE_VIEW_V2'),
            'planning': lambda c: c.update(stateBasis='PLANNING_WORKING_STATE'),
            'boolean-bound': lambda c: c['policy']['prompt'].update(minimum=False),
            'unknown-kind': lambda c: c['policy']['prompt'].update(kind='COMPUTED_DAMAGE'),
            'raw-script': lambda c: c['policy']['prompt'].update(script='DB$ ChooseNumber'),
            'wrong-coverage': lambda c: c['coverage'].update(prompt='UNSUPPORTED'),
            'unknown-player': lambda c: c['policy'].update(prompt=dict(kind='NUMBER_OPTIONS', values=[1], relatedPlayer='p9')),
            'contradictory-omission': lambda c: c.update(status='PARTIAL', omissions=[dict(component='PROMPT', reason='UNSUPPORTED')]),
        }
        for name, mutate in mutations.items():
            rows = rows3(); mutate(rows[2]['choice']['inputContext'])
            with self.subTest(name=name), self.assertRaises(module.InvalidData): self.export(rows)
        rows = rows3(); rows[2]['choice']['boundary'] = 'CONTROLLER_ANNOUNCEMENT'
        rows[2]['choice']['inputContext']['timing'] = 'BEFORE_OPERATION'
        with self.assertRaises(module.InvalidData): self.export(rows)
        rows = rows3(); rows[2]['choice']['options'] = dict(coverage='OBSERVED_DISCRETE_OPTIONS', items=[0], truncated=False)
        with self.assertRaises(module.InvalidData): self.export(rows)

    def test_unavailable_requests_keep_the_original_record_and_never_supply_a_numeric_prefix(self):
        for reason in ('UNSUPPORTED', 'OUTSIDE_CAPTURED_PREFIX', 'UNRESOLVED_BINDING'):
            rows = rows3(); c = rows[2]['choice']['inputContext']
            c.update(status='PARTIAL', omissions=[dict(component='PROMPT', reason=reason)])
            c['policy']['prompt'] = None; c['coverage']['prompt'] = 'UNAVAILABLE'
            self.export(rows)
            c['omissions'] = []; c['status'] = 'CAPTURED'
            with self.assertRaises(module.InvalidData): self.export(rows)
        rows = rows3(); c = rows[2]['choice']['inputContext']
        c.update(status='UNAVAILABLE', policy=None, omissions=[dict(component='CONTEXT', reason='BUDGET_EXHAUSTED')],
                 diagnostics=dict(playerBindings=[], cardBindings=[], stackBindings=[]))
        c['coverage'].update(playerView='UNAVAILABLE', stack='UNAVAILABLE', disclosures='UNAVAILABLE', prompt='UNAVAILABLE')
        self.export(rows)
        c['coverage']['prompt'] = 'CONTROLLER_REQUEST_V1'
        with self.assertRaises(module.InvalidData): self.export(rows)

    def test_parent_baseline_does_not_acquire_a_selector_prompt_by_inference(self):
        rows = fixture(); c = context3(chooser=0, coverage='UNSUPPORTED'); c['timing'] = 'BEFORE_OPERATION'
        rows[2]['decision']['inputContext'] = c
        self.export(rows)
        c['coverage']['prompt'] = 'CONTROLLER_REQUEST_V1'
        c['policy']['prompt'] = dict(kind='NUMBER_RANGE', minimum=0, maximum=3)
        with self.assertRaises(module.InvalidData): self.export(rows)
