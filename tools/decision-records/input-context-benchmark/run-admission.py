"""Build isolated admission hooks and run production/prefix controls plus candidate JVMs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--classpath-file', type=Path, required=True)
    p.add_argument('--java-home', type=Path, required=True)
    p.add_argument('--user-home', type=Path, required=True)
    p.add_argument('--output-dir', type=Path, required=True)
    p.add_argument('--rounds', type=int, default=3)
    p.add_argument('--smoke', action='store_true')
    args = p.parse_args()
    if args.rounds < 3 and not args.smoke:
        p.error('At least three measured rounds are required')
    here = Path(__file__).resolve().parent; repo = here.parents[2]
    out = args.output_dir.resolve(); out.mkdir(exist_ok=False, parents=True)
    classes = out / 'classes'; overlay = out / 'overlay'; classes.mkdir(); overlay.mkdir()
    cp = args.classpath_file.read_text().strip()
    sources = [here / 'AvailabilityAdmissionProbe.java', here / 'TraceWriterProbeSink.java']
    subprocess.run([str(args.java_home / 'bin/javac'), '--release', '17', '-cp', cp, '-d', str(classes), *map(str, sources)], check=True)
    production = repo / 'forge-ai/src/main/java/forge/ai/AiInputContext.java'
    original = production.read_text()
    funding = 'remaining = new AtomicLong(MAX_GAME_WORK / Math.max(1, chooser.getGame().getRegisteredPlayers().size()));'
    gate = 'final State state = controller.inputContextState();'
    assert original.count(funding) == original.count(gate) == 1, 'Producer hooks need a fresh boundary review'
    generated = original.replace(funding, 'remaining = new AtomicLong(AvailabilityAdmissionProbe.initialAllowance(chooser, MAX_GAME_WORK / Math.max(1, chooser.getGame().getRegisteredPlayers().size())));')
    generated = generated.replace(gate, gate + '\n        final String experimentReason = AvailabilityAdmissionProbe.beforeCapture(chooser, state);\n        if (experimentReason != null) { return unavailable(chooser, timing, stateBasis, experimentReason); }')
    source = overlay / 'AiInputContext.java'; source.write_text(generated)
    subprocess.run([str(args.java_home / 'bin/javac'), '--release', '17', '-cp', str(classes) + os.pathsep + cp, '-d', str(overlay), str(source)], check=True)
    class_paths = [repo / name for name in ('forge-ai/target/classes/forge/ai/AiInputContext.class', 'forge-ai/target/classes/forge/ai/AiChoiceRecorder.class', 'forge-ai/target/classes/forge/ai/AiContextDisclosures.class', 'forge-gui-desktop/target/classes/forge/view/SimulationTraceWriter.class')]
    metadata = dict(revision=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip(),
                    dirty=bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=repo)),
                    sourceSha256={str(s.relative_to(repo)): sha(s) for s in [*sources, production, Path(__file__).resolve()]},
                    productionClassSha256={str(s.relative_to(repo)): sha(s) for s in class_paths},
                    overlaySourceSha256=sha(source), smoke=args.smoke, rounds=args.rounds,
                    javaVersion=subprocess.check_output([str(args.java_home / 'bin/java'), '-version'], stderr=subprocess.STDOUT, text=True), commands={})
    for label, use_overlay, reverse in [('production', False, False), ('forward', True, False), ('reverse', True, True)]:
        command = ['xvfb-run', '-a', str(args.java_home / 'bin/java'), '-Xms256m', '-Xmx1g',
                   '-Duser.home=' + str(args.user_home.resolve()), '-Dforge.availability.rounds=' + str(args.rounds),
                   '-Dforge.availability.overlay=' + str(use_overlay).lower(), '-Dforge.availability.reverse=' + str(reverse).lower(),
                   '-Dforge.availability.smoke=' + str(args.smoke).lower(),
                   *['--add-opens=java.base/' + name + '=ALL-UNNAMED' for name in ('java.util', 'java.lang', 'java.lang.reflect', 'java.text', 'java.time')],
                   '-cp', (str(overlay) + os.pathsep if use_overlay else '') + str(classes) + os.pathsep + cp,
                   'forge.ai.AvailabilityAdmissionProbe', str(out / (label + '.json'))]
        metadata['commands'][label] = command
        (out / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
        start = time.monotonic(); print('Running', label, flush=True)
        with (out / (label + '.log')).open('w') as log:
            subprocess.run(command, cwd=repo / 'forge-gui-desktop', stdout=log, stderr=subprocess.STDOUT, check=True, timeout=1800)
        metadata[label + 'WallSeconds'] = time.monotonic() - start
        (out / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    assert all(sha(s) == metadata['productionClassSha256'][str(s.relative_to(repo))] for s in class_paths), 'Production targets changed'
    print('Completed isolated admission experiments', flush=True)


if __name__ == '__main__':
    main()
