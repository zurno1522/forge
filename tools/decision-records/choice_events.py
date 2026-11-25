"""Standalone subordinate-choice validation and streaming export. No inferred identity joins."""
import importlib.util
from pathlib import Path

_context_spec = importlib.util.spec_from_file_location('forge_input_context', Path(__file__).with_name('input_context.py'))
_contexts = importlib.util.module_from_spec(_context_spec)
_context_spec.loader.exec_module(_contexts)

validate_input_context = _contexts.validate

_entry_spec = importlib.util.spec_from_file_location('forge_entry_location', Path(__file__).with_name('entry_location.py'))
_entries = importlib.util.module_from_spec(_entry_spec)
_entry_spec.loader.exec_module(_entries)

validate_entry_location = _entries.validate

STAGES = {'CANDIDATE_EVALUATION', 'EXECUTION', 'RESOLUTION', 'CONTROLLER_CALLBACK',
          'DELEGATED_TARGET_SELECTION', 'TRIGGER_TARGET_SELECTION', 'EFFECT_COPY_TARGET_SELECTION',
          'RETARGET_SELECTION', 'PAYMENT', 'TRIGGER_PREPARATION'}
POOL_BOUNDARIES = {'DESTROY_REMOVAL_POOL', 'DESTROY_LAND_POOL', 'DESTROY_POLYMORPH_POOL',
                   'DAMAGE_OPPONENT_POOL', 'DAMAGE_KILLABLES', 'DAMAGE_MANDATORY_OPPONENT_POOL',
                   'DAMAGE_MANDATORY_FALLBACK_POOL', 'DAMAGE_KILLABLE_PLANESWALKERS', 'DAMAGE_PLANESWALKER_POOL',
                   'DRAW_PLAYER_POOL', 'DAMAGE_PLAYER_POOL', 'ATTACH_CARD_POOL', 'ATTACH_PLAYER_POOL', 'MASKED_TARGET_POOL'}
COST_KINDS = {
    'CostAddMana', 'CostBehold', 'CostBeholdExile', 'CostBlight', 'CostChooseColor',
    'CostChooseCreatureType', 'CostCollectEvidence', 'CostDamage', 'CostDiscard', 'CostDraw',
    'CostEnlist', 'CostExert', 'CostExile', 'CostExileFromStack', 'CostExiledMoveToGrave',
    'CostFlipCoin', 'CostForage', 'CostGainControl', 'CostGainLife', 'CostMill',
    'CostPartMana', 'CostPayEnergy', 'CostPayLife', 'CostPayShards', 'CostPromiseGift',
    'CostPutCardToLib', 'CostPutCounter', 'CostPutCounterYou', 'CostRemoveAnyCounter', 'CostRemoveCounter',
    'CostReturn', 'CostReveal', 'CostRevealChosen', 'CostRollDice', 'CostSacrifice',
    'CostTap', 'CostTapType', 'CostTeamwork', 'CostUnattach', 'CostUntap',
    'CostUntapType', 'CostWaterbend', 'OTHER'
}
TYPES = {'TRIGGER_PREPARATION_RESULT', 'PAYMENT_DECISION', 'PAYMENT_CARD_SELECTION', 'PAYMENT_MANA', 'PAYMENT_MANA_SELECTION', 'TARGET_SELECTION_RESULT', 'EFFECT_COPY_TARGET_RESULT', 'RETARGET_RESULT', 'TARGET_POOL_SELECTION', 'MODE_SELECTION', 'NUMBER_SELECTION', 'X_SELECTION', 'MASKED_CHOICE', 'EFFECT_ENTITY_SELECTION', 'ZONE_CHANGE_CARD_SELECTION'}
TYPES.update(('EFFECT_MULTI_ENTITY_SELECTION', 'ZONE_CHANGE_SELECTION_OPERATION'))
BOUNDARIES = {'TRIGGER_PREPARATION', 'COST_VISITOR', 'PAYMENT_SACRIFICE_POOL', 'PAYMENT_DISCARD_POOL', 'PAYMENT_EXILE_POOL', 'AUTO_MANA_PAYMENT', 'MANA_POOL_OPTIONS', 'CONTROLLER_TARGETS', 'COPY_TARGETS', 'CONTROLLER_RETARGET', 'CHARM_MODE_HEURISTIC', 'CONTROLLER_MODES',
              'CONTROLLER_NUMBER_RANGE', 'CONTROLLER_NUMBER_OPTIONS', 'CONTROLLER_ANNOUNCEMENT', 'MAX_X_HEURISTIC', 'X_ANNOUNCEMENT_REFINEMENT', 'MASKED_CHOICE', 'CONTROLLER_EFFECT_ENTITY', 'CONTROLLER_ZONE_CHANGE_CARD'} | POOL_BOUNDARIES

TYPE_BOUNDARIES = {
    'TRIGGER_PREPARATION_RESULT': {'TRIGGER_PREPARATION'},
    'TARGET_SELECTION_RESULT': {'CONTROLLER_TARGETS'},
    'EFFECT_COPY_TARGET_RESULT': {'COPY_TARGETS'},
    'RETARGET_RESULT': {'CONTROLLER_RETARGET'},
    'TARGET_POOL_SELECTION': POOL_BOUNDARIES,
    'MODE_SELECTION': {'CHARM_MODE_HEURISTIC', 'CONTROLLER_MODES'},
    'NUMBER_SELECTION': {'CONTROLLER_NUMBER_RANGE', 'CONTROLLER_NUMBER_OPTIONS', 'CONTROLLER_ANNOUNCEMENT'},
    'X_SELECTION': {'MAX_X_HEURISTIC', 'X_ANNOUNCEMENT_REFINEMENT'},
    'PAYMENT_DECISION': {'COST_VISITOR'},
    'PAYMENT_CARD_SELECTION': {'PAYMENT_DISCARD_POOL', 'PAYMENT_SACRIFICE_POOL', 'PAYMENT_EXILE_POOL'},
    'PAYMENT_MANA': {'AUTO_MANA_PAYMENT'},
    'PAYMENT_MANA_SELECTION': {'MANA_POOL_OPTIONS'},
    'MASKED_CHOICE': {'MASKED_CHOICE'},
    'EFFECT_ENTITY_SELECTION': {'CONTROLLER_EFFECT_ENTITY'},
    'ZONE_CHANGE_CARD_SELECTION': {'CONTROLLER_ZONE_CHANGE_CARD'},
    'EFFECT_MULTI_ENTITY_SELECTION': {'CONTROLLER_EFFECT_ENTITIES'},
    'ZONE_CHANGE_SELECTION_OPERATION': {'CONTROLLER_ZONE_CHANGE_SELECTIONS'},
}
BOUNDARIES.update(('CONTROLLER_EFFECT_ENTITIES', 'CONTROLLER_ZONE_CHANGE_SELECTIONS'))

