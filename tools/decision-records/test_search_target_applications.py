import copy
import json
import tempfile
import unittest
from pathlib import Path
from test_export import module, target_provenance_fixture


def search_fixture(operation='ROLLOUT_TARGET_OVERRIDE'):
    rows = target_provenance_fixture()
    d = rows[2]['decision']; chosen = d['subordinateChoices'][0]['selected']
    def plan(target):
        return dict(abilities=[dict(abilityId=7, targets=[copy.deepcopy(target)], targetsTruncated=False)], abilitiesTruncated=False)
    c = dict(applicationSchemaVersion=1, choiceId='search-application', choiceType='SEARCH_TARGET_APPLICATION',
        stage='SEARCH_TARGET_APPLICATION', chooserPlayerId=0, candidateAbilityId=7, sourceId=8,
        operation=operation, optionCoverage='OBSERVED_OPERATION_NOT_ALTERNATIVES', requestedTarget=copy.deepcopy(chosen),
        beforeTargets=plan(d['subordinateChoices'][0]['options'][0]), afterTargets=plan(chosen),
        changedAbilities=[0], status='ACCEPTED', candidateActionId='0:1:0')
    if operation == 'HEURISTIC_REVALIDATION': c['requestedTarget'] = None
    d.update(targetChoiceProvenanceSchemaVersion=2, searchTargetApplicationSchemaVersion=1,
        searchTargetApplications=[c], searchTargetApplicationOmissions=dict(budget=0, interrupted=0))
    provenance = rows[4]['targetChoiceProvenance']
    provenance.update(schemaVersion=2, coverage='EXPLICIT_TARGET_OPERATIONS_V2')
    provenance['abilities'][0]['targetChoices'] = [dict(choiceId=c['choiceId'], chooserPlayerId=0, stage='SEARCH_TARGET_APPLICATION', abilityIndex=0, targetIndex=0)]
    return rows


class SearchTargetApplicationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def export(self, rows, name='input'):
        path = self.root / (name + '.jsonl')
        path.write_text(''.join(json.dumps(r) + '\n' for r in rows))
        out = self.root / name
        summary = module.export([path], out)
        return summary, [json.loads(l) for l in (out / 'dataset.jsonl').read_text().splitlines()]

    def test_all_operation_types_link_to_the_selected_candidate_and_committed_slot(self):
        for operation in ('ROLLOUT_TARGET_OVERRIDE', 'RESTORE_APPROVED_TARGET', 'DISCOVERED_TARGET', 'GUARD_REAIM', 'HEURISTIC_REVALIDATION'):
            rows = search_fixture(operation)
            if operation == 'RESTORE_APPROVED_TARGET':
                # Equal identities still represent an explicit replacement, not a reused evaluation origin.
                rows[2]['decision']['searchTargetApplications'][0]['beforeTargets'] = copy.deepcopy(rows[2]['decision']['searchTargetApplications'][0]['afterTargets'])
            summary, dataset = self.export(rows, operation)
            self.assertEqual(summary['committedTargetChoiceOrigins'], {'EXPLICIT_SEARCH_APPLICATION': 1})
            self.assertEqual(summary['searchTargetOperationTypes'], {operation: 1})
            self.assertEqual(dataset[0]['decision']['searchTargetApplications'], rows[2]['decision']['searchTargetApplications'])

    def test_declined_application_and_unchanged_revalidation_preserve_old_evaluation_origins(self):
        for unchanged in (False, True):
            rows = search_fixture('HEURISTIC_REVALIDATION' if unchanged else 'ROLLOUT_TARGET_OVERRIDE')
            c = rows[2]['decision']['searchTargetApplications'][0]
            c.update(changedAbilities=[], status='ACCEPTED' if unchanged else 'DECLINED', beforeTargets=copy.deepcopy(c['afterTargets']))
            rows[4]['targetChoiceProvenance']['abilities'][0]['targetChoices'] = [dict(choiceId='opaque-choice', chooserPlayerId=0, stage='CANDIDATE_EVALUATION')]
            self.assertEqual(self.export(rows, str(unchanged))[0]['committedTargetChoiceOrigins'], {'EXPLICIT_EVALUATION_ADDITION': 1})

    def test_invalid_origins_and_cross_version_claims_are_rejected(self):
        for mutation in ('unselected', 'declined', 'unchanged', 'identity', 'slot', 'bool-index', 'missing-fields', 'version', 'coverage', 'stage', 'collision'):
            rows = search_fixture(); d=rows[2]['decision']; c=d['searchTargetApplications'][0]
            origin=rows[4]['targetChoiceProvenance']['abilities'][0]['targetChoices'][0]
            if mutation == 'unselected': c['candidateActionId'] = None
            elif mutation == 'declined': c['status'] = 'DECLINED'
            elif mutation == 'unchanged': c['changedAbilities'] = []
            elif mutation == 'identity': rows[4]['finalizedTargets']['abilities'][0]['targets'][0]['id'] += 1
            elif mutation == 'slot': origin['targetIndex'] = 1
            elif mutation == 'bool-index': origin['abilityIndex'] = False
            elif mutation == 'missing-fields': del origin['abilityIndex']
            elif mutation == 'version': rows[4]['targetChoiceProvenance']['schemaVersion'] = 1
            elif mutation == 'coverage': rows[4]['targetChoiceProvenance']['coverage'] = 'EXPLICIT_TARGET_ADDITIONS_V1'
            elif mutation == 'stage': origin['stage'] = 'CANDIDATE_EVALUATION'; del origin['abilityIndex']; del origin['targetIndex']
            elif mutation == 'collision': c['choiceId'] = d['subordinateChoices'][0]['choiceId']
            with self.subTest(mutation=mutation), self.assertRaises(module.InvalidData): self.export(rows, mutation)

    def test_invalid_operation_fields_and_private_details_are_rejected(self):
        for mutation in ('undeclared', 'actor', 'candidate', 'operation', 'alternatives', 'private', 'requested', 'plan-root', 'changed-index', 'omission', 'duplicate'):
            rows=search_fixture(); d=rows[2]['decision']; c=d['searchTargetApplications'][0]
            if mutation=='undeclared': del d['searchTargetApplicationSchemaVersion']
            elif mutation=='actor': c['chooserPlayerId']=1
            elif mutation=='candidate': c['candidateAbilityId']=99
            elif mutation=='operation': c['operation']='GUESSED'
            elif mutation=='alternatives': c['optionCoverage']='ALL_LEGAL_TARGETS'
            elif mutation=='private': c['prompt']='private card text'
            elif mutation=='requested': c['requestedTarget']['id']+=1
            elif mutation=='plan-root': c['afterTargets']['abilities'][0]['abilityId']=99
            elif mutation=='changed-index': c['changedAbilities']=[False]
            elif mutation=='omission': d['searchTargetApplicationOmissions']['budget']=True
            elif mutation=='duplicate': d['searchTargetApplications'].append(copy.deepcopy(c))
            with self.subTest(mutation=mutation), self.assertRaises(module.InvalidData): self.export(rows, mutation)

    def test_masked_operation_never_claims_a_target_origin(self):
        rows=search_fixture(); c=rows[2]['decision']['searchTargetApplications'][0]
        c.update(operation='MASKED', optionCoverage='MASKED', requestedTarget=None, beforeTargets=None, afterTargets=None, changedAbilities=[], status='MASKED')
        rows[4]['targetChoiceProvenance']['abilities'][0]['targetChoices']=[None]
        summary, dataset=self.export(rows)
        self.assertEqual(summary['committedTargetChoiceOrigins'], {'UNKNOWN':1})
        self.assertIsNone(dataset[0]['decision']['searchTargetApplications'][0]['afterTargets'])
        c['requestedTarget']=dict(type='PLAYER',id=1)
        with self.assertRaises(module.InvalidData): self.export(rows,'leak')

    def test_capture_failures_keep_available_data_without_claiming_an_applied_result(self):
        for status in ('CAPTURE_FAILED', 'CAPTURE_INTERRUPTED'):
            rows=search_fixture(); d=rows[2]['decision']; c=d['searchTargetApplications'][0]
            c.update(status=status, afterTargets=None, changedAbilities=[])
            if status == 'CAPTURE_INTERRUPTED': d['searchTargetApplicationOmissions']['interrupted']=1
            rows[4]['targetChoiceProvenance']['abilities'][0]['targetChoices']=[None]
            summary, dataset=self.export(rows,status)
            self.assertEqual(summary['searchTargetApplicationStatuses'],{status:1})
            self.assertEqual(summary['committedTargetChoiceOrigins'],{'UNKNOWN':1})

    def test_interrupted_application_can_be_retained_without_inventing_a_result(self):
        rows=search_fixture(); d=rows[2]['decision']; c=d['searchTargetApplications'][0]
        c.update(status='INCOMPLETE', afterTargets=None, changedAbilities=[], candidateActionId=None)
        d.update(status='AI_TIMEOUT', selection='PASS_PRIORITY', selectedActions=[])
        d['subordinateChoices'][0]['candidateActionId']=None
        rows=[r for r in rows if r['type'] not in ('AI_ACTION_EXECUTION','STACK_ADDED','STACK_RESOLVED','STACK_REMOVED')]
        for i,r in enumerate(rows[1:-1],1): r['eventIndex']=i
        summary,dataset=self.export(rows)
        self.assertFalse(dataset[0]['outcomeEligible'])
        self.assertEqual(summary['searchTargetApplicationStatuses'],{'INCOMPLETE':1})
