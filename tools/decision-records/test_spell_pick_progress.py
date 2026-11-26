import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_spell_request_input import request_fixture


def progress_fixture(affected=False):
    rows = request_fixture(affected)
    parent = rows[2]['choice']; parent['inputContext'].update(schemaVersion=15, profile='PERMITTED_CHOICE_VIEW_V15')
    parent['options']['requestedSelections'] = parent['inputContext']['policy']['prompt']['requestedSelections'] = 2
    child = rows[3]['choice']; ctx = child['inputContext']; ctx.update(schemaVersion=15, profile='PERMITTED_CHOICE_VIEW_V15')
    child['options']['spellSelectionProgress'] = dict(schemaVersion=1, progressCoverage='COMPLETE', progressReason=None,
        attemptIndex=0, priorNonNullReturnCount=0, retainedCount=0, requestedSelections=2,
        receiptCoverage='COMPLETE', receiptReason=None, priorReturns=[])
    ctx['coverage']['prompt'] = 'RECEIVED_SPELL_PROGRESS_V1'
    ctx['policy']['prompt'] = dict(kind='SPELL_ABILITY_PICK_PROGRESS', affectedPlayer='p0' if affected else None, requestedSelections=2,
        attemptIndex=0, priorNonNullReturnCount=0, retainedCount=0, previousReturns=[])
    pair = copy.deepcopy(rows[3:5]); second = pair[0]['choice']; second['choiceId'] = pair[1]['choice']['choiceId'] = 'second-spell'
    second['options']['items'] = [copy.deepcopy(child['options']['items'][1])]
    second['options']['spellSelectionProgress'].update(attemptIndex=1, priorNonNullReturnCount=1, retainedCount=1,
        priorReturns=[copy.deepcopy(rows[4]['choice']['selection']['selected'])])
    second['inputContext']['policy']['prompt'].update(attemptIndex=1, priorNonNullReturnCount=1, retainedCount=1, previousReturns=[None])
    pair[1]['choice']['selection']['selected'] = copy.deepcopy(second['options']['items'][0])
    rows[5]['choice']['selection'].update(items=copy.deepcopy(child['options']['items']), selectedCount=2)
    rows[5:5] = pair
    for index, row in enumerate(rows[1:-1], 1): row['eventIndex'] = index
    return rows


def omit_prompt(child, reason):
    ctx = child['inputContext']; ctx['policy']['prompt'] = None; ctx['coverage']['prompt'] = 'UNAVAILABLE'; ctx['status'] = 'PARTIAL'
    ctx['omissions'].append(dict(component='PROMPT', reason=reason))


class SpellPickProgressTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup); self.root = Path(self.tmp.name); self.index = 0

    def export(self, rows):
        self.index += 1; path = self.root / f'{self.index}.jsonl'; out = self.root / f'out-{self.index}'
        path.write_text(''.join(json.dumps(row) + '\n' for row in rows))
        result = module.export([path], out); self.assertEqual(result, module.export([path])); return result, out

    def test_actual_prior_return_occurrences_are_permitted_without_ability_identity(self):
        result, out = self.export(progress_fixture())
        self.assertTrue(any('V15' in text and 'Ability identities stay unresolved' in text for text in result['limitations']))
        values = [json.loads(line) for line in (out/'choices.jsonl').read_text().splitlines()]
        self.assertTrue(all(not v['trainingReady'] for v in values))
        self.assertEqual(len(values), 3)

    def test_missing_collected_first_selector_does_not_invent_empty_history(self):
        rows = progress_fixture(); del rows[3:5]
        # Model rows lost before collector indexing, not an invalid gap in a saved full stream.
        for index, row in enumerate(rows[1:-1], 1): row['eventIndex'] = index
        self.export(rows)
        self.assertEqual(rows[3]['choice']['options']['spellSelectionProgress']['priorNonNullReturnCount'], 1)

    def test_unavailable_receipts_and_counter_overflow_preserve_partial_semantics(self):
        for reason in ('CAPTURE_FAILED', 'UNSUPPORTED', 'COUNTER_OVERFLOW'):
            rows = progress_fixture(); child = rows[5]['choice']; progress = child['options']['spellSelectionProgress']
            progress.update(receiptCoverage='UNAVAILABLE', receiptReason=reason, priorReturns=[])
            if reason == 'COUNTER_OVERFLOW': progress.update(progressCoverage='UNAVAILABLE', progressReason=reason, attemptIndex=None, priorNonNullReturnCount=None)
            omit_prompt(child, reason)
            with self.subTest(reason=reason): self.export(rows)

    def test_planning_and_duplicate_returns_keep_their_meanings(self):
        rows = progress_fixture()
        for i in (3, 5):
            child = rows[i]['choice']; child['stage'] = 'CANDIDATE_EVALUATION'
            child['entryLocation']['stateBasis'] = child['inputContext']['stateBasis'] = 'PLANNING_WORKING_STATE'
        self.export(rows)
        rows = progress_fixture(); ref = copy.deepcopy(rows[4]['choice']['selection']['selected'])
        rows[5]['choice']['options']['items'] = [ref]; rows[6]['choice']['selection']['selected'] = ref
        rows[7]['choice']['selection']['items'] = [ref, ref]; self.export(rows)

    def test_roles_and_bounded_receipt_truncation_are_independent(self):
        self.export(progress_fixture(True))
        rows = progress_fixture(); parent, first, second = (rows[i]['choice'] for i in (2, 3, 5))
        ref = copy.deepcopy(rows[4]['choice']['selection']['selected'])
        for choice, index in ((first, 64), (second, 65)):
            choice['options']['spellSelectionProgress'].update(attemptIndex=index, priorNonNullReturnCount=index, retainedCount=index,
                requestedSelections=66, priorReturns=[copy.deepcopy(ref) for _ in range(64)])
            choice['inputContext']['policy']['prompt'].update(attemptIndex=index, priorNonNullReturnCount=index, retainedCount=index,
                requestedSelections=66, previousReturns=[None] * 64)
        second['options']['spellSelectionProgress'].update(receiptCoverage='PREFIX', receiptReason='OUTSIDE_CAPTURED_PREFIX')
        omit_prompt(second, 'OUTSIDE_CAPTURED_PREFIX')
        parent['options']['requestedSelections'] = parent['inputContext']['policy']['prompt']['requestedSelections'] = 66
        rows[7]['choice']['selection'].update(items=[copy.deepcopy(ref) for _ in range(65)] + [copy.deepcopy(rows[6]['choice']['selection']['selected'])], selectedCount=66)
        self.export(rows)
        second['options']['spellSelectionProgress']['receiptCoverage'] = 'COMPLETE'
        with self.assertRaises(module.InvalidData): self.export(rows)

    def test_later_outer_stack_membership_does_not_rewrite_earlier_receipt_snapshots(self):
        rows = progress_fixture()
        # An ability's diagnostic ID is stable, but its stack-instance association is sampled at each boundary.
        rows[7]['choice']['selection']['items'][0]['stackId'] = 800
        self.export(rows)
        self.assertIsNone(rows[5]['choice']['options']['spellSelectionProgress']['priorReturns'][0]['stackId'])

    def test_strict_history_owner_counts_receipts_and_feature_permissions(self):
        errors = ('old-profile', 'no-owner', 'wrong-owner', 'wrong-actor', 'request', 'progress-version', 'extra', 'bool-index',
            'negative-index', 'overflow-index', 'count-mismatch', 'negative-retained', 'missing-receipt', 'extra-receipt',
            'card-receipt', 'changed-return', 'prompt-mismatch', 'raw-id-feature', 'local-id-feature', 'incomplete-prompt',
            'reversed-index', 'prior-decline', 'prior-error', 'outer-count', 'outer-receipt', 'unsupported-without-reason',
            'receipt-prefix-without-truncation', 'missing-progress', 'wrong-prompt-coverage', 'outer-injection', 'v14-fallback')
        for error in errors:
            rows = progress_fixture(); child = rows[5]['choice']; progress = child['options']['spellSelectionProgress']; prompt = child['inputContext']['policy']['prompt']
            if error == 'old-profile': child['inputContext'].update(schemaVersion=14, profile='PERMITTED_CHOICE_VIEW_V14')
            if error == 'no-owner': child['parentChoiceId'] = None
            if error == 'wrong-owner': child['parentChoiceId'] = rows[3]['choice']['choiceId']
            if error == 'wrong-actor': child['chooserPlayerId'] += 100
            if error == 'request': progress['requestedSelections'] = 3
            if error == 'progress-version': progress['schemaVersion'] = True
            if error == 'extra': progress['title'] = 'secret'
            if error == 'bool-index': progress['attemptIndex'] = True
            if error == 'negative-index': progress['attemptIndex'] = -1
            if error == 'overflow-index': progress['attemptIndex'] = 1 << 63
            if error == 'count-mismatch': progress['priorNonNullReturnCount'] = 0
            if error == 'negative-retained': progress['retainedCount'] = -1
            if error == 'missing-receipt': progress['priorReturns'] = []
            if error == 'extra-receipt': progress['priorReturns'] *= 2
            if error == 'card-receipt': progress['priorReturns'] = [dict(type='CARD', id=101, gameTimestamp=1)]
            if error == 'changed-return': progress['priorReturns'][0]['id'] = 900
            if error == 'prompt-mismatch': prompt['requestedSelections'] = 0
            if error == 'raw-id-feature': prompt['previousReturns'] = [101]
            if error == 'local-id-feature': prompt['previousReturns'] = ['s0']
            if error == 'incomplete-prompt': progress.update(receiptCoverage='UNAVAILABLE', receiptReason='CAPTURE_FAILED')
            if error == 'reversed-index': progress.update(attemptIndex=0, priorNonNullReturnCount=0, retainedCount=0, priorReturns=[]); prompt.update(attemptIndex=0, priorNonNullReturnCount=0, retainedCount=0, previousReturns=[])
            if error in ('prior-decline', 'prior-error'): rows[4]['choice'].update(status='DECLINED' if error == 'prior-decline' else 'ERROR', selection=None)
            if error == 'outer-count': rows[7]['choice']['selection'].update(items=[], selectedCount=0)
            if error == 'outer-receipt': rows[7]['choice']['selection']['items'].reverse()
            if error == 'unsupported-without-reason': progress.update(progressCoverage='UNAVAILABLE')
            if error == 'receipt-prefix-without-truncation': progress.update(receiptCoverage='PREFIX', receiptReason='OUTSIDE_CAPTURED_PREFIX')
            if error == 'missing-progress': child['options'].pop('spellSelectionProgress')
            if error == 'wrong-prompt-coverage': child['inputContext']['coverage']['prompt'] = 'RECEIVED_SPELL_REQUEST_V1'
            if error == 'v14-fallback': child['inputContext']['policy']['prompt'] = dict(kind='SPELL_ABILITY_REQUEST', affectedPlayer=None); child['inputContext']['coverage']['prompt'] = 'RECEIVED_SPELL_REQUEST_V1'
            if error == 'outer-injection': rows[2]['choice']['options']['spellSelectionProgress'] = copy.deepcopy(progress)
            with self.subTest(error=error), self.assertRaises(module.InvalidData): self.export(rows)

    def test_incomplete_history_cannot_recover_by_claiming_empty_or_changed_receipts(self):
        for mutated in ('recovered', 'changed'):
            rows = progress_fixture(); first = rows[3]['choice']; second = rows[5]['choice']
            if mutated == 'recovered':
                first['options']['spellSelectionProgress'].update(receiptCoverage='UNAVAILABLE', receiptReason='UNSUPPORTED'); omit_prompt(first, 'UNSUPPORTED')
            else:
                # Earlier known receipt prefixes cannot be rewritten even when intervening rows are missing.
                first['options']['spellSelectionProgress'].update(attemptIndex=1, priorNonNullReturnCount=1, retainedCount=1, priorReturns=[dict(type='SPELL_ABILITY', id=999, stackId=None)])
                first['inputContext']['policy']['prompt'].update(attemptIndex=1, priorNonNullReturnCount=1, retainedCount=1, previousReturns=[None])
                second['options']['spellSelectionProgress'].update(attemptIndex=2, priorNonNullReturnCount=2, retainedCount=2, priorReturns=copy.deepcopy(rows[2]['choice']['options']['items']))
                second['inputContext']['policy']['prompt'].update(attemptIndex=2, priorNonNullReturnCount=2, retainedCount=2, previousReturns=[None, None])
            with self.subTest(mutated=mutated), self.assertRaises(module.InvalidData): self.export(rows)

    def test_direct_v14_records_remain_unchanged_and_cannot_claim_v15_history(self):
        result, _ = self.export(request_fixture()); self.assertFalse(any('V15' in text for text in result['limitations']))
