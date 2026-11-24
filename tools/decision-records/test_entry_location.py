import copy
import json
from pathlib import Path
import tempfile
import unittest

from test_context_bindings import rows4
from test_choice_events import choices_fixture
from test_export import module


def entry(timing='BEFORE_SELECTOR'):
    kind = {'BEFORE_SELECTOR': 'SELECTOR', 'BEFORE_OPERATION': 'OPERATION',
            'AFTER_SELECTION_BEFORE_APPLICATION': 'APPLICATION'}[timing]
    return dict(schemaVersion=1, status='CAPTURED', kind=kind, timing=timing, stateBasis='RULES_STATE',
                turn=0, phase='UPKEEP', turnPlayerId=0, reason=None)


def target_rows(application=False):
    rows = rows4(); c = rows[2]['choice']; options = c['options']
    timing = 'AFTER_SELECTION_BEFORE_APPLICATION' if application else 'BEFORE_SELECTOR'
    c.update(choiceType='TARGET_POOL_SELECTION', boundary='DESTROY_REMOVAL_POOL', entryLocation=entry(timing),
             options=dict(coverage='OBSERVED_HEURISTIC_POOL_NOT_ALL_LEGAL_TARGET_SETS', items=options['items'],
                          truncated=False, priorTargets=[], priorTargetsTruncated=False,
                          poolSnapshotTiming='AT_TARGET_ADDITION' if application else 'BEFORE_CHOOSER',
                          purpose='TARGET_RECOMMENDATION'))
    c['inputContext']['timing'] = timing
    return rows


class EntryLocationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.index = 0

    def export(self, rows):
        self.index += 1; source = self.root / f'{self.index}.jsonl'
        source.write_text(''.join(json.dumps(row)+'\n' for row in rows))
        out = self.root / f'out-{self.index}'
        result = module.export([source], out)
        self.assertEqual(result['inputCoverageAudit'], module.export([source])['inputCoverageAudit'])
        return result, out

    def test_new_entry_uses_its_own_clock_without_upgrading_legacy_parent_or_start_groups(self):
        result, out = self.export(target_rows())
        audit = result['inputCoverageAudit']; location = audit['entryLocationAudit']
        self.assertEqual(location['records'], 1)
        self.assertEqual(location['kinds'], {'SELECTOR': 1})
        groups = [r for r in audit['byTurn'] if r['turnBasis'] == 'ENTRY_LOCATION']
        self.assertEqual([(r['turn'], r['playerId']) for r in groups], [(0, 1)])
        self.assertNotIn('DESTROY_REMOVAL_POOL', audit['knownInputBoundaryInventoryV1'])
        self.assertIn('DESTROY_REMOVAL_POOL', location['knownInputBoundaryInventoryV2']['CHOICE'])
        row = json.loads((out/'choices.jsonl').read_text())
        self.assertFalse(row['trainingReady'])
        self.assertNotIn('entryLocation', row['start']['choice']['inputContext']['policy'])
        legacy, _ = self.export(choices_fixture())
        self.assertNotIn('entryLocationAudit', legacy['inputCoverageAudit'])
        self.assertTrue(any(r['turnBasis'] == 'CHOICE_START_EVENT' for r in legacy['inputCoverageAudit']['byTurn']))

    def test_application_keeps_legacy_pool_timing_and_supported_diagnostic_bindings(self):
        result, out = self.export(target_rows(application=True))
        row = json.loads((out/'choices.jsonl').read_text())
        choice = row['start']['choice']
        self.assertEqual(choice['entryLocation']['kind'], 'APPLICATION')
        self.assertEqual(choice['options']['poolSnapshotTiming'], 'AT_TARGET_ADDITION')
        self.assertEqual(result['inputContextBindings']['publicOptionLinks'], 1)

    def test_unavailable_entry_clock_stays_unknown_even_if_a_view_or_event_has_a_clock(self):
        rows = target_rows(); value = rows[2]['choice']['entryLocation']
        value.update(status='UNAVAILABLE', kind='UNESTABLISHED', timing='UNESTABLISHED', stateBasis='UNESTABLISHED',
                     turn=None, phase=None, turnPlayerId=None, reason='CAPTURE_FAILED')
        result, _ = self.export(rows)
        group = next(r for r in result['inputCoverageAudit']['byTurn'] if r['turnBasis'] == 'ENTRY_LOCATION_UNAVAILABLE')
        self.assertIsNone(group['turn'])
        self.assertEqual(result['inputCoverageAudit']['entryLocationAudit']['reasons'], {'CAPTURE_FAILED': 1})

    def test_invalid_entry_identity_versions_and_boundary_claims_are_rejected(self):
        for error in ('version', 'kind', 'turn', 'boolean', 'phase', 'player', 'reason', 'basis', 'application', 'hidden', 'extra'):
            rows = target_rows(); choice = rows[2]['choice']; value = choice['entryLocation']
            if error == 'version': value['schemaVersion'] = 2
            if error == 'kind': value['kind'] = 'OPERATION'
            if error == 'turn': value['turn'] = -1
            if error == 'boolean': value['turn'] = True
            if error == 'phase': value['phase'] = 'UNRECOGNIZED_PHASE'
            if error == 'player': value['turnPlayerId'] = 99
            if error == 'reason': value['reason'] = 'CAPTURE_FAILED'
            if error == 'basis': value['stateBasis'] = 'PLANNING_WORKING_STATE'
            if error == 'application': choice['options']['poolSnapshotTiming'] = 'AT_TARGET_ADDITION'
            if error == 'hidden': value.update(status='UNAVAILABLE', kind='UNESTABLISHED', timing='UNESTABLISHED', stateBasis='UNESTABLISHED', turn=None, phase=None, turnPlayerId=None, reason='HIDDEN')
            if error == 'extra': value['decisionId'] = 'invented location join'
            with self.subTest(error=error), self.assertRaises(module.InvalidData): self.export(rows)

    def test_new_target_view_does_not_allow_private_or_stale_option_identity(self):
        for error in ('private', 'stale', 'missing-entry'):
            rows = target_rows(); choice = rows[2]['choice']; context = choice['inputContext']
            if error == 'private': context['policy']['cards'][0]['zone'] = 'Hand'
            if error == 'stale': choice['options']['items'][0]['gameTimestamp'] += 1
            if error == 'missing-entry': choice.pop('entryLocation')
            with self.subTest(error=error), self.assertRaises(module.InvalidData): self.export(rows)
