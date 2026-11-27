"""Run isolated admission variants through native simulation; never overwrite production classes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

POLICIES = [('PRODUCTION', 0), ('PREFIX', 0), ('HASH4', 0), ('HASH8', 0), ('HASH8', 1), ('RESERVE', 0)]
CASES = ('damage', 'repeated', 'commander-a', 'commander-b', 'timeout')


def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()


def overlay_source(original):
    if 'PROFILE = "PERMITTED_CHOICE_VIEW_V28"' not in original:
        raise ValueError('The V28 experimental overlay requires a fresh source-boundary review for this profile')
    share = 'MAX_GAME_WORK / Math.max(1, chooser.getGame().getRegisteredPlayers().size())'
    substitutions = [
        ('private final AtomicLong remaining;', 'private final AtomicLong remaining;\n        private final GameAdmissionExperiment.Actor experimentActor;'),
        ('remaining = new AtomicLong(' + share + ');', 'remaining = new AtomicLong(GameAdmissionExperiment.initialAllowance(chooser, ' + share + '));'),
        ('disclosures = new AiContextDisclosures(chooser, this::acquireWork);', 'disclosures = new AiContextDisclosures(chooser, this::acquireWork);\n            experimentActor = GameAdmissionExperiment.register(chooser, this, ' + share + ');'),
        ('''private boolean acquireWork() {
            long available = remaining.get();
            while (available > 0) {
                if (remaining.compareAndSet(available, available - 1)) { return true; }
                available = remaining.get();
            }
            return false;
        }''', '''private boolean acquireWork() {
            synchronized (experimentActor == null ? remaining : experimentActor) {
                long available = remaining.get();
                while (available > 0) {
                    if (remaining.compareAndSet(available, available - 1)) { GameAdmissionExperiment.debit(experimentActor); return true; }
                    available = remaining.get();
                }
                return false;
            }
        }'''),
        ('''final State state = controller.inputContextState();
        if (!state.acquireWork()) { return unavailable(chooser, timing, stateBasis, "BUDGET_EXHAUSTED"); }
        return new Capture(chooser, timing, stateBasis, open, state, prompt, options, parameters).build();''', '''final State state = controller.inputContextState();
        final GameAdmissionExperiment.Ticket ticket = GameAdmissionExperiment.begin(chooser, state, state.experimentActor, timing, stateBasis);
        final Map<String, Object> result;
        try {
            if (ticket.reason != null) { result = unavailable(chooser, timing, stateBasis, ticket.reason); }
            else if (!state.acquireWork()) { result = unavailable(chooser, timing, stateBasis, "BUDGET_EXHAUSTED"); }
            else { result = new Capture(chooser, timing, stateBasis, open, state, prompt, options, parameters).build(); }
        } catch (RuntimeException | Error failure) { GameAdmissionExperiment.failed(ticket); throw failure; }
        return GameAdmissionExperiment.finish(ticket, result);''')]
    for before, after in substitutions:
        if original.count(before) != 1: raise ValueError('Producer hook requires a fresh source-boundary review')
        original = original.replace(before, after)
    return original


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ('classpath-file', 'java-home', 'user-home', 'output-dir'): parser.add_argument('--' + flag, type=Path, required=True)
    parser.add_argument('--cases', nargs='+', choices=CASES, default=list(CASES))
    parser.add_argument('--smoke', action='store_true')
    args = parser.parse_args()
    here = Path(__file__).resolve().parent; repo = here.parents[2]; out = args.output_dir.resolve()
    out.mkdir(parents=True, exist_ok=False); classes=out/'classes'; classes.mkdir(); overlay=out/'overlay'; overlay.mkdir(); games=out/'games'; games.mkdir()
    cp=args.classpath_file.read_text().strip(); source=here/'GameAdmissionExperiment.java'
    subprocess.run([str(args.java_home/'bin/javac'),'--release','17','-cp',cp,'-d',str(classes),str(source)],check=True)
    production=repo/'forge-ai/src/main/java/forge/ai/AiInputContext.java'; generated=overlay/'AiInputContext.java';generated.write_text(overlay_source(production.read_text()))
    subprocess.run([str(args.java_home/'bin/javac'),'--release','17','-cp',str(classes)+os.pathsep+cp,'-d',str(overlay),str(generated)],check=True)
    templates=json.loads((repo/'docs/Development/assessments/COV-007-damage-policy/commands.json').read_text())['games']
    commander=json.loads((repo/'docs/Development/assessments/COV-008-availability/commands.json').read_text())['four-player-31009']
    paths=list((repo/'forge-ai/target/classes/forge/ai').glob('Ai*.class'))
    paths += [repo/p for p in ('forge-game/target/classes/forge/game/Game.class','forge-game/target/classes/forge/game/GameAction.class','forge-core/target/classes/forge/util/MyRandom.class','forge-gui-desktop/target/classes/forge/view/SimulationTraceCollector.class','forge-gui-desktop/target/classes/forge/view/SimulationTraceWriter.class')]
    revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip();dirty=bool(subprocess.check_output(['git','status','--porcelain'],cwd=repo))
    meta=dict(revision=revision,dirty=dirty,smoke=args.smoke,cases=['damage'] if args.smoke else args.cases,
              sourceSha256={str(p.relative_to(repo)):sha(p) for p in (source,production,repo/'forge-game/src/main/java/forge/game/GameAction.java',Path(__file__).resolve())},
              selectedProductionClassSha256={str(p.relative_to(repo)):sha(p) for p in paths},overlaySourceSha256=sha(generated),commands={})
    for case in meta['cases']:
        policies=[POLICIES[i] for i in (0,1,3)] if args.smoke else (list(reversed(POLICIES)) if case=='commander-b' else POLICIES)
        for policy,salt in policies:
            label=f'{case}-{policy.lower()}-{salt}';template=list(templates['cards' if case=='damage' else 'repeated']) if case in ('damage','repeated') else list(commander)
            template[2]=str(args.java_home/'bin/java')
            template[template.index('-cp')+1]=(str(overlay)+os.pathsep if policy!='PRODUCTION' else '')+str(classes)+os.pathsep+cp
            if case=='commander-b': template[template.index('-s')+1]='31010'
            if case=='timeout': template[template.index('-c')+1]='1'
            template[template.index('-Duser.home=/workspace/.forge-home')]='-Duser.home='+str(args.user_home.resolve())
            template=[('-Dforge.simulation.revision='+revision) if c.startswith('-Dforge.simulation.revision=') else '-Dforge.simulation.dirty='+str(dirty).lower() if c.startswith('-Dforge.simulation.dirty=') else c for c in template]
            # Resolve committed deck paths for the current checkout, independently of recorded /workspace paths.
            template[template.index('-D')+1]=str(repo/('tools/trace-test-decks' if case in ('damage','repeated') else 'tools/decision-records/hardening/decks'))
            for flag,suffix in (('-log-game','-full.jsonl'),('-log-decisions','-decisions.jsonl')): template[template.index(flag)+1]=str(games/(label+suffix))
            i=template.index('forge.view.Main');command=template[:i]+['-Dforge.admission.policy='+policy,'-Dforge.admission.salt='+str(salt),'-Dforge.admission.sidecar='+str(games/(label+'-sidecar.json')),'forge.ai.GameAdmissionExperiment']+template[i+1:]
            meta['commands'][label]=dict(case=case,policy=policy,salt=salt,seed=int(command[command.index('-s')+1]),seconds=int(command[command.index('-c')+1]),command=command)
            (out/'metadata.json').write_text(json.dumps(meta,indent=2)+'\n');print('Running',label,flush=True);start=time.monotonic()
            with (games/(label+'.log')).open('w') as log: result=subprocess.run(command,cwd=repo/'forge-gui-desktop',stdout=log,stderr=subprocess.STDOUT,timeout=360)
            if result.returncode: raise RuntimeError(f'{label} failed; inspect {games/(label+".log")}')
            meta['commands'][label]['processWallSeconds']=time.monotonic()-start
            (out/'metadata.json').write_text(json.dumps(meta,indent=2)+'\n');print('Completed',label,flush=True)
    if any(sha(p)!=meta['selectedProductionClassSha256'][str(p.relative_to(repo))] for p in paths): raise RuntimeError('Production classes changed')
    print('Completed native admission campaign',flush=True)


if __name__=='__main__':main()
