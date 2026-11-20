#!/usr/bin/env python3
"""Validate Forge decision streams/full traces; export a dataset and standalone report.

Standard library only. This is an analysis dataset, not a complete RL observation/action space.
"""
import argparse
import collections
import hashlib
import importlib.util
import itertools
import os
import sqlite3
import tempfile
import json
from pathlib import Path
import sys

# Also support analysis callers that load this standalone script by absolute path.
_spool_spec = importlib.util.spec_from_file_location('forge_decision_spool', Path(__file__).with_name('decision_spool.py'))
_spool = importlib.util.module_from_spec(_spool_spec)
_spool_spec.loader.exec_module(_spool)
Spool = _spool.Spool


def ordered_games(games):
    return games.ordered_keys() if isinstance(games, _spool.Games) else sorted(games)


class InvalidData(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise InvalidData(message)


def integer(value):
    return isinstance(value, int) and not isinstance(value, bool)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()


TARGET_CHOICE_BOUNDARIES = {'DESTROY_REMOVAL_POOL', 'DESTROY_LAND_POOL', 'DESTROY_POLYMORPH_POOL',
                            'DAMAGE_OPPONENT_POOL', 'DAMAGE_KILLABLES', 'DAMAGE_MANDATORY_OPPONENT_POOL',
                            'DAMAGE_MANDATORY_FALLBACK_POOL', 'DAMAGE_KILLABLE_PLANESWALKERS',
                            'DAMAGE_PLANESWALKER_POOL', 'MASKED_TARGET_POOL'}


def validate_choice_reference(ref):
    require(isinstance(ref, dict), 'invalid choice reference')
    required = {'CARD': {'type', 'id', 'gameTimestamp'}, 'PLAYER': {'type', 'id'},
                'SPELL_ABILITY': {'type', 'id', 'stackId'}}.get(ref.get('type'))
    require(required is not None and set(ref) == required, 'invalid choice reference fields')
    require(integer(ref['id']), 'invalid choice reference identifier')
    if ref['type'] == 'CARD':
        require(integer(ref['gameTimestamp']), 'invalid choice card timestamp')
    if ref['type'] == 'SPELL_ABILITY':
        require(ref['stackId'] is None or integer(ref['stackId']), 'invalid choice stack identifier')


def validate_subordinate_choices(d, masked_ids):
    version = d.get('subordinateChoiceSchemaVersion')
    if version is None:
        require(not any(key in d for key in ('subordinateChoices', 'subordinateChoiceCoverage', 'subordinateChoiceOmissions')), 'undeclared subordinate choices')
        return
    require(integer(version) and version == 1, 'unsupported subordinate choice schema')
    require(d.get('subordinateChoiceCoverage') == 'OBSERVED_HEURISTIC_TARGET_POOLS_V1', 'invalid subordinate choice coverage')
    choices = d.get('subordinateChoices')
    require(isinstance(choices, list), 'missing subordinate choices')
    if 'subordinateChoiceOmissions' in d:
        omissions = d['subordinateChoiceOmissions']
        require(isinstance(omissions, dict) and set(omissions) == {'budget', 'interrupted'}
                and all(integer(value) and value >= 0 for value in omissions.values()), 'invalid target choice omissions')
    seen = set()
    actions = {action.get('actionId'): action for action in d['selectedActions'] if 'actionId' in action}
    fields = {'choiceSchemaVersion', 'choiceId', 'choiceType', 'stage', 'chooserPlayerId', 'candidateAbilityId',
              'abilityId', 'sourceId', 'boundary', 'optionCoverage', 'priorTargets', 'options', 'status',
              'selected', 'selectedOptionIndex', 'candidateIndex', 'candidateActionId'}
    for choice in choices:
        optional = {'optionsTruncated', 'priorTargetsTruncated', 'purpose'}
        require(isinstance(choice, dict) and fields <= set(choice) <= fields | optional, 'invalid subordinate choice fields')
        require(all(isinstance(choice[key], bool) for key in ('optionsTruncated', 'priorTargetsTruncated') if key in choice), 'invalid target capture truncation flag')
        require(integer(choice['choiceSchemaVersion']) and choice['choiceSchemaVersion'] == 1, 'invalid target choice schema')
        choice_id = choice['choiceId']
        require(isinstance(choice_id, str) and choice_id and choice_id not in seen, 'invalid or duplicate choice id')
        seen.add(choice_id)
        require(choice['choiceType'] == 'TARGET_POOL_SELECTION' and choice['stage'] == 'CANDIDATE_EVALUATION', 'invalid target choice stage/type')
        require(integer(choice['chooserPlayerId']) and choice['chooserPlayerId'] == d['playerId'], 'choice/decision chooser mismatch')
        require(all(integer(choice[key]) for key in ('candidateAbilityId', 'abilityId', 'sourceId')), 'invalid target choice identity')
        require(choice['boundary'] in TARGET_CHOICE_BOUNDARIES, 'invalid target choice boundary')
        if 'purpose' in choice:
            purpose = 'MASKED_TARGET_PLANNING' if choice['boundary'] == 'MASKED_TARGET_POOL' else 'OPPONENT_PREFERENCE' if choice['boundary'] == 'DAMAGE_OPPONENT_POOL' else 'TARGET_RECOMMENDATION'
            require(choice['purpose'] == purpose, 'choice purpose/boundary mismatch')
        require(choice['sourceId'] not in masked_ids or choice['boundary'] == 'MASKED_TARGET_POOL', 'choice boundary exposes masked source')
        require(choice['optionCoverage'] == 'OBSERVED_HEURISTIC_POOL_NOT_ALL_LEGAL_TARGET_SETS', 'invalid target option coverage')
        index = choice['candidateIndex']
        if index is not None:
            require(integer(index) and 0 <= index < len(d['candidates']), 'invalid choice candidate index')
            candidate = d['candidates'][index]
            require(candidate.get('abilityId') == choice['candidateAbilityId'] and candidate.get('sourceId') == choice['sourceId'], 'choice/candidate identity mismatch')
        action_id = choice['candidateActionId']
        if action_id is not None:
            require(isinstance(action_id, str) and action_id in actions, 'choice has no selected candidate action')
            require(actions[action_id]['abilityId'] == choice['candidateAbilityId'] and actions[action_id]['sourceId'] == choice['sourceId'], 'choice/action identity mismatch')
        for name in ('options', 'priorTargets'):
            require(isinstance(choice[name], list), 'invalid choice target list')
            for ref in choice[name]:
                validate_choice_reference(ref)
        selected, selected_index = choice['selected'], choice['selectedOptionIndex']
        if selected is not None:
            validate_choice_reference(selected)
        matches = sum(ref == selected for ref in choice['options'])
        status = choice['status']
        if status == 'SELECTED':
            require(selected is not None and integer(selected_index) and 0 <= selected_index < len(choice['options'])
                    and choice['options'][selected_index] == selected and matches == 1, 'choice selection/options mismatch')
        elif status in ('DECLINED', 'ERROR', 'INCOMPLETE'):
            require(selected is None and selected_index is None, 'unobserved choice carries a selection')
        elif status in ('OUTSIDE_OBSERVED_POOL', 'AMBIGUOUS_MATCH'):
            require(selected is not None and selected_index is None and (matches == 0 if status == 'OUTSIDE_OBSERVED_POOL' else matches > 1), 'invalid uncertain target choice')
        else:
            require(False, 'invalid target choice status')


def validate_decision(record, game):
    d = record.get('decision')
    require(isinstance(d, dict), 'AI_DECISION lacks decision object')
    require(d.get('decisionSchemaVersion') == 1, 'unsupported decision schema version')
    require(d.get('actionExecutionSchemaVersion') in (None, 1), 'unsupported action execution schema version')
    require(isinstance(d.get('decisionId'), str) and d['decisionId'], 'missing decisionId')
    require(integer(d.get('playerId')), 'invalid decision playerId')
    require(d.get('decisionType') == 'MAIN_PHASE_PLAY_SELECTION', 'unsupported decision type')
    require(d.get('candidateCoverage') == 'OBSERVED_EVALUATIONS_NOT_ALL_LEGAL_ACTIONS', 'unknown candidate coverage')
    require(d.get('observationCoverage') == 'PARTIAL_PLAYER_VIEW_V1', 'unknown observation coverage')
    require(d.get('status') in ('COMPLETED', 'AI_TIMEOUT', 'EVALUATION_TIMEOUT', 'EVALUATION_ERROR', 'ERROR'), 'invalid decision status')
    require(d.get('selection') in ('PLAY', 'PASS_PRIORITY', 'NO_ACTION'), 'invalid selection')
    require(integer(d.get('elapsedNanos')) and d['elapsedNanos'] >= 0, 'invalid decision duration')
    require(record.get('phase') in ('MAIN1', 'MAIN2'), 'decision outside a main phase')
    observation = d.get('observation')
    require(isinstance(observation, dict), 'missing observation')
    require(observation.get('turn') == record.get('turn') and observation.get('phase') == record.get('phase'), 'observation/envelope phase or turn mismatch')
    require(observation.get('turnPlayerId') == d['playerId'], 'decision outside acting player turn')
    players = observation.get('players')
    require(isinstance(players, list) and players, 'missing observation players')
    observed_ids = set()
    card_ids = set()
    masked_ids = set()
    for player in players:
        pid = player.get('id')
        require(integer(pid) and pid not in observed_ids and pid in game['players'], 'invalid or duplicate observation player')
        observed_ids.add(pid)
        require(integer(player.get('life')), 'invalid life total')
        for key in ('handCount', 'libraryCount'):
            require(integer(player.get(key)) and player[key] >= 0, 'invalid zone count')
        require(isinstance(player.get('floatingManaColors'), list) and all(integer(x) for x in player['floatingManaColors']), 'invalid mana observation')
        cards = player.get('cards')
        require(isinstance(cards, list), 'missing observed cards')
        for card in cards:
            require(integer(card.get('id')) and card['id'] not in card_ids, 'missing or duplicate observed card id')
            card_ids.add(card['id'])
            require(card.get('zone') in ('Hand', 'Battlefield', 'Command', 'Graveyard', 'Exile'), 'library/unsupported zone exposed')
            require(card['zone'] != 'Hand' or pid == d['playerId'], 'opponent hand exposed')
            require(isinstance(card.get('identityKnown'), bool), 'missing identity visibility flag')
            if not card['identityKnown']:
                masked_ids.add(card['id'])
                require(not any(k in card for k in ('name', 'types', 'manaCost', 'power', 'toughness')), 'masked card exposes identity/details')
    require(d['playerId'] in observed_ids, 'acting player absent from observation')
    candidates, selected = d.get('candidates'), d.get('selectedActions')
    require(isinstance(candidates, list) and isinstance(selected, list), 'missing candidates/selectedActions')
    for action in candidates + selected:
        land_option = action.get('kind') == 'LAND' and action.get('assessment') == 'LAND_OPTION' and action.get('assessmentStage') == 'LAND_SHORTLIST'
        require(integer(action.get('sourceId')) and (integer(action.get('abilityId')) or land_option and action not in selected), 'invalid action identifiers')
        if land_option:
            require(isinstance(action.get('heuristicDefault'), bool) and isinstance(action.get('searchOffered'), bool), 'invalid land shortlist flags')
        if 'assessmentDetail' in action:
            detail = action['assessmentDetail']
            require(isinstance(detail, dict) and isinstance(detail.get('reason'), str) and detail['reason'], 'invalid assessment detail')
            require('handlerRating' not in detail or integer(detail['handlerRating']), 'invalid handler rating')
            for field in ('handler', 'handlerAssessment'):
                require(field not in detail or isinstance(detail[field], str), 'invalid handler detail')
        require(action.get('kind') in ('LAND', 'SPELL', 'ABILITY'), 'unsupported action kind')
        if action['sourceId'] in masked_ids:
            require(not any(k in action for k in ('sourceName', 'description')), 'action exposes masked card identity')
            require(not any(k != 'reason' for k in action.get('assessmentDetail', {})), 'handler detail exposes masked card identity')
    for candidate in candidates:
        require(isinstance(candidate.get('assessment'), str) and candidate['assessment'], 'candidate lacks assessment')
    require(bool(selected) == (d['selection'] == 'PLAY'), 'selection/action count mismatch')
    if d.get('actionExecutionSchemaVersion') == 1:
        require(all(isinstance(a.get('actionId'), str) and a['actionId'] for a in selected), 'selected action lacks actionId')
    require((d['selection'] == 'NO_ACTION') == (d['status'] == 'ERROR'), 'selection error status mismatch')
    if 'search' in d:
        search = d['search']
        require(search.get('method') == 'FLAT_MONTE_CARLO' and isinstance(search.get('decidedByRollouts'), bool), 'invalid search summary')
        require(isinstance(search.get('reason'), str), 'missing search reason')
        for key in ('rollouts', 'candidatesEvaluated', 'elapsedNanos'):
            require(integer(search.get(key)) and search[key] >= 0, 'invalid search count/duration')
    validate_subordinate_choices(d, masked_ids)
    return d


ACTION_EVENT_TYPES = ('STACK_ADDED', 'STACK_RESOLVED', 'STACK_REMOVED', 'STACK_COUNTERED', 'STACK_CLEARED', 'LAND_PLAYED')
ACTION_EVENT_FIELDS = ('type', 'game', 'eventIndex', 'turn', 'phase', 'decisionId', 'actionId', 'stackId', 'abilityId', 'fizzled', 'finalizedTargets', 'finalizedModes', 'finalizedX', 'finalizedPayments')


def validate_finalized_payments(snapshot):
    if snapshot is None:
        return
    fields = {'schemaVersion', 'coverage', 'mana', 'lastLifePayment', 'phyrexianLifePaid', 'optionalCosts',
              'alternativeCost', 'keywordAmounts', 'withoutManaCost', 'cardPayments', 'unrecognizedCardPaymentGroups'}
    require(isinstance(snapshot, dict) and set(snapshot) == fields, 'invalid payment snapshot fields')
    require(integer(snapshot['schemaVersion']) and snapshot['schemaVersion'] == 1, 'unsupported payment schema')
    require(snapshot['coverage'] == 'ENGINE_PAYMENT_FACTS_V1', 'invalid payment coverage')
    for key in ('lastLifePayment', 'phyrexianLifePaid', 'unrecognizedCardPaymentGroups'):
        require(integer(snapshot[key]) and snapshot[key] >= 0, 'invalid payment count')
    require(isinstance(snapshot['withoutManaCost'], bool), 'invalid mana-cost waiver flag')

    def token(value):
        return isinstance(value, str) and value.isascii() and value.replace('_', '').isalnum()

    def card(ref):
        require(isinstance(ref, dict) and set(ref) == {'id', 'gameTimestamp'}, 'invalid payment card identity')
        require(integer(ref['id']) and integer(ref['gameTimestamp']), 'invalid payment card identifiers')

    require(isinstance(snapshot['optionalCosts'], list) and all(token(c) for c in snapshot['optionalCosts']), 'invalid optional payment costs')
    require(len(snapshot['optionalCosts']) == len(set(snapshot['optionalCosts'])), 'duplicate optional cost flag')
    require(snapshot['alternativeCost'] is None or token(snapshot['alternativeCost']), 'invalid alternative cost')
    require(isinstance(snapshot['mana'], list), 'invalid paid mana list')
    for mana in snapshot['mana']:
        require(isinstance(mana, dict) and set(mana) == {'color', 'playerId', 'source', 'snow'}, 'invalid paid mana fields')
        require(mana['color'] in ('W', 'U', 'B', 'R', 'G', 'C'), 'invalid paid mana color')
        require(mana['playerId'] is None or integer(mana['playerId']), 'invalid mana player identity')
        require(mana['snow'] is None or isinstance(mana['snow'], bool), 'invalid snow mana flag')
        if mana['source'] is not None:
            card(mana['source'])
    require(isinstance(snapshot['keywordAmounts'], list), 'invalid keyword payment amounts')
    for keyword in snapshot['keywordAmounts']:
        require(isinstance(keyword, dict) and set(keyword) == {'keyword', 'amount'}, 'invalid keyword payment fields')
        require(token(keyword['keyword']) and integer(keyword['amount']) and keyword['amount'] >= 0, 'invalid keyword payment amount')
    require(len({k['keyword'] for k in snapshot['keywordAmounts']}) == len(snapshot['keywordAmounts']), 'duplicate keyword payment aggregate')
    require(isinstance(snapshot['cardPayments'], list), 'invalid card payment list')
    types = ('Discarded', 'Enlisted', 'Exerted', 'Exiled', 'MovedToGrave', 'ControlGained', 'CardPutToLib',
             'CounterPut', 'Returned', 'Revealed', 'Sacrificed', 'Tapped', 'Unattached', 'Untapped')
    for payment in snapshot['cardPayments']:
        require(isinstance(payment, dict) and set(payment) == {'type', 'intrinsic', 'cards'}, 'invalid card payment fields')
        require(payment['type'] in types + tuple(t + 'Cards' for t in types) and isinstance(payment['intrinsic'], bool), 'invalid card payment type/flag')
        require(isinstance(payment['cards'], list), 'invalid paid card list')
        for ref in payment['cards']:
            card(ref)


def validate_finalized_x(snapshot):
    if snapshot is None:
        return
    require(isinstance(snapshot, dict) and set(snapshot) == {'schemaVersion', 'announcedValue'}, 'invalid finalized X snapshot')
    require(integer(snapshot['schemaVersion']) and snapshot['schemaVersion'] == 1, 'unsupported finalized X schema')
    value = snapshot['announcedValue']
    require(value is None or integer(value) and value >= 0, 'invalid announced X value')


def validate_finalized_modes(snapshot):
    if snapshot is None:
        return
    require(isinstance(snapshot, dict) and set(snapshot) == {'schemaVersion', 'modal', 'modes'}, 'invalid finalized mode snapshot')
    require(integer(snapshot['schemaVersion']) and snapshot['schemaVersion'] == 1, 'unsupported finalized mode schema')
    require(isinstance(snapshot['modal'], bool) and isinstance(snapshot['modes'], list), 'invalid finalized mode flags/list')
    require(snapshot['modal'] or not snapshot['modes'], 'mode choices on nonmodal action')
    for mode in snapshot['modes']:
        require(isinstance(mode, dict) and set(mode) == {'abilityId', 'optionIndex'}, 'invalid finalized mode reference')
        require(integer(mode['abilityId']), 'invalid finalized mode ability id')
        require(mode['optionIndex'] is None or integer(mode['optionIndex']) and mode['optionIndex'] >= 0, 'invalid finalized mode option index')


def validate_finalized_targets(snapshot, ability_id):
    if snapshot is None:
        return  # An unavailable engine entry is not an observed empty target list.
    require(isinstance(snapshot, dict) and set(snapshot) == {'schemaVersion', 'abilities'}, 'invalid finalized target snapshot')
    require(integer(snapshot['schemaVersion']) and snapshot['schemaVersion'] == 1, 'unsupported finalized target schema')
    groups = snapshot['abilities']
    require(isinstance(groups, list) and groups, 'finalized target snapshot lacks root ability')
    for group in groups:
        require(isinstance(group, dict) and set(group) == {'abilityId', 'usesTargeting', 'targets'}, 'invalid target ability group')
        require(integer(group['abilityId']) and isinstance(group['usesTargeting'], bool), 'invalid target ability identity/flag')
        require(isinstance(group['targets'], list), 'invalid finalized target list')
        for target in group['targets']:
            require(isinstance(target, dict), 'invalid finalized target reference')
            kind = target.get('type')
            required = {'CARD': {'type', 'id', 'gameTimestamp'}, 'PLAYER': {'type', 'id'},
                        'SPELL_ABILITY': {'type', 'id', 'stackId'}}.get(kind)
            require(required is not None and required <= set(target) <= required | {'dividedAmount'}, 'invalid finalized target fields')
            require(integer(target['id']), 'invalid finalized target identifier')
            if kind == 'CARD':
                require(integer(target['gameTimestamp']), 'invalid target card timestamp')
            if kind == 'SPELL_ABILITY':
                require(target['stackId'] is None or integer(target['stackId']), 'invalid targeted stack identifier')
            if 'dividedAmount' in target:
                require(integer(target['dividedAmount']) and target['dividedAmount'] >= 0, 'invalid divided target allocation')
    require(groups[0]['abilityId'] == ability_id, 'finalized target root/stack ability mismatch')


def accept_action_event(record, game):
    """Join explicit references only; preserve observed events without inferring a result."""
    if record['type'] == 'STACK_CLEARED':
        require(record.get('actionId') is None and record.get('decisionId') is None, 'bulk clear has a single action reference')
        cleared = record.get('clearedActions', [])
        require(isinstance(cleared, list), 'invalid cleared actions')
        require(all(isinstance(ref, dict) and set(ref) == {'stackId', 'decisionId', 'actionId'} for ref in cleared), 'invalid cleared action reference')
        for ref in cleared:
            _accept_action_event(dict(record, **ref), game)
    elif record.get('actionId') is not None or record.get('decisionId') is not None:
        _accept_action_event(record, game)


def _accept_action_event(record, game):
    action_id = record.get('actionId')
    require(isinstance(action_id, str) and action_id in game['actions'], 'engine event has no preceding selected action')
    action = game['actions'][action_id]
    require(record.get('decisionId') == action['identity']['decisionId'], 'engine event decision/action mismatch')
    require(action['events'] and action['events'][0]['execution']['status'] == 'STARTED', 'engine event before execution start')
    kind = record['type']
    if 'finalizedTargets' in record:
        require(kind == 'STACK_ADDED', 'finalized target snapshot outside stack addition')
        validate_finalized_targets(record['finalizedTargets'], record.get('abilityId'))
    if 'finalizedModes' in record:
        require(kind == 'STACK_ADDED', 'finalized mode snapshot outside stack addition')
        validate_finalized_modes(record['finalizedModes'])
    if 'finalizedX' in record:
        require(kind == 'STACK_ADDED', 'finalized X snapshot outside stack addition')
        validate_finalized_x(record['finalizedX'])
    if 'finalizedPayments' in record:
        require(kind == 'STACK_ADDED', 'payment snapshot outside stack addition')
        validate_finalized_payments(record['finalizedPayments'])
    require(not any(e['type'] == kind for e in action['engineEvents']), 'duplicate linked engine event')
    if kind == 'LAND_PLAYED':
        require(action['identity']['kind'] == 'LAND', 'land event linked to non-land selection')
    else:
        require(action['identity']['kind'] != 'LAND' and integer(record.get('stackId')), 'invalid linked stack identity')
        if kind != 'STACK_CLEARED':
            require(integer(record.get('abilityId')), 'linked stack event lacks ability identity')
        if kind == 'STACK_ADDED':
            action['stackIdentity'] = (record['stackId'], record['abilityId'])
            for prior in action['events']:
                execution = prior['execution']
                if execution['status'] == 'SUCCEEDED' and execution['reason'] == 'STACKED':
                    require((execution['stackId'], execution['executedAbilityId']) == action['stackIdentity'], 'execution/stack identity mismatch')
        else:
            require(action['stackIdentity'] is not None, 'linked stack event without stack addition')
            stack_id, ability_id = action['stackIdentity']
            require(record['stackId'] == stack_id and record.get('abilityId', ability_id) == ability_id, 'linked stack identity mismatch')
    if kind == 'STACK_RESOLVED':
        require(isinstance(record.get('fizzled'), bool), 'resolution lacks fizzled flag')
    event = {key: record[key] for key in ACTION_EVENT_FIELDS if key in record}
    event['postOutcome'] = game['outcome'] is not None
    action['engineEvents'].append(event)
    game['actions'][action_id] = action


def accept_execution(record, game):
    execution = record.get('execution')
    require(isinstance(execution, dict) and execution.get('executionSchemaVersion') == 1, 'invalid action execution schema')
    require(set(execution) <= {'executionSchemaVersion', 'actionId', 'decisionId', 'playerId', 'abilityId', 'sourceId', 'kind',
                               'status', 'reason', 'executedAbilityId', 'stackId'}, 'unexpected execution payload field')
    action_id = execution.get('actionId')
    require(isinstance(action_id, str) and action_id in game['actions'], 'execution has no preceding selected action')
    action = game['actions'][action_id]
    require(all(integer(execution.get(k)) for k in ('playerId', 'abilityId', 'sourceId')), 'invalid execution identity identifiers')
    for key in ('decisionId', 'playerId', 'abilityId', 'sourceId', 'kind'):
        require(execution.get(key) == action['identity'][key], 'execution/selection identity mismatch')
    require(isinstance(execution.get('reason'), str) and execution['reason'], 'missing execution reason')
    for key in ('executedAbilityId', 'stackId'):
        require(key not in execution or integer(execution[key]), 'invalid execution engine identifier')
    status = execution.get('status')
    require(action['terminal'] is None, 'execution after terminal result')
    if status == 'STARTED':
        require(not action['events'], 'duplicate execution start')
    elif status == 'ABANDONED':
        require(not action['events'], 'attempted action cannot be abandoned')
        action['terminal'] = status
    else:
        require(status in ('SUCCEEDED', 'FAILED', 'ERROR', 'UNOBSERVED'), 'invalid action execution status')
        require(action['events'] and action['events'][0]['execution']['status'] == 'STARTED', 'execution result without start')
        if status == 'SUCCEEDED' and execution['reason'] == 'STACKED':
            require('stackId' in execution and 'executedAbilityId' in execution, 'stacked action lacks engine identifiers')
            if action['stackIdentity'] is not None:
                require((execution['stackId'], execution['executedAbilityId']) == action['stackIdentity'], 'execution/stack identity mismatch')
        action['terminal'] = status
    # Keep only the execution envelope, not unrelated diagnostic fields from a full trace.
    event = {key: record[key] for key in ('type', 'game', 'eventIndex', 'turn', 'phase') if key in record}
    event.update(execution=execution, postOutcome=game['outcome'] is not None)
    action['events'].append(event)
    game['actions'][action_id] = action


def load_run(path, *, games=None):
    """Validate completely before returning anything that can be written to the dataset."""
    metadata = None
    finished = None
    games = {} if games is None else games
    records = 0
    file_hash = hashlib.sha256()
    with path.open('rb') as source:
        for line_number, raw in enumerate(source, 1):
            file_hash.update(raw)
            try:
                record = json.loads(raw)
                require(isinstance(record, dict), 'record is not an object')
                kind = record.get('type')
                require(isinstance(kind, str), 'record lacks type')
                require(finished is None, 'record after RUN_FINISHED')
                if metadata is None:
                    require(kind == 'RUN_STARTED' and record.get('schemaVersion') == 1, 'file must start with supported RUN_STARTED')
                    require(record.get('streamKind') in (None, 'FULL_GAME_TRACE', 'AI_DECISIONS'), 'unsupported stream kind')
                    require(record.get('actionEventSchemaVersion') in (None, 1), 'unsupported action event schema')
                    require(record.get('eventIndexMode', 'CONTIGUOUS') in ('CONTIGUOUS', 'SHARED_WITH_GAME_TRACE'), 'unsupported event index mode')
                    require(record.get('eventIndexMode') != 'SHARED_WITH_GAME_TRACE' or record.get('streamKind') == 'AI_DECISIONS', 'shared indices require a filtered decision stream')
                    require('runId' not in record or isinstance(record['runId'], str) and record['runId'], 'invalid runId')
                    metadata = record
                    metadata['sourcePath'] = str(path.resolve())
                    participants = record.get('participants')
                    require(isinstance(participants, list) and participants, 'missing run participants')
                    participant_ids = [p.get('participantId') for p in participants]
                    require(all(integer(p) for p in participant_ids) and len(set(participant_ids)) == len(participant_ids), 'invalid run participant ids')
                    for p in participants:
                        require(isinstance(p.get('deckName'), str) and p['deckName'], 'missing deck name')
                        require(isinstance(p.get('deck'), dict), 'missing deck snapshot')
                        for cards in p['deck'].values():
                            require(isinstance(cards, list), 'malformed deck section')
                            for card in cards:
                                require(isinstance(card.get('name'), str) and integer(card.get('count')) and card['count'] > 0, 'invalid deck card/count')
                    records += 1
                    continue
                require(kind != 'RUN_STARTED', 'duplicate RUN_STARTED')
                if kind == 'RUN_FINISHED':
                    finished = record
                else:
                    number = record.get('game')
                    require(integer(number) and number > 0, 'missing game number')
                    game = games.setdefault(number, dict(lastIndex=0, start=None, outcome=None, finish=None, execution=None, decisions=[], decisionIds=set(), players={}, actions={}))
                    require(game['execution'] is None, 'scoped event after GAME_EXECUTION_FINISHED')
                    index = record.get('eventIndex')
                    require(integer(index) and index > game['lastIndex'], 'event indices not strictly increasing')
                    if metadata.get('eventIndexMode', 'CONTIGUOUS') == 'CONTIGUOUS':
                        require(index == game['lastIndex'] + 1, 'event index gap')
                    game['lastIndex'] = index
                    if metadata.get('streamKind') == 'AI_DECISIONS':
                        require(kind in ('GAME_STARTED', 'AI_DECISION', 'AI_ACTION_EXECUTION', 'GAME_OUTCOME', 'GAME_FINISHED', 'GAME_EXECUTION_FINISHED') + ACTION_EVENT_TYPES, 'unexpected full game event in decision stream')
                        if kind in ACTION_EVENT_TYPES:
                            require(metadata.get('actionEventSchemaVersion') == 1, 'linked event in undeclared decision stream')
                            require(set(record) <= set(ACTION_EVENT_FIELDS) | {'clearedActions'}, 'unexpected diagnostic field in linked event')
                            require(record.get('actionId') is not None or kind == 'STACK_CLEARED' and record.get('clearedActions'), 'unlinked engine event in decision stream')
                    if kind == 'GAME_STARTED':
                        require(game['start'] is None, 'duplicate GAME_STARTED')
                        game['start'] = record
                        for participant in record.get('players', []):
                            pid = participant.get('player', {}).get('id')
                            require(integer(pid) and pid not in game['players'], 'invalid game player id')
                            require(participant.get('participantId') in participant_ids, 'game player has no run participant')
                            game['players'][pid] = participant['participantId']
                    elif kind == 'AI_DECISION':
                        require(game['start'] is not None, 'decision before game start')
                        # Cancellation sets the outcome before an in-flight thinking session returns.
                        # Retain its actual position in the stream; never give such a choice an outcome label.
                        record['postOutcome'] = game['outcome'] is not None
                        d = validate_decision(record, game)
                        require(d['decisionId'] not in game['decisionIds'], 'duplicate decision id')
                        game['decisionIds'].add(d['decisionId'])
                        require(d['playerId'] in game['players'], 'unknown deciding player')
                        game['decisions'].append(record)
                        if d.get('actionExecutionSchemaVersion') == 1:
                            for selected in d['selectedActions']:
                                action_id = selected['actionId']
                                require(action_id not in game['actions'], 'duplicate selected actionId')
                                identity = dict(selected, decisionId=d['decisionId'], playerId=d['playerId'])
                                game['actions'][action_id] = dict(identity=identity, events=[], terminal=None, engineEvents=[], stackIdentity=None)
                    elif kind == 'AI_ACTION_EXECUTION':
                        accept_execution(record, game)
                    elif kind in ACTION_EVENT_TYPES:
                        accept_action_event(record, game)
                    elif kind == 'GAME_OUTCOME':
                        require(game['outcome'] is None, 'duplicate outcome')
                        game['outcome'] = record
                    elif kind == 'GAME_FINISHED':
                        require(game['finish'] is None and game['outcome'] is not None, 'finish without outcome or duplicate finish')
                        game['finish'] = record
                    elif kind == 'GAME_EXECUTION_FINISHED':
                        require(record.get('status') in ('NORMAL', 'TIMEOUT', 'EXCEPTION'), 'invalid execution status')
                        game['execution'] = record
                    games[number] = game
                records += 1
            except (InvalidData, ValueError, KeyError, TypeError, AttributeError) as exc:
                raise InvalidData(f'{path}:{line_number}: {exc}') from exc
    require(metadata is not None and finished is not None, f'{path}: incomplete run (missing boundaries)')
    require(games, f'{path}: run has no games')
    counts = collections.Counter()
    for number, game in games.items():
        execution, outcome = game['execution'], game['outcome']
        require(execution is not None, f'{path}: game {number} lacks execution finish')
        counts[execution['status']] += 1
        if execution['status'] == 'NORMAL':
            require(all(a['terminal'] is not None for a in game['actions'].values()), f'{path}: normally completed game has unfinished selected actions')
        if execution['status'] != 'EXCEPTION':
            require(game['finish'] is not None and outcome is not None, f'{path}: game {number} lacks outcome/finish')
            # A cancellation can finalize a draw during setup, before GAME_STARTED is emitted.
            # There are no decisions to label, but the attempted game's administrative result matters.
        if execution['status'] == 'NORMAL' or game['decisions']:
            require(game['start'] is not None, f'{path}: game {number} lacks start')
        if outcome is not None:
            require(isinstance(outcome.get('draw'), bool) and isinstance(outcome.get('winners'), list), f'{path}: malformed outcome')
            require(outcome['draw'] == (not outcome['winners']), f'{path}: draw/winner inconsistency')
            require(outcome.get('endReason') != 'Draw' or outcome['draw'], f'{path}: Draw reason has winner')
            for winner in outcome['winners']:
                require(isinstance(winner, dict), f'{path}: malformed winner')
                require(winner.get('player', {}).get('id') in game['players'], f'{path}: unknown winner')
                require(game['players'][winner['player']['id']] == winner.get('participantId'), f'{path}: winner participant mismatch')
    require(all(number == expected for expected, number in enumerate(ordered_games(games), 1)), f'{path}: attempted game sequence has gaps')
    require(finished.get('status') in ('NORMAL', 'TIMEOUT', 'EXCEPTION', 'ABORTED'), f'{path}: invalid run status')
    expected = 'EXCEPTION' if counts['EXCEPTION'] else 'TIMEOUT' if counts['TIMEOUT'] else 'NORMAL'
    require(finished['status'] in (expected, 'ABORTED'), f'{path}: aggregate status disagrees with executions')
    for key, expected_count in (('gamesAttempted', len(games)), ('gamesNormal', counts['NORMAL']), ('gamesTimedOut', counts['TIMEOUT']), ('gamesFailed', counts['EXCEPTION'])):
        require(finished.get(key) == expected_count, f'{path}: {key} disagrees with executions')
    metadata['sourceSha256'] = file_hash.hexdigest()
    metadata.setdefault('runId', 'legacy-' + file_hash.hexdigest())
    metadata.setdefault('pilotMode', 'UNKNOWN')
    metadata.setdefault('engineRevision', 'UNKNOWN')
    metadata.setdefault('engineDirty', 'UNKNOWN')
    for participant in metadata['participants']:
        participant['deckSha256'] = digest(participant['deck'])
    return dict(metadata=metadata, games=games, finished=finished, records=records)


def dataset_rows(run):
    meta = run['metadata']
    participants = {p['participantId']: p for p in meta['participants']}
    for number in ordered_games(run['games']):
        game = run['games'][number]
        for record in game['decisions']:
            d = record['decision']
            participant_id = game['players'][d['playerId']]
            participant = participants[participant_id]
            linked = []
            for selected in d['selectedActions']:
                action = game['actions'].get(selected.get('actionId'))
                linked.append(dict(actionId=selected.get('actionId'),
                                   status=(action['terminal'] or 'INCOMPLETE') if action else 'UNAVAILABLE',
                                   events=action['events'] if action else [],
                                   engineEventCoverage='LINKED_EVENTS_V1' if meta.get('actionEventSchemaVersion') == 1 and action else 'UNAVAILABLE',
                                   engineEvents=action['engineEvents'] if action else []))
            eligible = (run['finished']['status'] != 'ABORTED' and game['execution']['status'] == 'NORMAL' and d['status'] == 'COMPLETED' and not record.get('postOutcome', False))
            eligible = eligible and not any(a['status'] in ('ABANDONED', 'ERROR', 'UNOBSERVED', 'INCOMPLETE')
                                           or any(e['postOutcome'] for e in a['events'] + a['engineEvents']) for a in linked)
            outcome = game['outcome']
            winners = [p['participantId'] for p in outcome['winners']] if outcome else []
            label = ('DRAW' if outcome['draw'] else 'WIN' if participant_id in winners else 'LOSS') if eligible else None
            yield dict(datasetSchemaVersion=1, runId=meta['runId'], game=number, decisionId=d['decisionId'],
                       sourceEventIndex=record['eventIndex'], postOutcome=record.get('postOutcome', False), participantId=participant_id,
                       deckName=participant.get('deckName'), deckSha256=participant['deckSha256'],
                       aiProfile=participant.get('aiProfile'), pilotMode=meta['pilotMode'], seed=meta.get('seed'),
                       forgeVersion=meta.get('forgeVersion'), engineRevision=meta['engineRevision'], engineDirty=meta['engineDirty'],
                       executionStatus=game['execution']['status'], outcomeEligible=eligible,
                       outcomeLabel=label, trainingReady=False, actionExecutions=linked, decision=d)


def matches_selected(action, candidate):
    if action['kind'] == 'LAND' and candidate['kind'] == 'LAND':
        return action['sourceId'] == candidate['sourceId']
    return action.get('abilityId') == candidate.get('abilityId')


def make_coverage(runs, rows, *, spool=None):
    common = ('selections', 'selectedActionKinds', 'phases', 'decisionStatuses', 'outcomeLabels',
              'pilotModes', 'assessments', 'assessmentDetails', 'cardAiHandlers', 'handlerAssessments',
              'rejectedCards', 'rejectedActionKinds', 'rejectedCardKinds', 'searchReasons')
    other = ('executionStatuses', 'actionExecutionStatuses', 'linkedEngineEvents', 'actionEngineEventCoverage',
             'finalizedTargetSnapshots', 'finalizedTargetTypes', 'finalizedModeSnapshots', 'selectedModeCounts',
             'finalizedXSnapshots', 'announcedXValues', 'finalizedPaymentSnapshots', 'optionalCostSelections',
             'alternativeCostSelections', 'cardPaymentGroups')
    choice_keys = ('subordinateChoiceCoverage', 'subordinateChoiceTypes', 'subordinateChoiceStatuses',
                   'subordinateChoiceBoundaries', 'targetPoolSizes', 'targetChoiceCandidateLinks',
                   'targetChoiceAssessmentLinks', 'targetChoiceCaptureBudgetCoverage',
                   'targetPoolCaptureCompleteness', 'priorTargetsCaptureCompleteness', 'subordinateChoicePurposes')
    counters = {name: spool.counter('coverage:' + name, name in common) if spool else collections.Counter()
                for name in common + other + choice_keys}
    totals = dict(outcomeEligibleDecisions=0, censoredDecisions=0, decisionsAfterOutcome=0,
                  decisionsWithAssessments=0, selectedActionsWithoutAssessment=0,
                  unrecognizedCardPaymentGroups=0, searchRecords=0, searchDecidedByRollouts=0)
    grouped = {}
    declared_choices = False
    choice_records = 0
    declared_targeted_actions = 0
    targeted_actions_with_pool_record = 0
    targeted_actions_with_recommendation = 0
    budget_omissions = 0
    interrupted_captures = 0

    def add(name, value):
        counters[name].update((value,))

    def count(name):
        counter = counters[name]
        return counter if spool else dict(counter.most_common() if name in common else counter.items())

    for run in runs:
        for game in run['games'].values():
            add('executionStatuses', game['execution']['status'])
    for row in rows:
        d = row['decision']
        declared = d.get('subordinateChoiceSchemaVersion') == 1
        declared_choices = declared_choices or declared
        if declared:
            omissions = d.get('subordinateChoiceOmissions')
            add('targetChoiceCaptureBudgetCoverage', 'DECLARED' if omissions is not None else 'UNAVAILABLE')
            if omissions is not None:
                budget_omissions += omissions['budget']
                interrupted_captures += omissions['interrupted']
        observed_candidates = {choice['candidateActionId'] for choice in d.get('subordinateChoices', [])
                               if choice['candidateActionId'] is not None}
        recommended_candidates = {choice['candidateActionId'] for choice in d.get('subordinateChoices', [])
                                  if choice.get('purpose') == 'TARGET_RECOMMENDATION' and choice['candidateActionId'] is not None}
        add('subordinateChoiceCoverage', d['subordinateChoiceCoverage'] if declared else 'UNAVAILABLE')
        for choice in d.get('subordinateChoices', []):
            choice_records += 1
            add('subordinateChoiceTypes', choice['choiceType'])
            add('subordinateChoiceStatuses', choice['status'])
            add('subordinateChoiceBoundaries', choice['boundary'])
            add('subordinateChoicePurposes', choice.get('purpose', 'UNAVAILABLE'))
            add('targetPoolSizes', len(choice['options']))
            add('targetChoiceCandidateLinks', 'EXACT_SELECTED_CANDIDATE' if choice['candidateActionId'] is not None else 'NO_EXACT_SELECTED_ACTION_LINK')
            add('targetChoiceAssessmentLinks', 'INDEXED_ASSESSMENT' if choice['candidateIndex'] is not None else 'MISSING_ASSESSMENT')
            for flag, metric in (('optionsTruncated', 'targetPoolCaptureCompleteness'),
                                 ('priorTargetsTruncated', 'priorTargetsCaptureCompleteness')):
                add(metric, 'TRUNCATED' if choice.get(flag) is True else 'OBSERVED_FULL_LIST' if choice.get(flag) is False else 'UNAVAILABLE')
        for name, value in (('selections', d['selection']), ('phases', d['observation']['phase']),
                            ('decisionStatuses', d['status']), ('outcomeLabels', row['outcomeLabel'] or 'CENSORED'),
                            ('pilotModes', row['pilotMode'])):
            add(name, value)
        totals['outcomeEligibleDecisions'] += row['outcomeEligible']
        totals['censoredDecisions'] += not row['outcomeEligible']
        totals['decisionsAfterOutcome'] += row['postOutcome']
        totals['decisionsWithAssessments'] += bool(d['candidates'])
        for action in d['selectedActions']:
            add('selectedActionKinds', action['kind'])
            totals['selectedActionsWithoutAssessment'] += not any(matches_selected(action, c) for c in d['candidates'])
        for candidate in d['candidates']:
            add('assessments', candidate['assessment'])
            detail = candidate.get('assessmentDetail')
            if detail is not None:
                add('assessmentDetails', detail['reason'])
                if 'handler' in detail:
                    add('cardAiHandlers', detail['handler'])
                if 'handlerAssessment' in detail:
                    add('handlerAssessments', detail['handlerAssessment'])
            if candidate['assessment'] not in ('WillPlay', 'LAND_OPTION'):
                source = candidate.get('sourceName', 'IDENTITY_MASKED')
                add('rejectedCards', source)
                add('rejectedActionKinds', candidate['kind'])
                add('rejectedCardKinds', source + ' [' + candidate['kind'] + ']')
        if 'search' in d:
            totals['searchRecords'] += 1
            totals['searchDecidedByRollouts'] += d['search'].get('decidedByRollouts', False)
            add('searchReasons', d['search']['reason'])
        for action in row['actionExecutions']:
            add('actionExecutionStatuses', action['status'])
            add('actionEngineEventCoverage', action['engineEventCoverage'])
            for event in action['engineEvents']:
                add('linkedEngineEvents', event['type'])
                if event['type'] != 'STACK_ADDED':
                    continue
                if declared and event.get('finalizedTargets') is not None and any(group['usesTargeting'] for group in event['finalizedTargets']['abilities']):
                    declared_targeted_actions += 1
                    targeted_actions_with_pool_record += action['actionId'] in observed_candidates
                    targeted_actions_with_recommendation += action['actionId'] in recommended_candidates
                for field, metric in (('finalizedTargets', 'finalizedTargetSnapshots'),
                                      ('finalizedModes', 'finalizedModeSnapshots'),
                                      ('finalizedX', 'finalizedXSnapshots'),
                                      ('finalizedPayments', 'finalizedPaymentSnapshots')):
                    add(metric, 'OBSERVED' if event.get(field) is not None else 'UNAVAILABLE')
                targets = event.get('finalizedTargets')
                if targets is not None:
                    for group in targets['abilities']:
                        for target in group['targets']:
                            add('finalizedTargetTypes', target['type'])
                modes = event.get('finalizedModes')
                if modes is not None and modes['modal']:
                    add('selectedModeCounts', len(modes['modes']))
                x = event.get('finalizedX')
                if x is not None and x['announcedValue'] is not None:
                    add('announcedXValues', x['announcedValue'])
                payment = event.get('finalizedPayments')
                if payment is not None:
                    for cost in payment['optionalCosts']:
                        add('optionalCostSelections', cost)
                    if payment['alternativeCost'] is not None:
                        add('alternativeCostSelections', payment['alternativeCost'])
                    for group in payment['cardPayments']:
                        add('cardPaymentGroups', group['type'])
                    totals['unrecognizedCardPaymentGroups'] += payment['unrecognizedCardPaymentGroups']
        if spool:
            spool.group(row)
        else:
            key = (row['deckName'], row['deckSha256'], row['pilotMode'])
            group = grouped.setdefault(key, dict(deck=key[0], deckSha256=key[1], pilotMode=key[2],
                                               decisions=0, passes=0, withAssessments=0, censored=0))
            group['decisions'] += 1
            group['passes'] += d['selection'] == 'PASS_PRIORITY'
            group['withAssessments'] += bool(d['candidates'])
            group['censored'] += not row['outcomeEligible']
    summary = dict(runs=len(runs), games=sum(len(r['games']) for r in runs), decisions=len(rows),
                   sourceRecords=sum(r['records'] for r in runs), trainingReady=False)
    summary['executionStatuses'] = count('executionStatuses')
    for name in common:
        summary[name] = count(name)
    for name in ('outcomeEligibleDecisions', 'censoredDecisions', 'decisionsAfterOutcome',
                 'decisionsWithAssessments', 'selectedActionsWithoutAssessment'):
        summary[name] = totals[name]
    for name in other[1:]:
        summary[name] = count(name)
    for name in ('unrecognizedCardPaymentGroups', 'searchRecords', 'searchDecidedByRollouts'):
        summary[name] = totals[name]
    run_summaries = (dict(runId=r['metadata']['runId'], decks=[p.get('deckName') for p in r['metadata']['participants']],
                          pilotMode=r['metadata']['pilotMode'], seed=r['metadata'].get('seed'), status=r['finished']['status'],
                          decisions=sum(len(g['decisions']) for g in r['games'].values())) for r in runs)
    summary['runsSummary'] = spool.sequence('run_summaries', run_summaries) if spool else list(run_summaries)
    summary['groups'] = spool.sequence('group_summaries', spool.groups()) if spool else [grouped[key] for key in sorted(grouped)]
    if declared_choices:
        summary['subordinateChoiceRecords'] = choice_records
        summary['declaredSelectedTargetedActions'] = declared_targeted_actions
        summary['selectedTargetedActionsWithPoolRecord'] = targeted_actions_with_pool_record
        summary['selectedTargetedActionsWithTargetRecommendation'] = targeted_actions_with_recommendation
        summary['subordinateChoicesOmittedByBudget'] = budget_omissions
        summary['subordinateChoicesOmittedDuringCapture'] = interrupted_captures
        for name in choice_keys:
            summary[name] = count(name)
    summary['limitations'] = [
        'Main-phase play selection with an empty stack only; no mulligan, combat, reactive-priority or subordinate-choice coverage.',
        'Candidate assessments are partial and may repeat an ability; counts are assessment observations, not unique rejected legal actions.',
        'Rejection reasons mix strategic preferences, timing and affordability; they do not establish rules illegality.',
        'Player observations are partial. Missing state includes remembered reveals, detailed mana restrictions and delayed effects.',
        'Win/loss labels describe eventual game outcomes, not whether individual choices were correct. trainingReady remains false.',
        'Search summaries omit per-candidate values and the heuristic counterfactual; actual choice-change rates cannot be inferred.',
        'Action execution joins are available when declared by new records; legacy executions remain UNAVAILABLE. Execution success does not establish resolution or strategic value.',
        'Linked engine events retain resolution, counter, removal, bulk-clear and land-play observations where recorded. Missing events do not establish failure or resolution; downstream effects are not attributed.',
        'Finalized target snapshots describe committed stack targets, not alternatives or why they were selected. They omit identities beyond IDs, later retargeting, and choices made during resolution; legacy or unavailable snapshots are not empty target lists.',
        'Payment snapshots contain recorded engine facts, not every cost decision. lastLifePayment is not a total and must not be summed with phyrexianLifePaid. LKI/current card lists can refer to the same paid card. Counter-removal choices and complete restriction/payment alternatives remain outside this coverage.',
        'Legacy land records and some direct combo selections can lack assessment rows.',
        'UNKNOWN engine/pilot metadata in legacy inputs is retained explicitly rather than guessed.',
        'Handler labels are preserved from Forge: MissingPhaseRestrictions means a phase check declined the play, not necessarily missing card configuration.',
    ]
    if declared_choices:
        summary['limitations'][0] = 'Main-phase play selection with an empty stack only; additional target-planning pool observations are restricted to these candidate evaluations. No mulligan, combat or reactive-priority decision coverage.'
        summary['limitations'].append('Subordinate target records cover selected heuristic pools during candidate evaluation, not every target chooser or all legal target sets. A candidate action link does not establish execution or a committed target; mode, X, payment and delegated/resolution choices remain outside this coverage.')
    return summary


def counter_json_chunks(counter):
    items = counter.sorted_items() if isinstance(counter, _spool.DiskCounter) else sorted(counter.items())
    yield '{'
    for index, (key, value) in enumerate(items):
        if index:
            yield ', '
        text = 'null' if key is None else 'true' if key is True else 'false' if key is False else str(key)
        yield json.dumps(text) + ': ' + json.dumps(value)
    yield '}'


def report_chunks(summary, runs):
    def cell(value):
        return str(value).replace('|', '\\|').replace('\n', ' ')

    def lines(*values):
        for value in values:
            yield value + '\n'

    def counter_line(prefix, name, suffix='`.'):
        yield prefix + '`'
        yield from counter_json_chunks(summary[name])
        yield suffix + '\n'

    yield from lines('# Decision coverage report', '',
                     f"Validated **{summary['decisions']} decisions** from **{summary['games']} games** in **{summary['runs']} runs**.")
    yield from counter_line('Execution results: ', 'executionStatuses',
                            f"`. Outcome-eligible decisions: {summary['outcomeEligibleDecisions']}; censored: {summary['censoredDecisions']}.")
    yield from lines('', '**This is a coverage and data-integrity report, not a rating of deck strength or strategic correctness.**', '',
                     '| Deck | Pilot | Decisions | Passes | With assessments | Censored |',
                     '| --- | --- | ---: | ---: | ---: | ---: |')
    for group in summary['groups']:
        yield from lines('| ' + ' | '.join(cell(group[k]) for k in ('deck', 'pilotMode', 'decisions', 'passes', 'withAssessments', 'censored')) + ' |')
    yield from lines('', '## Assessment observations', '', '| Assessment | Count |', '| --- | ---: |')
    for reason, count in summary['assessments'].items():
        yield from lines(f'| {cell(reason)} | {count} |')
    yield from lines('', 'Counts can include repeated evaluation of the same ability. Passing can reflect a strategic choice or an unapproved action, not necessarily absence of legal plays.', '',
                     '## Rejection details', '')
    for prefix, name in (('Where available: ', 'assessmentDetails'), ('Card AI handlers: ', 'cardAiHandlers'),
                         ('Handler assessments: ', 'handlerAssessments')):
        yield from counter_line(prefix, name)
    yield from lines('Legacy inputs without detail remain unspecified. LAND_OPTION rows are shortlist entries, not rejections.', '',
                     '## Coverage gaps', '')
    for prefix, name in (
        ('Decision statuses: ', 'decisionStatuses'), ('Selected action kinds: ', 'selectedActionKinds'),
        ('Phases: ', 'phases'), ('Selected action execution results: ', 'actionExecutionStatuses'),
        ('Linked engine events: ', 'linkedEngineEvents'), ('Engine-event coverage: ', 'actionEngineEventCoverage'),
        ('Finalized target snapshots: ', 'finalizedTargetSnapshots'),
        ('Finalized target references (including sub-abilities): ', 'finalizedTargetTypes'),
        ('Finalized mode snapshots: ', 'finalizedModeSnapshots'), ('Selected mode counts per modal action: ', 'selectedModeCounts'),
        ('Finalized X snapshots: ', 'finalizedXSnapshots'), ('Announced X values: ', 'announcedXValues'),
        ('Payment snapshots (recorded engine facts): ', 'finalizedPaymentSnapshots'),
        ('Optional cost selections: ', 'optionalCostSelections'), ('Alternative cost selections: ', 'alternativeCostSelections'),
        ('Card payment groups (LKI/current lists are separate, not unique paid-card counts): ', 'cardPaymentGroups')):
        yield from counter_line(prefix, name)
    yield from lines(f"Unrecognized card-payment groups omitted: {summary['unrecognizedCardPaymentGroups']}.",
                     f"{summary['decisionsWithAssessments']} decisions have assessment rows. {summary['selectedActionsWithoutAssessment']} selected actions lack a matching assessment row.",
                     f"{summary['decisionsAfterOutcome']} in-flight decisions completed after an outcome was announced; these are retained in order and censored.",
                     f"{summary['searchRecords']} decisions include search summaries; {summary['searchDecidedByRollouts']} report that rollouts decided the choice.", '')
    yield from counter_line('Search reasons: ', 'searchReasons')
    rejected = dict(itertools.islice(summary['rejectedCardKinds'].items(), 15))
    yield from lines('', 'Frequently rejected card assessments: `' + json.dumps(rejected, ensure_ascii=False) + '`.', '')
    for text in summary['limitations']:
        yield from lines('- ' + text)
    yield from lines('', '## Runs', '', '| Decks | Pilot | Seed | Result | Decisions |', '| --- | --- | --- | --- | ---: |')
    for run in summary['runsSummary']:
        yield from lines('| ' + ' | '.join(cell(' vs. '.join(run['decks']) if k == 'decks' else run[k]) for k in ('decks', 'pilotMode', 'seed', 'status', 'decisions')) + ' |')
    if 'subordinateChoiceRecords' in summary:
        yield from lines('', '## Subordinate target-pool selections', '',
                         f"Recorded {summary['subordinateChoiceRecords']} observed selections during candidate evaluation.")
        for prefix, name in (('Declaration coverage: ', 'subordinateChoiceCoverage'),
                             ('Selection statuses: ', 'subordinateChoiceStatuses'),
                             ('Observed boundaries: ', 'subordinateChoiceBoundaries'),
                             ('Observed planning purposes: ', 'subordinateChoicePurposes'),
                             ('Observed pool sizes: ', 'targetPoolSizes'),
                             ('Selected-candidate links: ', 'targetChoiceCandidateLinks'),
                             ('Assessment links: ', 'targetChoiceAssessmentLinks'),
                             ('Capture-budget declarations: ', 'targetChoiceCaptureBudgetCoverage'),
                             ('Observed pool capture: ', 'targetPoolCaptureCompleteness'),
                             ('Prior-target capture: ', 'priorTargetsCaptureCompleteness')):
            yield from counter_line(prefix, name)
        yield from lines('These are heuristic pool observations, not complete legal target sets, committed execution choices or per-choice rewards.')
        yield from lines('Opponent-preference records choose whose board or life total to consider; the preferred opponent need not be the spell target.')
        yield from lines(f"Among declaring decisions with an observed committed target snapshot, {summary['selectedTargetedActionsWithPoolRecord']} of {summary['declaredSelectedTargetedActions']} selected targeted actions have an exact candidate link to at least one pool record. This measures presence, not complete choice coverage.")
        yield from lines(f"Of those targeted actions, {summary['selectedTargetedActionsWithTargetRecommendation']} have a declared target-recommendation record; opponent preference alone does not count as that recommendation.")
        yield from lines(f"Declared omissions: {summary['subordinateChoicesOmittedByBudget']} by capture budget and {summary['subordinateChoicesOmittedDuringCapture']} interrupted captures. Missing declarations remain unavailable, not proof of zero omissions.")
    yield from lines('', '## Reproducibility', '', 'The companion manifest contains exact deck snapshots, deck hashes, input hashes and run metadata. The following summary is readable without the input logs.', '',
                     '| Run | Seed | Pilot | Engine revision | Dirty | Source SHA256 |', '| --- | --- | --- | --- | --- | --- |')
    for run in runs:
        metadata = run['metadata']
        yield from lines('| ' + ' | '.join(cell(metadata.get(k)) for k in ('runId', 'seed', 'pilotMode', 'engineRevision', 'engineDirty', 'sourceSha256')) + ' |')


def report_markdown(summary, runs):
    return ''.join(report_chunks(summary, runs))


EXPORT_NAMES = ('dataset.jsonl', 'examples.jsonl', 'manifest.json', 'coverage.json', 'coverage.md', 'validation.json')
COMPACT_KEYS = ('runs', 'games', 'decisions', 'executionStatuses', 'outcomeEligibleDecisions', 'censoredDecisions')


def write_export(out, spool, summary, examples):
    def json_file(name, value):
        with (out / name).open('w', encoding='utf-8') as sink:
            # json.dump uses the streaming encoder, including our disk-backed list/dict views.
            json.dump(value, sink, indent=2, ensure_ascii=False)
            sink.write('\n')

    with (out / 'dataset.jsonl').open('w', encoding='utf-8') as dataset:
        for row in spool.rows:
            dataset.write(json.dumps(row, ensure_ascii=False) + '\n')
    sample, selected_indices, seen = [], set(), set()
    if examples:
        for index, row in enumerate(spool.rows):
            key = (row['pilotMode'], row['decision']['selection'], row['outcomeEligible'], bool(row['decision'].get('search')),
                   tuple(a['status'] for a in row['actionExecutions']))
            if row['decision'].get('subordinateChoiceSchemaVersion') == 1:
                key += (tuple(sorted({(choice.get('purpose', 'UNAVAILABLE'), choice['status'],
                                       choice['candidateActionId'] is not None,
                                       'TRUNCATED' if choice.get('optionsTruncated') is True else 'FULL' if choice.get('optionsTruncated') is False else 'UNAVAILABLE')
                                      for choice in row['decision']['subordinateChoices']})),)
            if key not in seen:
                seen.add(key)
                sample.append(row)
                selected_indices.add(index)
                if len(sample) == examples:
                    break
        if len(sample) < examples:
            for index, row in enumerate(spool.rows):
                if index not in selected_indices:
                    sample.append(row)
                    if len(sample) == examples:
                        break
    with (out / 'examples.jsonl').open('w', encoding='utf-8') as sink:
        for row in sample:
            sink.write(json.dumps(row, ensure_ascii=False) + '\n')

    def contexts():
        for run in spool.runs:
            for number in ordered_games(run['games']):
                game = run['games'][number]
                yield dict(runId=run['metadata']['runId'], game=number, gameStarted=game['start'],
                           executionStatus=game['execution']['status'], recordedOutcome=game['outcome'],
                           administrativeOutcome=game['execution']['status'] != 'NORMAL')
    inputs = spool.sequence('manifest_inputs', (run['metadata'] for run in spool.runs))
    games = spool.sequence('manifest_games', contexts())
    json_file('manifest.json', dict(datasetSchemaVersion=1, inputs=inputs, games=games))
    json_file('coverage.json', summary)
    with (out / 'coverage.md').open('w', encoding='utf-8') as sink:
        for chunk in report_chunks(summary, spool.runs):
            sink.write(chunk)
    json_file('validation.json', dict(valid=True, inputs=len(spool.runs), decisions=len(spool.rows),
              checks=['run/game boundaries', 'event order', 'schemas and identities', 'engine-event references',
                      'visibility constraints', 'status/outcome agreement', 'timeout/error censoring']))


def publish_export(out, spool, summary, examples):
    out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.forge-export-', dir=out.parent) as directory:
        staged = Path(directory)
        write_export(staged, spool, summary, examples)
        created_directory = not out.exists()
        out.mkdir(exist_ok=True)
        published = []
        try:
            for name in EXPORT_NAMES:
                # Same-filesystem exclusive publication cannot overwrite an existing/racing file.
                os.link(staged / name, out / name)
                published.append(name)
        except BaseException:
            for name in published:
                target = out / name
                if target.exists() and os.path.samefile(staged / name, target):
                    target.unlink()
            if created_directory and not any(out.iterdir()):
                out.rmdir()
            raise


def export(paths, output_dir=None, examples=12, *, compact_summary=False):
    paths = [Path(p) for p in paths]
    require(len({p.resolve() for p in paths}) == len(paths), 'same input supplied twice')
    with Spool() as spool:
        for slot, path in enumerate(paths):
            run = load_run(path, games=spool.games(slot))
            run_id = run['metadata']['runId']
            require(run_id not in spool.run_ids, 'duplicate runId: choose either full trace or decision stream, not both')
            spool.run_ids.add(run_id)
            spool.add_run(run)
        for run in spool.runs:
            for row in dataset_rows(run):
                spool.rows.append(row)
        summary = make_coverage(spool.runs, spool.rows, spool=spool)
        if output_dir is not None:
            out = Path(output_dir)
            require(not any((out / name).exists() for name in EXPORT_NAMES), 'output files already exist; choose a fresh output directory')
            require(not any((out / name).resolve() in {p.resolve() for p in paths} for name in EXPORT_NAMES), 'output would overwrite an input')
            publish_export(out, spool, summary, examples)
        # The CLI needs only fixed-size counts. Library users may request the full aggregate report.
        result = {key: summary[key] for key in COMPACT_KEYS} if compact_summary else summary
        return _spool.materialize(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('inputs', nargs='+', type=Path)
    parser.add_argument('--output-dir', type=Path)
    parser.add_argument('--validate-only', action='store_true')
    parser.add_argument('--examples', type=int, default=12)
    args = parser.parse_args()
    if args.validate_only == bool(args.output_dir):
        parser.error('choose exactly one of --output-dir or --validate-only')
    if args.examples < 1:
        parser.error('--examples must be positive')
    try:
        summary = export(args.inputs, args.output_dir, args.examples, compact_summary=True)
    except (InvalidData, OSError, sqlite3.Error) as exc:
        print(f'Validation/export failed: {exc}', file=sys.stderr)
        return 1
    print(json.dumps({k: summary[k] for k in ('runs', 'games', 'decisions', 'executionStatuses', 'outcomeEligibleDecisions', 'censoredDecisions')}, indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main())
