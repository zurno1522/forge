from pathlib import Path
import subprocess,json,time
repo=Path('/workspace/forge');out=Path('/workspace/work/cov008-availability/games');base=json.loads(Path('/workspace/work/opt005/games/commands.json').read_text())['four-player'];revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip();commands={}
for seed in (31009,31010):
 label='four-player-'+str(seed);cmd=list(base);cmd[cmd.index('-cp')+1]=Path('/workspace/.forge-tools/trace-classpath.txt').read_text().strip();cmd[cmd.index('-s')+1]=str(seed)
 for flag,suffix in [('-log-game','-full.jsonl'),('-log-decisions','-decisions.jsonl')]:cmd[cmd.index(flag)+1]=str(out/(label+suffix))
 cmd=[('-Dforge.simulation.revision='+revision) if c.startswith('-Dforge.simulation.revision=') else '-Dforge.simulation.dirty=false' if c.startswith('-Dforge.simulation.dirty=') else c for c in cmd];commands[label]=cmd;(out/'commands.json').write_text(json.dumps(commands,indent=2)+'\n');start=time.monotonic()
 with (out/(label+'.log')).open('w') as log:subprocess.run(cmd,cwd=repo/'forge-gui-desktop',stdout=log,stderr=subprocess.STDOUT,check=True,timeout=300)
 print(label,'finished',round(time.monotonic()-start,2),flush=True)
