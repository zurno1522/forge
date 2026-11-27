"""Run fixed-callback physical collector/UTF-8 cost probes; outputs are synthetic operations, not games."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time


def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for flag in ('classpath-file', 'java-home', 'user-home', 'output-dir'): p.add_argument('--' + flag, type=Path, required=True)
    p.add_argument('--iterations', type=int, default=8); p.add_argument('--warmup', type=int, default=4); p.add_argument('--rounds', type=int, default=4)
    p.add_argument('--order', choices=('control-first', 'profile-first'), default='control-first')
    p.add_argument('--smoke', action='store_true')
    args = p.parse_args()
    if args.iterations < 1 or args.warmup < 1 or args.rounds < (1 if args.smoke else 4): p.error('Positive iterations/warmup and four rounds required, or explicit --smoke')
    here = Path(__file__).resolve().parent; repo = here.parents[2]; out = args.output_dir.resolve(); out.mkdir(parents=True, exist_ok=False)
    classes = out / 'classes'; classes.mkdir(); cp = args.classpath_file.read_text().strip()
    exports = ['--add-exports=java.base/' + n + '=ALL-UNNAMED' for n in ('jdk.internal.org.objectweb.asm', 'jdk.internal.org.objectweb.asm.commons')]
    sources = [here / (n + '.java') for n in ('InputContextPhaseAgent', 'InputContextPhaseMetrics', 'InputContextWorkloadProbe', 'TraceWriterProbeSink', 'TraceCollectorProbeSink', 'InputContextPipelineProbe')]
    subprocess.run([str(args.java_home / 'bin/javac'), *exports, '-cp', cp, '-d', str(classes), *map(str, sources)], check=True)
    manifest = out / 'agent.mf'; manifest.write_text('Premain-Class: forge.ai.InputContextPhaseAgent\n\n')
    jar = out / 'agent.jar'; subprocess.run([str(args.java_home / 'bin/jar'), 'cfm', str(jar), str(manifest), '-C', str(classes), '.'], check=True)
    metadata = dict(revision=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip(),
                    dirty=bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=repo)), smoke=args.smoke, commands={},
                    scope='SYNTHETIC_OPERATIONS_WITH_REAL_COLLECTOR_UTF8', sources={str(f.relative_to(repo)):sha(f) for f in sources})
    tracked = [repo / n for n in ('forge-ai/target/classes/forge/ai/AiInputContext.class', 'forge-ai/target/classes/forge/ai/AiInputContext$State.class',
               'forge-ai/target/classes/forge/ai/AiInputContext$Capture.class', 'forge-ai/target/classes/forge/ai/AiInputAdmission.class',
               'forge-ai/target/classes/forge/ai/PlayerControllerAi.class', 'forge-game/target/classes/forge/game/Game.class',
               'forge-gui-desktop/target/classes/forge/view/SimulationTraceCollector.class', 'forge-gui-desktop/target/classes/forge/view/SimulationTraceWriter.class')]
    metadata['productionClassSha256'] = {str(f.relative_to(repo)):sha(f) for f in tracked}
    for label in (('control', 'profiled') if args.order == 'control-first' else ('profiled', 'control')):
        command = ['xvfb-run', '-a', str(args.java_home / 'bin/java'), '-Xms256m', '-Xmx1g', *exports,
                   '-Duser.home=' + str(args.user_home.resolve()), '-Dforge.context.pipeline=true',
                   '-Dforge.context.profile=' + str(label == 'profiled').lower(), '-Dforge.pipeline.iterations=' + str(args.iterations),
                   '-Dforge.pipeline.warmup=' + str(args.warmup), '-Dforge.pipeline.rounds=' + str(args.rounds),
                   '-javaagent:' + str(jar), *['--add-opens=java.base/' + n + '=ALL-UNNAMED' for n in ('java.util', 'java.lang', 'java.lang.reflect', 'java.text', 'java.time')],
                   '-cp', str(classes) + os.pathsep + cp, 'forge.ai.InputContextPipelineProbe', str(out / label)]
        metadata['commands'][label] = command; (out / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
        print('Running pipeline', label, flush=True); start = time.monotonic()
        with (out / (label + '.log')).open('w') as log: subprocess.run(command, cwd=repo / 'forge-gui-desktop', stdout=log, stderr=subprocess.STDOUT, timeout=600, check=True)
        metadata[label + 'ProcessWallSeconds'] = time.monotonic() - start
        (out / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    if any(sha(repo / n) != h for n, h in metadata['productionClassSha256'].items()): raise ValueError('Production classes changed')
    print('Completed synthetic pipeline pair; played games: zero', flush=True)


if __name__ == '__main__': main()
