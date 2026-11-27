"""Validate process-envelope counters without inventing unsupported values or capture attribution."""


def require(ok, message):
    if not ok: raise ValueError('native resource metrics: ' + message)


def natural(value): return type(value) is int and 0 <= value < 1 << 63


def validate(value, command_arguments):
    fields = {'schemaVersion','scope','wallNanos','processCpuNanos','allThreadsJavaAllocatedBytes',
              'peakResidentBytesSinceJvmStart','heapUsedAtShutdown','heapPoolPeaksSinceJvmStart',
              'gcSinceJvmStart','commandArguments','trainingReady'}
    require(isinstance(value,dict) and set(value)==fields, 'unknown fields')
    require(type(value['schemaVersion']) is int and value['schemaVersion']==1 and value['scope']=='MAIN_COUNTER_SETUP_TO_JVM_SHUTDOWN_HOOK' and value['trainingReady'] is False, 'unknown scope/version')
    require(isinstance(value['commandArguments'],list) and all(isinstance(a,str) for a in value['commandArguments']) and value['commandArguments']==command_arguments, 'native command differs')
    require(natural(value['wallNanos']) and value['wallNanos']>0 and natural(value['heapUsedAtShutdown']), 'invalid elapsed/heap measurement')
    for key in ('processCpuNanos','allThreadsJavaAllocatedBytes','peakResidentBytesSinceJvmStart'):
        require(value[key] is None or natural(value[key]), 'invalid optional counter: '+key)
    pools=value['heapPoolPeaksSinceJvmStart'];require(isinstance(pools,dict) and all(isinstance(k,str) and (v is None or natural(v)) for k,v in pools.items()), 'invalid heap pools')
    gc=value['gcSinceJvmStart'];require(isinstance(gc,dict), 'invalid GC declaration')
    for name,entry in gc.items():
        require(isinstance(name,str) and isinstance(entry,dict) and set(entry)=={'collections','milliseconds'} and all(v is None or natural(v) for v in entry.values()), 'invalid GC counters')
    return value
