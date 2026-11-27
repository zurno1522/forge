import copy
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from test_export import module

HERE = Path(__file__).with_name('input-context-benchmark')
spec = importlib.util.spec_from_file_location('pipeline_costs', HERE/'summarize-pipeline-costs.py')
pipeline = importlib.util.module_from_spec(spec); spec.loader.exec_module(pipeline)
CASES = json.loads(gzip.decompress((HERE/'fixtures/pipeline-cases.json.gz').read_bytes()))
FILE = json.loads(gzip.decompress((HERE/'fixtures/pipeline-file.json.gz').read_bytes()))


def row(mode='NUMBER'):
    return copy.deepcopy(next(r for r in CASES['profiled'] if r['fixture']=='SMALL' and r['mode']==mode))


class PipelineMeasurementTests(unittest.TestCase):
    def test_actual_native_receipts_keep_healthy_exhausted_and_delivery_failures_distinct(self):
        for label in ('control', 'profiled'):
            self.assertEqual(len(pipeline.validate_matrix(CASES[label],label=='profiled',True)),18)
        self.assertEqual(row('WRITER_FAILURE')['statuses'],{'PARTIAL':2})
        self.assertEqual(row('WRITER_FAILURE')['measuredFileBytes'],0)
        self.assertEqual(row('CAPTURE_FAILURE')['sourceDelta']['observationFailures'],2)
        self.assertEqual(row('EXHAUSTED')['workConsumed'],0)

    def test_callback_source_and_entry_counts_cannot_hide_lost_or_repeated_work(self):
        for field in ('callbacks','choiceEvents','entryLocationRecords'):
            r=row();r[field]+=1
            with self.subTest(field=field),self.assertRaises(ValueError):pipeline.validate_row(r,True)
        for field in ('entered','callbackReturned','finished','observationFailures','inputUnavailable'):
            r=row();r['sourceDelta'][field]+=1
            with self.subTest(field=field),self.assertRaises(ValueError):pipeline.validate_row(r,True)

    def test_unavailable_or_failed_work_cannot_be_reported_as_healthy(self):
        for mode in ('EXHAUSTED','CAPTURE_FAILURE','WRITER_FAILURE'):
            r=row(mode);r['mode']='NUMBER'
            with self.subTest(mode=mode),self.assertRaises(ValueError):pipeline.validate_row(r,True)
        r=row('EXHAUSTED');r['remainingWork']=1
        with self.assertRaises(ValueError):pipeline.validate_row(r,True)
        r=row('WRITER_FAILURE');r['sinkFailed']=False
        with self.assertRaises(ValueError):pipeline.validate_row(r,True)

    def test_entry_receipt_unknown_failure_factory_and_final_flush_are_separate_phase_calls(self):
        for mode in ('NUMBER','MAX_X','DAMAGE','CAPTURE_FAILURE','WRITER_FAILURE'):
            for phase in ('eventFreeze','contextFreeze','serialization'):
                r=row(mode);r['phases'][phase]['calls']+=1
                with self.subTest(mode=mode,phase=phase),self.assertRaises(ValueError):pipeline.validate_row(r,True)
        r=row();r['profiled']=False
        with self.assertRaisesRegex(ValueError,'Instrumented control'):pipeline.validate_row(r,False)

    def test_schema_counts_and_metric_types_are_strict(self):
        for field,value in (('measurementSchemaVersion',True),('profile','PERMITTED_CHOICE_VIEW_V28'),('round',True),('iterations',True),('callbacks',True),('allocatedBytes',-1),('measuredFileOffset',-1)):
            r=row();r[field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):pipeline.validate_row(r,True)

    def test_actual_encoded_file_round_trips_unicode_and_the_measured_byte_slice(self):
        with tempfile.TemporaryDirectory() as directory:
            r=copy.deepcopy(FILE['row']);path=Path(directory)/'physical.jsonl';path.write_bytes(FILE['data'].encode());r['file']=str(path)
            pipeline.validate_row(r,True);pipeline.validate_file(r,module._choices)
            self.assertIn('α',FILE['data']);self.assertIn('🧪',FILE['data'])

    def test_file_hash_byte_interval_and_line_receipts_detect_corruption(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'physical.jsonl';path.write_bytes(FILE['data'].encode())
            for field in ('fileBytes','measuredFileOffset','measuredFileBytes','measuredLineCount'):
                r=copy.deepcopy(FILE['row']);r['file']=str(path);r[field]+=1
                with self.subTest(field=field),self.assertRaises(ValueError):pipeline.validate_file(r,module._choices)
            r=copy.deepcopy(FILE['row']);r['file']=str(path);path.write_bytes(path.read_bytes()+b'x')
            with self.assertRaises(ValueError):pipeline.validate_file(r,module._choices)

    def test_correct_byte_hashes_do_not_hide_reordered_events_or_reused_choice_ids(self):
        for fault in ('order','reused-id'):
            r=copy.deepcopy(FILE['row']);data=FILE['data'].encode()[r['measuredFileOffset']:r['measuredEndOffset']]
            records=[json.loads(s) for s in data.splitlines()]
            if fault=='order':records[1]['eventIndex']+=100
            else:
                records[2]['choice']['choiceId']=records[0]['choice']['choiceId'];records[3]['choice']['choiceId']=records[0]['choice']['choiceId']
            data=(''.join(json.dumps(s)+'\n' for s in records)).encode()
            with tempfile.TemporaryDirectory() as directory:
                path=Path(directory)/'physical.jsonl';path.write_bytes(data)
                r.update(file=str(path),fileBytes=len(data),measuredFileOffset=0,measuredEndOffset=len(data),measuredFileBytes=len(data),fileSha256=hashlib.sha256(data).hexdigest(),measuredFileSha256=hashlib.sha256(data).hexdigest())
                with self.subTest(fault=fault),self.assertRaises(ValueError):pipeline.validate_file(r,module._choices)

    def test_incomplete_duplicate_or_smoke_matrices_cannot_claim_a_complete_study(self):
        with self.assertRaisesRegex(ValueError,'Incomplete rounds'):pipeline.validate_matrix(CASES['profiled'],True)
        with self.assertRaisesRegex(ValueError,'matrix'):pipeline.validate_matrix(CASES['profiled'][:-1],True,True)
        with self.assertRaisesRegex(ValueError,'matrix'):pipeline.validate_matrix(CASES['profiled']+[CASES['profiled'][0]],True,True)
