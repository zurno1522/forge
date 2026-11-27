"""Private development validation/reporting for unsupported admission experiments; no dataset output."""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
import re
from pathlib import Path

EXPERIMENT_REASONS = {'EXPERIMENT_SAMPLED', 'EXPERIMENT_RESERVED'}
POLICIES = {'PRODUCTION', 'PREFIX', 'HASH4', 'HASH8', 'RESERVE'}
IGNORED_FIELDS = {'eventIndex', 'runId', 'engineRevision', 'engineDirty', 'elapsedNanos', 'targetChoiceProvenance', 'deckFingerprints', 'sourcePath', 'sourceSha256', 'inputContext'}
MASK=(1<<64)-1


def require(condition, message):
    if not condition: raise ValueError(message)


def natural(v):return isinstance(v,int) and not isinstance(v,bool) and 0<=v<(1<<63)


def mix(z):
    z=((z^(z>>30))*0xbf58476d1ce4e5b9)&MASK;z=((z^(z>>27))*0x94d049bb133111eb)&MASK;return z^(z>>31)


def normalize(value, path=()):
    if isinstance(value,dict):return {k:normalize(v,path+(k,)) for k,v in value.items() if k not in IGNORED_FIELDS and not (k=='inputUnavailable' and path[-2:] in (('targetCaptureAccounting','counts'),('searchCaptureAccounting','counts')))}
    if isinstance(value,list):return [normalize(v,path) for v in value]
    return value


def digest(value):return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':')).encode()).hexdigest()


