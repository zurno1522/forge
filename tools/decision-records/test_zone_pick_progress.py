import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_zone_operation import operation_fixture
from test_zone_request_input import zone_fixture
from test_entry_location import entry
from test_context_bindings import rows4
from test_multi_entity_choices import multi_fixture


def progress_fixture():
    rows = operation_fixture(); parent = rows[2]['choice']
    parent['options']['requestedIterations'] = parent['inputContext']['policy']['prompt']['requestedIterations'] = 2
    children = zone_fixture()[2:4]; child = children[0]['choice']; context = child['inputContext']
    receipt = copy.deepcopy(child['options']['items'][0])
    child.update(choiceId='zone-progress-2', parentChoiceId=parent['choiceId'], entryLocation=entry())
    child['options'].update(destination='Hand', origins=copy.deepcopy(parent['options']['origins']), deciderPlayerId=1,
        zoneSelectionProgress=dict(schemaVersion=1, progressCoverage='COMPLETE', progressReason=None, attemptIndex=2,
            loopPosition=1, priorNonNullReturnCount=1, retainedCount=1, requestedIterations=2,
            receiptCoverage='COMPLETE', receiptReason=None, priorReturns=[receipt]))
    context.update(schemaVersion=10, profile='PERMITTED_CHOICE_VIEW_V10')
    context['coverage']['prompt'] = 'RECEIVED_ZONE_PROGRESS_V1'
    context['policy']['prompt'] = dict(kind='ZONE_PICK_PROGRESS', minimumPicks=0, maximumPicks=1, optional=True,
        requestedIterations=2, destination='Hand', origins=copy.deepcopy(parent['options']['origins']),
        sourcePlayer='p0', decider='p1', attemptIndex=2, loopPosition=1, priorNonNullReturnCount=1,
        retainedCount=1, previousReturns=['c0'])
    children[1]['choice']['choiceId'] = child['choiceId']
    rows[3]['choice']['selection'].update(items=[],selectedCount=0)
    rows[3:3] = children
    for index,row in enumerate(rows[1:-1],1): row['eventIndex']=index
    return rows


def add_following(rows, completed=True):
    child=copy.deepcopy(rows[3:5]);start=child[0]['choice'];progress=start['options']['zoneSelectionProgress'];prompt=start['inputContext']['policy']['prompt']
    start['choiceId']='zone-progress-3';child[1]['choice']['choiceId']=start['choiceId']
    progress.update(attemptIndex=3,priorNonNullReturnCount=2 if completed else 1,
                    priorReturns=progress['priorReturns']*(2 if completed else 1))
    prompt.update(attemptIndex=3,priorNonNullReturnCount=2 if completed else 1,previousReturns=['c0']*(2 if completed else 1))
    if not completed: rows[4]['choice'].update(status='DECLINED',selection=None)
    rows[5:5]=child
    for index,row in enumerate(rows[1:-1],1): row['eventIndex']=index
    return rows


class ZonePickProgressTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.index=0

    def export(self,rows):
        self.index+=1;source=self.root/f'{self.index}.jsonl';out=self.root/f'out-{self.index}'
        source.write_text(''.join(json.dumps(r)+'\n' for r in rows))
        result=module.export([source],out);materialized=module.export([source])
        self.assertEqual(result['inputCoverageAudit'],materialized['inputCoverageAudit'])
        self.assertEqual(result.get('zoneSelectionProgress'),materialized.get('zoneSelectionProgress'))
        return result,out

    def test_history_does_not_require_missing_earlier_rows_or_a_final_retained_pile(self):
        rows=progress_fixture();result,out=self.export(rows)
        values=[json.loads(line) for line in (out/'choices.jsonl').read_text().splitlines()]
        child=next(v for v in values if v['choiceId']=='zone-progress-2')
        self.assertFalse(child['trainingReady']);self.assertEqual(child['start']['choice'],rows[3]['choice'])
        self.assertEqual(result['zoneSelectionProgress']['counts']['resolvedEntries'],1)
        self.assertEqual(result['zoneSelectionProgress']['counts']['priorReturnsAcrossRecords'],1)
        self.assertIsNone(result['zoneSelectionProgress']['unobservedAttemptCount'])
        self.assertTrue(any('V10' in text for text in result['limitations']))

    def test_declined_retries_duplicate_returns_and_unresolved_private_identities_keep_distinct_counts(self):
        self.export(add_following(progress_fixture(),True))
        self.export(add_following(progress_fixture(),False))
        rows=progress_fixture();context=rows[3]['choice']['inputContext'];context['policy']['cards'][0]['zone']='Hand'
        context['policy']['prompt']['previousReturns']=[None]
        for link in context['diagnostics']['optionBindings']['items']:link['entityRef']=None
        result,_=self.export(rows)
        self.assertEqual(result['zoneSelectionProgress']['counts']['unresolvedEntries'],1)

    def test_long_ordinals_and_incomplete_receipts_never_claim_a_complete_prompt(self):
        rows=progress_fixture();choice=rows[3]['choice'];meta=choice['options']['zoneSelectionProgress'];prompt=choice['inputContext']['policy']['prompt']
        meta['attemptIndex']=prompt['attemptIndex']=(1<<63)-1;self.export(rows)
        for reason in ('OUTSIDE_CAPTURED_PREFIX','CAPTURE_FAILED','UNSUPPORTED','COUNTER_OVERFLOW'):
            rows=progress_fixture();choice=rows[3]['choice'];context=choice['inputContext'];meta=choice['options']['zoneSelectionProgress']
            meta.update(receiptCoverage='PREFIX' if reason=='OUTSIDE_CAPTURED_PREFIX' else 'UNAVAILABLE',receiptReason=reason)
            if reason=='OUTSIDE_CAPTURED_PREFIX':meta.update(attemptIndex=66,priorNonNullReturnCount=65,priorReturns=meta['priorReturns']*64)
            else:meta['priorReturns']=[]
            if reason=='COUNTER_OVERFLOW':meta.update(progressCoverage='UNAVAILABLE',progressReason=reason,attemptIndex=None,priorNonNullReturnCount=None)
            context.update(status='PARTIAL');context['policy']['prompt']=None;context['coverage']['prompt']='UNAVAILABLE'
            context['omissions'].append(dict(component='PROMPT',reason=reason));self.export(rows)

    def test_malformed_ownership_counts_permissions_and_public_associations_are_rejected(self):
        for error in ('old-profile','missing-entry','missing-parent','parent-type','actor','ability','iterations','origin','decider','source',
                      'index-bool','index-negative','index-too-large','returns','retained','prompt-count','prompt-optional','receipt-ts',
                      'receipt-player','private-link','unknown-ref','extra-prompt','extra-component','false-complete','reason','old-entity-history'):
            rows=progress_fixture();choice=rows[3]['choice'];context=choice['inputContext'];meta=choice['options']['zoneSelectionProgress'];prompt=context['policy']['prompt']
            if error=='old-profile':context.update(schemaVersion=9,profile='PERMITTED_CHOICE_VIEW_V9')
            if error=='missing-entry':choice.pop('entryLocation')
            if error=='missing-parent':choice['parentChoiceId']=None
            if error=='parent-type':
                other=multi_fixture();other[2]['choice']['choiceId']=rows[2]['choice']['choiceId'];other[3]['choice']['choiceId']=rows[2]['choice']['choiceId']
                rows[2]=other[2];rows[5]=other[3]
                for index,row in enumerate(rows[1:-1],1):row['eventIndex']=index
            if error=='actor':choice['chooserPlayerId']=0;context['perspective'].update(chooserPlayerId=0,controllingPlayerId=0)
            if error=='ability':choice['abilityId']+=1
            if error=='iterations':meta['requestedIterations']=prompt['requestedIterations']=3
            if error=='origin':choice['options']['origins']=prompt['origins']=['Exile']
            if error=='decider':choice['options']['deciderPlayerId']=0;prompt['decider']='p0'
            if error=='source':prompt['sourcePlayer']='p1'
            if error=='index-bool':meta['attemptIndex']=True
            if error=='index-negative':meta['attemptIndex']=-1
            if error=='index-too-large':meta['attemptIndex']=1<<63
            if error=='returns':meta['priorNonNullReturnCount']=3
            if error=='retained':prompt['retainedCount']=4
            if error=='prompt-count':prompt['priorNonNullReturnCount']=0
            if error=='prompt-optional':prompt['optional']=False
            if error=='receipt-ts':meta['priorReturns'][0]['gameTimestamp']+=1
            if error=='receipt-player':meta['priorReturns']=[dict(type='PLAYER',id=0)]
            if error=='private-link':
                context['policy']['cards'][0]['zone']='Hand'
                for link in context['diagnostics']['optionBindings']['items']:link['entityRef']=None
            if error=='unknown-ref':prompt['previousReturns']=['c99']
            if error=='extra-prompt':prompt['offered']=['c0']
            if error=='extra-component':meta['otherId']=123
            if error=='false-complete':meta['receiptCoverage']='PREFIX'
            if error=='reason':meta['receiptReason']='CAPTURE_FAILED'
            if error=='old-entity-history':choice['options']['selectionSequence']=dict(meta)
            with self.subTest(error=error),self.assertRaises(module.InvalidData):self.export(rows)

    def test_exact_attempt_progress_is_monotonic_but_missing_rows_and_missing_terminals_are_unknown(self):
        for error in ('repeat','reverse','missing-success','invented-return','error-continued'):
            rows=add_following(progress_fixture());first=rows[3]['choice'];second=rows[5]['choice'];meta=second['options']['zoneSelectionProgress'];prompt=second['inputContext']['policy']['prompt']
            if error in ('repeat','reverse'):meta['attemptIndex']=prompt['attemptIndex']=2 if error=='repeat' else 1
            if error=='missing-success':meta.update(priorNonNullReturnCount=1,priorReturns=meta['priorReturns'][:1]);prompt.update(priorNonNullReturnCount=1,previousReturns=['c0'])
            if error=='invented-return':meta.update(priorNonNullReturnCount=3,priorReturns=meta['priorReturns']+meta['priorReturns'][:1]);prompt.update(priorNonNullReturnCount=3,previousReturns=['c0']*3)
            if error=='error-continued':rows[4]['choice'].update(status='ERROR',selection=None)
            with self.subTest(error=error),self.assertRaises(module.InvalidData):self.export(rows)
        rows=add_following(progress_fixture());meta=rows[5]['choice']['options']['zoneSelectionProgress'];prompt=rows[5]['choice']['inputContext']['policy']['prompt']
        meta['attemptIndex']=prompt['attemptIndex']=9;self.export(rows)
        rows=add_following(progress_fixture());rows.pop(4)
        next(row for row in rows if row['type']=='GAME_EXECUTION_FINISHED')['status']='TIMEOUT'
        rows[-1].update(status='TIMEOUT',gamesNormal=0,gamesTimedOut=1)
        for index,row in enumerate(rows[1:-1],1):row['eventIndex']=index
        self.export(rows)

    def test_legacy_records_do_not_gain_progress_or_overflow_vocabulary(self):
        result,_=self.export(operation_fixture());self.assertNotIn('zoneSelectionProgress',result)
        self.assertFalse(any('V10' in text for text in result['limitations']))
        rows=rows4();context=rows[2]['choice']['inputContext'];context.update(schemaVersion=9,profile='PERMITTED_CHOICE_VIEW_V9',status='PARTIAL')
        context['policy']['prompt']=None;context['coverage']['prompt']='UNAVAILABLE';context['omissions'].append(dict(component='PROMPT',reason='COUNTER_OVERFLOW'))
        with self.assertRaises(module.InvalidData):self.export(rows)
