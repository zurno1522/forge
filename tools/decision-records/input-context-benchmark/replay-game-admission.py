"""Diagnostic longer-limit replays of censored candidates; preserve the original campaign results."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('campaign',type=Path);parser.add_argument('--output-dir',type=Path,required=True);parser.add_argument('--seconds',type=int,default=180);args=parser.parse_args()
    here=Path(__file__).resolve().parent;repo=here.parents[2]
    spec=importlib.util.spec_from_file_location('private_game_validator',here/'validate-game-admission.py');validation=importlib.util.module_from_spec(spec);spec.loader.exec_module(validation)
    root=args.campaign;meta=json.loads((root/'metadata.json').read_text());validation.validate_matrix(meta,root)
    index=json.loads((root/'reports/index.json').read_text());validation.require(set(index)==set(meta['commands']),'Validate the complete campaign before replay')
    targets=[label for label,r in index.items() if r['case']!='timeout' and r['status']=='TIMEOUT' and index[r['case']+'-production-0']['status']=='NORMAL']
    args.output_dir.mkdir(parents=True,exist_ok=False);games=args.output_dir/'games';games.mkdir();results={};commands={}
    execution=dict(originalCampaignRevision=meta['revision'],replayRevision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip(),sourceSha256={},selectedClassSha256={})
    for name in ('forge-game/src/main/java/forge/game/GameAction.java','forge-ai/src/main/java/forge/ai/AiInputContext.java','tools/decision-records/input-context-benchmark/GameAdmissionExperiment.java'):
        execution['sourceSha256'][name]=hashlib.sha256((repo/name).read_bytes()).hexdigest()
    for name in ('forge-game/target/classes/forge/game/GameAction.class','forge-ai/target/classes/forge/ai/AiInputContext.class'):
        execution['selectedClassSha256'][name]=hashlib.sha256((repo/name).read_bytes()).hexdigest()
    (args.output_dir/'execution.json').write_text(json.dumps(execution,indent=2)+'\n')
    for label in targets:
        original=meta['commands'][label];validation.require(args.seconds>original['seconds'],'Diagnostic limit must exceed original')
        descriptor=dict(original);descriptor.pop('processWallSeconds',None);command=list(original['command']);command[command.index('-c')+1]=str(args.seconds)
        for flag,suffix in (('-log-game','-full.jsonl'),('-log-decisions','-decisions.jsonl')):command[command.index(flag)+1]=str((games/(label+suffix)).resolve())
        command=[('-Dforge.simulation.revision='+execution['replayRevision']) if c.startswith('-Dforge.simulation.revision=') else c for c in command]
        command=[('-Dforge.admission.sidecar='+str((games/(label+'-sidecar.json')).resolve())) if c.startswith('-Dforge.admission.sidecar=') else c for c in command]
        descriptor.update(command=command,seconds=args.seconds);commands[label]=descriptor
        (args.output_dir/'commands.json').write_text(json.dumps(commands,indent=2)+'\n');print('Replaying',label,flush=True)
        with (games/(label+'.log')).open('w') as log:p=subprocess.run(command,cwd=repo/'forge-gui-desktop',stdout=log,stderr=subprocess.STDOUT,timeout=args.seconds+180)
        validation.require(p.returncode==0,'Replay process failed: '+label)
        candidate=validation.validate_game(args.output_dir,label,descriptor);control_label=original['case']+'-production-0';control=json.loads((root/'reports'/(control_label+'.json')).read_text())
        validation.require(candidate['status']=='NORMAL','Diagnostic replay still censored: '+label)
        for field in ('engineProjectionSha256','aiResultsProjectionSha256'):validation.require(candidate[field]==control[field],'Diagnostic native behavior differs: '+label)
        for field in ('rngStateSha256','rngCalls'):validation.require(candidate['sourceCaptureAttempts'][field]==control['sourceCaptureAttempts'][field],'Diagnostic RNG differs')
        def engine(path):return [validation.normalize(r) for r in map(json.loads,path.read_text().splitlines()) if not r['type'].startswith('AI_') and r['type'] not in ('RUN_STARTED','RUN_FINISHED')]
        original_path=root/'games'/(label+'-full.jsonl');a=engine(root/'games'/(control_label+'-full.jsonl'));b=engine(original_path);common=next((i for i,(x,y) in enumerate(zip(a,b)) if x!=y),min(len(a),len(b)))
        divergence=b[common] if common<len(b) else None
        validation.require(divergence is not None and divergence.get('type')=='GAME_OUTCOME' and divergence.get('draw') is True,'Censored run diverged before forced outcome')
        candidate.update(diagnosticReplay=True,originalStatus='TIMEOUT',originalTraceSha256=hashlib.sha256(original_path.read_bytes()).hexdigest(),commonEnginePrefixBeforeForcedDraw=common,normalNativeBehaviorMatchesControl=True,originalTimeoutRemainsCensored=True)
        results[label]=candidate;(args.output_dir/(label+'.json')).write_text(json.dumps(candidate,indent=2)+'\n')
    (args.output_dir/'index.json').write_text(json.dumps({k:{a:b for a,b in r.items() if a not in ('jointObservedAvailability','sourceCaptureAttempts')} for k,r in results.items()},indent=2)+'\n')
    print('Validated',len(results),'diagnostic replays; original timeouts unchanged',flush=True)


if __name__=='__main__':main()
