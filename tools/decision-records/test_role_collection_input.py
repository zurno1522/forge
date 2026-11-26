import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_export import module
from test_received_parameters import parameter_fixture, card_ref, receipt


def role_fixture(key='ENTITY_ATTACHMENT_ROLES', count=1):
    rows = parameter_fixture('NONE', {}, 'ENTITY'); c = rows[2]['choice']; ctx = c['inputContext']
    ctx.update(schemaVersion=18, profile='PERMITTED_CHOICE_VIEW_V18')
    raw = card_ref(); local = ctx['diagnostics']['cardBindings'][0]['entityRef']
    c['options']['callbackParameters'] = receipt(key, dict(entities=[copy.deepcopy(raw) for _ in range(count)]))
    ctx['policy']['receivedParameters'] = dict(kind=key, entities=[local]*count)
    return rows


class RoleCollectionInputTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup); self.root = Path(self.tmp.name); self.index = 0

    def export(self, rows):
        self.index += 1; path = self.root/f'{self.index}.jsonl'; out = self.root/f'out-{self.index}'
        path.write_text(''.join(json.dumps(row)+'\n' for row in rows)); result = module.export([path], out)
        self.assertEqual(result, module.export([path])); return result, out

    def test_whole_received_roles_include_empty_and_duplicates_without_pool_permission(self):
        for kind in ('ENTITY_ATTACHMENT_ROLES','ENTITY_ATTACKER_ROLES'):
            for count in (0,1,3,64):
                result, out = self.export(role_fixture(kind,count)); row=json.loads((out/'choices.jsonl').read_text())
                self.assertEqual(len(row['start']['choice']['inputContext']['policy']['receivedParameters']['entities']),count)
                self.assertFalse(row['trainingReady']); self.assertTrue(any('V18' in text and 'request roles' in text for text in result['limitations']))
                self.assertNotIn('ENTITY_ATTACHMENT_ROLES',result['inputCoverageAudit']['knownInputBoundaryInventoryV1'])

    def test_oversized_and_unknown_diagnostics_stay_wholly_unavailable(self):
        for coverage in ('UNAVAILABLE','UNSUPPORTED'):
            rows=role_fixture();c=rows[2]['choice'];ctx=c['inputContext'];raw=c['options']['callbackParameters']
            raw.update(coverage=coverage,kind='ENTITY_ATTACHMENT_ROLES' if coverage=='UNAVAILABLE' else None,values=None,reason='OUTSIDE_CAPTURED_PREFIX' if coverage=='UNAVAILABLE' else 'UNSUPPORTED')
            ctx['policy']['receivedParameters']=None;ctx['coverage']['receivedParameters']='UNAVAILABLE';ctx['status']='PARTIAL';ctx['omissions'].append(dict(component='PARAMETERS',reason=raw['reason']));self.export(rows)
            ctx['policy']['receivedParameters']=dict(kind='ENTITY_ATTACHMENT_ROLES',entities=[]);ctx['coverage']['receivedParameters']='RECEIVED_CALLBACK_PARAMETERS_V1'
            with self.assertRaises(module.InvalidData):self.export(rows)

    def test_one_private_or_unresolved_member_refuses_whole_projection_and_keeps_other_components(self):
        rows=role_fixture(count=2);c=rows[2]['choice'];ctx=c['inputContext'];c['options']['callbackParameters']['values']['entities'][1]['id']+=99
        ctx['policy']['receivedParameters']=None;ctx['coverage']['receivedParameters']='UNAVAILABLE';ctx['status']='PARTIAL';ctx['omissions'].append(dict(component='PARAMETERS',reason='UNRESOLVED_BINDING'))
        self.export(rows);self.assertTrue(ctx['policy']['players'])
        # A raw empty list has no unresolved member; refusal cannot invent one.
        c['options']['callbackParameters']['values']['entities']=[]
        with self.assertRaises(module.InvalidData):self.export(rows)

    def test_profile_whole_bound_membership_order_and_raw_role_permissions_are_strict(self):
        errors=('profile','too-many-raw','too-many-feature','player-raw','null-raw','wrong-raw','raw-id','unknown-ref','private-ref','stale','missing-item','extra-raw-field','extra-feature-field','not-list','missing-receipt','no-entry')
        for error in errors:
            rows=role_fixture(count=2);c=rows[2]['choice'];ctx=c['inputContext'];raw=c['options']['callbackParameters']['values'];features=ctx['policy']['receivedParameters']
            if error=='profile':ctx.update(schemaVersion=17,profile='PERMITTED_CHOICE_VIEW_V17')
            if error=='too-many-raw':raw['entities']*=33
            if error=='too-many-feature':features['entities']*=33
            if error=='player-raw':raw['entities'][0]=dict(type='PLAYER',id=0)
            if error=='null-raw':raw['entities'][0]=None
            if error=='wrong-raw':raw['entities'][0]='c0'
            if error=='raw-id':features['entities'][0]=raw['entities'][0]
            if error=='unknown-ref':features['entities'][0]='c99'
            if error=='private-ref':
                ctx['policy']['cards'][0]['zone']='Hand';ctx['diagnostics']['optionBindings']=dict(coverage='UNSUPPORTED',items=[],truncated=False)
            if error=='stale':raw['entities'][0]['gameTimestamp']+=1
            if error=='missing-item':features['entities'].pop()
            if error=='extra-raw-field':raw['truncated']=False
            if error=='extra-feature-field':features['poolCount']=2
            if error=='not-list':features['entities']={}
            if error=='missing-receipt':c['options'].pop('callbackParameters')
            if error=='no-entry':c.pop('entryLocation')
            with self.subTest(error=error),self.assertRaises(module.InvalidData):self.export(rows)

    def test_order_and_duplicate_correspondence_are_checked_against_same_view_bindings(self):
        rows=role_fixture(count=3);c=rows[2]['choice'];ctx=c['inputContext'];card=copy.deepcopy(ctx['policy']['cards'][0]);binding=copy.deepcopy(ctx['diagnostics']['cardBindings'][0])
        card['entityRef']='c1';binding.update(entityRef='c1',id=binding['id']+1);ctx['policy']['cards'].append(card);ctx['diagnostics']['cardBindings'].append(binding)
        c['options']['callbackParameters']['values']['entities'][1]['id']=binding['id'];ctx['policy']['receivedParameters']['entities'][1]='c1';self.export(rows)
        ctx['policy']['receivedParameters']['entities']=['c1','c0','c0']
        with self.assertRaises(module.InvalidData):self.export(rows)

    def test_new_role_kind_never_grants_numeric_trigger_parent_or_old_profile_permission(self):
        from test_trigger_preparation_input import trigger_fixture
        for rows in (parameter_fixture('NONE',{}),trigger_fixture()):
            c=rows[2]['choice'];ctx=c['inputContext'];ctx.update(schemaVersion=18,profile='PERMITTED_CHOICE_VIEW_V18')
            c['options']['callbackParameters']=receipt('ENTITY_ATTACKER_ROLES',dict(entities=[]));ctx['policy']['receivedParameters']=dict(kind='ENTITY_ATTACKER_ROLES',entities=[])
            with self.assertRaises(module.InvalidData):self.export(rows)
        from test_received_parameters import upgrade
        from test_entity_selection_history import history_fixture
        rows=upgrade(history_fixture());parent=rows[2]['choice']['inputContext'];parent.update(schemaVersion=18,profile='PERMITTED_CHOICE_VIEW_V18')
        self.export(rows)
        parent['policy']['receivedParameters']=dict(kind='ENTITY_ATTACHMENT_ROLES',entities=['c0']);parent['coverage']['receivedParameters']='RECEIVED_CALLBACK_PARAMETERS_V1'
        with self.assertRaises(module.InvalidData):self.export(rows)