CONTEXT_TIMING = {
    'MAIN_PHASE_PLAY_SELECTION': 'BEFORE_OPERATION',
    'CONTROLLER_TARGETS': 'BEFORE_OPERATION',
    'COST_VISITOR': 'BEFORE_OPERATION',
    'AUTO_MANA_PAYMENT': 'BEFORE_OPERATION',
    'CONTROLLER_NUMBER_RANGE': 'BEFORE_SELECTOR',
    'CONTROLLER_NUMBER_OPTIONS': 'BEFORE_SELECTOR',
    'CONTROLLER_ANNOUNCEMENT': 'BEFORE_OPERATION',
    'PAYMENT_DISCARD_POOL': 'BEFORE_SELECTOR',
    'PAYMENT_SACRIFICE_POOL': 'BEFORE_SELECTOR',
    'CONTROLLER_EFFECT_ENTITY': 'BEFORE_SELECTOR',
    'CONTROLLER_ZONE_CHANGE_CARD': 'BEFORE_SELECTOR',
}
CONTEXT_TIMING_V2 = {**{key: value for key, value in CONTEXT_TIMING.items() if key != 'MAIN_PHASE_PLAY_SELECTION'},
                     **{boundary: ['BEFORE_SELECTOR', 'AFTER_SELECTION_BEFORE_APPLICATION']
                        for boundary in sorted(POOL_BOUNDARIES - {'MASKED_TARGET_POOL'})}}
MODE_CONTEXT_TIMING = {'CHARM_MODE_HEURISTIC': 'BEFORE_SELECTOR', 'CONTROLLER_MODES': 'BEFORE_OPERATION'}
MULTI_CONTEXT_TIMING = {'CONTROLLER_EFFECT_ENTITIES': 'BEFORE_OPERATION'}
ZONE_OPERATION_CONTEXT_TIMING = {'CONTROLLER_ZONE_CHANGE_SELECTIONS': 'BEFORE_OPERATION'}


def input_boundary(value, boundary, stage=None, choice=None):
    expected = CONTEXT_TIMING.get(boundary)
    multi = choice is not None and choice['choiceType'] == 'EFFECT_MULTI_ENTITY_SELECTION'
    if multi:
        require('entryLocation' in choice, 'outer entity input lacks its own entry')
        expected = MULTI_CONTEXT_TIMING[boundary]
    operation = choice is not None and choice['choiceType'] == 'ZONE_CHANGE_SELECTION_OPERATION'
    if operation:
        require('entryLocation' in choice and value['schemaVersion'] >= 9, 'zone operation lacks own entry/profile')
        expected = ZONE_OPERATION_CONTEXT_TIMING[boundary]
    mode = choice is not None and boundary in MODE_CONTEXT_TIMING and 'entryLocation' in choice
    if mode:
        expected = MODE_CONTEXT_TIMING[boundary]
        require(value['stateBasis'] == 'PLANNING_WORKING_STATE', 'mode input claims committed state')
    target_pool = choice is not None and choice['choiceType'] == 'TARGET_POOL_SELECTION' and 'entryLocation' in choice
    if target_pool:
        require(value['timing'] in ('BEFORE_SELECTOR', 'AFTER_SELECTION_BEFORE_APPLICATION'), 'invalid target input timing')
        options = choice['options']
        expected = ('AFTER_SELECTION_BEFORE_APPLICATION' if options['poolSnapshotTiming'] == 'AT_TARGET_ADDITION' else 'BEFORE_SELECTOR') if options['coverage'] != 'UNAVAILABLE' else value['timing']
    if expected is None:
        hidden_source = boundary == 'MASKED_CHOICE' and {'component': 'CONTEXT', 'reason': 'HIDDEN'} in value['omissions']
        require(value['status'] == 'UNAVAILABLE'
                and (hidden_source or {'component': 'CONTEXT', 'reason': 'NO_AUDITED_INPUT_BOUNDARY'} in value['omissions']),
                'input context claims an unaudited boundary')
    else:
        require(value['timing'] == expected, 'input context boundary/timing mismatch')
    if value['status'] != 'UNAVAILABLE':
        require(value['stateBasis'] != 'UNESTABLISHED', 'captured context has no state basis')
        if stage == 'CANDIDATE_EVALUATION':
            require(value['stateBasis'] == 'PLANNING_WORKING_STATE', 'evaluation context claims rules state')
        prompt = value['policy'].get('prompt')
        if value['schemaVersion'] >= 4 and value['diagnostics']['optionBindings']['coverage'] != 'UNSUPPORTED':
            require(boundary in ('CONTROLLER_EFFECT_ENTITY', 'CONTROLLER_ZONE_CHANGE_CARD') or target_pool, 'option links at unaudited input boundary')
        if prompt is not None:
            if prompt['kind'] == 'ZONE_CHANGE_OPERATION_REQUEST':
                require(operation and value['schemaVersion'] >= 9, 'zone operation request at unaudited boundary')
            elif prompt['kind'] == 'MODE_BOUNDS':
                require(mode and value['schemaVersion'] >= 5, 'mode prompt at unaudited boundary')
            elif prompt['kind'] == 'ENTITY_BOUNDS':
                require(multi and value['schemaVersion'] >= 6, 'entity bounds at unaudited boundary')
            elif prompt['kind'] == 'ENTITY_PICK_SEQUENCE':
                require(boundary == 'CONTROLLER_EFFECT_ENTITY' and value['schemaVersion'] >= 7
                        and 'entryLocation' in choice and 'selectionSequence' in choice['options'], 'history at unaudited boundary')
            elif prompt['kind'] == 'ZONE_PICK_PROGRESS':
                require(boundary == 'CONTROLLER_ZONE_CHANGE_CARD' and value['schemaVersion'] >= 10
                        and 'entryLocation' in choice and 'zoneSelectionProgress' in choice['options'], 'zone progress at unaudited boundary')
            elif prompt['kind'] == 'ZONE_CHANGE_REQUEST':
                require(boundary == 'CONTROLLER_ZONE_CHANGE_CARD' and value['schemaVersion'] >= 8
                        and 'entryLocation' in choice, 'zone request at unaudited boundary')
            else:
                require(boundary == {'NUMBER_RANGE': 'CONTROLLER_NUMBER_RANGE', 'NUMBER_OPTIONS': 'CONTROLLER_NUMBER_OPTIONS',
                                     'SINGLE_ENTITY': 'CONTROLLER_EFFECT_ENTITY'}[prompt['kind']], 'typed prompt at unaudited boundary')


