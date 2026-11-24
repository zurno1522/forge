#!/usr/bin/env python3
"""Run V4 workload control/profile JVMs sequentially against a built Forge checkout."""
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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath-file', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--user-home', type=Path, required=True, help='Writable isolated Forge profile directory')
    parser.add_argument('--order', choices=('control-first', 'profile-first'), default='control-first')
    parser.add_argument('--iterations', type=int, default=80)
    parser.add_argument('--warmup', type=int, default=20)
    parser.add_argument('--rounds', type=int, default=4)
    parser.add_argument('--java-home', type=Path, default=Path(os.environ.get('JAVA_HOME', '/usr')))
    args = parser.parse_args()
    if args.iterations <= 0 or args.warmup < 0 or args.rounds < 4:
        parser.error('iterations must be positive, warmup nonnegative and rounds at least four')
    root = Path(__file__).resolve().parents[3]
    here = Path(__file__).resolve().parent
    out = args.output_dir.resolve()
    out.mkdir(parents=True, exist_ok=False)
    classes = out / 'classes'; classes.mkdir()
    exports = ['--add-exports=java.base/' + name + '=ALL-UNNAMED'
               for name in ('jdk.internal.org.objectweb.asm', 'jdk.internal.org.objectweb.asm.commons')]
    cp = args.classpath_file.read_text().strip()
    sources = [here / ('InputContextPhase' + name + '.java') for name in ('Agent', 'Metrics')]
    sources += [here / 'InputContextWorkloadProbe.java', here / 'TraceWriterProbeSink.java']
    subprocess.run([str(args.java_home / 'bin/javac'), *exports, '-cp', cp, '-d', str(classes),
                    *map(str, sources)], check=True)
    manifest = out / 'agent.mf'; manifest.write_text('Premain-Class: forge.ai.InputContextPhaseAgent\n\n')
    jar = out / 'agent.jar'
    subprocess.run([str(args.java_home / 'bin/jar'), 'cfm', str(jar), str(manifest), '-C', str(classes), '.'], check=True)
    metadata = dict(revision=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
                    dirty=bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=root)),
                    sourceSha256={str(p.relative_to(root)): sha(p) for p in sources},
                    classSha256={str(p.relative_to(root)): sha(p) for p in (
                        root / 'forge-ai/target/classes/forge/ai/AiInputContext.class',
                        root / 'forge-ai/target/classes/forge/ai/AiInputContext$Capture.class',
                        root / 'forge-ai/target/classes/forge/ai/AiEffectChoiceRecorder.class',
                        root / 'forge-ai/target/classes/forge/ai/AiObservationAccounting.class',
                        root / 'forge-ai/target/classes/forge/ai/AiChoiceRecorder.class',
                        root / 'forge-ai/target/classes/forge/ai/AiContextDisclosures.class',
                        root / 'forge-ai/target/classes/forge/ai/AiDecisionEvent.class',
                        root / 'forge-gui-desktop/target/classes/forge/view/SimulationTraceWriter.class',
                        root / 'forge-game/target/classes/forge/game/Game.class')},
                    javaVersion=subprocess.check_output([str(args.java_home / 'bin/java'), '-version'], stderr=subprocess.STDOUT, text=True),
                    nonoverlappingJvms=True, commands={})
    for label in (('control', 'profiled') if args.order == 'control-first' else ('profiled', 'control')):
        command = ['xvfb-run', '-a', str(args.java_home / 'bin/java'), '-Xms256m', '-Xmx1g', *exports,
                   '-Duser.home=' + str(args.user_home.resolve()),
                   '-Dforge.context.iterations=' + str(args.iterations), '-Dforge.context.warmup=' + str(args.warmup),
                   '-Dforge.context.rounds=' + str(args.rounds),
                   '-javaagent:' + str(jar), '-Dforge.context.profile=' + str(label == 'profiled').lower(),
                   *['--add-opens=java.base/' + name + '=ALL-UNNAMED'
                     for name in ('java.util', 'java.lang', 'java.lang.reflect', 'java.text', 'java.time')],
                   '-cp', str(classes) + os.pathsep + cp, 'forge.ai.InputContextWorkloadProbe', str(out / (label + '.json'))]
        metadata['commands'][label] = command
        start = time.monotonic()
        print('Running', label, flush=True)
        with (out / (label + '.log')).open('w') as log:
            subprocess.run(command, cwd=root / 'forge-gui-desktop', check=True, stdout=log, stderr=subprocess.STDOUT, timeout=600)
        metadata[label + 'WallSeconds'] = time.monotonic() - start
        (out / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print('Completed control and profile JVMs:', out, flush=True)


if __name__ == '__main__':
    main()
