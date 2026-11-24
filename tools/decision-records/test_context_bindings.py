import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_prompt_input_context import rows3
from test_input_context import known_card, disclosure


def rows4():
    rows = rows3(); start = rows[2]['choice']; context = start['inputContext']
    context.update(schemaVersion=4, profile='PERMITTED_CHOICE_VIEW_V4')
    context['policy']['prompt'] = None; context['coverage']['prompt'] = 'UNSUPPORTED'
    card = known_card(context, zone='Battlefield', name='Island')
    binding = context['diagnostics']['cardBindings'][0]
    start.update(choiceType='EFFECT_ENTITY_SELECTION', boundary='CONTROLLER_EFFECT_ENTITY',
                 options=dict(coverage='OBSERVED_EFFECT_OPTIONS_DIAGNOSTIC_ONLY',
                              items=[dict(type='CARD', id=binding['id'], gameTimestamp=binding['gameTimestamp'])],
                              truncated=False, optional=False, relatedPlayerId=None))
    context['diagnostics']['optionBindings'] = dict(coverage='DIAGNOSTIC_PUBLIC_CURRENT_V1',
        items=[dict(optionIndex=0, entityRef=card['entityRef'])], truncated=False)
    rows[3]['choice']['selection'] = dict(selected=copy.deepcopy(start['options']['items'][0]), selectedOptionIndex=0, match='UNIQUE')
    return rows


class ContextBindingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.index = 0

    def export(self, rows):
        self.index += 1; path = self.root / f'{self.index}.jsonl'
        path.write_text(''.join(json.dumps(row) + '\n' for row in rows))
        out = self.root / f'out-{self.index}'
        return module.export([path], out), out

    def test_links_stay_diagnostic_and_do_not_certify_training_readiness(self):
        rows = rows4(); summary, out = self.export(rows)
        row = json.loads((out/'choices.jsonl').read_text())
        self.assertFalse(row['trainingReady'])
        self.assertNotIn('optionBindings', row['start']['choice']['inputContext']['policy'])
        self.assertEqual(summary['inputContextProfiles'], {'PERMITTED_CHOICE_VIEW_V4': 1})
        self.assertEqual(summary['inputContextBindings'], dict(contextRecords=1,
            **{'recordCoverage:DIAGNOSTIC_PUBLIC_CURRENT_V1': 1}, optionEntries=1, publicOptionLinks=1, unresolvedOptionEntries=0))

    def test_private_stale_and_wrong_identity_links_are_rejected(self):
        for mutation in ('private', 'timestamp', 'id', 'ordinal', 'prefix', 'unknown-ref', 'raw-id', 'wrong-version'):
            rows = rows4(); c = rows[2]['choice']['inputContext']; links = c['diagnostics']['optionBindings']
            if mutation == 'private': c['policy']['cards'][0]['zone'] = 'Hand'
            if mutation == 'timestamp': rows[2]['choice']['options']['items'][0]['gameTimestamp'] += 1
            if mutation == 'id': rows[2]['choice']['options']['items'][0]['id'] += 1
            if mutation == 'ordinal': links['items'][0]['optionIndex'] = True
            if mutation == 'prefix': links['truncated'] = True
            if mutation == 'unknown-ref': links['items'][0]['entityRef'] = 'c9'
            if mutation == 'raw-id': links['items'][0]['id'] = 42
            if mutation == 'wrong-version': c.update(schemaVersion=3, profile='PERMITTED_CHOICE_VIEW_V3')
            with self.subTest(mutation=mutation), self.assertRaises(module.InvalidData): self.export(rows)

    def test_duplicates_and_unresolved_entries_do_not_invent_unique_option_positions(self):
        rows = rows4(); start = rows[2]['choice']; links = start['inputContext']['diagnostics']['optionBindings']
        start['options']['items'] *= 2; links['items'].append(dict(optionIndex=1, entityRef='c0'))
        rows[3]['choice']['selection'].update(selectedOptionIndex=None, match='AMBIGUOUS')
        self.export(rows)
        for link in links['items']: link['entityRef'] = None
        self.export(rows)

    def test_direct_public_disclosure_links_need_matching_public_face_and_zone(self):
        rows = rows4(); c = rows[2]['choice']['inputContext']; fact = disclosure(c, name='Island')
        fact.update(source='CONTROLLER_CARD_REVEAL', zone='Battlefield', currentObjectAssociation='c0')
        c['coverage']['disclosures'] = fact['scope']
        self.export(rows)
        for mutation in ('zone', 'name', 'face-down', 'hidden', 'planning', 'view-only', 'duplicate', 'truncated'):
            bad = copy.deepcopy(rows); context = bad[2]['choice']['inputContext']; card = context['policy']['cards'][0]
            if mutation == 'zone': card['zone'] = 'Library'
            if mutation == 'name': card['name'] = 'Forest'
            if mutation == 'face-down': card['faceDown'] = True
            if mutation == 'hidden': card.update(faceDown=True, identityKnown=False, name=None)
            if mutation == 'planning': context['stateBasis'] = 'PLANNING_WORKING_STATE'
            if mutation == 'view-only': context['policy']['disclosedFacts'][0]['source'] = 'CONTROLLER_VIEW_REVEAL'
            if mutation == 'duplicate':
                duplicate = copy.deepcopy(card); duplicate['entityRef'] = 'c1'; context['policy']['cards'].append(duplicate)
                context['diagnostics']['cardBindings'].append(dict(entityRef='c1', id=99, gameTimestamp=100))
            if mutation == 'truncated': context.update(status='PARTIAL', omissions=[dict(component='ENTITIES', reason='OUTSIDE_CAPTURED_PREFIX')])
            with self.subTest(mutation=mutation), self.assertRaises(module.InvalidData): self.export(bad)

    def test_missing_or_unavailable_links_never_get_filled_from_diagnostic_ids(self):
        rows = rows4(); c = rows[2]['choice']['inputContext']
        c['diagnostics']['optionBindings'] = dict(coverage='UNSUPPORTED', items=[], truncated=False)
        self.export(rows)
        c['policy'] = None; c['status'] = 'UNAVAILABLE'; c['omissions'] = [dict(component='CONTEXT', reason='INTERRUPTED')]
        c['coverage'].update(playerView='UNAVAILABLE', disclosures='UNAVAILABLE', stack='UNAVAILABLE', prompt='UNAVAILABLE')
        c['diagnostics'] = dict(playerBindings=[], cardBindings=[], stackBindings=[],
            optionBindings=dict(coverage='UNAVAILABLE', items=[], truncated=True))
        self.export(rows)
        c['diagnostics']['optionBindings']['items'] = [dict(optionIndex=0, entityRef='c0')]
        with self.assertRaises(module.InvalidData): self.export(rows)