def prompt_arguments(choice):
    context = choice['inputContext']
    prompt = (context['policy'] or {}).get('prompt')
    if prompt is None: return
    option = choice['options']
    if prompt['kind'] == 'ZONE_CHANGE_OPERATION_REQUEST':
        require(all(prompt[k] == option[k] for k in ('requestedIterations', 'destination', 'origins')), 'zone operation arguments differ')
        by_ref = {b['entityRef']: b['id'] for b in context['diagnostics']['playerBindings']}
        for field, raw in (('sourcePlayer', 'sourcePlayerId'), ('decider', 'deciderPlayerId')):
            require((None if prompt[field] is None else by_ref[prompt[field]]) == option[raw], 'zone operation role differs')
    elif prompt['kind'] == 'ZONE_PICK_PROGRESS':
        progress = option['zoneSelectionProgress']
        require(progress['progressCoverage'] == progress['receiptCoverage'] == 'COMPLETE', 'incomplete progress claims a prompt')
        require(all(prompt[k] == progress[k] for k in ('attemptIndex','loopPosition','priorNonNullReturnCount','retainedCount','requestedIterations')), 'progress prompt differs from entry')
        require(all(prompt[k] == option[k] for k in ('optional','destination','origins')), 'progress request differs from callback')
        by_ref = {b['entityRef']:b['id'] for b in context['diagnostics']['playerBindings']}
        require((None if prompt['decider'] is None else by_ref[prompt['decider']]) == option['deciderPlayerId'], 'progress decider differs')
        sequence_links(choice, {'priorSelections':progress['priorReturns']}, {'previousSelections':prompt['previousReturns']})
    elif prompt['kind'] == 'ZONE_CHANGE_REQUEST':
        require(all(prompt[k] == option[k] for k in ('optional', 'destination', 'origins')), 'zone request differs from callback arguments')
        by_ref = {b['entityRef']: b['id'] for b in context['diagnostics']['playerBindings']}
        require((None if prompt['decider'] is None else by_ref[prompt['decider']]) == option['deciderPlayerId'], 'zone request decider differs from callback')
    elif prompt['kind'] == 'ENTITY_PICK_SEQUENCE':
        sequence = option['selectionSequence']
        require(sequence['coverage'] == 'COMPLETE', 'incomplete history claims a sequence prompt')
        require(all(prompt[k] == sequence[k] for k in ('minimumTotal', 'maximumTotal', 'priorSelectionCount')), 'sequence prompt differs from observed history')
        require(prompt['optional'] == option['optional'], 'sequence optionality differs from callback')
        sequence_links(choice, sequence, prompt)
    elif prompt['kind'] == 'MODE_BOUNDS':
        require(option['coverage'] == 'OBSERVED_MODE_OPTIONS', 'mode bounds lack received arguments')
        require((prompt['minimumPicks'], prompt['maximumPicks'], prompt['allowRepeat']) ==
                (option['min'], option['max'], option['allowRepeat']), 'mode bounds differ from callback arguments')
    elif prompt['kind'] == 'ENTITY_BOUNDS':
        require(option['coverage'] in ('OBSERVED_EFFECT_OPTIONS_DIAGNOSTIC_ONLY', 'UNAVAILABLE'), 'entity bounds lack received arguments')
        require((prompt['minimumPicks'], prompt['maximumPicks']) == (option['min'], option['max']), 'entity bounds differ from callback arguments')
    elif prompt['kind'] == 'NUMBER_RANGE':
        require(option['coverage'] == 'OBSERVED_RANGE', 'prompt range lacks range arguments')
        require((prompt['minimum'], prompt['maximum']) == (option['minimum'], option['maximum']), 'prompt range differs from callback arguments')
    elif prompt['kind'] == 'NUMBER_OPTIONS':
        require(option['coverage'] == 'OBSERVED_DISCRETE_OPTIONS', 'prompt numeric list lacks list arguments')
        require(not option['truncated'] and prompt['values'] == option['items'], 'prompt numeric list differs from callback arguments')
    else:
        require(prompt['optional'] == option['optional'], 'prompt optionality differs from callback arguments')
    if prompt['kind'] in ('SINGLE_ENTITY', 'ENTITY_BOUNDS', 'ENTITY_PICK_SEQUENCE'):
        related = prompt['relatedPlayer']
        by_ref = {binding['entityRef']: binding['id'] for binding in context['diagnostics']['playerBindings']}
        require((None if related is None else by_ref[related]) == option['relatedPlayerId'], 'prompt player differs from callback arguments')


def zone_progress_metadata(choice):
    value=choice['options']['zoneSelectionProgress']
    require(choice['choiceType']=='ZONE_CHANGE_CARD_SELECTION' and 'entryLocation' in choice
            and choice.get('inputContext',{}).get('schemaVersion',0)>=10, 'zone progress lacks entry/profile')
    require(isinstance(value,dict) and set(value)=={'schemaVersion','progressCoverage','progressReason','attemptIndex','loopPosition',
            'priorNonNullReturnCount','retainedCount','requestedIterations','receiptCoverage','receiptReason','priorReturns'}
            and integer(value['schemaVersion']) and value['schemaVersion']==1,'invalid zone progress fields')
    require(all(integer(value[k]) and -(1<<31)<=value[k]<(1<<31) for k in ('loopPosition','requestedIterations'))
            and integer(value['retainedCount']) and 0<=value['retainedCount']<(1<<31),'invalid engine progress integers')
    require(all(value[k] is None or integer(value[k]) and 0<=value[k]<=(1<<63)-1 for k in ('attemptIndex','priorNonNullReturnCount')),'invalid progress long counters')
    if value['progressCoverage']=='COMPLETE':
        require(value['progressReason'] is None and value['loopPosition']>=0 and value['attemptIndex'] is not None
                and value['priorNonNullReturnCount'] is not None and value['priorNonNullReturnCount']<=value['attemptIndex'],'incomplete progress claims completeness')
    else:
        require(value['progressCoverage']=='UNAVAILABLE' and value['progressReason'] in ('UNSUPPORTED','COUNTER_OVERFLOW'),'invalid unavailable progress')
    refs=value['priorReturns'];count=value['priorNonNullReturnCount']
    require(isinstance(refs,list) and len(refs)<=64 and (count is None or len(refs)<=count),'invalid return receipt prefix')
    for ref in refs: typed_reference(ref,'CARD')
    if value['receiptCoverage']=='COMPLETE':
        require(value['progressCoverage']=='COMPLETE' and len(refs)==count and value['receiptReason'] is None,'incomplete receipts claim completeness')
    elif value['receiptCoverage']=='PREFIX':
        require(value['progressCoverage']=='COMPLETE' and len(refs)<count and value['receiptReason']=='OUTSIDE_CAPTURED_PREFIX','invalid receipt prefix')
    else:
        require(value['receiptCoverage']=='UNAVAILABLE' and value['receiptReason'] in ('CAPTURE_FAILED','UNSUPPORTED','COUNTER_OVERFLOW'),'invalid unavailable receipts')


def zone_progress_owner(choice,states):
    value=choice['options']['zoneSelectionProgress'];parent_id=choice['parentChoiceId']
    require(parent_id is not None,'zone progress lacks outer owner')
    state=states[parent_id];parent=state['start']['choice'];option=parent['options']
    require(parent['choiceType']=='ZONE_CHANGE_SELECTION_OPERATION' and all(parent[k]==choice[k] for k in ('chooserPlayerId','abilityId','sourceId')),'zone progress owner differs')
    require(value['requestedIterations']==option['requestedIterations']
            and all(choice['options'][k]==option[k] for k in ('destination','origins','deciderPlayerId')),'zone progress received arguments differ')
    prompt=((choice['inputContext'].get('policy') or {}).get('prompt'))
    if prompt is not None and prompt['kind']=='ZONE_PICK_PROGRESS':
        bindings={b['entityRef']:b['id'] for b in choice['inputContext']['diagnostics']['playerBindings']}
        require((None if prompt['sourcePlayer'] is None else bindings.get(prompt['sourcePlayer']))==option['sourcePlayerId'],'progress source role differs')
    require(not state.get('zoneProgressUnavailable') or value['progressCoverage']=='UNAVAILABLE','unavailable progress became complete')
    require(not state.get('zoneReceiptsIncomplete') or value['receiptCoverage']!='COMPLETE','incomplete receipts became complete')
    index,count=value['attemptIndex'],value['priorNonNullReturnCount']
    if index is not None:
        require(index>state.get('zoneLastIndex',-1),'duplicate or reversed attempt index')
        state['zoneLastIndex']=index
    previous=state.get('zoneLastCount')
    if previous is not None and index is not None and count is not None:
        gap=index-previous['index'];delta=count-previous['count']
        require(0<=delta<=gap,'return count differs from attempt gap')
        terminal=states[previous['choiceId']]['terminal']
        if terminal is not None:
            status=terminal['choice']['status']
            require(status!='ERROR' or value['progressCoverage']=='UNAVAILABLE','progress continued after selector error')
            if status=='COMPLETED':require(delta>=1 and (gap!=1 or delta==1),'completed callback missing from count')
            if status=='DECLINED' and gap==1:require(delta==0,'declined callback increments return count')
    if index is not None and count is not None:
        state['zoneLastCount']=dict(index=index,count=count,choiceId=choice['choiceId'])
    state['zoneProgressUnavailable']=state.get('zoneProgressUnavailable',False) or value['progressCoverage']=='UNAVAILABLE'
    state['zoneReceiptsIncomplete']=state.get('zoneReceiptsIncomplete',False) or value['receiptCoverage']!='COMPLETE'
    states[parent_id]=state


