import importlib.util
from pathlib import Path
import unittest

path = Path(__file__).with_name('input-context-benchmark') / 'summarize-workloads.py'
spec = importlib.util.spec_from_file_location('workload_measurements', path)
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)
FIELDS = ('entered', 'prepared', 'omitted', 'callbackStarted', 'callbackReturned', 'callbackThrew', 'inputUnavailable',
          'startAttempts', 'startReturned', 'terminalAttempts', 'terminalReturned', 'finished', 'observationFailures',
          'omittedUnsupported', 'omittedClosed', 'omittedFailure')


def row(workload, mode, round, profiled, memory=False, exhaustion=False):
    players, board, stack, delivery = summary.CONFIG[workload]
    n = 1 if memory else 4 if exhaustion else 2
    available = n//2 if exhaustion else n
    unavailable = n-available if exhaustion else 0
    inputs = mode in summary.INPUTS
    tracked = mode in ('ACCOUNTED_BASE', 'NUMERIC', 'ENTITY')
    off = mode in ('DISABLED', 'COPY')
    account = dict.fromkeys(FIELDS, 0)
    if tracked:
        for key in ('entered', 'prepared', 'callbackStarted', 'callbackReturned', 'startAttempts', 'startReturned', 'terminalAttempts', 'terminalReturned', 'finished'):
            account[key] = n
        account['inputUnavailable'] = unavailable
    phases = {key: dict(calls=0, cpuNanos=0, allocatedBytes=0) for key in summary.PHASES}
    if profiled:
        for key in ('capture', 'contextFreeze', 'canonicalOrdering'):
            phases[key]['calls'] = n if inputs else 0
        for key in ('eventFreeze', 'publication', 'serialization'):
            phases[key]['calls'] = 0 if off or mode == 'CAPTURE' else 2*n
    phases['peakSampledPayloadBytes'] = 128 if memory else 0
    statuses = dict(PARTIAL=available) if inputs else {}
    if unavailable: statuses['UNAVAILABLE'] = unavailable
    return dict(workload=workload, players=players, boardCardsPerPlayer=board, stackEntries=stack, delivery=delivery,
                mode=mode, round=round, memorySample=memory, exhaustionSample=exhaustion, profiled=profiled,
                iterations=n, warmupIterations=0, cpuNanos=1000*n, allocatedBytes=100*n,
                callbacks=0 if mode == 'CAPTURE' else n, events=0 if off or mode == 'CAPTURE' else 2*n,
                phases=phases, statuses=statuses, omissions={}, accountingDelta=account,
                workConsumed=0 if off else 10*n, remainingWork=None if off else 0 if exhaustion else 1000,
                linkedDisclosureFacts=available*32 if inputs and delivery == 'PUBLIC32' and workload != 'CAPPED' else 0,
                diagnosticOptionEntries=available*players*board if mode == 'ENTITY' else 0,
                linkedOptions=available if mode == 'ENTITY' else 0,
                windows=[dict(fromInvocation=0, invocations=2, available=2, unavailable=0),
                         dict(fromInvocation=2, invocations=2, available=0, unavailable=2)] if exhaustion else [],
                policySha256=workload+'-'+mode, lastContextReachableBytes=128, lastContextRawBytes=64,
                controllerLedgersReachableBytes=128, disclosureFactPayloadReachableBytes=64,
                publicProofSlots=32 if delivery == 'PUBLIC32' else 0, proofShellShallowBytes=128)


def measurements(profiled):
    rows = [row(w, m, r, profiled) for w in summary.WORKLOADS for m in summary.MODES for r in range(4)]
    if profiled: rows.extend(row(w, 'ENTITY', 0, True, memory=True) for w in summary.WORKLOADS)
    rows.append(row('MULTIPLAYER', 'ENTITY', 0, profiled, exhaustion=True))
    return rows


