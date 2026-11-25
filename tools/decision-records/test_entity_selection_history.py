import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_context_bindings import rows4
from test_entry_location import entry
from test_multi_entity_choices import multi_fixture


def history_fixture():
    rows = multi_fixture(); parent = rows[2]['choice']; child = rows4()[2:4]
    start, end = child[0]['choice'], child[1]['choice']; context = start['inputContext']
    receipt = copy.deepcopy(start['options']['items'][0])
    start.update(choiceId='history-child', parentChoiceId=parent['choiceId'], entryLocation=entry())
    start['options'].update(items=[dict(type='PLAYER', id=0)], optional=True, relatedPlayerId=0,
        selectionSequence=dict(schemaVersion=1, coverage='COMPLETE', minimumTotal=0, maximumTotal=2,
                               priorSelectionCount=1, priorSelections=[receipt], reason=None))
    context.update(schemaVersion=7, profile='PERMITTED_CHOICE_VIEW_V7')
    context['policy']['prompt'] = dict(kind='ENTITY_PICK_SEQUENCE', minimumPicks=0, maximumPicks=1,
        optional=True, relatedPlayer='p0', minimumTotal=0, maximumTotal=2, priorSelectionCount=1, previousSelections=['c0'])
    context['coverage']['prompt'] = 'RECEIVED_ENTITY_SEQUENCE_V1'
    context['diagnostics']['optionBindings'] = dict(coverage='UNSUPPORTED', items=[], truncated=False)
    end.update(choiceId=start['choiceId'], selection=dict(selected=dict(type='PLAYER', id=0), selectedOptionIndex=0, match='UNIQUE'))
    rows[3:3] = child
    for i, row in enumerate(rows[1:-1], 1): row['eventIndex'] = i
    return rows


class EntitySelectionHistoryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.index = 0

    def export(self, rows):
        self.index += 1; source = self.root/f'{self.index}.jsonl'; out = self.root/f'out-{self.index}'
        source.write_text(''.join(json.dumps(r)+'\n' for r in rows))
        result = module.export([source], out)
        self.assertEqual(result['inputCoverageAudit'], module.export([source])['inputCoverageAudit'])
        return result, out

    def test_current_public_history_is_preserved_without_requiring_all_earlier_messages_or_readiness(self):
        rows = history_fixture(); result, out = self.export(rows)
        values = [json.loads(s) for s in (out/'choices.jsonl').read_text().splitlines()]
        child = next(v for v in values if v['choiceId']=='history-child')
        self.assertFalse(child['trainingReady'])
        self.assertEqual(child['start']['choice'], rows[3]['choice'])
        self.assertEqual(result['inputContextProfiles'], {'PERMITTED_CHOICE_VIEW_V6': 1, 'PERMITTED_CHOICE_VIEW_V7': 1})
        self.assertTrue(any('V7' in s and 'history' in s for s in result['limitations']))
        self.assertEqual(result['entitySelectionHistory']['counts'], dict(records=1, **{'coverage:COMPLETE': 1},
            priorReturnsAcrossRecords=1,receiptEntriesAcrossRecords=1,projectedPrompts=1,projectedEntries=1,resolvedEntries=1,unresolvedEntries=0))
        self.assertIsNone(result['entitySelectionHistory']['unobservedSequenceCount'])

    def test_unresolved_private_identity_is_a_known_return_not_an_empty_history(self):
        rows = history_fixture(); context = rows[3]['choice']['inputContext']
        context['policy']['cards'][0]['zone'] = 'Hand'
        context['policy']['prompt']['previousSelections'] = [None]
        _, out = self.export(rows)
        values = [json.loads(s) for s in (out/'choices.jsonl').read_text().splitlines()]
        child = next(v for v in values if v['choiceId']=='history-child')
        self.assertEqual(child['start']['choice']['inputContext']['policy']['prompt']['priorSelectionCount'], 1)
        self.assertEqual(child['start']['choice']['inputContext']['policy']['prompt']['previousSelections'], [None])

    def test_planning_permission_is_specific_and_legacy_v6_outputs_do_not_gain_history(self):
        rows = history_fixture(); choice = rows[3]['choice']
        choice['stage'] = 'CANDIDATE_EVALUATION'
        choice['entryLocation']['stateBasis'] = choice['inputContext']['stateBasis'] = 'PLANNING_WORKING_STATE'
        self.export(rows)
        result, _ = self.export(multi_fixture())
        self.assertFalse(any('V7' in s for s in result['limitations']))
        self.assertNotIn('entitySelectionHistory', result)

    def test_prefix_and_failed_history_do_not_emit_a_complete_prompt(self):
        for coverage, reason, count in (('PREFIX','OUTSIDE_CAPTURED_PREFIX',65), ('UNAVAILABLE','CAPTURE_FAILED',1), ('UNAVAILABLE','UNSUPPORTED',1)):
            rows = history_fixture(); parent=rows[2]['choice']; choice=rows[3]['choice']; context=choice['inputContext']
            sequence=choice['options']['selectionSequence']; receipt=sequence['priorSelections'][0]
            parent['options']['max']=67; parent['inputContext']['policy']['prompt']['maximumPicks']=67
            sequence.update(coverage=coverage, reason=reason, maximumTotal=67, priorSelectionCount=count,
                            priorSelections=[receipt]*64 if coverage=='PREFIX' else [])
            context.update(status='PARTIAL'); context['policy']['prompt']=None; context['coverage']['prompt']='UNAVAILABLE'
            context['omissions'].append(dict(component='PROMPT',reason=reason))
            rows[5]['choice']['selection'].update(selectedCount=count+1, items=[receipt]*count+[dict(type='PLAYER',id=0)], truncated=False)
            self.export(rows)

    def test_malformed_counts_ownership_scope_and_public_bindings_are_rejected(self):
        errors=('v6','missing-entry','missing-parent','owner-type','owner-actor','owner-ability','bounds','count','bool-count',
                'profile-kind','prompt-count','prompt-optional','optional','role','receipt-ts','receipt-id','private-link',
                'unknown-ref','extra-prompt','extra-sequence','prefix-complete','reason','raw-ref','outer-count','late-parent')
        for error in errors:
            rows=history_fixture(); parent=rows[2]['choice']; choice=rows[3]['choice']; context=choice['inputContext']
            sequence=choice['options']['selectionSequence']; prompt=context['policy']['prompt']
            if error=='v6': context.update(schemaVersion=6,profile='PERMITTED_CHOICE_VIEW_V6')
            if error=='missing-entry': choice.pop('entryLocation')
            if error=='missing-parent': choice['parentChoiceId']=None
            if error=='owner-type':
                plan=dict(abilities=[dict(abilityId=7,targets=[],targetsTruncated=False)],abilitiesTruncated=False)
                parent.update(choiceType='TARGET_SELECTION_RESULT',boundary='CONTROLLER_TARGETS',options=dict(coverage='PRIOR_TARGET_PLAN_ONLY',priorTargets=plan))
                parent['inputContext']['policy']['prompt']=None;parent['inputContext']['coverage']['prompt']='UNSUPPORTED'
                rows[5]['choice']['selection']=dict(accepted=True,targets=plan)
            if error=='owner-actor': choice['chooserPlayerId']=0; context['perspective'].update(chooserPlayerId=0,controllingPlayerId=0)
            if error=='owner-ability': choice['abilityId']+=1
            if error=='bounds': sequence['maximumTotal']=prompt['maximumTotal']=3
            if error=='count': sequence['priorSelectionCount']=2
            if error=='bool-count': sequence['priorSelectionCount']=True
            if error=='profile-kind': context['coverage']['prompt']='CONTROLLER_REQUEST_V1'
            if error=='prompt-count': prompt['priorSelectionCount']=0
            if error=='prompt-optional': prompt['optional']=False
            if error=='optional': choice['options']['optional']=False
            if error=='role': prompt['relatedPlayer']='p1'
            if error=='receipt-ts': sequence['priorSelections'][0]['gameTimestamp']+=1
            if error=='receipt-id': sequence['priorSelections'][0]['id']+=1
            if error=='private-link': context['policy']['cards'][0]['zone']='Hand'
            if error=='unknown-ref': prompt['previousSelections']=['c999']
            if error=='extra-prompt': prompt['previousSelectionIds']=[8]
            if error=='extra-sequence': sequence['decisionId']='invented'
            if error=='prefix-complete': sequence.update(coverage='PREFIX',reason='OUTSIDE_CAPTURED_PREFIX')
            if error=='reason': sequence['reason']='CAPTURE_FAILED'
            if error=='raw-ref': prompt['previousSelections']=[8]
            if error=='outer-count': rows[5]['choice']['selection'].update(items=[],selectedCount=0,truncated=False)
            if error=='late-parent': rows[3:6]=[rows[5],rows[3],rows[4]]
            for i,row in enumerate(rows[1:-1],1): row['eventIndex']=i
            with self.subTest(error=error),self.assertRaises(module.InvalidData): self.export(rows)

    def test_duplicate_sequence_position_is_rejected_but_a_missing_earlier_position_is_not_inferred(self):
        rows=history_fixture(); duplicate=copy.deepcopy(rows[3:5])
        for row in duplicate: row['choice']['choiceId']='duplicate-history'
        rows[5:5]=duplicate
        for i,row in enumerate(rows[1:-1],1): row['eventIndex']=i
        with self.assertRaises(module.InvalidData): self.export(rows)