def sequence_metadata(choice):
    value = choice['options']['selectionSequence']
    require(choice['choiceType'] == 'EFFECT_ENTITY_SELECTION' and 'entryLocation' in choice
            and choice.get('inputContext', {}).get('schemaVersion', 0) >= 7, 'sequence metadata lacks audited entry/profile')
    require(isinstance(value, dict) and set(value) == {'schemaVersion', 'coverage', 'minimumTotal', 'maximumTotal',
            'priorSelectionCount', 'priorSelections', 'reason'} and integer(value['schemaVersion']) and value['schemaVersion'] == 1, 'invalid sequence metadata fields')
    require(value['coverage'] in ('COMPLETE', 'PREFIX', 'UNAVAILABLE')
            and all(integer(value[k]) and -(1 << 31) <= value[k] < (1 << 31) for k in ('minimumTotal', 'maximumTotal')), 'invalid sequence bounds/coverage')
    count, refs = value['priorSelectionCount'], value['priorSelections']
    require(integer(count) and 0 <= count < (1 << 31) and isinstance(refs, list) and len(refs) <= min(count, 64), 'invalid sequence receipt prefix')
    for ref in refs:
        reference(ref); require(ref['type'] in ('CARD', 'PLAYER'), 'non-entity history receipt')
    if value['coverage'] == 'COMPLETE':
        require(count == len(refs) and value['reason'] is None, 'incomplete receipt history claims completeness')
    elif value['coverage'] == 'PREFIX':
        require(len(refs) < count and value['reason'] == 'OUTSIDE_CAPTURED_PREFIX', 'invalid receipt prefix omission')
    else:
        require(value['reason'] in ('CAPTURE_FAILED', 'UNSUPPORTED', 'INTERRUPTED'), 'unavailable receipt history has no reason')
    require(choice['options']['optional'] == (count >= value['minimumTotal']), 'sequence local optionality differs from actual loop')


def sequence_owner(choice, states):
    value = choice['options']['selectionSequence']
    parent_id = choice['parentChoiceId']
    require(parent_id is not None, 'sequence lacks recorded outer owner')
    state = states[parent_id]
    parent = state['start']['choice']
    require(parent['choiceType'] == 'EFFECT_MULTI_ENTITY_SELECTION'
            and all(parent[k] == choice[k] for k in ('chooserPlayerId', 'abilityId', 'sourceId')), 'sequence owner differs from controller request')
    require((value['minimumTotal'], value['maximumTotal']) == (parent['options']['min'], parent['options']['max']), 'sequence bounds differ from outer request')
    require(value['priorSelectionCount'] > state.get('sequenceMaxPriorCount', -1), 'duplicate or reversed selector sequence position')
    state['sequenceMaxPriorCount'] = value['priorSelectionCount']
    states[parent_id] = state


def sequence_links(choice, sequence, prompt):
    context = choice['inputContext']; diagnostic = context['diagnostics']
    players = {b['entityRef']: dict(type='PLAYER', id=b['id']) for b in diagnostic['playerBindings']}
    cards = {b['entityRef']: dict(type='CARD', id=b['id'], gameTimestamp=b['gameTimestamp']) for b in diagnostic['cardBindings']}
    public = {c['entityRef'] for c in context['policy']['cards'] if c['zone'] in ('Battlefield', 'Command', 'Graveyard', 'Stack')
              or c['zone'] == 'Exile' and not c['faceDown']}
    refs = prompt['previousSelections']
    require(len(refs) == len(sequence['priorSelections']), 'history link prefix differs from receipts')
    for local, receipt in zip(refs, sequence['priorSelections']):
        if local is None: continue
        require(local in players or local in public, 'private or unknown history reference')
        require((players | cards)[local] == receipt, 'history receipt identity/incarnation mismatch')


def option_links(choice):
    context = choice['inputContext']
    if context['schemaVersion'] < 4 or context['policy'] is None: return
    links = context['diagnostics']['optionBindings']
    supported = choice['boundary'] in ('CONTROLLER_EFFECT_ENTITY', 'CONTROLLER_ZONE_CHANGE_CARD') or choice['choiceType'] == 'TARGET_POOL_SELECTION' and 'entryLocation' in choice
    if not supported:
        require(links['coverage'] == 'UNSUPPORTED', 'option links at unaudited boundary')
        return
    if links['coverage'] == 'UNSUPPORTED': return  # Optional capability; never infer missing links.
    options = choice['options']
    if options['coverage'] == 'UNAVAILABLE':
        require(links['coverage'] == 'UNAVAILABLE', 'unavailable pool has links')
        return
    require(links['coverage'] == 'DIAGNOSTIC_PUBLIC_CURRENT_V1' and len(links['items']) == len(options['items'])
            and links['truncated'] == options['truncated'], 'option link prefix differs from observed pool')
    diagnostic = context['diagnostics']
    players = {binding['entityRef']: dict(type='PLAYER', id=binding['id']) for binding in diagnostic['playerBindings']}
    cards = {binding['entityRef']: dict(type='CARD', id=binding['id'], gameTimestamp=binding['gameTimestamp'])
             for binding in diagnostic['cardBindings']}
    for link, reference in zip(links['items'], options['items']):
        ref = link['entityRef']
        require(ref is None or (players | cards)[ref] == reference, 'option link identity mismatch')


def input_envelope(value, record):
    policy = value['policy']
    if policy is not None:
        for field in ('turn', 'phase'):
            if field in record:
                require(policy[field] == record[field], 'input context/envelope ' + field + ' mismatch')


