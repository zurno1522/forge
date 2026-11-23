import copy
import importlib.util
from pathlib import Path
import unittest

path = Path(__file__).with_name('input-context-benchmark') / 'summarize-phases.py'
spec = importlib.util.spec_from_file_location('phase_summary', path)
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)


def measurements(profiled):
    rows = []
    for board in (4, 30):
        for facts in (0, 32):
            for mode in summary.MODES:
                for round_number in range(4):
                    n = 10
                    calls = dict(capture=n if mode in ('INPUT_CHOICE', 'CAPTURE_ONLY') else 0,
                                 contextFreeze=n if mode in ('INPUT_CHOICE', 'CAPTURE_ONLY') else 0,
                                 canonicalOrdering=6*n if mode in ('INPUT_CHOICE', 'CAPTURE_ONLY') else 0,
                                 eventFreeze=2*n if mode in ('INPUT_CHOICE', 'BASE_CHOICE') else n if mode == 'FREEZE_ONLY' else 0,
                                 publication=2*n if mode in ('INPUT_CHOICE', 'BASE_CHOICE') else n if mode == 'PUBLISH_ONLY' else 0,
                                 serialization=2*n if mode in ('INPUT_CHOICE', 'BASE_CHOICE') else n if mode == 'SERIALIZE_ONLY' else 0,
                                 disclosureDelivery=n if mode == 'DISCLOSURE_ONLY' else 0)
                    phases = {phase: dict(calls=value if profiled else 0, cpuNanos=0, allocatedBytes=0) for phase,value in calls.items()}
                    if profiled and mode == 'INPUT_CHOICE':
                        phases['capture'].update(cpuNanos=10000, allocatedBytes=1000)
                        phases['contextFreeze'].update(cpuNanos=2000, allocatedBytes=200)
                        phases['canonicalOrdering'].update(cpuNanos=1000, allocatedBytes=100)
                        phases['publication'].update(cpuNanos=6000, allocatedBytes=600)
                        phases['serialization'].update(cpuNanos=5000, allocatedBytes=500)
                    phases['peakSampledPayloadBytes'] = 0
                    rows.append(dict(profiled=profiled, memorySample=False, boardCardsPerPlayer=board,
                                     disclosureFacts=facts, mode=mode, round=round_number, iterations=n,
                                     phases=phases, statuses={'CAPTURED': n} if mode in ('INPUT_CHOICE','CAPTURE_ONLY') else {},
                                     workConsumed=0, events=2*n if mode in ('INPUT_CHOICE','BASE_CHOICE') else n if mode=='PUBLISH_ONLY' else 0,
                                     callbacks=n if mode in ('INPUT_CHOICE','BASE_CHOICE','DISABLED','COPY') else 0,
                                     serializedCharactersIncludingWarmup=1000 if mode in ('INPUT_CHOICE','BASE_CHOICE','SERIALIZE_ONLY') else 0,
                                     policySha256='same-permitted-policy', cpuNanos=20000, allocatedBytes=2000,
                                     gcCollections=0, gcMillis=0, representativeRawBytes=100,
                                     representativeGzipBytes=50, lastContextReachableBytes=80, disclosureFactsReachableBytes=40))
            if profiled:
                for mode in ('INPUT_CHOICE','CAPTURE_ONLY','FREEZE_ONLY'):
                    row=copy.deepcopy(next(r for r in rows if r['boardCardsPerPlayer']==board and r['disclosureFacts']==facts and r['mode']==mode))
                    row['memorySample']=True
                    row['cpuNanos']=10**12  # Reflection/sizing cost must never contaminate timing medians.
                    row['phases']['peakSampledPayloadBytes']=1234
                    rows.append(row)
    return rows


class PhaseMeasurementTests(unittest.TestCase):
    def test_inclusive_children_are_subtracted_once_and_memory_sampling_is_excluded(self):
        result=summary.summarize(measurements(False),measurements(True))
        choice=result['fixtureResults'][0]['modes']['INPUT_CHOICE']
        self.assertAlmostEqual(choice['projectionResidual']['cpuMicrosPerOperation']['median'],0.7)
        self.assertEqual(choice['projectionResidual']['allocatedBytesPerOperation']['median'],70)
        self.assertAlmostEqual(choice['publicationResidual']['cpuMicrosPerOperation']['median'],0.1)
        self.assertEqual(choice['profiled']['cpuMicrosPerOperation']['median'],2)
        self.assertEqual(result['fixtureResults'][0]['memoryOnlySamples']['INPUT_CHOICE'],1234)

    def test_missing_round_unavailable_capture_and_missing_instrumentation_are_rejected(self):
        for error in ('missing','unavailable','uninstrumented','disabled-work'):
            rows=measurements(True)
            if error=='missing': rows.pop(0)
            if error=='unavailable':
                next(r for r in rows if r['mode']=='INPUT_CHOICE')['statuses']={'UNAVAILABLE':10}
            if error=='uninstrumented':
                next(r for r in rows if r['mode']=='INPUT_CHOICE')['phases']['capture']['calls']=0
            if error=='disabled-work':
                next(r for r in rows if r['mode']=='COPY')['workConsumed']=1
            with self.subTest(error=error),self.assertRaises(ValueError): summary.summarize(measurements(False),rows)

    def test_policy_difference_prevents_a_performance_comparison(self):
        rows=measurements(True)
        next(r for r in rows if r['mode']=='INPUT_CHOICE')['policySha256']='changed-private-or-public-view'
        with self.assertRaisesRegex(ValueError,'policy differs'): summary.summarize(measurements(False),rows)

    def test_missing_memory_evidence_is_not_reported_as_zero_peak(self):
        rows=[r for r in measurements(True) if not (r['memorySample'] and r['mode']=='INPUT_CHOICE')]
        with self.assertRaisesRegex(ValueError,'memory-only'): summary.summarize(measurements(False),rows)

    def test_overlapping_spans_cannot_produce_negative_exclusive_cost(self):
        rows=measurements(True)
        next(r for r in rows if r['mode']=='INPUT_CHOICE')['phases']['contextFreeze']['allocatedBytes']=1001
        with self.assertRaisesRegex(ValueError,'Child spans exceed'): summary.summarize(measurements(False),rows)

    def test_a_timed_serializer_that_produces_no_characters_is_rejected(self):
        rows=measurements(True)
        next(r for r in rows if r['mode']=='SERIALIZE_ONLY')['serializedCharactersIncludingWarmup']=0
        with self.assertRaisesRegex(ValueError,'sink character'): summary.summarize(measurements(False),rows)