class WorkloadMeasurementTests(unittest.TestCase):
    def test_profile_declarations_preserve_legacy_v4_and_prevent_mislabeled_new_measurements(self):
        control, profiled = measurements(False), measurements(True)
        self.assertEqual(summary.summarize(control, profiled)['profile'], 'PERMITTED_CHOICE_VIEW_V4')
        for r in control + profiled: r['inputContextProfile'] = 'PERMITTED_CHOICE_VIEW_V5'
        self.assertEqual(summary.summarize(control, profiled)['profile'], 'PERMITTED_CHOICE_VIEW_V5')
        for r in control + profiled: r['inputContextProfile'] = 'PERMITTED_CHOICE_VIEW_V6'
        self.assertEqual(summary.summarize(control, profiled)['profile'], 'PERMITTED_CHOICE_VIEW_V6')
        for r in control + profiled: r['inputContextProfile'] = 'PERMITTED_CHOICE_VIEW_V7'
        self.assertEqual(summary.summarize(control, profiled)['profile'], 'PERMITTED_CHOICE_VIEW_V7')
        for r in control + profiled: r['inputContextProfile'] = 'PERMITTED_CHOICE_VIEW_V8'
        self.assertEqual(summary.summarize(control, profiled)['profile'], 'PERMITTED_CHOICE_VIEW_V8')
        for r in control + profiled: r['inputContextProfile'] = 'PERMITTED_CHOICE_VIEW_V9'
        self.assertEqual(summary.summarize(control, profiled)['profile'], 'PERMITTED_CHOICE_VIEW_V9')
        for r in control + profiled: r['inputContextProfile'] = 'PERMITTED_CHOICE_VIEW_V10'
        self.assertEqual(summary.summarize(control, profiled)['profile'], 'PERMITTED_CHOICE_VIEW_V10')
        for r in control + profiled: r['inputContextProfile'] = 'PERMITTED_CHOICE_VIEW_V11'
        self.assertEqual(summary.summarize(control, profiled)['profile'], 'PERMITTED_CHOICE_VIEW_V11')
        for r in control + profiled: r['inputContextProfile'] = 'PERMITTED_CHOICE_VIEW_V12'
        self.assertEqual(summary.summarize(control, profiled)['profile'], 'PERMITTED_CHOICE_VIEW_V12')
        for r in control + profiled: r['inputContextProfile'] = 'PERMITTED_CHOICE_VIEW_V13'
        self.assertEqual(summary.summarize(control, profiled)['profile'], 'PERMITTED_CHOICE_VIEW_V13')
        for version in (14, 15):
            for r in control + profiled: r['inputContextProfile'] = f'PERMITTED_CHOICE_VIEW_V{version}'
            self.assertEqual(summary.summarize(control, profiled)['profile'], f'PERMITTED_CHOICE_VIEW_V{version}')
        control[0]['inputContextProfile'] = 'PERMITTED_CHOICE_VIEW_V4'
        with self.assertRaises(ValueError): summary.summarize(control, profiled)
        control[0].pop('inputContextProfile')
        with self.assertRaises(ValueError): summary.summarize(control, profiled)
        control[0]['inputContextProfile'] = 'PERMITTED_CHOICE_VIEW_V99'
        with self.assertRaises(ValueError): summary.summarize(control, profiled)

    def test_healthy_costs_and_exhaustion_evidence_are_kept_separate(self):
        result = summary.summarize(measurements(False), measurements(True))
        self.assertEqual(len(result['fixtures']), 5)
        self.assertEqual(result['fixtures'][0]['modes']['ENTITY']['control']['cpuMicrosPerOperation']['median'], 1)
        self.assertEqual(len(result['exhaustion']), 2)
        self.assertEqual(result['exhaustion'][0]['statuses']['UNAVAILABLE'], 2)

    def test_unavailable_input_cannot_make_a_healthy_round_appear_cheaper(self):
        rows = measurements(False)
        target = next(r for r in rows if r['mode'] == 'ENTITY')
        target['statuses'] = {'UNAVAILABLE': target['iterations']}
        target['cpuNanos'] = 1
        with self.assertRaisesRegex(ValueError, 'Unavailable capture'): summary.validate(rows, False)

    def test_false_public_proof_counts_callback_counts_and_scope_dimensions_are_rejected(self):
        for field in ('linkedDisclosureFacts', 'callbacks', 'diagnosticOptionEntries', 'players', 'source-count', 'phase-count', 'phase-overlap'):
            rows = measurements(True)
            target = next(r for r in rows if r['workload'] == 'PUBLIC32' and r['mode'] == 'ENTITY')
            if field == 'source-count': target['accountingDelta']['entered'] -= 1
            elif field == 'phase-count': target['phases']['eventFreeze']['calls'] -= 1
            elif field == 'phase-overlap': target['phases']['serialization']['cpuNanos'] = 1
            else: target[field] -= 1
            with self.subTest(field=field), self.assertRaises(ValueError): summary.validate(rows, True)

    def test_exhaustion_windows_must_reconcile_and_be_contiguous(self):
        for field in ('remainingWork', 'partition', 'offset', 'unavailable'):
            rows = measurements(False); target = rows[-1]
            if field == 'remainingWork': target[field] = 1
            if field == 'partition': target['windows'].pop()
            if field == 'offset': target['windows'][1]['fromInvocation'] = 1
            if field == 'unavailable': target['windows'][0]['unavailable'] = 1
            with self.subTest(field=field), self.assertRaises(ValueError): summary.validate(rows, False)

    def test_policy_mismatch_missing_round_and_missing_memory_do_not_produce_a_summary(self):
        for error in ('policy', 'round', 'memory'):
            rows = measurements(True)
            if error == 'policy': rows[0]['policySha256'] = 'changed information'
            if error == 'round': rows.pop(0)
            if error == 'memory': rows = [r for r in rows if not r['memorySample']]
            with self.subTest(error=error), self.assertRaises(ValueError): summary.summarize(measurements(False), rows)