def entry_location(choice, known_players=None):
    if 'entryLocation' not in choice: return
    value = choice['entryLocation']
    _entries.validate(value, known_players, choice.get('inputContext'))
    if choice['boundary'] == 'MASKED_CHOICE':
        require(value['status'] == 'UNAVAILABLE' and value['reason'] == 'HIDDEN', 'entry exposes masked source')
        return
    require(value['reason'] != 'HIDDEN', 'hidden entry has unmasked source')
    if value['status'] != 'CAPTURED': return
    expected = CONTEXT_TIMING.get(choice['boundary'])
    if choice['boundary'] in MULTI_CONTEXT_TIMING: expected = MULTI_CONTEXT_TIMING[choice['boundary']]
    if choice['boundary'] in ZONE_OPERATION_CONTEXT_TIMING: expected = ZONE_OPERATION_CONTEXT_TIMING[choice['boundary']]
    if choice['boundary'] in MODE_CONTEXT_TIMING:
        expected = MODE_CONTEXT_TIMING[choice['boundary']]
        require(value['stateBasis'] == 'PLANNING_WORKING_STATE', 'mode entry claims committed state')
    if choice['choiceType'] == 'TARGET_POOL_SELECTION':
        options = choice['options']
        if options['coverage'] == 'UNAVAILABLE':
            require(value['timing'] in ('BEFORE_SELECTOR', 'AFTER_SELECTION_BEFORE_APPLICATION'), 'invalid target entry timing')
            return
        expected = 'AFTER_SELECTION_BEFORE_APPLICATION' if options['poolSnapshotTiming'] == 'AT_TARGET_ADDITION' else 'BEFORE_SELECTOR'
    require(expected is not None and value['timing'] == expected, 'entry claims an unaudited boundary')


def integer(v):
    return isinstance(v, int) and not isinstance(v, bool)


def require(ok, message):
    if not ok:
        raise ValueError(message)


def reference(ref):
    require(isinstance(ref, dict), 'invalid choice identity')
    keys = {'CARD': {'type', 'id', 'gameTimestamp'}, 'PLAYER': {'type', 'id'},
            'SPELL_ABILITY': {'type', 'id', 'stackId'}}.get(ref.get('type'))
    require(keys is not None and set(ref) == keys and integer(ref['id']), 'invalid choice identity fields')
    if ref['type'] == 'CARD': require(integer(ref['gameTimestamp']), 'invalid choice incarnation')
    if ref['type'] == 'SPELL_ABILITY': require(ref['stackId'] is None or integer(ref['stackId']), 'invalid choice stack identity')


def prefix(value, mapper):
    require(isinstance(value, dict) and set(value) == {'items', 'truncated'} and isinstance(value['items'], list)
            and len(value['items']) <= 256 and isinstance(value['truncated'], bool), 'invalid payment prefix')
    for item in value['items']: mapper(item)


def typed_reference(value, kind):
    reference(value)
    require(value['type'] == kind, 'wrong payment identity type')


def mana_reference(value):
    require(isinstance(value, dict) and set(value) == {'source', 'player', 'color', 'snow'}
            and integer(value['color']) and value['color'] in (0, 1, 2, 4, 8, 16, 32)
            and isinstance(value['snow'], bool), 'invalid payment mana identity')
    if value['source'] is not None: typed_reference(value['source'], 'CARD')
    if value['player'] is not None: typed_reference(value['player'], 'PLAYER')


def target_plan(plan, ability):
    require(isinstance(plan, dict) and set(plan) == {'abilities', 'abilitiesTruncated'}, 'invalid choice target plan')
    groups = plan['abilities']
    require(isinstance(groups, list) and len(groups) <= 128 and isinstance(plan['abilitiesTruncated'], bool), 'invalid choice plan coverage')
    require(bool(groups) or plan['abilitiesTruncated'], 'choice plan lacks root')
    for i, group in enumerate(groups):
        require(isinstance(group, dict) and set(group) == {'abilityId', 'targets', 'targetsTruncated'}, 'invalid choice target group')
        require(integer(group['abilityId']) and (i != 0 or group['abilityId'] == ability), 'choice target root mismatch')
        require(isinstance(group['targets'], list) and len(group['targets']) <= 256 and isinstance(group['targetsTruncated'], bool), 'invalid choice target prefix')
        for ref in group['targets']: reference(ref)


