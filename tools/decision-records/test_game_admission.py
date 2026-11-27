import copy
import gzip
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from test_export import module as ordinary
from test_card_payment_request import card_fixture,unavailable

HERE=Path(__file__).with_name('input-context-benchmark')
spec=importlib.util.spec_from_file_location('game_admission_validator',HERE/'validate-game-admission.py')
report=importlib.util.module_from_spec(spec);spec.loader.exec_module(report)
runner_spec=importlib.util.spec_from_file_location('game_admission_runner',HERE/'run-game-admission.py')
runner=importlib.util.module_from_spec(runner_spec);runner_spec.loader.exec_module(runner)
CASES=json.loads((HERE/'fixtures/game-admission-sidecars.json').read_text())['cases']


def fixture(policy='prefix'):return copy.deepcopy(CASES[policy])


class NativeAdmissionValidationTests(unittest.TestCase):
    def test_actual_receipts_reconcile_and_controls_have_no_experiment_registry(self):
        for key in CASES:
            data=fixture(key);counts=report.validate_sidecar(data,data['policy'],0,{0,1})
            self.assertEqual(sum(counts.values()),sum(a['returned'] for a in data['actors']))
        self.assertEqual(fixture('production')['actors'],[])
        self.assertEqual(sum(a['entered'] for a in fixture()['actors']),654)

    def test_salts_unknown_actors_and_false_ordinary_control_cannot_use_experimental_receipts(self):
        for fault in ('salt','policy','unknown','duplicate','control'):
            data=fixture('hash8')
            if fault=='salt':data['salt']=1
            if fault=='policy':data['policy']='UNDECLARED'
            if fault=='unknown':data['actors'][0]['playerId']=9
            if fault=='duplicate':data['actors'].append(copy.deepcopy(data['actors'][0]))
            if fault=='control':data['policy']='PRODUCTION'
            with self.subTest(fault=fault),self.assertRaises(ValueError):report.validate_sidecar(data,'PRODUCTION' if fault=='control' else 'HASH8',0,{0,1})

    def test_capture_outside_and_returned_work_cannot_be_refunded_or_hidden(self):
        for field in ('captureWork','outsideCaptureWork','returnedWork','remaining','funded','held','returned','inFlightWork','overflow'):
            data=fixture();a=data['actors'][0]
            if field=='overflow':a[field]=True
            else:a[field]+=1
            with self.subTest(field=field),self.assertRaises(ValueError):report.validate_sidecar(data,'PREFIX',0,{0,1})

    def test_deliberate_skips_cannot_charge_or_relabel_samples(self):
        for fault in ('paid-skip','sample-count','invented-available','quality'):
            data=fixture('hash8');a=data['actors'][0];key=next(k for k in a['groups'] if k.endswith(':EXPERIMENT_SAMPLED'));g=a['groups'][key]
            if fault=='paid-skip':g['work']=1
            if fault=='sample-count':g['records']+=1
            if fault=='invented-available':g['available']=1
            if fault=='quality':g['representedCards']=1;g['representedZones']={'Hand':1}
            with self.subTest(fault=fault),self.assertRaises(ValueError):report.validate_sidecar(data,'HASH8',0,{0,1})

    def test_pending_capture_is_not_a_lost_or_completed_capture_at_cutoff(self):
        data=fixture();a=data['actors'][0];a['entered']+=1;a['inFlight']+=1;a['maximumInFlight']=max(a['maximumInFlight'],a['inFlight']);a['captureWork']+=10;a['inFlightWork']+=10;a['remaining']-=10
        report.validate_sidecar(data,'PREFIX',0,{0,1})
        a['returned']+=1
        with self.assertRaises(ValueError):report.validate_sidecar(data,'PREFIX',0,{0,1})

    def test_reservation_cannot_release_early_or_change_the_original_share(self):
        data=fixture();data['policy']='RESERVE'
        for a in data['actors']:
            a['funded']=64000;a['held']=64000;a['remaining']=a['funded']-a['captureWork']-a['outsideCaptureWork'];a['finalGameTurn']=22
        report.validate_sidecar(data,'RESERVE',0,{0,1})
        data['actors'][0]['finalGameTurn']=31
        with self.assertRaises(ValueError):report.validate_sidecar(data,'RESERVE',0,{0,1})

    def test_rng_snapshot_does_not_consume_draws_and_declared_limits_cannot_silently_change(self):
        for field in ('rngCallsAfterSnapshot','releaseThreshold','maximumGroups','schemaVersion','rngStateSha256'):
            data=fixture();data[field]='bogus' if field=='rngStateSha256' else data[field]+1
            with self.subTest(field=field),self.assertRaises(ValueError):report.validate_sidecar(data,'PREFIX',0,{0,1})

    def test_early_timeout_has_no_actor_coverage_claim_and_cannot_be_a_normal_control(self):
        data=fixture('hash8');data['actors']=[]
        report.validate_sidecar(data,'HASH8',0,{0,1},allow_empty=True)
        with self.assertRaises(ValueError):report.validate_sidecar(data,'HASH8',0,{0,1})

    def test_timeout_rng_can_advance_concurrently_without_becoming_a_normal_control(self):
        data=fixture();data['rngCallsAfterSnapshot']+=1
        report.validate_sidecar(data,'PREFIX',0,{0,1},stable_rng=False)
        with self.assertRaises(ValueError):report.validate_sidecar(data,'PREFIX',0,{0,1})

    def test_native_result_comparison_keeps_assessments_and_all_other_delivery_counters(self):
        original={'decision':{'targetCaptureAccounting':{'counts':{'inputUnavailable':0,'failed':0}},'selection':{'value':3}},'inputContext':{'policy':{}}}
        changed=copy.deepcopy(original);changed['decision']['targetCaptureAccounting']['counts']['inputUnavailable']=1;changed['inputContext']=None
        self.assertEqual(report.normalize(original),report.normalize(changed))
        changed['decision']['selection']['value']=4
        self.assertNotEqual(report.normalize(original),report.normalize(changed))
        changed=copy.deepcopy(original);changed['decision']['targetCaptureAccounting']['counts']['failed']=1
        self.assertNotEqual(report.normalize(original),report.normalize(changed))

    def test_private_validation_cannot_publish_exports_or_grant_reasons_to_ordinary_reader(self):
        before=set(ordinary._choices._contexts.REASONS)
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'trace.jsonl';rows=card_fixture();unavailable(rows[2]['choice']['inputContext']);rows[2]['choice']['inputContext']['omissions'][0]['reason']='EXPERIMENT_SAMPLED';path.write_text(''.join(json.dumps(r)+'\n' for r in rows))
            with self.assertRaises(ordinary.InvalidData):ordinary.export([path])
            summary,joint=report.inspect_trace(path);self.assertFalse(summary['trainingReady']);self.assertTrue(joint)
            with self.assertRaises(TypeError):report.inspect_trace(path,output_dir=Path(directory)/'exports')
            with self.assertRaises(ordinary.InvalidData):ordinary.export([path])
            self.assertEqual(set(ordinary._choices._contexts.REASONS),before)
            self.assertFalse((Path(directory)/'exports').exists())

    def test_incomplete_campaign_requires_explicit_progress_mode(self):
        meta=dict(cases=['damage'],smoke=False,commands={'one':dict(case='damage',policy='PRODUCTION',salt=0,seed=12073,processWallSeconds=1)})
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            self.assertEqual(report.validate_matrix(meta,root,True),6)
            with self.assertRaisesRegex(ValueError,'incomplete'):report.validate_matrix(meta,root)
            meta['commands']['duplicate']=dict(meta['commands']['one'])
            with self.assertRaisesRegex(ValueError,'duplicate'):report.validate_matrix(meta,root,True)

    def test_native_early_timeout_keeps_missing_start_and_sparse_zero_scope_distinct(self):
        data=json.loads(gzip.decompress((HERE/'fixtures/early-admission-timeout.json.gz').read_bytes()))
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);games=root/'games';games.mkdir();label='timeout-prefix-0'
            for kind in ('full','decisions'):(games/(label+'-'+kind+'.jsonl')).write_text(''.join(json.dumps(r)+'\n' for r in data[kind]))
            (games/(label+'-sidecar.json')).write_text(json.dumps(data['sidecar']))
            result=report.validate_game(root,label,data['command'])
            self.assertEqual(result['status'],'TIMEOUT');self.assertFalse(result['gameStartedObserved']);self.assertFalse(result['meterCoverageEstablished'])
            self.assertIsNone(result['initialShufflesBeforeGameStarted']);self.assertEqual(result['initialShufflesAtEarlyCutoff'],4)
            self.assertEqual(result['observedInputTotals']['records'],0);self.assertEqual(result['outcomeEligibleDecisions'],0)
            self.assertIsNone(result['sourceAccounting']['startRecordMissingFractionAtCutoffs'])

    def test_v28_experimental_overlay_refuses_new_production_admission_instead_of_replacing_its_gate(self):
        production=Path(__file__).resolve().parents[2]/'forge-ai/src/main/java/forge/ai/AiInputContext.java';source=production.read_text()
        self.assertIn('PERMITTED_CHOICE_VIEW_V29', source)
        self.assertEqual(production.read_text(),source)
        with self.assertRaisesRegex(ValueError, 'source-boundary review'):runner.overlay_source(source)
