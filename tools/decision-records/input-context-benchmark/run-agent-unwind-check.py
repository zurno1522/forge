"""Execute isolated bytecode tests of the real phase agent; no engine classes enter the fixture classpath."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--java-home', required=True, type=Path); p.add_argument('--output-dir', required=True, type=Path)
    p.add_argument('--agent-reference', help='Compile agent/metrics from a commit to reproduce a fail-before check')
    p.add_argument('--classpath-file', type=Path, help='Also check the actual built Forge boundaries')
    p.add_argument('--user-home', type=Path)
    args = p.parse_args()
    if bool(args.classpath_file) != bool(args.user_home): p.error('--classpath-file and --user-home must be used together')
    if args.agent_reference and args.classpath_file: p.error('A reference-agent fail-before check must use only the isolated fixture')
    here = Path(__file__).resolve().parent; out = args.output_dir.resolve()
    out.mkdir(parents=True, exist_ok=False); agent = out / 'agent'; agent.mkdir(); fixtures = out / 'fixtures'; fixtures.mkdir()
    exports = ['--add-exports=java.base/' + name + '=ALL-UNNAMED' for name in ('jdk.internal.org.objectweb.asm', 'jdk.internal.org.objectweb.asm.commons')]
    sources = [here / ('InputContextPhase' + name + '.java') for name in ('Agent', 'Metrics')]
    reference = None
    if args.agent_reference:
        repo = here.parents[2]; reference = subprocess.check_output(['git', 'rev-parse', args.agent_reference + '^{commit}'], cwd=repo, text=True).strip()
        original = out / 'reference-source'; original.mkdir()
        selected = []
        for f in sources:
            path = original / f.name; path.write_bytes(subprocess.check_output(['git', 'show', reference + ':' + str(f.relative_to(repo))], cwd=repo)); selected.append(path)
        sources = selected
    tests = sorted((here / 'fixtures/phase-agent-exceptions').rglob('*.java'))
    subprocess.run([str(args.java_home / 'bin/javac'), *exports, '-d', str(agent), *map(str, sources)], check=True)
    subprocess.run([str(args.java_home / 'bin/javac'), '--release', '17', '-cp', str(agent), '-d', str(fixtures), *map(str, tests)], check=True)
    manifest = out / 'agent.mf'; manifest.write_text('Premain-Class: forge.ai.InputContextPhaseAgent\n\n')
    jar = out / 'agent.jar'; subprocess.run([str(args.java_home / 'bin/jar'), 'cfm', str(jar), str(manifest), '-C', str(agent), '.'], check=True)
    command = [str(args.java_home / 'bin/java'), *exports, '--add-opens=java.base/java.util=ALL-UNNAMED',
               '--add-opens=java.base/java.lang=ALL-UNNAMED',
               '-Dforge.context.profile=true', '-javaagent:' + str(jar), '-cp', str(fixtures) + os.pathsep + str(agent), 'forge.ai.PhaseAgentFailureProbe']
    metadata = dict(command=command, agentReference=reference, fixtureScope='ISOLATED_AGENT_UNIT_NOT_GAMEPLAY',
                    sources={f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in sources},
                    fixtures={str(f.relative_to(here)):hashlib.sha256(f.read_bytes()).hexdigest() for f in tests}, trainingReady=False)
    (out / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    with (out / 'execution.log').open('w') as log: result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=60)
    metadata['exitCode'] = result.returncode; (out / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    if result.returncode: raise RuntimeError('Agent exception check failed; inspect ' + str(out / 'execution.log'))
    if args.classpath_file:
        native = out / 'native'; native.mkdir(); cp = str(agent) + os.pathsep + args.classpath_file.read_text().strip()
        source = here / 'InputContextNativeFailureProbe.java'
        subprocess.run([str(args.java_home / 'bin/javac'), '--release', '17', '-cp', cp, '-d', str(native), str(source)], check=True)
        native_command = ['xvfb-run', '-a', str(args.java_home / 'bin/java'), *exports,
                          '-Duser.home=' + str(args.user_home.resolve()), '-Dforge.context.profile=true',
                          '-javaagent:' + str(jar), '-cp', str(native) + os.pathsep + cp, 'forge.ai.InputContextNativeFailureProbe']
        metadata.update(nativeCommand=native_command, nativeSourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(), nativeScope='ACTUAL_FORGE_BOUNDARIES_NOT_PLAYED_GAME')
        (out / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
        with (out / 'native.log').open('w') as log:
            native_result = subprocess.run(native_command, cwd=here.parents[2] / 'forge-gui-desktop', stdout=log, stderr=subprocess.STDOUT, timeout=90)
        metadata['nativeExitCode'] = native_result.returncode; (out / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
        if native_result.returncode: raise RuntimeError('Native agent check failed; inspect ' + str(out / 'native.log'))
    print('Validated actual agent exception unwinding and memory-source retirement', flush=True)


if __name__ == '__main__': main()