def inspect_trace(path):
    """Fresh, private reader instance. This interface intentionally has no export directory."""
    root=Path(__file__).resolve().parents[1]
    spec=importlib.util.spec_from_file_location('private_admission_reader',root/'export.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    module._choices._contexts.REASONS=module._choices._contexts.REASONS|EXPERIMENT_REASONS
    groups={};original=module._audit.CoverageAudit
    class JointAudit(original):
        def observe(self,owner,boundary,context,**kw):
            super().observe(owner,boundary,context,**kw)
            turn,basis=kw['turn'],kw['turn_basis'];entry=kw.get('entry_location')
            if entry is not None:turn=entry['turn'];basis='ENTRY_LOCATION' if entry['status']=='CAPTURED' else 'ENTRY_LOCATION_UNAVAILABLE'
            key=(kw['run_id'],kw['game'],kw['player'],owner,boundary,turn,basis)
            g=groups.setdefault(key,dict(records=0,statuses=Counter(),reasons=Counter(),representedCards=0,disclosedFacts=0,linkedFacts=0))
            g['records']+=1;g['statuses']['ABSENT' if context is None else context['status']]+=1
            if context is not None:
                g['reasons'].update({o['reason'] for o in context['omissions']})
                if context['policy'] is not None:
                    p=context['policy'];g['representedCards']+=len(p['cards']);g['disclosedFacts']+=len(p['disclosedFacts']);g['linkedFacts']+=sum(f.get('currentObjectAssociation') not in (None,'UNRESOLVED') for f in p['disclosedFacts'])
    module._audit.CoverageAudit=JointAudit
    report=module.export([path])
    joint=[dict(runId=k[0],game=k[1],playerId=k[2],owner=k[3],boundary=k[4],turn=k[5],turnBasis=k[6],counts=v) for k,v in groups.items()]
    require(sum(g['counts']['records'] for g in joint)==report['inputCoverageAudit']['totals']['records'],'Joint observed records do not reconcile')
    return report,joint


def validate_sidecar(data, policy, salt, participants, *, stable_rng=True, allow_empty=False):
    require(type(data.get('schemaVersion')) is int and data['schemaVersion']==1 and data.get('scope')=='DEVELOPMENT_CAPTURE_ATTEMPTS_AT_PROCESS_SHUTDOWN' and data.get('trainingReady') is False,'Invalid sidecar scope')
    require(policy in POLICIES and data.get('policy')==policy and natural(salt) and data.get('salt')==salt,'Policy differs from command')
    require(data.get('releaseThreshold')==31 and data.get('maximumGroups')==8192,'Experiment limits changed')
    require(natural(data.get('rngCalls')) and natural(data.get('rngCallsAfterSnapshot')) and data['rngCallsAfterSnapshot']>=data['rngCalls'],'Invalid RNG counters')
    if stable_rng:require(data['rngCallsAfterSnapshot']==data['rngCalls'],'RNG advanced during normal-run snapshot')
    require(isinstance(data.get('rngStateSha256'),str) and re.fullmatch('[0-9a-f]{64}',data['rngStateSha256']),'Missing RNG state')
    actors=data.get('actors');require(isinstance(actors,list) and len(actors)<=16,'Invalid actors')
    require((not actors) if policy=='PRODUCTION' else bool(actors) or allow_empty,'Overlay/control hooks missing')
    ids=set();summary=Counter()
    for a in actors:
        require(natural(a.get('playerId')) and a['playerId'] in participants and a['playerId'] not in ids,'Unknown/duplicate actor');ids.add(a['playerId'])
        for key in ('share','funded','held','remaining','entered','returned','threw','inFlight','maximumInFlight','captureWork','outsideCaptureWork','returnedWork','failedWork','inFlightWork'):
            require(natural(a.get(key)),'Invalid actor count: '+key)
        require(natural(a.get('finalGameTurn')) and type(a.get('releaseTurn')) is int and a['releaseTurn']>=-1,'Invalid native turn declaration')
        require(a.get('overflow') is False and len(a['groups'])<=8192,'Telemetry overflow')
        share=256000//len(participants);require(a['share']==share and a['funded']+a['held']==share,'Player allowance changed')
        require(a['captureWork']+a['outsideCaptureWork']+a['remaining']==a['funded'],'Debit conservation failed')
        require(a['captureWork']==a['returnedWork']+a['failedWork']+a['inFlightWork'],'Owned capture work does not reconcile')
        require(a['entered']==a['returned']+a['threw']+a['inFlight'] and a['maximumInFlight']>=a['inFlight'],'Capture receipts do not reconcile')
        if policy!='RESERVE':require(a['held']==0 and a['releaseTurn']==-1,'Non-reserve policy changed funding')
        elif a['held']:require(a['held']==share-share//2 and a['finalGameTurn']<31 and a['releaseTurn']==-1,'Held allowance escaped horizon')
        else:require(a['finalGameTurn']>=31 and (a['releaseTurn']==-1 or a['releaseTurn']>=31),'Invalid reserve release')
        counts=Counter();work=0
        for key,g in a['groups'].items():
            parts=key.split(':');require(len(parts)==4 and parts[0].isdigit(),'Invalid capture stratum')
            turn,timing,basis,category=int(parts[0]),parts[1],parts[2],parts[3]
            require(turn<=a['finalGameTurn'],'Capture clock exceeds cutoff clock')
            require(timing in ('BEFORE_OPERATION','BEFORE_SELECTOR','AFTER_SELECTION_BEFORE_APPLICATION','UNESTABLISHED') and basis in ('RULES_STATE','PLANNING_WORKING_STATE','UNESTABLISHED') and category in ('AVAILABLE','BUDGET','OTHER_UNAVAILABLE',*EXPERIMENT_REASONS),'Unknown capture stratum')
            for field in ('records','work','available','partial','representedCards','disclosedFacts','linkedFacts'):require(natural(g.get(field)),'Invalid capture metric')
            require(g['records']>0 and g['partial']<=g['available']<=g['records'] and g['available']==(g['records'] if category=='AVAILABLE' else 0),'Availability partition failed')
            require(g['representedCards']<=128*g['available'] and g['linkedFacts']<=g['disclosedFacts']<=32*g['available'],'Invalid represented profile')
            require(isinstance(g['representedZones'],dict) and all(natural(v) for v in g['representedZones'].values()) and sum(g['representedZones'].values())==g['representedCards'],'Card/zone evidence differs')
            require(isinstance(g['omissionReasons'],dict) and all(natural(v) and v<=g['records'] for v in g['omissionReasons'].values()),'Invalid reason denominator')
            if category in EXPERIMENT_REASONS:
                require(g['work']==0 and g['omissionReasons'].get(category)==g['records'],'Skipped input charged or lost its reason')
            if category=='EXPERIMENT_RESERVED':require(policy=='RESERVE' and turn<31,'Unexpected reserve omission')
            if category=='EXPERIMENT_SAMPLED':require(policy in ('HASH4','HASH8'),'Unexpected sampling')
            counts[category]+=g['records'];work+=g['work']
        require(sum(counts.values())==a['returned'] and work==a['returnedWork'],'Returned capture strata differ')
        denominator=4 if policy=='HASH4' else 8 if policy=='HASH8' else 1
        expected=sum((mix(i^mix(salt+a['playerId']))&(denominator-1))!=0 for i in range(1,a['entered']+1)) if denominator>1 else 0
        require(counts['EXPERIMENT_SAMPLED']<=expected<=counts['EXPERIMENT_SAMPLED']+a['inFlight']+a['threw'],'Sampling differs from declared ordinal rule')
        summary.update(counts)
    return dict(summary)


def validate_game(root,label,command):
    games=root/'games';policy=command['policy'];salt=command['salt']
    full=games/(label+'-full.jsonl');filtered=games/(label+'-decisions.jsonl');sidecar=json.loads((games/(label+'-sidecar.json')).read_text())
    data=[json.loads(s) for s in full.read_text().splitlines()];finished=next(r for r in data if r['type']=='RUN_FINISHED');started=next(r for r in data if r['type']=='RUN_STARTED')
    game_start=next((r for r in data if r['type']=='GAME_STARTED'),None)
    participants={p['player']['id'] for p in game_start['players']} if game_start else {r['player']['id'] for r in data if r['type']=='SHUFFLE'}
    if game_start:require({p['participantId'] for p in game_start['players']}=={p['participantId'] for p in started['participants']},'Original participant binding changed')
    else:require(finished['status']=='TIMEOUT' and len(participants)==len(started['participants']),'Unsupported missing game start')
    require(sidecar.get('commandArguments')==command['command'][command['command'].index('forge.ai.GameAdmissionExperiment')+1:],'Sidecar command differs from native command')
    require(started.get('seed')==command['seed'],'Trace seed differs from command')
    reports=[];joints=[]
    for source in (full,filtered):
        report,joint=inspect_trace(source);reports.append(report);joints.append(joint)
    require(joints[0]==joints[1],'Full/filtered joint audit differs')
    for r in reports:r.pop('sourceRecords')
    require(reports[0]==reports[1],'Full/filtered validation reports differ')
    report=reports[0];attempts=validate_sidecar(sidecar,policy,salt,participants,stable_rng=finished['status']=='NORMAL',allow_empty=finished['status']=='TIMEOUT' and report['inputCoverageAudit']['totals']['records']==0);acc=report['observationAccounting'];require(acc['scopeComplete'] and not acc['overflowed'],'Source capture scope incomplete');counts=Counter(acc['counts']);require(counts['observationFailures']==0,'Source capture failed')
    starts=sum(r['type']=='AI_SUBORDINATE_CHOICE' and r['choice']['status']=='STARTED' for r in data);ends=sum(r['type']=='AI_SUBORDINATE_CHOICE' and r['choice']['status']!='STARTED' for r in data)
    require(acc['collector']['collectorStarts']==starts and acc['collector']['collectorTerminals']==ends,'Source/collector receipt mismatch')
    first=next((i for i,r in enumerate(data) if r['type']=='GAME_STARTED'),len(data));shuffles=sum('SHUFFLE' in r['type'] for r in data[:first]);require(shuffles==len(participants),'Initial shuffle order changed')
    normal=finished['status']=='NORMAL'
    if normal:
        require(starts==ends and counts['entered']==counts['finished'],'Normal run left source work pending')
        require(all(a['inFlight']==a['threw']==0 for a in sidecar['actors']),'Normal run left metered work pending/failed')
    else:require(finished['status']=='TIMEOUT' and report['outcomeEligibleDecisions']==0,'Unexpected status or timeout outcome eligibility')
    observed=report['inputCoverageAudit']['totals'];experimental={k:observed['omissionReasons'].get(k,0) for k in EXPERIMENT_REASONS}
    require(all(v<=attempts.get(k,0) for k,v in experimental.items()),'Observed experiment contexts exceed source receipts')
    require(policy not in ('PRODUCTION','PREFIX') or not any(experimental.values()),'Control has experimental omissions')
    projection=[normalize(r) for r in data if r['type'] not in ('RUN_STARTED','RUN_FINISHED')]
    engine=[r for r in projection if not r['type'].startswith('AI_')];ai=[r for r in projection if r['type'].startswith('AI_')]
    return dict(schemaVersion=1,scope='DEVELOPMENT_NATIVE_GAME_ADMISSION',case=command['case'],policy=policy,salt=salt,seed=command['seed'],status=finished['status'],trainingReady=False,
                fullFilteredReportParity=True,gameStartedObserved=game_start is not None,initialShufflesBeforeGameStarted=shuffles if game_start else None,initialShufflesAtEarlyCutoff=shuffles if game_start is None else None,meterCoverageEstablished=bool(sidecar['actors']),sourceAccounting=acc,parentCaptureAccounting={k:report.get(k) for k in ('targetCaptureAccounting','searchCaptureAccounting')},sourceCaptureAttempts=sidecar,attemptCategories=attempts,
                outcomeEligibleDecisions=report['outcomeEligibleDecisions'],censoredDecisions=report['censoredDecisions'],observedInputTotals=observed,jointObservedAvailability=joints[0],experimentalObserved=experimental,
                unpublishedOrDifferentCutoffExperimental={k:attempts.get(k,0)-v for k,v in experimental.items()},
                engineProjectionSha256=digest(engine),aiResultsProjectionSha256=digest(ai),files={p.name:dict(sha256=hashlib.sha256(p.read_bytes()).hexdigest(),bytes=p.stat().st_size) for p in (full,filtered)},
                limitations=['Capture attempts, observed input records and standalone source invocations have different scopes and cutoffs.',
                            'Experimental omission reasons are accepted only by a fresh private validation instance; no datasets/examples are written.',
                            'Joint audit clocks retain independent entry versus parent-container attribution. Aggregate profile counts are not complete inputs.',
                            'Shutdown snapshots exclude later work. Timeout sequences/RNG cutoffs are not deterministic controls.',
                            'The development debit monitor and telemetry add overhead; process time/file sizes do not isolate production capture cost.'])


def validate_matrix(meta, root, partial=False):
    cases=meta.get('cases');require(isinstance(cases,list) and cases and len(cases)==len(set(cases)), 'Invalid campaign cases')
    policies=[('PRODUCTION',0),('PREFIX',0),('HASH8',0)] if meta.get('smoke') else [('PRODUCTION',0),('PREFIX',0),('HASH4',0),('HASH8',0),('HASH8',1),('RESERVE',0)]
    expected={(case,policy,salt) for case in cases for policy,salt in policies}
    actual=[(c['case'],c['policy'],c['salt']) for c in meta['commands'].values()]
    require(len(actual)==len(set(actual)) and set(actual)<=expected, 'Unknown/duplicate campaign command')
    if not partial:
        require(set(actual)==expected, 'Campaign is incomplete; use explicit --partial only for progress')
        require(all(natural(c.get('seed')) and isinstance(c.get('processWallSeconds'),(int,float)) and c['processWallSeconds']>0 for c in meta['commands'].values()), 'Campaign process has not completed')
        require(all((root/'games'/(label+'-sidecar.json')).exists() for label in meta['commands']), 'Campaign sidecar missing')
    return len(expected)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('directory',type=Path);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--partial',action='store_true');parser.add_argument('--record-invalid',action='store_true');args=parser.parse_args()
    root=args.directory;meta=json.loads((root/'metadata.json').read_text());validate_matrix(meta,root,args.partial);args.output.mkdir(parents=True,exist_ok=True);results={}
    for label,command in meta['commands'].items():
        if not (root/'games'/(label+'-sidecar.json')).exists():continue
        print('Validating',label,flush=True)
        try:r=validate_game(root,label,command)
        except ValueError as failure:
            if not args.record_invalid:raise
            r=dict(schemaVersion=1,scope='REJECTED_DEVELOPMENT_TRACE',case=command['case'],policy=command['policy'],salt=command['salt'],seed=command['seed'],status='INVALID_TRACE',trainingReady=False,validationError=str(failure),files={})
            for suffix in ('full.jsonl','decisions.jsonl','sidecar.json'):
                p=root/'games'/(label+'-'+suffix)
                r['files'][p.name]=dict(sha256=hashlib.sha256(p.read_bytes()).hexdigest(),bytes=p.stat().st_size)
            last=json.loads((root/'games'/(label+'-full.jsonl')).read_text().splitlines()[-1]);r['executionStatusRecorded']=last.get('status') if last.get('type')=='RUN_FINISHED' else None;r['validationStatus']='REJECTED'
        results[label]=r
        (args.output/(label+'.json')).write_text(json.dumps(r,indent=2)+'\n')
    for case in meta['cases']:
        group=[r for r in results.values() if r['case']==case]
        controls=[r for r in group if r['policy']=='PRODUCTION']
        if not controls:continue
        control=controls[0]
        for r in group:
            if r['status']==control['status']=='NORMAL':
                require(r['engineProjectionSha256']==control['engineProjectionSha256'],'Native engine changed: '+case+' '+r['policy'])
                require(r['aiResultsProjectionSha256']==control['aiResultsProjectionSha256'],'Native AI results changed: '+case+' '+r['policy'])
                for k in ('rngStateSha256','rngCalls'):require(r['sourceCaptureAttempts'][k]==control['sourceCaptureAttempts'][k],'Native RNG changed')
                r['normalNativeBehaviorMatchesControl']=True
    for label,r in results.items():(args.output/(label+'.json')).write_text(json.dumps(r,indent=2)+'\n')
    (args.output/'index.json').write_text(json.dumps({k:{a:b for a,b in r.items() if a not in ('sourceCaptureAttempts','jointObservedAvailability','sourceAccounting')} for k,r in results.items()},indent=2)+'\n')
    rejected=sum(r['status']=='INVALID_TRACE' for r in results.values())
    (args.output/'campaign-summary.json').write_text(json.dumps(dict(processed=len(results),validated=len(results)-rejected,rejected=rejected,allValid=rejected==0,partial=args.partial,trainingReady=False),indent=2)+'\n')
    print('Processed',len(results),'games; validated',len(results)-rejected,'rejected',rejected,flush=True)


if __name__=='__main__':main()
