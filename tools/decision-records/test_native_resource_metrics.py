import copy
import importlib.util
import json
from pathlib import Path
import unittest

HERE=Path(__file__).with_name('input-context-benchmark')
spec=importlib.util.spec_from_file_location('native_resources',HERE/'native_resource_metrics.py')
metrics=importlib.util.module_from_spec(spec);spec.loader.exec_module(metrics)
CASES=json.loads((HERE/'fixtures/native-resource-cases.json').read_text())['cases']


class NativeResourceMetricTests(unittest.TestCase):
    def test_actual_native_process_envelopes_validate_without_capture_cost_inference(self):
        for case in CASES:
            self.assertIs(metrics.validate(case,case['commandArguments']),case)
            self.assertGreater(case['allThreadsJavaAllocatedBytes'],0)

    def test_unsupported_metrics_stay_unknown_instead_of_becoming_zero(self):
        case=copy.deepcopy(CASES[0])
        for key in ('processCpuNanos','allThreadsJavaAllocatedBytes','peakResidentBytesSinceJvmStart'):case[key]=None
        case['heapPoolPeaksSinceJvmStart']={'unsupported':None};case['gcSinceJvmStart']={'unsupported':dict(collections=None,milliseconds=None)}
        result=metrics.validate(case,case['commandArguments'])
        self.assertIsNone(result['processCpuNanos']);self.assertIsNone(result['gcSinceJvmStart']['unsupported']['collections'])

    def test_bad_versions_types_scope_and_negative_process_counters_are_rejected(self):
        for key,value in (('schemaVersion',True),('schemaVersion',2),('scope','CAPTURE_CPU'),('trainingReady',True),('wallNanos',0),('wallNanos',True),('processCpuNanos',-1),('allThreadsJavaAllocatedBytes',True),('heapUsedAtShutdown',-1),('peakResidentBytesSinceJvmStart',1<<63)):
            case=copy.deepcopy(CASES[0]);case[key]=value
            with self.subTest(key=key,value=value),self.assertRaises(ValueError):metrics.validate(case,case['commandArguments'])

    def test_resources_cannot_be_attached_to_another_native_command(self):
        case=copy.deepcopy(CASES[0]);args=list(case['commandArguments']);args[-1]='DIFFERENT'
        with self.assertRaisesRegex(ValueError,'command differs'):metrics.validate(case,args)
        case['unattributedNativeBytes']=0
        with self.assertRaisesRegex(ValueError,'unknown fields'):metrics.validate(case,case['commandArguments'])

    def test_heap_pool_peaks_and_gc_counters_keep_their_own_strict_scope(self):
        for value in (-1,True,1.0):
            case=copy.deepcopy(CASES[0]);case['heapPoolPeaksSinceJvmStart']={'pool':value}
            with self.subTest(value=value),self.assertRaises(ValueError):metrics.validate(case,case['commandArguments'])
        for entry in ({'collections':-1,'milliseconds':0},{'collections':0,'milliseconds':True},{'collections':0,'milliseconds':0,'exclusiveCaptureBytes':0}):
            case=copy.deepcopy(CASES[0]);case['gcSinceJvmStart']={'gc':entry}
            with self.subTest(entry=entry),self.assertRaises(ValueError):metrics.validate(case,case['commandArguments'])
