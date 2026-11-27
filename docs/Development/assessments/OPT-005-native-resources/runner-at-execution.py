"""Record traced native heuristic/Monte Carlo workloads and JVM resources; no overhead-equivalence claim."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time


def require(ok, message):
    if not ok: raise ValueError(message)


def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for flag in ('classpath-file','java-home','user-home','output-dir'):p.add_argument('--'+flag,type=Path,required=True)
    args=p.parse_args();here=Path(__file__).resolve().parent;repo=here.parents[2];out=args.output_dir.resolve();out.mkdir(parents=True,exist_ok=False)
    classes=out/'classes';classes.mkdir();games=out/'games';games.mkdir();cp=args.classpath_file.read_text().strip()
    source=here/'NativeGameCostLauncher.java'
    # Development launcher uses JDK 21's all-thread allocation counter; production still targets Java 17.
    subprocess.run([str(args.java_home/'bin/javac'),'-cp',cp,'-d',str(classes),str(source)],check=True)
    template=json.loads((repo/'docs/Development/assessments/COV-008-native-admission/primary-metadata.json').read_text())['commands']
    spec=importlib.util.spec_from_file_location('native_cost_reader',here.parent/'export.py');reader=importlib.util.module_from_spec(spec);spec.loader.exec_module(reader)
    metadata=dict(revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip(),dirty=bool(subprocess.check_output(['git','status','--porcelain'],cwd=repo)),scope='TRACED_NATIVE_WORKLOAD_RESOURCES_NOT_OVERHEAD_CONTROLS',sources={str(source.relative_to(repo)):sha(source)},commands={})
    selected=[repo/n for n in ('forge-ai/target/classes/forge/ai/AiInputContext.class','forge-ai/target/classes/forge/ai/AiInputAdmission.class','forge-ai/target/classes/forge/ai/PlayerControllerAi.class','forge-game/target/classes/forge/game/Game.class','forge-game/target/classes/forge/game/GameAction.class','forge-gui-desktop/target/classes/forge/view/SimulationTraceCollector.class','forge-gui-desktop/target/classes/forge/view/SimulationTraceWriter.class')]
    metadata['productionClassSha256']={str(f.relative_to(repo)):sha(f) for f in selected};results={}
    for case,seeds in (('damage',(12073,12074)),('commander-a',(31009,31010))):
        for seed in seeds:
            for pilot in (('off','on') if seed%2 else ('on','off')):
                label=f'{case}-{seed}-{pilot}';command=list(template[case+'-production-0']['command'])
                command=[s for s in command if not s.startswith('-Dforge.admission.')]
                command[2]=str(args.java_home/'bin/java');command[command.index('-cp')+1]=str(classes)+os.pathsep+cp
                command[command.index('forge.ai.GameAdmissionExperiment')]='forge.view.NativeGameCostLauncher'
                command[command.index('-s')+1]=str(seed);command[command.index('-mc')+1]=pilot
                command[command.index('-c')+1]='45' if pilot=='on' else '90'
                command[command.index('-D')+1]=str(repo/('tools/trace-test-decks' if case=='damage' else 'tools/decision-records/hardening/decks'))
                for flag,suffix in (('-log-game','-full.jsonl'),('-log-decisions','-decisions.jsonl')):command[command.index(flag)+1]=str(games/(label+suffix))
                command=[('-Duser.home='+str(args.user_home.resolve())) if s.startswith('-Duser.home=') else '-Dforge.simulation.revision='+metadata['revision'] if s.startswith('-Dforge.simulation.revision=') else '-Dforge.simulation.dirty='+str(metadata['dirty']).lower() if s.startswith('-Dforge.simulation.dirty=') else s for s in command]
                command.insert(command.index('forge.view.NativeGameCostLauncher'),'-Dforge.cost.sidecar='+str(games/(label+'-resources.json')))
                metadata['commands'][label]=command;(out/'metadata.json').write_text(json.dumps(metadata,indent=2)+'\n')
                print('Running native resource workload',label,flush=True);started=time.monotonic()
                with (games/(label+'.log')).open('w') as log:subprocess.run(command,cwd=repo/'forge-gui-desktop',stdout=log,stderr=subprocess.STDOUT,timeout=360,check=True)
                elapsed=time.monotonic()-started;paths=[games/(label+'-'+kind+'.jsonl') for kind in ('full','decisions')]
                reports=[reader.export([f]) for f in paths]
                for r in reports:r.pop('sourceRecords')
                require(reports[0]==reports[1],'Full/filtered native resource reports differ: '+label)
                report=reports[0];acc=report['observationAccounting'];require(acc['scopeComplete'] and not acc['overflowed'] and acc['counts'].get('observationFailures',0)==0,'Unhealthy native observation scope')
                records=[json.loads(s) for s in paths[0].read_text().splitlines()];finished=records[-1];require(finished['type']=='RUN_FINISHED' and finished['status'] in ('NORMAL','TIMEOUT'),'Native run failed')
                game_start=next((i for i,r in enumerate(records) if r['type']=='GAME_STARTED'),None)
                require(game_start is not None and sum(r['type']=='SHUFFLE' for r in records[:game_start])==len(records[0]['participants']),'Native initial shuffle order changed')
                if finished['status']=='TIMEOUT':require(report['outcomeEligibleDecisions']==0,'Timeout gained outcome eligibility')
                searches=[r['decision']['search'] for r in records if r['type']=='AI_DECISION' and 'search' in r['decision']]
                search_summary=dict(records=len(searches),rollouts=sum(s['rollouts'] for s in searches),candidatesEvaluated=sum(s['candidatesEvaluated'] for s in searches))
                if pilot=='on':require(search_summary['rollouts']>0,'Monte Carlo flag did not exercise native rollouts: '+label)
                resource=json.loads((games/(label+'-resources.json')).read_text());require(resource['schemaVersion']==1 and resource['trainingReady'] is False,'Unknown resource scope')
                require(resource['commandArguments']==command[command.index('forge.view.NativeGameCostLauncher')+1:],'Resource command binding differs')
                results[label]=dict(case=case,seed=seed,pilot=pilot,pilotModeRecorded=records[0].get('pilotMode'),searchSummary=search_summary,limitSeconds=45 if pilot=='on' else 90,status=finished['status'],processWallSeconds=elapsed,resources=resource,coverage=report,traceFiles={f.name:dict(sha256=sha(f),bytes=f.stat().st_size) for f in paths},trainingReady=False)
                (out/'results.json').write_text(json.dumps(results,indent=2)+'\n');print('Validated',label,finished['status'],flush=True)
    require(len(results)==8,'Incomplete native resource cohort')
    require(all(sha(repo/n)==h for n,h in metadata['productionClassSha256'].items()),'Production classes changed')
    print('Validated eight native resource workloads; Monte Carlo outcomes are not deterministic equivalence controls',flush=True)


if __name__=='__main__':main()