def options(choice):
    option = choice['options']
    if choice['choiceType'] == 'MASKED_CHOICE':
        require(option == {'coverage': 'MASKED'} and choice['boundary'] == 'MASKED_CHOICE', 'unmasked private choice')
        return
    if choice['choiceType'] == 'ZONE_CHANGE_SELECTION_OPERATION':
        require(isinstance(option, dict) and set(option) == {'coverage', 'branch', 'requestedIterations', 'destination', 'origins', 'sourcePlayerId', 'deciderPlayerId'}
                and option['coverage'] == 'RECEIVED_ZONE_OPERATION_REQUEST' and option['branch'] == 'ORDINARY_HIDDEN_ORIGIN', 'invalid zone operation options')
        require('entryLocation' in choice and choice.get('inputContext', {}).get('schemaVersion', 0) >= 9, 'zone operation lacks entry/profile')
        require(integer(option['requestedIterations']) and -(1 << 31) <= option['requestedIterations'] < (1 << 31), 'invalid iteration request')
        require(isinstance(option['destination'], str) and option['destination'] in _contexts.ZONES
                and isinstance(option['origins'], list) and len(option['origins']) <= len(_contexts.ZONES)
                and all(isinstance(z, str) and z in _contexts.ZONES for z in option['origins']), 'invalid operation zones')
        require(all(option[k] is None or integer(option[k]) for k in ('sourcePlayerId', 'deciderPlayerId')), 'invalid operation roles')
        return
    if choice['choiceType'] == 'EFFECT_MULTI_ENTITY_SELECTION':
        require(isinstance(option, dict) and set(option) == {'coverage', 'items', 'truncated', 'min', 'max', 'relatedPlayerId'}
                and option['coverage'] in ('UNAVAILABLE', 'OBSERVED_EFFECT_OPTIONS_DIAGNOSTIC_ONLY'), 'invalid outer entity options')
        require(isinstance(option['items'], list) and len(option['items']) <= 256 and isinstance(option['truncated'], bool)
                and all(integer(option[k]) and -(1 << 31) <= option[k] < (1 << 31) for k in ('min', 'max'))
                and (option['relatedPlayerId'] is None or integer(option['relatedPlayerId'])), 'invalid outer entity arguments')
        if option['coverage'] == 'UNAVAILABLE': require(not option['items'] and option['truncated'], 'unavailable outer pool has entries')
        for ref in option['items']:
            reference(ref); require(ref['type'] in ('CARD', 'PLAYER'), 'non-entity in outer pool')
        return
    if choice['choiceType'] in ('EFFECT_ENTITY_SELECTION', 'ZONE_CHANGE_CARD_SELECTION'):
        common = {'coverage', 'items', 'truncated', 'optional'}
        zone = choice['choiceType'] == 'ZONE_CHANGE_CARD_SELECTION'
        expected = common | ({'destination', 'origins', 'deciderPlayerId'} if zone else {'relatedPlayerId'})
        if 'zoneSelectionProgress' in option:
            require(zone, 'entity callback claims zone progress')
            expected |= {'zoneSelectionProgress'}
            zone_progress_metadata(choice)
        if 'selectionSequence' in option:
            require(not zone, 'zone-change callback claims controller-owned history')
            expected |= {'selectionSequence'}
            sequence_metadata(choice)
        require(isinstance(option, dict) and set(option) == expected
                and option['coverage'] in ('UNAVAILABLE', 'OBSERVED_ZONE_CHANGE_OPTIONS_DIAGNOSTIC_ONLY' if zone else 'OBSERVED_EFFECT_OPTIONS_DIAGNOSTIC_ONLY'),
                'invalid effect choice options')
        require(isinstance(option['items'], list) and len(option['items']) <= 256
                and isinstance(option['truncated'], bool) and isinstance(option['optional'], bool), 'invalid effect option prefix')
        if option['coverage'] == 'UNAVAILABLE':
            require(not option['items'] and option['truncated'], 'unavailable effect pool claims captured options')
        for ref in option['items']:
            typed_reference(ref, 'CARD') if zone else reference(ref)
        related = option['deciderPlayerId' if zone else 'relatedPlayerId']
        require(related is None or integer(related), 'invalid related choice player')
        if zone:
            require(option['destination'] in _contexts.ZONES and isinstance(option['origins'], list)
                    and len(option['origins']) <= len(_contexts.ZONES)
                    and all(origin in _contexts.ZONES for origin in option['origins']), 'invalid zone-change origin/destination')
        return
    if choice['choiceType'] == 'PAYMENT_DECISION':
        require(isinstance(option, dict) and set(option) == {'coverage', 'costKind', 'effect'}
                and option['coverage'] == 'PAYMENT_VISITOR_INPUT_NOT_ALTERNATIVES' and option['costKind'] in COST_KINDS
                and isinstance(option['effect'], bool) and choice['boundary'] == 'COST_VISITOR', 'invalid payment visitor input')
        return
    if choice['choiceType'] == 'PAYMENT_CARD_SELECTION':
        require(isinstance(option, dict) and set(option) == {'coverage', 'cards', 'minimum', 'maximum'}
                and option['coverage'] == 'OBSERVED_CARD_OPTIONS_NOT_ALL_LEGAL_PAYMENT_PLANS'
                and integer(option['minimum']) and integer(option['maximum'])
                and choice['boundary'] in ('PAYMENT_DISCARD_POOL', 'PAYMENT_SACRIFICE_POOL', 'PAYMENT_EXILE_POOL'), 'invalid payment card pool')
        prefix(option['cards'], lambda v: typed_reference(v, 'CARD'))
        return
    if choice['choiceType'] == 'PAYMENT_MANA':
        require(isinstance(option, dict) and set(option) == {'coverage', 'remainingManaValue', 'effect'}
                and option['coverage'] == 'OBSERVED_REQUIREMENT_NOT_ALTERNATIVE_PLANS'
                and integer(option['remainingManaValue']) and isinstance(option['effect'], bool)
                and choice['boundary'] == 'AUTO_MANA_PAYMENT', 'invalid mana requirement')
        return
    if choice['choiceType'] == 'PAYMENT_MANA_SELECTION':
        require(isinstance(option, dict) and set(option) == {'coverage', 'mana'} and option['coverage'] == 'OBSERVED_MANA_OPTIONS'
                and choice['boundary'] == 'MANA_POOL_OPTIONS', 'invalid mana options')
        prefix(option['mana'], mana_reference)
        return
    if choice['choiceType'] in ('NUMBER_SELECTION', 'X_SELECTION'):
        require(isinstance(option, dict), 'invalid numeric options')
        coverage = option.get('coverage')
        if choice['choiceType'] == 'X_SELECTION':
            require(set(option) == {'coverage', 'minimum', 'maximum', 'previousValue'} and coverage in ('OBSERVED_SELECTION_ONLY', 'HEURISTIC_BOUND_NOT_LEGAL_SET'), 'invalid X options')
            require(option['previousValue'] is None or integer(option['previousValue']), 'invalid previous X value')
            if coverage == 'OBSERVED_SELECTION_ONLY':
                require(option['minimum'] is None and option['maximum'] is None, 'unavailable X alternatives claim a bound')
            else:
                require(option['minimum'] == 0 and integer(option['minimum']) and integer(option['maximum']), 'invalid X heuristic bound')
        elif coverage == 'OBSERVED_RANGE':
            require(set(option) == {'coverage', 'minimum', 'maximum'} and integer(option['minimum']) and integer(option['maximum']), 'invalid observed numeric range')
        else:
            require(set(option) == {'coverage', 'items', 'truncated'} and coverage == 'OBSERVED_DISCRETE_OPTIONS'
                    and isinstance(option['items'], list) and len(option['items']) <= 256
                    and all(integer(v) for v in option['items']) and isinstance(option['truncated'], bool), 'invalid numeric option prefix')
        return
    if choice['choiceType'] == 'MODE_SELECTION':
        require(isinstance(option, dict) and set(option) == {'coverage', 'items', 'truncated', 'min', 'max', 'allowRepeat'}, 'invalid mode options')
        require(option['coverage'] == 'OBSERVED_MODE_OPTIONS' and choice['boundary'] in ('CHARM_MODE_HEURISTIC', 'CONTROLLER_MODES'), 'invalid mode coverage/boundary')
        require(isinstance(option['truncated'], bool) and isinstance(option['allowRepeat'], bool)
                and integer(option['min']) and integer(option['max']), 'invalid observed mode constraints')
        require(isinstance(option['items'], list) and len(option['items']) <= 256, 'invalid mode option prefix')
        for i, mode in enumerate(option['items']):
            require(isinstance(mode, dict) and set(mode) == {'abilityId', 'optionIndex', 'definitionIndex'} and integer(mode['abilityId'])
                    and integer(mode['optionIndex']) and mode['optionIndex'] == i, 'invalid mode option identity/index')
            require(mode['definitionIndex'] is None or integer(mode['definitionIndex']) and mode['definitionIndex'] >= 0, 'invalid mode definition index')
        return
    if choice['choiceType'] == 'TARGET_POOL_SELECTION':
        if option == {'coverage': 'UNAVAILABLE'}: return
        require(isinstance(option, dict) and set(option) == {'coverage', 'items', 'truncated', 'priorTargets', 'priorTargetsTruncated', 'poolSnapshotTiming', 'purpose'}, 'invalid standalone target pool')
        require(option['coverage'] == 'OBSERVED_HEURISTIC_POOL_NOT_ALL_LEGAL_TARGET_SETS' and choice['boundary'] in POOL_BOUNDARIES, 'invalid target pool coverage/boundary')
        require(option['poolSnapshotTiming'] in ('BEFORE_CHOOSER', 'AT_TARGET_ADDITION') and isinstance(option['truncated'], bool)
                and isinstance(option['priorTargetsTruncated'], bool), 'invalid pool timing/prefix')
        require(option['purpose'] == ('OPPONENT_PREFERENCE' if choice['boundary'] == 'DAMAGE_OPPONENT_POOL' else 'TARGET_RECOMMENDATION'), 'invalid target pool purpose')
        for field in ('items', 'priorTargets'):
            require(isinstance(option[field], list) and len(option[field]) <= 256, 'invalid target pool prefix')
            for ref in option[field]: reference(ref)
        return
    require(isinstance(option, dict) and set(option) == {'coverage', 'priorTargets'}
            and option['coverage'] == 'PRIOR_TARGET_PLAN_ONLY', 'invalid targeting choice options')
    target_plan(option['priorTargets'], choice['abilityId'])


