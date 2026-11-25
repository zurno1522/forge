import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_context_bindings import rows4
from test_entry_location import entry


def zone_fixture():
    rows = rows4(); choice = rows[2]['choice']; context = choice['inputContext']
    choice.update(choiceType='ZONE_CHANGE_CARD_SELECTION', boundary='CONTROLLER_ZONE_CHANGE_CARD', entryLocation=entry())
    choice['options'].pop('relatedPlayerId')
    choice['options'].update(coverage='OBSERVED_ZONE_CHANGE_OPTIONS_DIAGNOSTIC_ONLY', destination='Hand',
                            origins=['Sideboard', 'Hand', 'Hand'], optional=True, deciderPlayerId=0)
    context.update(schemaVersion=8, profile='PERMITTED_CHOICE_VIEW_V8')
    context['coverage']['prompt'] = 'RECEIVED_ZONE_REQUEST_V1'
    context['policy']['prompt'] = dict(kind='ZONE_CHANGE_REQUEST', minimumPicks=0, maximumPicks=1,
        optional=True, destination='Hand', origins=['Sideboard', 'Hand', 'Hand'], decider='p0')
    return rows


class ZoneRequestInputTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.index = 0

    def export(self, rows):
        self.index += 1; source = self.root/f'{self.index}.jsonl'; out = self.root/f'out-{self.index}'
        source.write_text(''.join(json.dumps(r)+'\n' for r in rows))
        result = module.export([source], out)
        self.assertEqual(result['inputCoverageAudit'], module.export([source])['inputCoverageAudit'])
        return result, out

    def test_received_request_order_and_duplicates_survive_export_without_becoming_a_movement_or_history(self):
        rows = zone_fixture(); result, out = self.export(rows)
        choice = json.loads((out/'choices.jsonl').read_text())
        self.assertEqual(choice['start']['choice']['inputContext']['policy']['prompt'], rows[2]['choice']['inputContext']['policy']['prompt'])
        self.assertFalse(choice['trainingReady'])
        self.assertNotIn('entitySelectionHistory', result)
        self.assertTrue(any('V8' in text and 'request' in text for text in result['limitations']))

    def test_none_empty_null_role_and_planning_requests_are_explicit(self):
        for planning in (False, True):
            rows = zone_fixture(); choice = rows[2]['choice']; context = choice['inputContext']
            choice['options'].update(destination='None', origins=[], deciderPlayerId=None)
            context['policy']['prompt'].update(destination='None', origins=[], decider=None)
            if planning:
                choice['stage']='CANDIDATE_EVALUATION'
                context['stateBasis']=choice['entryLocation']['stateBasis']='PLANNING_WORKING_STATE'
            self.export(rows)

    def test_mismatched_or_invented_request_arguments_and_permissions_are_rejected(self):
        for error in ('old-profile','entry','boundary','timing','kind-coverage','optional','pick-min','pick-max','origin-order',
                      'destination','decider','unknown-decider','extra','history','bad-zone','bad-origin','too-many-origins','boolean-pick',
                      'destination-type','origin-type','decider-type'):
            rows = zone_fixture(); choice = rows[2]['choice']; context = choice['inputContext']; prompt=context['policy']['prompt']
            if error=='old-profile': context.update(schemaVersion=7,profile='PERMITTED_CHOICE_VIEW_V7')
            if error=='entry': choice.pop('entryLocation')
            if error=='boundary':
                choice.update(choiceType='EFFECT_ENTITY_SELECTION',boundary='CONTROLLER_EFFECT_ENTITY')
                choice['options']={k:v for k,v in choice['options'].items() if k not in ('destination','origins','deciderPlayerId')}
                choice['options'].update(coverage='OBSERVED_EFFECT_OPTIONS_DIAGNOSTIC_ONLY',relatedPlayerId=0)
            if error=='timing': context['timing']='BEFORE_OPERATION'
            if error=='kind-coverage': context['coverage']['prompt']='CONTROLLER_REQUEST_V1'
            if error=='optional': prompt['optional']=False
            if error=='pick-min': prompt['minimumPicks']=1
            if error=='pick-max': prompt['maximumPicks']=2
            if error=='origin-order': prompt['origins']=['Hand','Hand','Sideboard']
            if error=='destination': prompt['destination']='Exile'
            if error=='decider': prompt['decider']='p1'
            if error=='unknown-decider': prompt['decider']='p9'
            if error=='extra': prompt['entityPool']=['c0']
            if error=='history': prompt['priorSelectionCount']=0
            if error=='bad-zone': prompt['destination']='FutureZone'
            if error=='bad-origin': prompt['origins']=['FutureZone']
            if error=='too-many-origins': prompt['origins']=['Hand']*65
            if error=='boolean-pick': prompt['maximumPicks']=True
            if error=='destination-type': prompt['destination']=[]
            if error=='origin-type': prompt['origins']=[{}]
            if error=='decider-type': prompt['decider']=[]
            with self.subTest(error=error), self.assertRaises(module.InvalidData): self.export(rows)

    def test_unavailable_request_does_not_hide_the_record_and_legacy_does_not_gain_permission(self):
        rows=zone_fixture(); context=rows[2]['choice']['inputContext']
        context.update(status='PARTIAL'); context['policy']['prompt']=None;context['coverage']['prompt']='UNAVAILABLE'
        context['omissions'].append(dict(component='PROMPT',reason='UNRESOLVED_BINDING'))
        self.export(rows)
        result,_=self.export(rows4())
        self.assertFalse(any('V8' in text for text in result['limitations']))
