import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import fixture, module
from test_choice_events import choices_fixture
from test_input_context import input_context, known_card


def context2(chooser=1, empty=False):
    context = input_context(chooser)
    context.update(schemaVersion=2, profile='PERMITTED_CHOICE_VIEW_V2')
    context['coverage']['stack'] = 'PUBLIC_ROOT_STACK_V1'
    context['policy']['stack'] = dict(entries=[], truncated=False, isResolving=False, resolving=None, resolvingCoverage='CAPTURED')
    context['diagnostics']['stackBindings'] = []
    if not empty:
        card = known_card(context, zone='Stack', name='Lightning Bolt')
        context['policy']['stack']['entries'] = [dict(entityRef='s0', kind='SPELL', controller='p0', source=card['entityRef'], rootX=3,
            targets=[dict(entityRef='p1', dividedAmount=3)], targetsCoverage='ROOT_TARGETS_ONLY')]
        context['diagnostics']['stackBindings'] = [dict(entityRef='s0', id=100, abilityId=7)]
    return context


class StackInputContextTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup); self.root = Path(self.tmp.name)

    def export(self, context, name='case'):
        rows = choices_fixture(); rows[2]['choice']['inputContext'] = context
        return self.rows(rows, name)

    def rows(self, rows, name):
        path = self.root / (name + '.jsonl'); path.write_text(''.join(json.dumps(r)+'\n' for r in rows))
        return module.export([path], self.root / name)

    def test_versions_remain_explicit_and_stack_is_preserved_in_dataset_and_audit(self):
        self.export(input_context(), 'v1')
        c = context2(); result = self.export(c, 'v2')
        row = json.loads((self.root/'v2/choices.jsonl').read_text())
        self.assertEqual(row['start']['choice']['inputContext'], c)
        self.assertFalse(row['trainingReady'])
        self.assertEqual(result['inputCoverageAudit']['totals']['optionBindings'], {'NO_CONTEXT':1, 'DIAGNOSTIC_ONLY_V2':1})
        self.assertEqual(result['inputCoverageAudit']['totals']['components']['stack:PUBLIC_ROOT_STACK_V1'],1)
        bad = copy.deepcopy(c); bad['profile']='PERMITTED_CHOICE_VIEW_V1'
        with self.assertRaises(module.InvalidData): self.export(bad, 'profile-mismatch')

    def test_root_target_bindings_can_refer_to_another_captured_stack_entry(self):
        c=context2(); second=copy.deepcopy(c['policy']['stack']['entries'][0]);second['entityRef']='s1'
        c['policy']['stack']['entries'].append(second)
        c['diagnostics']['stackBindings'].append(dict(entityRef='s1', id=101, abilityId=8))
        c['policy']['stack']['entries'][0]['targets']=[dict(entityRef='s1', dividedAmount=None)]
        self.export(c)
        c['policy']['stack']['entries'][0]['targets'][0]['entityRef']='s2'
        with self.assertRaises(module.InvalidData): self.export(c,'outside-prefix')

    def test_private_source_target_and_engine_identity_leaks_are_rejected(self):
        for corruption in ('private-source','private-target','raw-id','hidden-x','hidden-targets'):
            c=context2(); entry=c['policy']['stack']['entries'][0];card=c['policy']['cards'][0]
            if corruption=='private-source': card['zone']='Hand'
            if corruption=='private-target':
                private=copy.deepcopy(card);private.update(entityRef='c1',zone='Library');c['policy']['cards'].append(private)
                c['diagnostics']['cardBindings'].append(dict(entityRef='c1',id=99,gameTimestamp=100))
                entry['targets']=[dict(entityRef='c1',dividedAmount=None)]
            if corruption=='raw-id': entry['abilityId']=7
            if corruption.startswith('hidden'):
                card.update(identityKnown=False,name=None,faceDown=True)
                c.update(status='PARTIAL',omissions=[dict(component='STACK',reason='HIDDEN')])
                if corruption=='hidden-x': entry.update(targets=None,targetsCoverage='UNAVAILABLE')
                else: entry['rootX']=None
            with self.subTest(corruption=corruption),self.assertRaises(module.InvalidData):self.export(c,corruption)

    def test_limits_and_resolving_consistency_cannot_claim_missing_information(self):
        mutations={
            'prefix-without-reason':lambda c:c['policy']['stack'].update(truncated=True),
            'unknown-resolver':lambda c:c['policy']['stack'].update(isResolving=True,resolving='s9'),
            'unlinked-resolver':lambda c:c['policy']['stack'].update(isResolving=True),
            'inactive-resolver':lambda c:c['policy']['stack'].update(resolving='s0'),
            'extra-targets':lambda c:c['policy']['stack']['entries'][0].update(targets=[dict(entityRef='p1',dividedAmount=0)]*17),
            'extra-entries':lambda c:c['policy']['stack'].update(entries=c['policy']['stack']['entries']*9),
            'missing-diagnostic':lambda c:c['diagnostics'].update(stackBindings=[]),
        }
        for name,change in mutations.items():
            c=context2();change(c)
            with self.subTest(name=name),self.assertRaises(module.InvalidData):self.export(c,name)
        c=context2();c['policy']['stack'].update(isResolving=True,resolving='s0');self.export(c,'active')

    def test_hidden_and_planning_details_stay_unavailable_while_public_stack_objects_remain(self):
        for mode in ('hidden','planning'):
            c=context2();entry=c['policy']['stack']['entries'][0]
            entry.update(rootX=None,targets=None,targetsCoverage='UNAVAILABLE')
            c.update(status='PARTIAL',omissions=[dict(component='STACK',reason='HIDDEN' if mode=='hidden' else 'UNSUPPORTED')])
            if mode=='hidden':c['policy']['cards'][0].update(identityKnown=False,name=None,faceDown=True)
            else:c['stateBasis']='PLANNING_WORKING_STATE'
            self.export(c,mode)
            if mode=='planning':
                entry['rootX']=99
                with self.assertRaises(module.InvalidData):self.export(c,'invented-commitment')

    def test_unavailable_context_has_no_stack_payload_or_diagnostic_bindings(self):
        c=context2();c.update(status='UNAVAILABLE',policy=None,diagnostics=dict(playerBindings=[],cardBindings=[],stackBindings=[]),
                             omissions=[dict(component='CONTEXT',reason='BUDGET_EXHAUSTED')])
        c['coverage'].update(playerView='UNAVAILABLE',disclosures='UNAVAILABLE',stack='UNAVAILABLE')
        self.export(c)
        c['diagnostics']['stackBindings']=[dict(entityRef='s0',id=100,abilityId=7)]
        with self.assertRaises(module.InvalidData):self.export(c,'stale-stack')

    def test_parent_baseline_stays_empty_stack_and_legacy_profile_cannot_acquire_v2_fields(self):
        rows=fixture();rows[2]['decision']['inputContext']=context2(chooser=0,empty=True)
        self.rows(rows,'parent-empty')
        rows[2]['decision']['inputContext']=context2(chooser=0)
        with self.assertRaises(module.InvalidData):self.rows(rows,'parent-pending')
        c=context2();c.update(schemaVersion=1,profile='PERMITTED_CHOICE_VIEW_V1');c['coverage']['stack']='UNSUPPORTED'
        with self.assertRaises(module.InvalidData):self.export(c,'old-shape')