def selection(start, terminal):
    value = terminal['selection']
    if terminal['status'] in ('DECLINED', 'ERROR') or start['choiceType'] == 'MASKED_CHOICE':
        require(value is None, 'unavailable choice carries selection')
        return
    if start['choiceType'] in ('EFFECT_MULTI_ENTITY_SELECTION', 'ZONE_CHANGE_SELECTION_OPERATION'):
        zone = start['choiceType'] == 'ZONE_CHANGE_SELECTION_OPERATION'
        require(isinstance(value, dict) and set(value) == {'coverage', 'items', 'selectedCount', 'truncated'}
                and value['coverage'] in ('OBSERVED_RETAINED_CARD_PREFIX' if zone else 'OBSERVED_RETURNED_ENTITY_PREFIX', 'UNAVAILABLE'), 'invalid outer entity result')
        items, count = value['items'], value['selectedCount']
        require(isinstance(items, list) and len(items) <= 256 and integer(count) and 0 <= count < (1 << 31)
                and len(items) <= count and isinstance(value['truncated'], bool), 'invalid outer entity result prefix')
        require(not items and value['truncated'] if value['coverage'] == 'UNAVAILABLE'
                else value['truncated'] == (count > len(items)), 'invalid outer entity result coverage')
        for ref in items:
            reference(ref); require(ref['type'] == 'CARD' if zone else ref['type'] in ('CARD', 'PLAYER'), 'invalid outer result identity')
        return
    if start['choiceType'] == 'PAYMENT_DECISION':
        require(isinstance(value, dict) and set(value) == {'decisionCount', 'cards', 'mana', 'players', 'abilities', 'colorMask', 'unrecordedDetails'}
                and integer(value['decisionCount']), 'invalid payment decision')
        prefix(value['cards'], lambda v: typed_reference(v, 'CARD'))
        for field, mapper in (('mana', mana_reference), ('players', lambda v: typed_reference(v, 'PLAYER')), ('abilities', lambda v: typed_reference(v, 'SPELL_ABILITY'))):
            if value[field] is not None: prefix(value[field], mapper)
        require(value['colorMask'] is None or integer(value['colorMask']) and 0 <= value['colorMask'] <= 31, 'invalid payment colors')
        require(isinstance(value['unrecordedDetails'], list) and all(v in ('TYPE_TEXT', 'COUNTER_TABLE', 'MANA_CONVERSION_MATRIX') for v in value['unrecordedDetails'])
                and len(set(value['unrecordedDetails'])) == len(value['unrecordedDetails']), 'invalid payment unavailable details')
        return
    if start['choiceType'] == 'PAYMENT_CARD_SELECTION':
        prefix(value, lambda v: typed_reference(v, 'CARD'))
        return
    if start['choiceType'] == 'PAYMENT_MANA':
        require(isinstance(value, dict) and set(value) == {'paymentRoutineReturned', 'costSatisfied', 'manaAbilities'}
                and value['paymentRoutineReturned'] is True and isinstance(value['costSatisfied'], bool), 'invalid mana payment return')
        prefix(value['manaAbilities'], lambda v: typed_reference(v, 'SPELL_ABILITY'))
        return
    if start['choiceType'] == 'PAYMENT_MANA_SELECTION':
        mana_reference(value)
        return
    if start['choiceType'] in ('NUMBER_SELECTION', 'X_SELECTION'):
        require(isinstance(value, dict) and set(value) == {'value', 'optionIndex', 'domainMatch'} and integer(value['value']), 'invalid numeric return')
        option, index = start['options'], value['optionIndex']
        require(index is None or integer(index), 'invalid numeric option index')
        if option['coverage'] == 'OBSERVED_DISCRETE_OPTIONS':
            matches = sum(v == value['value'] for v in option['items'])
            expected = 'UNIQUE_CAPTURED_OPTION' if matches == 1 else 'OUTSIDE_CAPTURED_OPTIONS' if matches == 0 else 'AMBIGUOUS_CAPTURED_OPTIONS'
            require(index is not None and 0 <= index < len(option['items']) and option['items'][index] == value['value'] if matches == 1 else index is None, 'numeric return/option mismatch')
        else:
            require(index is None, 'range/unknown numeric result claims an index')
            expected = 'UNAVAILABLE' if option['coverage'] == 'OBSERVED_SELECTION_ONLY' else 'WITHIN_OBSERVED_DOMAIN' if option['minimum'] <= value['value'] <= option['maximum'] else 'OUTSIDE_OBSERVED_DOMAIN'
        require(value['domainMatch'] == expected, 'invalid numeric domain match')
        return
    if start['choiceType'] == 'MODE_SELECTION':
        require(isinstance(value, dict) and set(value) == {'available', 'modes', 'selectedCount', 'truncated', 'accepted'}, 'invalid mode selection')
        require(isinstance(value['available'], bool) and isinstance(value['truncated'], bool)
                and (value['accepted'] is None or isinstance(value['accepted'], bool)), 'invalid mode selection flags')
        modes = value['modes']; count = value['selectedCount']
        require(isinstance(modes, list) and len(modes) <= 256, 'invalid selected mode prefix')
        require(count is None and not modes and not value['truncated'] if not value['available']
                else integer(count) and count >= len(modes) and value['truncated'] == (count > len(modes)), 'invalid selected mode count')
        offered = start['options']['items']
        for mode in modes:
            require(isinstance(mode, dict) and set(mode) == {'abilityId', 'optionIndex', 'definitionIndex'} and integer(mode['abilityId']), 'invalid selected mode identity')
            require(mode['definitionIndex'] is None or integer(mode['definitionIndex']) and mode['definitionIndex'] >= 0, 'invalid selected mode definition index')
            index = mode['optionIndex']
            require(index is None or integer(index) and 0 <= index < len(offered) and mode['abilityId'] == offered[index]['abilityId'], 'invalid selected mode option link')
            require(index is None or mode['definitionIndex'] == offered[index]['definitionIndex'], 'mode definition/offered index mismatch')
        return
    if start['choiceType'] in ('TARGET_POOL_SELECTION', 'EFFECT_ENTITY_SELECTION', 'ZONE_CHANGE_CARD_SELECTION'):
        require(isinstance(value, dict) and set(value) == {'selected', 'selectedOptionIndex', 'match'}, 'invalid target pool return')
        typed_reference(value['selected'], 'CARD') if start['choiceType'] == 'ZONE_CHANGE_CARD_SELECTION' else reference(value['selected'])
        index, kind = value['selectedOptionIndex'], value['match']
        require(index is None or integer(index), 'invalid target pool index')
        if start['options']['coverage'] == 'UNAVAILABLE':
            require(index is None and kind == 'UNAVAILABLE', 'unavailable pool claims indexed return')
            return
        refs = start['options']['items']; matches = sum(ref == value['selected'] for ref in refs)
        require(kind == ('UNIQUE' if matches == 1 else 'OUTSIDE_CAPTURED_POOL' if matches == 0 else 'AMBIGUOUS'), 'invalid target pool match status')
        require(index is not None and 0 <= index < len(refs) and refs[index] == value['selected'] if matches == 1 else index is None, 'invalid target pool return index')
        return
    require(isinstance(value, dict), 'invalid targeting result')
    fields = {'accepted', 'targets'} if start['choiceType'] in ('TARGET_SELECTION_RESULT', 'TRIGGER_PREPARATION_RESULT') else {'targets'}
    require(set(value) == fields, 'invalid targeting result fields')
    if 'accepted' in value: require(isinstance(value['accepted'], bool), 'invalid targeting acceptance')
    target_plan(value['targets'], start['abilityId'])


