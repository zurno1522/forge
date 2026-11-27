from pathlib import Path
import importlib.util,hashlib,json,subprocess
repo=Path('/workspace/forge');root=Path('/workspace/work/cov008-availability');games=root/'games'
spec=importlib.util.spec_from_file_location('availability',repo/'tools/decision-records/input-context-benchmark/summarize-availability.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
results={}
for seed in (31009,31010):
 label='four-player-'+str(seed);coverages=[];reports=[];outputs=[]
 for kind in ('full','decisions'):
  source=games/(label+'-'+kind+'.jsonl');out=games/(label+'-'+kind+'-export');outputs.append(out)
  if not out.exists():subprocess.run(['python3',str(repo/'tools/decision-records/export.py'),str(source),'--output-dir',str(out)],check=True,capture_output=True)
  c=json.loads((out/'coverage.json').read_text());coverages.append(c);r=m.summarize(c);reports.append(r)
  if kind=='full':(root/(label+'-availability.json')).write_text(json.dumps(r,indent=2)+'\n')
 parity={name:(outputs[0]/name).read_bytes()==(outputs[1]/name).read_bytes() for name in ('dataset.jsonl','choices.jsonl','examples.jsonl','validation.json')};assert all(parity.values()),parity
 assert reports[0]==reports[1]
 for c in coverages:c.pop('sourceRecords')
 assert coverages[0]==coverages[1]
 records=[json.loads(s) for s in (games/(label+'-full.jsonl')).read_text().splitlines()]
 finished=next(r for r in records if r['type']=='RUN_FINISHED');first=next(i for i,r in enumerate(records) if r['type']=='GAME_STARTED');assert sum('SHUFFLE' in r['type'] for r in records[:first])==4
 starts=sum(r['type']=='AI_SUBORDINATE_CHOICE' and r['choice']['status']=='STARTED' for r in records);ends=sum(r['type']=='AI_SUBORDINATE_CHOICE' and r['choice']['status']!='STARTED' for r in records)
 acc=coverages[0]['observationAccounting'];assert acc['scopeComplete'] and not acc['overflowed'] and acc['counts']['observationFailures']==0
 assert acc['collector']['collectorStarts']==starts and acc['collector']['collectorTerminals']==ends
 if finished['status']=='NORMAL':assert acc['counts']['entered']==acc['counts']['finished'] and starts==ends and finished['gamesNormal']==1
 else:assert all(not json.loads(s)['outcomeEligible'] for s in (outputs[0]/'dataset.jsonl').read_text().splitlines())
 results[label]=dict(status=finished['status'],shufflesBeforeGameStarted=4,fullFilteredParity=parity,coverageParity=True,availabilityReportParity=True,sourceAccounting=acc,streamFiles={kind:dict(sha256=hashlib.sha256((games/(label+'-'+kind+'.jsonl')).read_bytes()).hexdigest(),bytes=(games/(label+'-'+kind+'.jsonl')).stat().st_size) for kind in ('full','decisions')})
 print(label,finished['status'],reports[0]['totals'],flush=True)
(root/'validation.json').write_text(json.dumps(results,indent=2)+'\n')
