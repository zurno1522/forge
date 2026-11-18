#!/usr/bin/env python3
"""Development-only HARD-001 measurements. Requires built desktop test classes and JDK 21.

Run under Xvfb/on a desktop. Allocation is measured by the controlled Java probe;
CLI runs measure wall/CPU/peak process RSS, not allocated or exclusively retained bytes.
"""
import argparse
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import threading
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath-file', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--forge-home', type=Path, required=True)
    parser.add_argument('--commander-decks', type=Path, required=True)
    parser.add_argument('--simple-decks', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--macro-only', action='store_true')
    parser.add_argument('--rounds', type=int, default=3)
    args = parser.parse_args()
    args.java_home = args.java_home.resolve()
    args.forge_home = args.forge_home.resolve()
    root = args.output_dir.resolve()
    if root == args.forge_home or args.forge_home in root.parents:
        parser.error('output directory must be outside the seed home (which is copied for each run)')
    if args.rounds < 1:
        parser.error('rounds must be positive')
    root.mkdir(parents=True, exist_ok=False)
    repo = Path(__file__).resolve().parents[3]
    source = Path(__file__).resolve().parent
    cp = args.classpath_file.read_text().strip()
    java = args.java_home / 'bin' / 'java'
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
    dirty = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=repo))
    quota = Path('/sys/fs/cgroup/cpu.max')
    metadata = dict(revision=revision, dirty=dirty, args={k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()},
                    logicalCpus=os.cpu_count(), cgroupCpuMax=quota.read_text().strip() if quota.exists() else None,
                    javaVersion=subprocess.check_output([str(java), '-version'], stderr=subprocess.STDOUT, text=True))
    (root / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    opens = ['--add-opens=java.base/' + package + '=ALL-UNNAMED'
             for package in ('java.util', 'java.lang', 'java.lang.reflect', 'java.text', 'java.time')]
    if not args.macro_only:
        classes = root / 'classes'
        classes.mkdir()
        subprocess.run([str(args.java_home / 'bin' / 'javac'), '-cp', cp, '-d', str(classes),
                        str(source / 'HardeningSizeAgent.java'), str(source / 'HardeningProbe.java')], check=True)
        manifest = root / 'agent.mf'
        manifest.write_text('Manifest-Version: 1.0\nPremain-Class: forge.view.HardeningSizeAgent\n\n')
        jar = root / 'probe.jar'
        subprocess.run([str(args.java_home / 'bin' / 'jar'), 'cfm', str(jar), str(manifest), '-C', str(classes), '.'], check=True)
        command = [str(java), '-Xms256m', '-Xmx1g', '-javaagent:' + str(jar), '-Duser.home=' + str(args.forge_home),
                   *opens, '-cp', str(jar) + os.pathsep + cp, 'forge.view.HardeningProbe', str(root / 'controlled')]
        with (root / 'controlled.log').open('w') as log:
            subprocess.run(command, cwd=repo / 'forge-gui-desktop', stdout=log, stderr=subprocess.STDOUT, check=True)

    measurements = []
    modes = ('NONE', 'DECISIONS', 'FULL')
    scenarios = (
        ('simple', args.simple_decks.resolve(), ('trace-red.dck', 'trace-green.dck'), 'constructed', 3, 7451),
        ('commander', args.commander_decks.resolve(), ('doctor-who.dck', 'ur-dragon-draconic-domination.dck'), 'commander', 1, 31002),
    )
    for scenario, decks, names, format_name, games, seed in scenarios:
        for round_index in range(args.rounds):
            for offset in range(3):
                mode = modes[(round_index + offset) % 3]
                label = f'{scenario}-{round_index}-{mode}'
                home = root / (label + '-home')
                shutil.copytree(args.forge_home, home)
                command = [str(java), '-Xms256m', '-Xmx1g', '-Duser.home=' + str(home),
                           '-Dforge.simulation.revision=' + revision, '-Dforge.simulation.dirty=' + str(dirty).lower(),
                           *opens, '-cp', cp, 'forge.view.Main', 'sim', '-f', format_name, '-mc', 'off', '-q',
                           '-D', str(decks), '-d', *names, '-n', str(games), '-s', str(seed), '-c', '90']
                if mode == 'DECISIONS':
                    command += ['-log-decisions', str(root / (label + '.jsonl'))]
                if mode == 'FULL':
                    command += ['-log-game', str(root / (label + '.jsonl'))]
                (root / (label + '.command.json')).write_text(json.dumps(command, indent=2) + '\n')
                print('START', label, flush=True)
                start = time.perf_counter()
                with (root / (label + '.log')).open('w') as log:
                    child = subprocess.Popen(command, cwd=repo / 'forge-gui-desktop', stdout=log, stderr=subprocess.STDOUT)
                    watchdog = threading.Timer(360, child.kill)
                    watchdog.daemon = True
                    watchdog.start()
                    try:
                        _, status, usage = os.wait4(child.pid, 0)
                        child.returncode = os.waitstatus_to_exitcode(status)
                    finally:
                        watchdog.cancel()
                wall = time.perf_counter() - start
                if child.returncode:
                    raise RuntimeError(f'{label} exited {child.returncode}; inspect its log')
                lines = (root / (label + '.log')).read_text().splitlines()
                outcome = [line for line in lines if line.startswith('Game Outcome:') and 'Match Duration' not in line]
                results = [line for line in lines if line.startswith('Game Result:')]
                if not outcome or len(results) != games:
                    raise RuntimeError(f'{label}: missing outcome/result evidence')
                game_millis = [int(m.group(1)) for line in results
                               if (m := re.search(r'(?:ended in\s+|Took\s+)(\d+)\s+ms', line))]
                if mode != 'NONE':
                    with (root / (label + '.jsonl')).open() as trace:
                        terminal = next((json.loads(line) for line in trace if '"type":"RUN_FINISHED"' in line), None)
                    if terminal is None or terminal['status'] != 'NORMAL' or terminal['gamesNormal'] != games:
                        raise RuntimeError(f'{label}: benchmark did not complete normally')
                result = dict(scenario=scenario, round=round_index, mode=mode, wallSeconds=wall,
                              cpuSeconds=usage.ru_utime + usage.ru_stime, peakRssKiB=usage.ru_maxrss,
                              outcomeSignature=outcome, gameResultCount=len(results), reportedGameMillis=game_millis,
                              outputBytes=(root / (label + '.jsonl')).stat().st_size if mode != 'NONE' else 0)
                measurements.append(result)
                (root / 'macro.json').write_text(json.dumps(measurements, indent=2) + '\n')
                print('DONE', label, f'{wall:.3f}s', flush=True)
    # Different outcomes make comparisons unsuitable as a same-workload estimate.
    comparability = {scenario: len({tuple(r['outcomeSignature']) for r in measurements if r['scenario'] == scenario}) == 1
                     for scenario, *_ in scenarios}
    (root / 'comparability.json').write_text(json.dumps(comparability, indent=2) + '\n')


if __name__ == '__main__':
    main()