def accept(record, game):
    require(set(record) <= {'type', 'game', 'eventIndex', 'turn', 'phase', 'choice'}, 'unexpected standalone choice diagnostics')
    c = record.get('choice')
    require(isinstance(c, dict) and integer(c.get('choiceSchemaVersion')) and c['choiceSchemaVersion'] == 1, 'unsupported standalone choice schema')
    cid = c.get('choiceId')
    require(isinstance(cid, str) and bool(cid), 'missing standalone choice id')
    status = c.get('status')
    states = game['choices']
    if status == 'STARTED':
        fields = {'choiceSchemaVersion', 'choiceId', 'parentChoiceId', 'status', 'choiceType', 'boundary', 'stage', 'chooserPlayerId',
                  'chooserKind', 'abilityId', 'sourceId', 'decisionId', 'actionId', 'candidateAbilityId', 'assessmentId', 'resolvingAbilityId', 'options'}
        require(fields <= set(c) <= fields | {'inputContext', 'entryLocation'} and c['choiceType'] in TYPES and c['boundary'] in BOUNDARIES, 'invalid standalone choice start')
        require(c['boundary'] in TYPE_BOUNDARIES[c['choiceType']], 'choice type/boundary mismatch')
        require(c['stage'] in STAGES and c['chooserKind'] in ('AI', 'OTHER'), 'invalid standalone choice actor/stage')
        require(all(integer(c[k]) for k in ('chooserPlayerId', 'abilityId', 'sourceId')), 'invalid standalone choice identity')
        require(all(c[k] is None or integer(c[k]) for k in ('candidateAbilityId', 'resolvingAbilityId')), 'invalid nullable choice identity')
        require(all(c[k] is None or isinstance(c[k], str) and c[k] for k in ('decisionId', 'actionId')), 'invalid choice parent identity')
        require(c['assessmentId'] is None or isinstance(c['assessmentId'], str) and c['assessmentId'] and c['candidateAbilityId'] is not None, 'invalid choice assessment identity')
        require(c['actionId'] is None or c['decisionId'] is not None, 'choice action has no decision')
        require(c['candidateAbilityId'] is None or c['decisionId'] is not None, 'choice candidate has no decision')
        require(cid not in states, 'duplicate standalone choice id')
        parent_choice = c['parentChoiceId']
        require(parent_choice is None or isinstance(parent_choice, str) and parent_choice in states, 'choice lacks prior parent choice')
        if parent_choice is not None:
            require(states[parent_choice]['terminal'] is None, 'choice began after its parent choice finished')
        options(c)
        if 'selectionSequence' in c['options']: sequence_owner(c, states)
        if 'zoneSelectionProgress' in c['options']: zone_progress_owner(c, states)
        if 'inputContext' in c:
            validate_input_context(c['inputContext'], c['chooserPlayerId'])
            input_boundary(c['inputContext'], c['boundary'], c['stage'], c)
            input_envelope(c['inputContext'], record)
            prompt_arguments(c)
            option_links(c)
        entry_location(c)
        states[cid] = dict(start=record, terminal=None, postOutcome=game['outcome'] is not None)
    else:
        require(cid in states, 'choice terminal lacks start')
        require(set(c) == {'choiceSchemaVersion', 'choiceId', 'status', 'selection', 'elapsedNanos'}
                and status in ('COMPLETED', 'DECLINED', 'ERROR'), 'invalid standalone choice terminal')
        require(integer(c['elapsedNanos']) and c['elapsedNanos'] >= 0, 'invalid choice duration')
        state = states[cid]
        require(state['terminal'] is None, 'duplicate standalone choice terminal')
        selection(state['start']['choice'], c)
        state['terminal'] = record
        state['postOutcome'] |= game['outcome'] is not None
        states[cid] = state


def context(state, game):
    c = state['start']['choice']; parent = None
    if 'selectionSequence' in c['options']:
        owner = game['choices'][c['parentChoiceId']]
        terminal = owner['terminal']
        if terminal is not None and terminal['choice']['status'] == 'COMPLETED':
            count = terminal['choice']['selection']['selectedCount']
            child = state['terminal']
            returned = child is not None and child['choice']['status'] == 'COMPLETED'
            require(count >= c['options']['selectionSequence']['priorSelectionCount'] + int(returned),
                    'selector sequence exceeds actual outer return count')
    if c['parentChoiceId'] is not None and state['terminal'] is not None:
        outer = game['choices'][c['parentChoiceId']]
        if outer['terminal'] is not None and game['execution']['status'] == 'NORMAL':
            require(outer['terminal']['eventIndex'] > state['terminal']['eventIndex'], 'nested choice completed after its parent')
    if c['decisionId'] is not None:
        require(c['decisionId'] in game['decisionContexts'], 'choice lacks parent decision')
        parent = game['decisionContexts'][c['decisionId']]
        if c['candidateAbilityId'] is not None:
            matched = (c['decisionId'], c['candidateAbilityId'], c['sourceId']) in game['choiceCandidates']
            require(matched or parent['status'] != 'COMPLETED', 'choice candidate lacks completed assessment')
        if c['assessmentId'] is not None:
            match = game['choiceAssessments'].get((c['decisionId'], c['assessmentId']))
            require(match == [c['candidateAbilityId'], c['sourceId']] or match is None and parent['status'] != 'COMPLETED', 'choice assessment identity mismatch')
    if c['actionId'] is not None:
        require(c['actionId'] in game['actions'], 'choice lacks selected parent action')
        action = game['actions'][c['actionId']]
        require(action['identity']['decisionId'] == c['decisionId'] and action['identity']['sourceId'] == c['sourceId'], 'choice parent action mismatch')
        require(action['events'] and action['events'][0]['execution']['status'] == 'STARTED'
                and action['events'][0]['eventIndex'] < state['start']['eventIndex'], 'choice precedes parent attempt')
    if game['start'] is not None:
        require(c['chooserPlayerId'] in game['players'], 'unknown standalone choice chooser')
        if 'inputContext' in c: validate_input_context(c['inputContext'], c['chooserPlayerId'], game['players'])
        entry_location(c, game['players'])
    if state['terminal'] is None:
        require(game['execution']['status'] != 'NORMAL' or parent is not None and parent['status'] != 'COMPLETED', 'normal choice lacks terminal result')
    return parent


def rows(runs):
    for run in runs:
        numbers = run['games'].ordered_keys() if hasattr(run['games'], 'ordered_keys') else sorted(run['games'])
        for number in numbers:
            game = run['games'][number]
            for state in game['choices'].values():
                parent = context(state, game)
                terminal = state['terminal']
                yield dict(choiceDatasetSchemaVersion=1, runId=run['metadata']['runId'], game=number,
                           choiceId=state['start']['choice']['choiceId'], start=state['start'], terminal=terminal,
                           status=terminal['choice']['status'] if terminal else 'INCOMPLETE',
                           gameExecutionStatus=game['execution']['status'], postOutcome=state['postOutcome'],
                           censored=run['finished']['status'] == 'ABORTED' or game['execution']['status'] != 'NORMAL' or state['postOutcome'] or terminal is None
                                    or game['start'] is None or state['start']['eventIndex'] < game['start']['eventIndex']
                                    or terminal['choice']['status'] == 'ERROR' or parent is not None and parent['status'] != 'COMPLETED',
                           trainingReady=False)
