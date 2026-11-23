"""Standalone subordinate-choice validation and streaming export. No inferred identity joins."""
import importlib.util
from pathlib import Path

_context_spec = importlib.util.spec_from_file_location('forge_input_context', Path(__file__).with_name('input_context.py'))
_contexts = importlib.util.module_from_spec(_context_spec)
_context_spec.loader.exec_module(_contexts)

validate_input_context = _contexts.validate

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
}

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


def input_boundary(value, boundary, stage=None):
    expected = CONTEXT_TIMING.get(boundary)
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


def input_envelope(value, record):
    policy = value['policy']
    if policy is not None:
        for field in ('turn', 'phase'):
            if field in record:
                require(policy[field] == record[field], 'input context/envelope ' + field + ' mismatch')


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
    if choice['choiceType'] in ('EFFECT_ENTITY_SELECTION', 'ZONE_CHANGE_CARD_SELECTION'):
        common = {'coverage', 'items', 'truncated', 'optional'}
        zone = choice['choiceType'] == 'ZONE_CHANGE_CARD_SELECTION'
        expected = common | ({'destination', 'origins', 'deciderPlayerId'} if zone else {'relatedPlayerId'})
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
        require(set(c) in (fields, fields | {'inputContext'}) and c['choiceType'] in TYPES and c['boundary'] in BOUNDARIES, 'invalid standalone choice start')
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
        if 'inputContext' in c:
            validate_input_context(c['inputContext'], c['chooserPlayerId'])
            input_boundary(c['inputContext'], c['boundary'], c['stage'])
            input_envelope(c['inputContext'], record)
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
