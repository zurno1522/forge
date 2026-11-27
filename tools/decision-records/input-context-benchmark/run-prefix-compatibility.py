"""Compare ordinary V29 prefix games with isolated, source-pinned V28 classes. No sampling overlays."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time

SOURCES = ('forge-ai/src/main/java/forge/ai/AiInputContext.java',
           'forge-ai/src/main/java/forge/ai/PlayerControllerAi.java',
           'forge-ai/src/main/java/forge/ai/AiEffectChoiceRecorder.java',
           'forge-gui-desktop/src/main/java/forge/view/SimulationTraceCollector.java')
IGNORED = {'runId', 'engineRevision', 'engineDirty', 'elapsedNanos', 'eventIndex', 'sourcePath', 'sourceSha256'}


def require(ok, message):
    if not ok: raise ValueError(message)


def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()


def digest(value): return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def semantic(value):
    """Retain all represented inputs, results, provenance and accounting; remove only envelope metadata."""
    if isinstance(value, dict):
        context = str(value.get('profile', '')).startswith('PERMITTED_CHOICE_VIEW_V')
        return {k: semantic(v) for k, v in value.items()
                if k not in IGNORED and not (context and k in ('schemaVersion', 'profile', 'admission'))}
    if isinstance(value, list): return [semantic(v) for v in value]
    return value


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path); result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result); return result


def validate(root, label, reader):
    paths = [root / 'games' / (label + '-' + kind + '.jsonl') for kind in ('full', 'decisions')]
    reports = [reader.export([p]) for p in paths]
    for r in reports: r.pop('sourceRecords')
    require(reports[0] == reports[1], 'Full/filtered validation differs: ' + label)
    report = reports[0]; require(report['observationAccounting']['scopeComplete'], 'Incomplete source scope')
    require(report['observationAccounting']['counts'].get('observationFailures', 0) == 0, 'Observation failed')
    records = [json.loads(s) for s in paths[0].read_text().splitlines()]
    finished = records[-1]; require(finished['type'] == 'RUN_FINISHED', 'Missing run finish')
    start = next((i for i, r in enumerate(records) if r['type'] == 'GAME_STARTED'), None)
    initial = [r for r in records[:start] if r['type'] == 'SHUFFLE']
    participants = records[0]['participants']; require(len(initial) == len(participants), 'Initial shuffle order/cardinality changed')
    rng_path = root / 'games' / (label + '-rng.json'); rng = json.loads(rng_path.read_text())
    require(rng['policy'] == 'PRODUCTION' and rng['actors'] == [], 'Unexpected experimental metering')
    normal = finished['status'] == 'NORMAL'
    if normal: require(rng['rngCalls'] == rng['rngCallsAfterSnapshot'], 'Normal-run RNG raced snapshot')
    else: require(finished['status'] == 'TIMEOUT' and report['outcomeEligibleDecisions'] == 0, 'Invalid censoring')
    rows = [semantic(r) for r in records if r['type'] not in ('RUN_STARTED', 'RUN_FINISHED')]
    return dict(status=finished['status'], normal=normal, trainingReady=False,
                gameStartedObserved=start is not None, initialShuffles=len(initial),
                engineSha256=digest([r for r in rows if not r['type'].startswith('AI_')]),
                aiAndRepresentedInputsSha256=digest([r for r in rows if r['type'].startswith('AI_')]),
                rngStateSha256=rng['rngStateSha256'], rngCalls=rng['rngCalls'],
                inputProfiles=report.get('inputContextProfiles', {}), admissionAudit=report['inputCoverageAudit'].get('admissionAudit'),
                sourceAccounting=report['observationAccounting'], inputCoverageAudit=report['inputCoverageAudit'],
                files={p.name: dict(sha256=sha(p), bytes=p.stat().st_size) for p in paths + [rng_path]})


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for flag in ('classpath-file', 'java-home', 'user-home', 'output-dir'): p.add_argument('--' + flag, type=Path, required=True)
    p.add_argument('--reference', default='555c3fbbb35')
    args = p.parse_args(); here = Path(__file__).resolve().parent; repo = here.parents[2]
    # Resolve the committed reference explicitly; never borrow an arbitrary old working directory.
    reference = subprocess.check_output(['git', 'rev-parse', args.reference + '^{commit}'], cwd=repo, text=True).strip()
    out = args.output_dir.resolve(); out.mkdir(parents=True, exist_ok=False)
    for child in ('baseline-source', 'baseline-classes', 'wrapper', 'games'): (out / child).mkdir()
    cp = args.classpath_file.read_text().strip(); sources = []
    meta = dict(reference=reference, currentRevision=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip(),
                currentDirty=bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=repo)), referenceSources={}, currentSources={}, commands={})
    for name in SOURCES:
        data = subprocess.check_output(['git', 'show', reference + ':' + name], cwd=repo)
        target = out / 'baseline-source' / Path(name).name; target.write_bytes(data); sources.append(str(target))
        meta['referenceSources'][name] = sha(target); meta['currentSources'][name] = sha(repo / name)
    require('PERMITTED_CHOICE_VIEW_V28' in (out / 'baseline-source/AiInputContext.java').read_text(), 'Reference is not V28')
    require('PERMITTED_CHOICE_VIEW_V29' in (repo / SOURCES[0]).read_text(), 'Current producer is not V29')
    action = 'forge-game/src/main/java/forge/game/GameAction.java'
    require(subprocess.check_output(['git', 'show', reference + ':' + action], cwd=repo) == (repo / action).read_bytes(), 'GameAction differs from reference')
    for name in (action, 'forge-ai/src/main/java/forge/ai/AiInputAdmission.java',
                 'tools/decision-records/input-context-benchmark/run-prefix-compatibility.py',
                 'tools/decision-records/input-context-benchmark/GameAdmissionExperiment.java',
                 'tools/decision-records/export.py', 'tools/decision-records/input_context.py',
                 'tools/decision-records/coverage_audit.py'):
        meta['currentSources'][name] = sha(repo / name)
    subprocess.run([str(args.java_home / 'bin/javac'), '--release', '17', '-cp', cp, '-d', str(out / 'baseline-classes'), *sources], check=True)
    subprocess.run([str(args.java_home / 'bin/javac'), '--release', '17', '-cp', cp, '-d', str(out / 'wrapper'), str(here / 'GameAdmissionExperiment.java')], check=True)
    tracked = [repo / 'forge-ai/target/classes/forge/ai' / name for name in
               ('AiInputContext.class', 'AiInputContext$State.class', 'AiInputContext$Capture.class',
                'AiInputAdmission.class', 'PlayerControllerAi.class', 'AiEffectChoiceRecorder.class')]
    tracked += [repo / 'forge-gui-desktop/target/classes/forge/view/SimulationTraceCollector.class']
    tracked += [repo / 'forge-game/target/classes/forge/game' / name for name in ('Game.class', 'GameAction.class')]
    tracked += [repo / 'forge-core/target/classes/forge/util/MyRandom.class']
    meta['productionClassSha256'] = {str(f.relative_to(repo)): sha(f) for f in tracked}
    meta['referenceClassSha256'] = {str(f.relative_to(out / 'baseline-classes')): sha(f)
                                   for f in (out / 'baseline-classes').rglob('*.class')}
    templates = json.loads((repo / 'docs/Development/assessments/COV-008-native-admission/primary-metadata.json').read_text())['commands']
    reader = load('prefix_ordinary_reader', here.parent / 'export.py'); results = {}
    for index, case in enumerate(('damage', 'repeated', 'commander-a', 'timeout')):
        for version in (('V28', 'V29') if index % 2 == 0 else ('V29', 'V28')):
            label = case + '-' + version; command = list(templates[case + '-production-0']['command'])
            command[2] = str(args.java_home / 'bin/java')
            command[command.index('-cp') + 1] = ((str(out / 'baseline-classes') + os.pathsep) if version == 'V28' else '') + str(out / 'wrapper') + os.pathsep + cp
            command[command.index('-D') + 1] = str(repo / ('tools/trace-test-decks' if case in ('damage', 'repeated') else 'tools/decision-records/hardening/decks'))
            for flag, suffix in (('-log-game', '-full.jsonl'), ('-log-decisions', '-decisions.jsonl')): command[command.index(flag) + 1] = str(out / 'games' / (label + suffix))
            command = [('-Dforge.admission.sidecar=' + str(out / 'games' / (label + '-rng.json'))) if s.startswith('-Dforge.admission.sidecar=') else
                       '-Duser.home=' + str(args.user_home.resolve()) if s.startswith('-Duser.home=') else
                       '-Dforge.simulation.revision=' + (reference if version == 'V28' else meta['currentRevision']) if s.startswith('-Dforge.simulation.revision=') else
                       '-Dforge.simulation.dirty=' + str(version == 'V29' and meta['currentDirty']).lower() if s.startswith('-Dforge.simulation.dirty=') else s for s in command]
            meta['commands'][label] = command; (out / 'metadata.json').write_text(json.dumps(meta, indent=2) + '\n')
            print('Running', label, flush=True); started = time.monotonic()
            with (out / 'games' / (label + '.log')).open('w') as log:
                subprocess.run(command, cwd=repo / 'forge-gui-desktop', stdout=log, stderr=subprocess.STDOUT, timeout=360, check=True)
            elapsed = time.monotonic() - started; validation_started = time.monotonic()
            results[label] = validate(out, label, reader); results[label]['processWallSeconds'] = elapsed
            results[label]['validationWallSeconds'] = time.monotonic() - validation_started
            if case != 'timeout': require(results[label]['normal'], 'Normal compatibility control was censored: ' + label)
            (out / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
            print('Validated', label, results[label]['status'], flush=True)
        a, b = results[case + '-V28'], results[case + '-V29']
        if a['normal'] and b['normal']:
            for field in ('engineSha256', 'aiAndRepresentedInputsSha256', 'rngStateSha256', 'rngCalls'):
                require(a[field] == b[field], 'Native prefix compatibility changed: ' + case + ' ' + field)
            b['normalCompatibilityMatches'] = True
    require(all(sha(repo / name) == value for name, value in meta['productionClassSha256'].items()), 'Production classes changed during games')
    require(all(sha(repo / name) == value for name, value in meta['currentSources'].items()), 'Sources changed during games')
    (out / 'results.json').write_text(json.dumps(results, indent=2) + '\n'); print('Validated eight ordinary prefix games', flush=True)


if __name__ == '__main__': main()
