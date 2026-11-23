import copy
import json
import tempfile
import unittest
from pathlib import Path

from test_choice_events import choices_fixture
from test_export import fixture, module


def input_context(chooser=1, timing='BEFORE_OPERATION'):
    return dict(schemaVersion=1, profile='PERMITTED_CHOICE_VIEW_V1', status='CAPTURED',
                timing=timing, stateBasis='RULES_STATE',
                perspective=dict(chooserPlayerId=chooser, controllingPlayerId=chooser),
                coverage=dict(playerView='PERMITTED_CURRENT_FIELDS_V1', stack='UNSUPPORTED',
                              disclosures='UNSUPPORTED_PRIOR_SCOPE', history='INCOMPLETE', options='DIAGNOSTIC_ONLY'),
                policy=dict(turn=1, phase='MAIN1', turnPlayer='p0', chooser='p' + str(chooser),
                            players=[dict(entityRef='p' + str(i), life=40, handCount=0, libraryCount=60,
                                          floatingManaColors=[]) for i in range(2)], cards=[], disclosedFacts=[]),
                diagnostics=dict(playerBindings=[dict(entityRef='p' + str(i), id=i) for i in range(2)], cardBindings=[]),
                omissions=[])


def known_card(context, zone='Hand', name='Island'):
    card = dict(entityRef='c0', owner=context['policy']['chooser'], controller=context['policy']['chooser'],
                zone=zone, tapped=False, faceDown=False, identityKnown=True, name=name,
                types='Basic Land — Island', manaCost='no cost', power=None, toughness=None, counters={})
    context['policy']['cards'].append(card)
    context['diagnostics']['cardBindings'].append(dict(entityRef='c0', id=(1 << 42) + 8, gameTimestamp=(1 << 52) + 3))
    return card


def disclosure(context, name='Island'):
    context['coverage']['disclosures'] = 'STACK_RESOLUTION'
    fact = dict(entityRef='d0', source='CONTROLLER_VIEW_REVEAL', scope='STACK_RESOLUTION', zone='Library',
                presentedFace='Original', name=name, types='Basic Land — Island', manaCost='no cost',
                currentObjectAssociation='UNRESOLVED')
    context['policy']['disclosedFacts'].append(fact)
    return fact


class InputContextTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def write(self, rows, name='input.jsonl'):
        path = self.root / name
        path.write_text(''.join(json.dumps(row) + '\n' for row in rows))
        return path

    def test_legacy_absence_keeps_dataset_format_and_does_not_claim_empty_contexts(self):
        for standalone in (False, True):
            rows = choices_fixture() if standalone else fixture()
            output = self.root / str(standalone)
            summary = module.export([self.write(rows, str(standalone) + '.jsonl')], output)
            self.assertNotIn('inputContextRecords', summary)
            self.assertNotIn('Inline choice input contexts', (output / 'coverage.md').read_text())
            self.assertEqual(len(list(output.iterdir())), 7 if standalone else 6)
            exported = (output / ('choices.jsonl' if standalone else 'dataset.jsonl')).read_text()
            self.assertNotIn('inputContext', exported)

    def test_parent_and_delegated_chooser_contexts_are_preserved_without_cross_filling(self):
        rows = choices_fixture()
        choice = rows[2]['choice']
        choice['inputContext'] = input_context()
        known_card(choice['inputContext'], name='Private chooser hand')
        disclosure(choice['inputContext'], name='Actually disclosed library card')
        decision = next(row['decision'] for row in rows if row['type'] == 'AI_DECISION')
        decision['inputContext'] = input_context(chooser=0)
        before = copy.deepcopy(decision['inputContext'])
        out = self.root / 'out'
        summary = module.export([self.write(rows)], out)
        dataset = json.loads((out / 'dataset.jsonl').read_text())
        exported_choice = json.loads((out / 'choices.jsonl').read_text())
        self.assertEqual(dataset['decision']['inputContext'], before)
        self.assertEqual(exported_choice['start']['choice']['inputContext'], choice['inputContext'])
        self.assertNotIn('Private chooser hand', json.dumps(dataset['decision']['inputContext']))
        self.assertEqual(summary['inputContextOwnerStatuses'], {'DECISION:CAPTURED': 1, 'CHOICE:CAPTURED': 1})
        self.assertFalse(dataset['trainingReady'])
        self.assertFalse(exported_choice['trainingReady'])
        self.assertIn('Only inputContext.policy', (out / 'coverage.md').read_text())

    def test_partial_and_unavailable_have_explicit_omissions_and_legacy_absence_is_separate(self):
        for status, reason in (('PARTIAL', 'OUTSIDE_CAPTURED_PREFIX'), ('UNAVAILABLE', 'BUDGET_EXHAUSTED'),
                               ('UNAVAILABLE', 'CAPTURE_FAILED'), ('UNAVAILABLE', 'INTERRUPTED')):
            rows = choices_fixture()
            context = input_context()
            context.update(status=status, omissions=[dict(component='CONTEXT', reason=reason)])
            if status == 'UNAVAILABLE':
                context['policy'] = None
                context['coverage'].update(playerView='UNAVAILABLE', disclosures='UNAVAILABLE')
                context['diagnostics'] = dict(playerBindings=[], cardBindings=[])
            rows[2]['choice']['inputContext'] = context
            summary = module.export([self.write(rows, status + reason + '.jsonl')])
            self.assertEqual(summary['inputContextStatuses'], {'ABSENT': 1, status: 1})
            self.assertEqual(summary['inputContextOmissions'], {'CONTEXT:' + reason: 1})

    def test_unknown_versions_and_malformed_policy_bindings_perspectives_and_coverage_are_rejected(self):
        mutations = {
            'boolean-version': lambda c: c.update(schemaVersion=True),
            'future-version': lambda c: c.update(schemaVersion=4),
            'future-profile': lambda c: c.update(profile='PERMITTED_CHOICE_VIEW_V2'),
            'wrong-chooser': lambda c: c['perspective'].update(chooserPlayerId=0),
            'controlled-policy': lambda c: c['perspective'].update(controllingPlayerId=0),
            'unknown-controller': lambda c: c['perspective'].update(controllingPlayerId=999),
            'raw-id-feature': lambda c: c['policy'].update(cardId=8),
            'future-outcome-feature': lambda c: c['policy'].update(winner=1),
            'diagnostic-in-policy': lambda c: c['policy']['players'][0].update(id=0),
            'unbound-chooser': lambda c: c['policy'].update(chooser='p9'),
            'wrong-binding': lambda c: c['diagnostics']['playerBindings'][1].update(entityRef='p0'),
            'foreign-binding': lambda c: c['diagnostics']['playerBindings'][0].update(id=999),
            'duplicate-binding': lambda c: c['diagnostics']['playerBindings'][1].update(id=0),
            'missing-card-binding': lambda c: c['diagnostics'].update(cardBindings=[]),
            'raw-card-reference': lambda c: c['policy']['cards'][0].update(entityRef='engine:8'),
            'unknown-owner': lambda c: c['policy']['cards'][0].update(owner='p9'),
            'private-hidden-name': lambda c: c['policy']['cards'][0].update(faceDown=True, identityKnown=False),
            'unknown-face-up-identity': lambda c: c['policy']['cards'][0].update(faceDown=False, identityKnown=False, name=None),
            'unbounded-mana': lambda c: c['policy']['players'][0].update(floatingManaColors=[1] * 129),
            'bad-mana': lambda c: c['policy']['players'][0].update(floatingManaColors=[3]),
            'captured-omission': lambda c: c.update(omissions=[dict(component='CONTEXT', reason='INTERRUPTED')]),
            'partial-no-omission': lambda c: c.update(status='PARTIAL'),
            'unavailable-with-policy': lambda c: c.update(status='UNAVAILABLE'),
            'unknown-timing': lambda c: c.update(timing='AFTER_RESULT'),
            'wrong-boundary-timing': lambda c: c.update(timing='BEFORE_SELECTOR'),
            'missing-state-basis': lambda c: c.update(stateBasis='UNESTABLISHED'),
            'full-history-claim': lambda c: c['coverage'].update(history='COMPLETE'),
            'full-stack-claim': lambda c: c['coverage'].update(stack='COMPLETE'),
            'unavailable-disclosures-with-policy': lambda c: c['coverage'].update(disclosures='UNAVAILABLE'),
            'option-identity-claim': lambda c: c['coverage'].update(options='ALL_LEGAL'),
            'unknown-disclosure-association': lambda c: c['policy']['disclosedFacts'][0].update(currentObjectAssociation='EXACT'),
            'disclosure-engine-id': lambda c: c['policy']['disclosedFacts'][0].update(id=8),
            'disclosure-scope': lambda c: c['policy']['disclosedFacts'][0].update(scope='CURRENT_PROMPT'),
            'disclosure-live-card': lambda c: c['policy']['disclosedFacts'][0].update(card=dict(id=8)),
            'unbounded-text': lambda c: c['policy']['disclosedFacts'][0].update(name='a' * 257),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                rows = choices_fixture()
                context = input_context()
                known_card(context)
                disclosure(context)
                mutate(context)
                rows[2]['choice']['inputContext'] = context
                with self.assertRaises(module.InvalidData):
                    module.export([self.write(rows, name + '.jsonl')])

    def test_masked_public_face_is_allowed_but_it_cannot_disclose_the_hidden_name(self):
        rows = choices_fixture()
        context = input_context()
        card = known_card(context, zone='Battlefield')
        card.update(faceDown=True, identityKnown=False, name=None, types='Creature', manaCost='no cost', power=2, toughness=2)
        rows[2]['choice']['inputContext'] = context
        self.assertEqual(module.export([self.write(rows)])['inputContextStatuses'], {'ABSENT': 1, 'CAPTURED': 1})
        card['name'] = 'Hidden front face'
        with self.assertRaises(module.InvalidData): module.export([self.write(rows, 'hidden.jsonl')])

    def test_parent_context_is_validated_and_cannot_borrow_the_childs_private_view(self):
        rows = fixture()
        rows[2]['decision']['inputContext'] = input_context(chooser=1)
        with self.assertRaises(module.InvalidData): module.export([self.write(rows)])
        rows[2]['decision']['inputContext'] = input_context(chooser=0)
        rows[2]['decision']['inputContext']['stateBasis'] = 'PLANNING_WORKING_STATE'
        with self.assertRaises(module.InvalidData): module.export([self.write(rows, 'planning-baseline.jsonl')])
        for field, wrong in (('turn', 99), ('phase', 'MAIN2'), ('turnPlayer', 'p1')):
            rows[2]['decision']['inputContext'] = input_context(chooser=0)
            rows[2]['decision']['inputContext']['policy'][field] = wrong
            with self.assertRaises(module.InvalidData): module.export([self.write(rows, 'wrong-' + field + '.jsonl')])

    def test_cached_result_and_evaluation_boundaries_cannot_claim_fresh_rules_input(self):
        rows = choices_fixture()
        rows[2]['choice']['inputContext'] = input_context()
        rows[2]['choice']['stage'] = 'CANDIDATE_EVALUATION'
        with self.assertRaises(module.InvalidData): module.export([self.write(rows, 'false-rules.jsonl')])
        rows[2]['choice']['stage'] = 'CONTROLLER_CALLBACK'
        rows[2]['choice'].update(choiceType='NUMBER_SELECTION', boundary='CONTROLLER_ANNOUNCEMENT',
                                options=dict(coverage='OBSERVED_RANGE', minimum=0, maximum=2))
        rows[3]['choice']['selection'] = dict(value=1, optionIndex=None, domainMatch='WITHIN_OBSERVED_DOMAIN')
        # Announcement is an audited outer operation, not necessarily a fresh selector.
        self.assertEqual(module.export([self.write(rows, 'announcement-operation.jsonl')])['inputContextRecords'], 1)
        rows[2]['choice']['inputContext']['timing'] = 'BEFORE_SELECTOR'
        with self.assertRaises(module.InvalidData): module.export([self.write(rows, 'late-input.jsonl')])

    def test_disclosure_superset_does_not_infer_membership_or_backfill_parent_and_full_filtered_views_match(self):
        rows = choices_fixture()
        start, terminal = rows[2]['choice'], rows[3]['choice']
        refs = [dict(type='CARD', id=i, gameTimestamp=10 + i) for i in range(2)]
        start.update(choiceType='EFFECT_ENTITY_SELECTION', boundary='CONTROLLER_EFFECT_ENTITY',
                     options=dict(coverage='OBSERVED_EFFECT_OPTIONS_DIAGNOSTIC_ONLY', items=refs,
                                  truncated=False, optional=False, relatedPlayerId=0),
                     inputContext=input_context(timing='BEFORE_SELECTOR'))
        for i in range(5):
            fact = disclosure(input_context(), name='Disclosed ' + str(i))
            fact['entityRef'] = 'd' + str(i)
            start['inputContext']['policy']['disclosedFacts'].append(fact)
        start['inputContext']['coverage']['disclosures'] = 'STACK_RESOLUTION'
        terminal['selection'] = dict(selected=refs[0], selectedOptionIndex=0, match='UNIQUE')
        rows[0]['streamKind'] = 'AI_DECISIONS'
        full = copy.deepcopy(rows)
        full[0]['streamKind'] = 'FULL_GAME_TRACE'
        full.insert(4, dict(type='SHUFFLE', game=1, eventIndex=4, cards=['Hidden future order']))
        for i, row in enumerate(full[1:-1], 1): row['eventIndex'] = i
        # Preserve source ordering indices for retained records as filtered CLI streams do.
        rows[0]['eventIndexMode'] = 'SHARED_WITH_GAME_TRACE'
        for row in rows[4:-1]: row['eventIndex'] += 1
        filtered = module.load_run(self.write(rows, 'filtered.jsonl'))
        full_run = module.load_run(self.write(full, 'full.jsonl'))
        filtered_choices = list(module._choices.rows([filtered]))
        full_choices = list(module._choices.rows([full_run]))
        self.assertEqual(filtered_choices, full_choices)
        context = full_choices[0]['start']['choice']['inputContext']
        self.assertEqual(len(context['policy']['disclosedFacts']), 5)
        self.assertEqual(len(full_choices[0]['start']['choice']['options']['items']), 2)
        self.assertNotIn('items', context['policy'])
        self.assertNotIn('Hidden future order', json.dumps(context))
        self.assertNotIn('inputContext', next(row['decision'] for row in full if row['type'] == 'AI_DECISION'))

    def test_zone_change_selection_preserves_original_references_and_validates_exact_match(self):
        rows = choices_fixture()
        card = dict(type='CARD', id=1 << 41, gameTimestamp=1 << 51)
        rows[2]['choice'].update(choiceType='ZONE_CHANGE_CARD_SELECTION', boundary='CONTROLLER_ZONE_CHANGE_CARD',
                                options=dict(coverage='OBSERVED_ZONE_CHANGE_OPTIONS_DIAGNOSTIC_ONLY', items=[card],
                                             truncated=False, optional=False, destination='Hand', origins=['Library'], deciderPlayerId=1),
                                inputContext=input_context(timing='BEFORE_SELECTOR'))
        rows[3]['choice']['selection'] = dict(selected=card, selectedOptionIndex=0, match='UNIQUE')
        self.assertEqual(module.export([self.write(rows)])['standaloneChoiceTypes'], {'ZONE_CHANGE_CARD_SELECTION': 1})
        rows[3]['choice']['selection']['selectedOptionIndex'] = None
        with self.assertRaises(module.InvalidData): module.export([self.write(rows, 'unmatched.jsonl')])

    def test_interrupted_empty_option_prefix_preserves_return_without_inventing_a_match(self):
        rows = choices_fixture()
        context = input_context(timing='BEFORE_SELECTOR')
        context.update(status='UNAVAILABLE', policy=None, diagnostics=dict(playerBindings=[], cardBindings=[]),
                       omissions=[dict(component='CONTEXT', reason='INTERRUPTED')])
        context['coverage'].update(playerView='UNAVAILABLE', disclosures='UNAVAILABLE')
        rows[2]['choice'].update(choiceType='EFFECT_ENTITY_SELECTION', boundary='CONTROLLER_EFFECT_ENTITY',
                                options=dict(coverage='UNAVAILABLE', items=[],
                                             truncated=True, optional=False, relatedPlayerId=None),
                                inputContext=context)
        selected = dict(type='CARD', id=15, gameTimestamp=20)
        rows[3]['choice']['selection'] = dict(selected=selected, selectedOptionIndex=None, match='UNAVAILABLE')
        out = self.root / 'interrupted'
        summary = module.export([self.write(rows)], out)
        result = json.loads((out / 'choices.jsonl').read_text())
        self.assertEqual(result['terminal']['choice']['selection']['selected'], selected)
        self.assertEqual(summary['inputContextOmissions'], {'CONTEXT:INTERRUPTED': 1})
        rows[3]['choice']['selection']['match'] = 'UNIQUE'
        with self.assertRaises(module.InvalidData): module.export([self.write(rows, 'invented-match.jsonl')])
        rows[3]['choice']['selection']['match'] = 'UNAVAILABLE'
        rows[2]['choice']['options']['items'] = [selected]
        with self.assertRaises(module.InvalidData): module.export([self.write(rows, 'invented-pool.jsonl')])


if __name__ == '__main__':
    unittest.main()
