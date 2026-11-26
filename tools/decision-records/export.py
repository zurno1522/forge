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

_choice_spec = importlib.util.spec_from_file_location('forge_choice_events', Path(__file__).with_name('choice_events.py'))
_choices = importlib.util.module_from_spec(_choice_spec)
_choice_spec.loader.exec_module(_choices)

_audit_spec = importlib.util.spec_from_file_location('forge_coverage_audit', Path(__file__).with_name('coverage_audit.py'))
_audit = importlib.util.module_from_spec(_audit_spec)
_audit_spec.loader.exec_module(_audit)

_account_spec = importlib.util.spec_from_file_location('forge_observation_accounting', Path(__file__).with_name('observation_accounting.py'))
_accounting = importlib.util.module_from_spec(_account_spec)
_account_spec.loader.exec_module(_accounting)

_capture_spec = importlib.util.spec_from_file_location('forge_capture_accounting', Path(__file__).with_name('capture_accounting.py'))
_captures = importlib.util.module_from_spec(_capture_spec)
_capture_spec.loader.exec_module(_captures)


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
                            'DAMAGE_PLANESWALKER_POOL', 'MASKED_TARGET_POOL', 'DRAW_PLAYER_POOL', 'DAMAGE_PLAYER_POOL', 'ATTACH_CARD_POOL', 'ATTACH_PLAYER_POOL'}


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


def validate_search_target_applications(d, masked_ids, known_players=None):
    version = d.get('searchTargetApplicationSchemaVersion')
    if version is None:
        require(not any(k in d for k in ('searchTargetApplications', 'searchTargetApplicationOmissions')), 'undeclared search target applications')
        require(d.get('targetChoiceProvenanceSchemaVersion') != 2, 'search provenance without application declaration')
        return
    require(integer(version) and version == 1 and d.get('targetChoiceProvenanceSchemaVersion') == 2, 'invalid search target application declaration')
    records, omissions = d.get('searchTargetApplications'), d.get('searchTargetApplicationOmissions')
    require(isinstance(records, list) and len(records) <= 128, 'invalid search application budget')
    require(isinstance(omissions, dict) and set(omissions) == {'budget', 'interrupted'}
            and all(integer(v) and v >= 0 for v in omissions.values()), 'invalid search application omissions')
    actions = {a['actionId']: a for a in d['selectedActions']}
    seen = {c['choiceId'] for c in d.get('subordinateChoices', [])}
    operations = {'HEURISTIC_REVALIDATION', 'RESTORE_APPROVED_TARGET', 'ROLLOUT_TARGET_OVERRIDE', 'DISCOVERED_TARGET', 'GUARD_REAIM'}
    fields = {'applicationSchemaVersion', 'choiceId', 'choiceType', 'stage', 'chooserPlayerId', 'candidateAbilityId', 'sourceId',
              'operation', 'optionCoverage', 'requestedTarget', 'beforeTargets', 'afterTargets', 'changedAbilities', 'status', 'candidateActionId'}
    for c in records:
        require(isinstance(c, dict) and fields <= set(c) <= fields | {'entryLocation', 'inputContext'}, 'invalid search application fields')
        require(integer(c['applicationSchemaVersion']) and c['applicationSchemaVersion'] == 1
                and c['choiceType'] == c['stage'] == 'SEARCH_TARGET_APPLICATION', 'invalid search application type/stage')
        require(isinstance(c['choiceId'], str) and c['choiceId'] and c['choiceId'] not in seen, 'duplicate search application id')
        seen.add(c['choiceId'])
        require(all(integer(c[k]) for k in ('chooserPlayerId', 'candidateAbilityId', 'sourceId'))
                and c['chooserPlayerId'] == d['playerId'], 'invalid search application actor/identity')
        action_id = c['candidateActionId']
        require(action_id is None or isinstance(action_id, str) and action_id in actions, 'search application lacks selected action')
        if action_id is not None:
            require(actions[action_id].get('abilityId') == c['candidateAbilityId'] and actions[action_id]['sourceId'] == c['sourceId'], 'search application selected candidate mismatch')
        changed = c['changedAbilities']
        require(isinstance(changed, list) and len(changed) <= 128 and all(integer(v) and v >= 0 for v in changed)
                and changed == sorted(set(changed)), 'invalid changed search target abilities')
        hidden = c['operation'] == 'MASKED'
        timing = 'UNESTABLISHED' if hidden else 'BEFORE_OPERATION' if c['operation'] == 'HEURISTIC_REVALIDATION' else 'AFTER_SELECTION_BEFORE_APPLICATION'
        basis = 'UNESTABLISHED' if hidden else 'PLANNING_WORKING_STATE'
        context = c.get('inputContext')
        if 'inputContext' in c:
            require('entryLocation' in c, 'search input lacks its own entry')
            _choices.validate_input_context(context, c['chooserPlayerId'], known_players)
            require((context['timing'], context['stateBasis']) == (timing, basis), 'search input boundary mismatch')
            if hidden:
                require(context['status'] == 'UNAVAILABLE' and {'component': 'CONTEXT', 'reason': 'HIDDEN'} in context['omissions'], 'input exposes masked search operation')
            else:
                require({'component': 'CONTEXT', 'reason': 'HIDDEN'} not in context['omissions'], 'unmasked search has hidden input')
            if context['policy'] is not None:
                require(context['policy'].get('prompt') is None, 'unaudited search prompt')
                if context['schemaVersion'] >= 4:
                    require(context['diagnostics']['optionBindings']['coverage'] == 'UNSUPPORTED', 'search request is not an offered pool')
        if 'entryLocation' in c:
            entry = c['entryLocation']
            _choices.validate_entry_location(entry, known_players, context)
            if hidden:
                require(entry['status'] == 'UNAVAILABLE' and entry['reason'] == 'HIDDEN', 'entry exposes masked search operation')
            else:
                require(entry['reason'] != 'HIDDEN', 'unmasked search has hidden entry')
                if entry['status'] == 'CAPTURED':
                    require((entry['timing'], entry['stateBasis']) == (timing, basis), 'search entry boundary mismatch')
        if c['operation'] == 'MASKED':
            require(c['optionCoverage'] == 'MASKED' and c['requestedTarget'] is None and c['beforeTargets'] is None
                    and c['afterTargets'] is None and not changed and c['status'] in ('MASKED', 'INCOMPLETE'), 'unmasked private search operation')
            continue
        require(c['sourceId'] not in masked_ids and c['operation'] in operations
                and c['optionCoverage'] == 'OBSERVED_OPERATION_NOT_ALTERNATIVES', 'invalid search operation/coverage')
        require(c['status'] in ('ACCEPTED', 'DECLINED', 'ERROR', 'INCOMPLETE', 'CAPTURE_FAILED', 'CAPTURE_INTERRUPTED'), 'invalid search application status')
        if c['requestedTarget'] is not None: validate_choice_reference(c['requestedTarget'])
        require(c['operation'] != 'HEURISTIC_REVALIDATION' or c['requestedTarget'] is None, 'heuristic revalidation claims a requested target')
        _choices.target_plan(c['beforeTargets'], c['candidateAbilityId'])
        if c['afterTargets'] is None:
            require(not changed and (c['status'] == 'CAPTURE_FAILED' or c['status'] == 'CAPTURE_INTERRUPTED' and omissions['interrupted'] > 0
                    or c['status'] == 'INCOMPLETE' and d['status'] != 'COMPLETED'), 'completed search application lacks result')
            continue
        require(c['status'] in ('ACCEPTED', 'DECLINED', 'ERROR'), 'unavailable search application has a result')
        _choices.target_plan(c['afterTargets'], c['candidateAbilityId'])
        groups = c['afterTargets']['abilities']
        require(all(i < len(groups) for i in changed), 'changed search target ability outside result')
        if c['status'] == 'ACCEPTED' and c['operation'] != 'HEURISTIC_REVALIDATION':
            require(c['requestedTarget'] is not None and len(changed) == 1
                    and groups[changed[0]]['targets'] == [c['requestedTarget']] and not groups[changed[0]]['targetsTruncated'], 'accepted target application differs from its request')


def validate_subordinate_choices(d, masked_ids, known_players=None):
    _captures.validate(d)
    if 'targetChoiceProvenanceSchemaVersion' in d:
        require(integer(d['targetChoiceProvenanceSchemaVersion']) and d['targetChoiceProvenanceSchemaVersion'] in (1, 2)
                and d.get('subordinateChoiceSchemaVersion') == 1, 'invalid target provenance declaration')
    validate_search_target_applications(d, masked_ids, known_players)
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
        optional = {'optionsTruncated', 'priorTargetsTruncated', 'purpose', 'poolSnapshotTiming', 'entryLocation', 'inputContext'}
        require(isinstance(choice, dict) and fields <= set(choice) <= fields | optional, 'invalid subordinate choice fields')
        require('poolSnapshotTiming' not in choice or choice['poolSnapshotTiming'] == 'AT_TARGET_ADDITION', 'invalid pool snapshot timing')
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
        if 'inputContext' in choice:
            require('entryLocation' in choice and 'optionsTruncated' in choice, 'nested input lacks its own boundary/prefix declaration')
            context = choice['inputContext']
            _choices.validate_input_context(context, choice['chooserPlayerId'], known_players)
            timing = 'AFTER_SELECTION_BEFORE_APPLICATION' if choice.get('poolSnapshotTiming') == 'AT_TARGET_ADDITION' else 'BEFORE_SELECTOR'
            require(context['timing'] == timing and context['stateBasis'] == 'PLANNING_WORKING_STATE', 'nested target input boundary mismatch')
            if context['policy'] is not None:
                require(context['policy'].get('prompt') is None, 'unaudited nested target prompt')
            if choice['boundary'] == 'MASKED_TARGET_POOL':
                require(context['status'] == 'UNAVAILABLE' and {'component': 'CONTEXT', 'reason': 'HIDDEN'} in context['omissions'], 'input exposes masked target source')
        if 'entryLocation' in choice:
            projected = dict(choice, boundary='MASKED_CHOICE' if choice['boundary'] == 'MASKED_TARGET_POOL' else choice['boundary'],
                             options=dict(coverage=choice['optionCoverage'], items=choice['options'],
                                          truncated=choice.get('optionsTruncated', False),
                                          poolSnapshotTiming=choice.get('poolSnapshotTiming', 'BEFORE_CHOOSER')))
            _choices.entry_location(projected, known_players)
            if 'inputContext' in choice: _choices.option_links(projected)
            if choice['entryLocation']['status'] == 'CAPTURED':
                require(choice['entryLocation']['stateBasis'] == 'PLANNING_WORKING_STATE', 'nested target entry claims rules state')
        expected_type = {'DRAW_PLAYER_POOL': 'PLAYER', 'DAMAGE_PLAYER_POOL': 'PLAYER',
                         'ATTACH_PLAYER_POOL': 'PLAYER', 'ATTACH_CARD_POOL': 'CARD'}.get(choice['boundary'])
        if expected_type is not None:
            require(all(ref['type'] == expected_type for ref in choice['options']), 'target pool boundary/reference mismatch')
        selected, selected_index = choice['selected'], choice['selectedOptionIndex']
        if selected is not None:
            validate_choice_reference(selected)
            require(expected_type is None or selected['type'] == expected_type, 'target selection boundary/reference mismatch')
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
    if 'inputContext' in d:
        _choices.validate_input_context(d['inputContext'], d['playerId'], game['players'])
        _choices.input_boundary(d['inputContext'], d['decisionType'])
        _choices.input_envelope(d['inputContext'], record)
        require(d['inputContext']['stateBasis'] == 'RULES_STATE', 'main-phase baseline is not rules state')
        policy = d['inputContext']['policy']
        require(policy is None or policy['turnPlayer'] == policy['chooser'], 'main-phase context outside chooser turn')
        if policy is not None and d['inputContext']['schemaVersion'] >= 2:
            require(not policy['stack']['entries'] and not policy['stack']['isResolving'] and not policy['stack']['truncated'],
                    'main-phase baseline contains a pending stack')
        if policy is not None and d['inputContext']['schemaVersion'] >= 3:
            require(policy['prompt'] is None and d['inputContext']['coverage']['prompt'] == 'UNSUPPORTED',
                    'main-phase baseline claims a selector request')
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
        require('evaluatedX' not in action or action['evaluatedX'] is None or integer(action['evaluatedX']), 'invalid evaluated X value')
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
        require('assessmentId' not in candidate or isinstance(candidate['assessmentId'], str) and candidate['assessmentId'], 'invalid candidate assessment id')
    assessment_ids = [c['assessmentId'] for c in candidates if 'assessmentId' in c]
    require(len(set(assessment_ids)) == len(assessment_ids), 'duplicate candidate assessment id')
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
    validate_subordinate_choices(d, masked_ids, game['players'])
    return d


ACTION_EVENT_TYPES = ('STACK_ADDED', 'STACK_RESOLVED', 'STACK_REMOVED', 'STACK_COUNTERED', 'STACK_CLEARED', 'LAND_PLAYED')
ACTION_EVENT_FIELDS = ('type', 'game', 'eventIndex', 'turn', 'phase', 'decisionId', 'actionId', 'stackId', 'abilityId', 'fizzled', 'finalizedTargets', 'finalizedModes', 'finalizedX', 'finalizedPayments', 'targetChoiceProvenance')


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


def validate_target_provenance(record, action):
    snapshot = record['targetChoiceProvenance']
    if snapshot is None:
        return
    require(action.get('targetChoiceProvenanceSchemaVersion') in (1, 2), 'undeclared target provenance')
    require(isinstance(snapshot, dict) and set(snapshot) == {'schemaVersion', 'coverage', 'abilities', 'abilitiesTruncated'}, 'invalid target provenance fields')
    version = snapshot['schemaVersion']
    require(integer(version) and version == action['targetChoiceProvenanceSchemaVersion']
            and snapshot['coverage'] == ('EXPLICIT_TARGET_ADDITIONS_V1' if version == 1 else 'EXPLICIT_TARGET_OPERATIONS_V2'), 'invalid target provenance schema/coverage')
    targets = record.get('finalizedTargets')
    require(targets is not None, 'target provenance lacks committed targets')
    groups = snapshot['abilities']
    require(isinstance(groups, list) and len(groups) == min(128, len(targets['abilities']))
            and isinstance(snapshot['abilitiesTruncated'], bool)
            and snapshot['abilitiesTruncated'] == (len(groups) < len(targets['abilities'])), 'target provenance ability coverage mismatch')
    choices = action['targetChoiceOrigins']
    for ability_index, (group, committed) in enumerate(zip(groups, targets['abilities'])):
        require(isinstance(group, dict) and set(group) == {'abilityId', 'targetChoices', 'targetsTruncated'}, 'invalid target provenance ability fields')
        require(integer(group['abilityId']) and group['abilityId'] == committed['abilityId'], 'target provenance ability mismatch')
        origins = group['targetChoices']
        require(isinstance(origins, list) and len(origins) == min(256, len(committed['targets']))
                and isinstance(group['targetsTruncated'], bool)
                and group['targetsTruncated'] == (len(origins) < len(committed['targets'])), 'target provenance slot coverage mismatch')
        for target_index, (origin, target) in enumerate(zip(origins, committed['targets'])):
            if origin is None:
                continue
            require(isinstance(origin, dict), 'invalid target origin')
            search_origin = origin.get('stage') == 'SEARCH_TARGET_APPLICATION'
            fields = {'choiceId', 'chooserPlayerId', 'stage'} | ({'abilityIndex', 'targetIndex'} if search_origin else set())
            require(set(origin) == fields and (not search_origin or version == 2), 'invalid target origin fields')
            choice_id = origin['choiceId']
            require(isinstance(choice_id, str) and choice_id in choices, 'target origin has no exact selected-candidate choice')
            choice = choices[choice_id]
            require(integer(origin['chooserPlayerId']) and origin['chooserPlayerId'] == choice['chooserPlayerId']
                    and origin['stage'] == choice['stage'], 'target origin chooser/stage mismatch')
            if search_origin:
                require(choice.get('choiceType') == 'SEARCH_TARGET_APPLICATION' and choice['status'] == 'ACCEPTED'
                        and integer(origin['abilityIndex']) and origin['abilityIndex'] == ability_index
                        and integer(origin['targetIndex']) and origin['targetIndex'] == target_index
                        and ability_index in choice['changedAbilities'], 'invalid search target origin operation/slot')
                applied = choice['afterTargets']['abilities']
                require(ability_index < len(applied) and target_index < len(applied[ability_index]['targets'])
                        and applied[ability_index]['targets'][target_index] == {k: v for k, v in target.items() if k != 'dividedAmount'}, 'search target origin/committed identity mismatch')
                continue
            require(choice.get('choiceType') != 'SEARCH_TARGET_APPLICATION', 'search operation used as evaluation origin')
            require(choice.get('purpose') != 'OPPONENT_PREFERENCE' and choice['boundary'] != 'DAMAGE_OPPONENT_POOL'
                    and choice['selected'] is not None, 'target origin is not an observed target recommendation')
            require(choice['selected'] == {k: v for k, v in target.items() if k != 'dividedAmount'}, 'target origin/committed identity mismatch')


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
    if 'targetChoiceProvenance' in record:
        require(kind == 'STACK_ADDED', 'target provenance outside stack addition')
        validate_target_provenance(record, action)
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
    accounting_starts = 0
    accounting_terminals = 0
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
                    require(record.get('choiceEventSchemaVersion') is None or integer(record['choiceEventSchemaVersion']) and record['choiceEventSchemaVersion'] == 1, 'unsupported standalone choice capability')
                    require(record.get('observationAccountingSchemaVersion') is None or integer(record['observationAccountingSchemaVersion'])
                            and record['observationAccountingSchemaVersion'] == 1, 'unsupported observation accounting capability')
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
                    game = games.setdefault(number, dict(lastIndex=0, start=None, outcome=None, finish=None, execution=None, decisions=[], decisionIds=set(), players={}, actions={}, choices={}, decisionContexts={}, choiceCandidates=set(), choiceAssessments={}))
                    require(game['execution'] is None, 'scoped event after GAME_EXECUTION_FINISHED')
                    index = record.get('eventIndex')
                    require(integer(index) and index > game['lastIndex'], 'event indices not strictly increasing')
                    if metadata.get('eventIndexMode', 'CONTIGUOUS') == 'CONTIGUOUS':
                        require(index == game['lastIndex'] + 1, 'event index gap')
                    game['lastIndex'] = index
                    if metadata.get('streamKind') == 'AI_DECISIONS':
                        require(kind in ('GAME_STARTED', 'AI_DECISION', 'AI_ACTION_EXECUTION', 'AI_SUBORDINATE_CHOICE', 'GAME_OUTCOME', 'GAME_FINISHED', 'GAME_EXECUTION_FINISHED') + ACTION_EVENT_TYPES, 'unexpected full game event in decision stream')
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
                        game['decisionContexts'][d['decisionId']] = dict(status=d['status'])
                        for candidate in d['candidates']:
                            identity = (d['decisionId'], candidate.get('abilityId'), candidate['sourceId'])
                            game['choiceCandidates'].add(identity)
                            if 'assessmentId' in candidate:
                                game['choiceAssessments'][(d['decisionId'], candidate['assessmentId'])] = list(identity[1:])
                        if d.get('actionExecutionSchemaVersion') == 1:
                            for selected in d['selectedActions']:
                                action_id = selected['actionId']
                                require(action_id not in game['actions'], 'duplicate selected actionId')
                                identity = dict(selected, decisionId=d['decisionId'], playerId=d['playerId'])
                                game['actions'][action_id] = dict(identity=identity, events=[], terminal=None, engineEvents=[], stackIdentity=None,
                                        targetChoiceProvenanceSchemaVersion=d.get('targetChoiceProvenanceSchemaVersion'),
                                        targetChoiceOrigins={c['choiceId']: {k: c[k] for k in ('chooserPlayerId', 'stage', 'selected', 'boundary', 'purpose') if k in c}
                                                             for c in d.get('subordinateChoices', []) if c['candidateActionId'] == action_id}
                                        | {c['choiceId']: {k: c[k] for k in ('choiceType', 'chooserPlayerId', 'stage', 'status', 'changedAbilities', 'afterTargets')}
                                           for c in d.get('searchTargetApplications', []) if c['candidateActionId'] == action_id})
                    elif kind == 'AI_ACTION_EXECUTION':
                        accept_execution(record, game)
                    elif kind == 'AI_SUBORDINATE_CHOICE':
                        require(metadata.get('choiceEventSchemaVersion') == 1, 'undeclared standalone choice event')
                        _choices.accept(record, game)
                        if record['choice']['status'] == 'STARTED': accounting_starts += 1
                        else: accounting_terminals += 1
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
        try:
            for state in game['choices'].values(): _choices.context(state, game)
        except ValueError as error:
            raise InvalidData(f'{path}: game {number}: {error}') from error
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
    try:
        _accounting.validate(metadata, finished, len(games), accounting_starts, accounting_terminals, _choices.BOUNDARIES)
    except ValueError as error:
        raise InvalidData(f'{path}: {error}') from error
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
    capture_accounting = _captures.Aggregate()
    search_capture_accounting = _captures.Aggregate('search')
    audit = _audit.CoverageAudit(
        _spool.DiskMap(spool, 'audit:boundaries') if spool else {},
        _spool.DiskMap(spool, 'audit:turns') if spool else {}, _choices.CONTEXT_TIMING,
        {'CHOICE': _choices.CONTEXT_TIMING_V2,
         'NESTED_TARGET': {key: value for key, value in _choices.CONTEXT_TIMING_V2.items() if key in _choices.POOL_BOUNDARIES},
         'SEARCH_OPERATION': {key: 'BEFORE_OPERATION' if key == 'HEURISTIC_REVALIDATION' else 'AFTER_SELECTION_BEFORE_APPLICATION'
                              for key in ('HEURISTIC_REVALIDATION', 'RESTORE_APPROVED_TARGET', 'ROLLOUT_TARGET_OVERRIDE', 'DISCOVERED_TARGET', 'GUARD_REAIM')}},
        {'CHOICE': _choices.MODE_CONTEXT_TIMING}, {'CHOICE': _choices.MULTI_CONTEXT_TIMING},
        {'CHOICE': _choices.ZONE_OPERATION_CONTEXT_TIMING}, {'CHOICE': _choices.SPELL_CONTEXT_TIMING}, {'CHOICE': _choices.PREPARATION_CONTEXT_TIMING})
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
                   'targetPoolCaptureCompleteness', 'priorTargetsCaptureCompleteness', 'subordinateChoicePurposes',
                   'targetChoiceProvenanceSnapshots', 'committedTargetChoiceOrigins')
    counters = {name: spool.counter('coverage:' + name, name in common) if spool else collections.Counter()
                for name in common + other + choice_keys}
    totals = dict(outcomeEligibleDecisions=0, censoredDecisions=0, decisionsAfterOutcome=0,
                  decisionsWithAssessments=0, selectedActionsWithoutAssessment=0,
                  unrecognizedCardPaymentGroups=0, searchRecords=0, searchDecidedByRollouts=0)
    grouped = {}
    declared_choices = False
    declared_provenance = False
    search_operations, search_statuses = collections.Counter(), collections.Counter()
    search_declared = search_count = search_selected = search_budget = search_interrupted = 0
    choice_records = 0
    declared_targeted_actions = 0
    targeted_actions_with_pool_record = 0
    targeted_actions_with_recommendation = 0
    budget_omissions = 0
    interrupted_captures = 0
    evaluated_x = collections.Counter()
    input_statuses, input_owners, input_timings, input_bases, input_omissions, input_coverage = (collections.Counter() for _ in range(6))
    input_records = 0
    input_profiles = collections.Counter()
    input_bindings = collections.Counter()

    def add_input_context(value, owner):
        nonlocal input_records
        status = 'ABSENT' if value is None else value['status']
        input_statuses[status] += 1
        input_owners[owner + ':' + status] += 1
        if value is None: return
        input_records += 1
        input_profiles[value['profile']] += 1
        if value['schemaVersion'] >= 4:
            input_bindings['contextRecords'] += 1
            links = value['diagnostics']['optionBindings']
            input_bindings['recordCoverage:' + links['coverage']] += 1
            input_bindings['optionEntries'] += len(links['items'])
            input_bindings['publicOptionLinks'] += sum(link['entityRef'] is not None for link in links['items'])
            input_bindings['unresolvedOptionEntries'] += sum(link['entityRef'] is None for link in links['items'])
            for fact in (value['policy'] or {}).get('disclosedFacts', []):
                input_bindings['disclosureFacts'] += 1
                input_bindings['directPublicDisclosureLinks' if fact['currentObjectAssociation'] != 'UNRESOLVED'
                               else 'unresolvedDisclosureFacts'] += 1
        input_timings[value['timing']] += 1
        input_bases[value['stateBasis']] += 1
        for omission in value['omissions']:
            input_omissions[omission['component'] + ':' + omission['reason']] += 1
        for component, coverage in value['coverage'].items():
            input_coverage[component + ':' + coverage] += 1

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
        capture_accounting.observe(d)
        search_capture_accounting.observe(d)
        location = dict(run_id=row['runId'], game=row['game'], turn=d['observation'].get('turn'), player=d['playerId'])
        audit.observe('DECISION', d['decisionType'], d.get('inputContext'), **location, turn_basis='PARENT_BASELINE',
                      parent_omissions={'NESTED_TARGET': d.get('subordinateChoiceOmissions'),
                                        'SEARCH_OPERATION': d.get('searchTargetApplicationOmissions')})
        for choice in d.get('subordinateChoices', []):
            audit.observe('NESTED_TARGET', choice['boundary'], choice.get('inputContext'), **location,
                          turn_basis='PARENT_CONTAINER', entry_location=choice.get('entryLocation'))
            if 'inputContext' in choice: add_input_context(choice['inputContext'], 'NESTED_TARGET')
        for operation in d.get('searchTargetApplications', []):
            audit.observe('SEARCH_OPERATION', operation['operation'], operation.get('inputContext'), **location,
                          turn_basis='PARENT_CONTAINER', entry_location=operation.get('entryLocation'))
            if 'inputContext' in operation: add_input_context(operation['inputContext'], 'SEARCH_OPERATION')
        add_input_context(d.get('inputContext'), 'DECISION')
        declared = d.get('subordinateChoiceSchemaVersion') == 1
        declared_choices = declared_choices or declared
        declared_provenance = declared_provenance or d.get('targetChoiceProvenanceSchemaVersion') in (1, 2)
        if d.get('searchTargetApplicationSchemaVersion') == 1:
            search_declared += 1
            search_budget += d['searchTargetApplicationOmissions']['budget']
            search_interrupted += d['searchTargetApplicationOmissions']['interrupted']
            for application in d['searchTargetApplications']:
                search_count += 1
                search_selected += application['candidateActionId'] is not None
                search_operations[application['operation']] += 1
                search_statuses[application['status']] += 1
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
            if 'evaluatedX' in candidate:
                x = candidate['evaluatedX']
                evaluated_x['UNANNOUNCED' if x is None else 'ZERO' if x == 0 else 'POSITIVE' if x > 0 else 'NEGATIVE'] += 1
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
                if d.get('targetChoiceProvenanceSchemaVersion') in (1, 2):
                    provenance = event.get('targetChoiceProvenance')
                    add('targetChoiceProvenanceSnapshots', 'OBSERVED' if provenance is not None else 'UNAVAILABLE')
                    if provenance is not None:
                        for group in provenance['abilities']:
                            for origin in group['targetChoices']:
                                add('committedTargetChoiceOrigins', 'UNKNOWN' if origin is None else 'EXPLICIT_SEARCH_APPLICATION' if origin['stage'] == 'SEARCH_TARGET_APPLICATION' else 'EXPLICIT_EVALUATION_ADDITION')
                        if targets is not None:
                            omitted = sum(len(group['targets']) for group in targets['abilities']) - sum(len(group['targetChoices']) for group in provenance['abilities'])
                            counters['committedTargetChoiceOrigins'].update({'TRUNCATED': omitted} if omitted else {})
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
            if name in ('targetChoiceProvenanceSnapshots', 'committedTargetChoiceOrigins'):
                continue
            summary[name] = count(name)
    if declared_provenance:
        for name in ('targetChoiceProvenanceSnapshots', 'committedTargetChoiceOrigins'):
            summary[name] = count(name)
    if any(run['metadata'].get('choiceEventSchemaVersion') == 1 for run in runs):
        event_types, stages, statuses = collections.Counter(), collections.Counter(), collections.Counter()
        mode_links, mode_definitions, mode_plans, mode_capture = (collections.Counter() for _ in range(4))
        repeated_mode_refs = 0
        numeric_domains, numeric_matches, numeric_values = (collections.Counter() for _ in range(3))
        payment_kinds, payment_coverage, payment_details, payment_mana = (collections.Counter() for _ in range(4))
        choice_count = censored_count = 0
        history = collections.Counter()
        zone_history = collections.Counter()
        for row in _choices.rows(runs):
            choice_count += 1
            start = row['start']; choice = start['choice']
            audit.observe('CHOICE', choice['boundary'], choice.get('inputContext'), run_id=row['runId'],
                          game=row['game'], turn=start.get('turn'), player=choice['chooserPlayerId'],
                          turn_basis='CHOICE_START_EVENT', entry_location=choice.get('entryLocation'))
            add_input_context(row['start']['choice'].get('inputContext'), 'CHOICE')
            censored_count += row['censored']
            event_types[row['start']['choice']['choiceType']] += 1
            stages[row['start']['choice']['stage']] += 1
            statuses[row['status']] += 1
            zone_progress = choice['options'].get('zoneSelectionProgress')
            if zone_progress is not None:
                zone_history['records'] += 1
                zone_history['progress:' + zone_progress['progressCoverage']] += 1
                zone_history['receipts:' + zone_progress['receiptCoverage']] += 1
                zone_history['knownAttemptIndices'] += zone_progress['attemptIndex'] is not None
                if zone_progress['priorNonNullReturnCount'] is None: zone_history['unknownReturnCountRecords'] += 1
                else: zone_history['priorReturnsAcrossRecords'] += zone_progress['priorNonNullReturnCount']
                zone_history['receiptEntriesAcrossRecords'] += len(zone_progress['priorReturns'])
                prompt = ((choice.get('inputContext') or {}).get('policy') or {}).get('prompt')
                if prompt is not None and prompt['kind'] == 'ZONE_PICK_PROGRESS':
                    zone_history['projectedPrompts'] += 1
                    zone_history['projectedEntries'] += len(prompt['previousReturns'])
                    zone_history['resolvedEntries'] += sum(ref is not None for ref in prompt['previousReturns'])
                    zone_history['unresolvedEntries'] += sum(ref is None for ref in prompt['previousReturns'])
                else: zone_history['unavailablePrompts'] += 1
            sequence = choice['options'].get('selectionSequence')
            if sequence is not None:
                history['records'] += 1
                history['coverage:' + sequence['coverage']] += 1
                history['priorReturnsAcrossRecords'] += sequence['priorSelectionCount']
                history['receiptEntriesAcrossRecords'] += len(sequence['priorSelections'])
                prompt = ((choice.get('inputContext') or {}).get('policy') or {}).get('prompt')
                if prompt is not None and prompt['kind'] == 'ENTITY_PICK_SEQUENCE':
                    history['projectedPrompts'] += 1
                    history['projectedEntries'] += len(prompt['previousSelections'])
                    history['resolvedEntries'] += sum(ref is not None for ref in prompt['previousSelections'])
                    history['unresolvedEntries'] += sum(ref is None for ref in prompt['previousSelections'])
                else:
                    history['unavailablePrompts'] += 1
            if row['start']['choice']['choiceType'].startswith('PAYMENT_'):
                start = row['start']['choice']
                payment_coverage[start['options']['coverage']] += 1
                value = row['terminal']['choice']['selection'] if row['terminal'] else None
                if start['choiceType'] == 'PAYMENT_DECISION':
                    payment_kinds[start['options']['costKind']] += 1
                    if value is not None: payment_details.update(value['unrecordedDetails'])
                if start['choiceType'] == 'PAYMENT_MANA':
                    payment_mana[row['status'] if value is None else 'COST_SATISFIED' if value['costSatisfied'] else 'COST_REMAINS'] += 1
            if row['start']['choice']['choiceType'] in ('NUMBER_SELECTION', 'X_SELECTION'):
                numeric_domains[row['start']['choice']['options']['coverage']] += 1
                value = row['terminal']['choice']['selection'] if row['terminal'] else None
                if value is not None:
                    numeric_matches[value['domainMatch']] += 1
                    numeric_values['ZERO' if value['value'] == 0 else 'POSITIVE' if value['value'] > 0 else 'NEGATIVE'] += 1
            if row['start']['choice']['choiceType'] == 'MODE_SELECTION':
                value = row['terminal']['choice']['selection'] if row['terminal'] else None
                if value is None:
                    mode_plans[row['status']] += 1
                else:
                    mode_plans['OBSERVED' if value['available'] else 'UNAVAILABLE'] += 1
                    mode_capture['UNAVAILABLE' if not value['available'] else 'PREFIX' if value['truncated'] else 'FULL_LIST'] += 1
                    seen_modes = set()
                    for mode in value['modes']:
                        mode_links['EXACT_CAPTURED_OPTION' if mode['optionIndex'] is not None else 'UNMAPPED'] += 1
                        mode_definitions['KNOWN' if mode['definitionIndex'] is not None else 'UNAVAILABLE'] += 1
                        repeated_mode_refs += mode['abilityId'] in seen_modes
                        seen_modes.add(mode['abilityId'])
        summary.update(standaloneChoiceRecords=choice_count, censoredStandaloneChoices=censored_count,
                       standaloneChoiceTypes=dict(event_types), standaloneChoiceStages=dict(stages),
                       standaloneChoiceStatuses=dict(statuses))
        if zone_history:
            summary['zoneSelectionProgress'] = dict(schemaVersion=1, scope='PRESENT_PROGRESS_COMPONENTS_ONLY', counts=dict(zone_history),
                unobservedAttemptCount=None,
                definition='Observed components and repeated history vectors overlap; they are not unique operations, choices or returns. Retained counts and movements are separate. Complete receipts can have unresolved identities. Missing components/attempts remain unknown and trainingReady remains false.')
        if history:
            summary['entitySelectionHistory'] = dict(schemaVersion=1, scope='PRESENT_SEQUENCE_COMPONENTS_ONLY',
                counts=dict(history), unobservedSequenceCount=None,
                definition='Records and repeated history-vector entries overlap; these are not unique choices or returns. COMPLETE describes receipts, not resolved identities or learning readiness. Absence of a component is unknown capability, not an empty history.')
        if event_types['MODE_SELECTION']:
            summary.update(standaloneModeOptionLinks=dict(mode_links), standaloneModeDefinitionLinks=dict(mode_definitions),
                           standaloneModePlanCoverage=dict(mode_plans), standaloneModePlanCapture=dict(mode_capture),
                           standaloneRepeatedModeReferences=repeated_mode_refs)
        if payment_coverage:
            summary.update(standalonePaymentCostKinds=dict(payment_kinds), standalonePaymentCoverage=dict(payment_coverage),
                           standalonePaymentUnrecordedDetails=dict(payment_details), standaloneManaPaymentResults=dict(payment_mana))
        if numeric_domains:
            summary.update(standaloneNumericDomainCoverage=dict(numeric_domains), standaloneNumericMatches=dict(numeric_matches),
                           standaloneNumericValueStates=dict(numeric_values))
    if search_declared:
        summary.update(searchTargetDeclaredDecisions=search_declared, searchTargetApplications=search_count,
                       searchTargetOperationTypes=dict(search_operations), searchTargetApplicationStatuses=dict(search_statuses),
                       searchTargetApplicationsOnSelectedCandidates=search_selected,
                       searchTargetApplicationsOmittedByBudget=search_budget, searchTargetApplicationsOmittedDuringCapture=search_interrupted)
    if evaluated_x:
        summary['evaluatedXAssessmentStates'] = dict(evaluated_x)
    if input_records:
        summary.update(inputContextRecords=input_records, inputContextStatuses=dict(input_statuses),
                       inputContextProfiles=dict(input_profiles),
                       inputContextOwnerStatuses=dict(input_owners), inputContextTimings=dict(input_timings),
                       inputContextStateBases=dict(input_bases), inputContextOmissions=dict(input_omissions),
                       inputContextCoverage=dict(input_coverage))
    summary['inputCoverageAudit'] = audit.summary(spool.sequence if spool else lambda name, values: list(values))
    if input_bindings: summary['inputContextBindings'] = dict(input_bindings)
    accounting = _accounting.summarize(runs)
    if accounting is not None: summary['observationAccounting'] = accounting
    captures = capture_accounting.summary()
    if captures is not None: summary['targetCaptureAccounting'] = captures
    search_captures = search_capture_accounting.summary()
    if search_captures is not None: summary['searchCaptureAccounting'] = search_captures
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
    if any(run['metadata'].get('choiceEventSchemaVersion') == 1 for run in runs):
        summary['limitations'][0] = 'Main-phase play selection plus observed subordinate callbacks. No complete mulligan, combat, reactive-priority or legal-choice coverage.'
        summary['limitations'] = [v for v in summary['limitations'] if not v.startswith('Subordinate target records cover')]
        summary['limitations'].append('Standalone choices cover instrumented target, mode, numeric/X and payment boundaries, including execution and resolution callbacks. Unknown parents, copied modes, missing option pools and capped prefixes remain explicit; alternatives are observed inputs, not complete legal plans.')
        summary['limitations'].append('Completed payment visitors are proposals, not proof of payment. Mana routine outcomes describe that routine only; finalized engine payment facts remain separate. Type text, counter tables and conversion matrices are marked unrecorded. Mana pool choices without an active ability context are not recorded.')
    if input_records:
        summary['limitations'].append('Inline input contexts cover audited boundaries only. CAPTURED means the named partial profile was captured, not complete Magic knowledge or every AI input. ABSENT means unavailable capability, not an empty view. Only inputContext.policy is the permitted feature projection; diagnostic joins, original option membership/order, manifests, outcomes and legacy observation fields are excluded. Disclosed facts have unresolved current-object associations. Stack coverage is profile-declared and bounded where supported; long-term historical knowledge remains incomplete. trainingReady remains false.')
    if 'PERMITTED_CHOICE_VIEW_V3' in input_profiles:
        summary['limitations'].append('V3 separately permits audited numeric domains and local single-entity pick constraints in policy.prompt. Original entity pools remain diagnostic-only. Request coverage does not certify effect semantics, outer multi-pick history, option bindings or a complete legal action set.')
    if {'PERMITTED_CHOICE_VIEW_V4', 'PERMITTED_CHOICE_VIEW_V5', 'PERMITTED_CHOICE_VIEW_V6', 'PERMITTED_CHOICE_VIEW_V7', 'PERMITTED_CHOICE_VIEW_V8', 'PERMITTED_CHOICE_VIEW_V9', 'PERMITTED_CHOICE_VIEW_V10', 'PERMITTED_CHOICE_VIEW_V11', 'PERMITTED_CHOICE_VIEW_V12', 'PERMITTED_CHOICE_VIEW_V13', 'PERMITTED_CHOICE_VIEW_V14', 'PERMITTED_CHOICE_VIEW_V15', 'PERMITTED_CHOICE_VIEW_V16', 'PERMITTED_CHOICE_VIEW_V17'} & input_profiles.keys():
        summary['limitations'] = [text.replace('Disclosed facts have unresolved current-object associations.',
            'Only directly proved public disclosure associations can be linked; other associations remain unresolved.') for text in summary['limitations']]
        summary['limitations'].append('V4 retains typed request fields and adds diagnostic current-option links. Those links do not authorize entity pool membership, count or ordering as model features. Direct public disclosure links require delivery proof without an intervening engine event. No profile certifies complete learning examples.')
    if {'PERMITTED_CHOICE_VIEW_V5', 'PERMITTED_CHOICE_VIEW_V6', 'PERMITTED_CHOICE_VIEW_V7', 'PERMITTED_CHOICE_VIEW_V8', 'PERMITTED_CHOICE_VIEW_V9', 'PERMITTED_CHOICE_VIEW_V10', 'PERMITTED_CHOICE_VIEW_V11', 'PERMITTED_CHOICE_VIEW_V12', 'PERMITTED_CHOICE_VIEW_V13', 'PERMITTED_CHOICE_VIEW_V14', 'PERMITTED_CHOICE_VIEW_V15', 'PERMITTED_CHOICE_VIEW_V16', 'PERMITTED_CHOICE_VIEW_V17'} & input_profiles.keys():
        summary['limitations'].append('V5 additionally permits exact received scalar mode bounds at audited planning selector/operation entries. These are invocation arguments, not committed rules constraints or mode identities, membership/order, effect semantics, cached-plan cause or a complete legal mode domain. Other hypothetical prompt families remain unsupported.')
    if {'PERMITTED_CHOICE_VIEW_V6', 'PERMITTED_CHOICE_VIEW_V7', 'PERMITTED_CHOICE_VIEW_V8', 'PERMITTED_CHOICE_VIEW_V9', 'PERMITTED_CHOICE_VIEW_V10', 'PERMITTED_CHOICE_VIEW_V11', 'PERMITTED_CHOICE_VIEW_V12', 'PERMITTED_CHOICE_VIEW_V13', 'PERMITTED_CHOICE_VIEW_V14', 'PERMITTED_CHOICE_VIEW_V15', 'PERMITTED_CHOICE_VIEW_V16', 'PERMITTED_CHOICE_VIEW_V17'} & input_profiles.keys():
        summary['limitations'] = [s.replace('Other hypothetical prompt families remain unsupported.', 'V5 itself grants no other hypothetical prompt permission.') for s in summary['limitations']]
        summary['limitations'].append('V6 additionally permits exact received total entity bounds and the supplied related-player role at the audited outer entity operation. This operation overlaps its inner selectors. Parent choice links are diagnostic; prior-selection history and entity pool membership/count/order remain outside the permitted projection. These received arguments may describe planning work and do not certify legal results or complete learning examples.')
    if {'PERMITTED_CHOICE_VIEW_V7', 'PERMITTED_CHOICE_VIEW_V8', 'PERMITTED_CHOICE_VIEW_V9', 'PERMITTED_CHOICE_VIEW_V10', 'PERMITTED_CHOICE_VIEW_V11', 'PERMITTED_CHOICE_VIEW_V12', 'PERMITTED_CHOICE_VIEW_V13', 'PERMITTED_CHOICE_VIEW_V14', 'PERMITTED_CHOICE_VIEW_V15', 'PERMITTED_CHOICE_VIEW_V16', 'PERMITTED_CHOICE_VIEW_V17'} & input_profiles.keys():
        summary['limitations'] = [s.replace('Parent choice links are diagnostic; prior-selection history and entity pool membership/count/order remain outside the permitted projection.',
            'V6 parent links alone grant no history; V7 declares its own history permission below. Entity pool membership/count/order remain excluded.') for s in summary['limitations']]
        summary['limitations'].append('V7 additionally permits bounded successful-return history within an audited base-controller entity operation at the next selector entry. Current public associations require exact receipt identity/incarnation and fresh local bindings; unresolved identities stay null. Incomplete histories are unavailable, and direct/unaudited selector history remains unknown. No current offered-pool membership/count/order, complete game history or learning readiness follows from this permission.')
    if {'PERMITTED_CHOICE_VIEW_V8', 'PERMITTED_CHOICE_VIEW_V9', 'PERMITTED_CHOICE_VIEW_V10', 'PERMITTED_CHOICE_VIEW_V11', 'PERMITTED_CHOICE_VIEW_V12', 'PERMITTED_CHOICE_VIEW_V13', 'PERMITTED_CHOICE_VIEW_V14', 'PERMITTED_CHOICE_VIEW_V15', 'PERMITTED_CHOICE_VIEW_V16', 'PERMITTED_CHOICE_VIEW_V17'} & input_profiles.keys():
        summary['limitations'].append('V8 additionally permits the received single zone-change request at its audited selector: destination, ordered origins, local optionality and current decider role. These invocation arguments do not predict actual movement, authorize offered cards or establish outer repetition/history, effect semantics or learning readiness. None and empty origins retain their request meanings.')
    if {'PERMITTED_CHOICE_VIEW_V9', 'PERMITTED_CHOICE_VIEW_V10', 'PERMITTED_CHOICE_VIEW_V11', 'PERMITTED_CHOICE_VIEW_V12', 'PERMITTED_CHOICE_VIEW_V13', 'PERMITTED_CHOICE_VIEW_V14', 'PERMITTED_CHOICE_VIEW_V15', 'PERMITTED_CHOICE_VIEW_V16', 'PERMITTED_CHOICE_VIEW_V17'} & input_profiles.keys():
        summary['limitations'].append('V9 additionally permits the received ordinary engine zone-operation iteration request, ordered zones and current source/decider roles at its own entry before reveal. The terminal describes retained cards before later processing, not callback returns or movement. Child links remain diagnostic and grant no zone-selection history. Outer/inner observations overlap; no legal domain, complete history or learning readiness follows.')
    if {'PERMITTED_CHOICE_VIEW_V10', 'PERMITTED_CHOICE_VIEW_V11', 'PERMITTED_CHOICE_VIEW_V12', 'PERMITTED_CHOICE_VIEW_V13', 'PERMITTED_CHOICE_VIEW_V14', 'PERMITTED_CHOICE_VIEW_V15', 'PERMITTED_CHOICE_VIEW_V16', 'PERMITTED_CHOICE_VIEW_V17'} & input_profiles.keys():
        summary['limitations'].append('V10 additionally permits audited ordinary zone-selector progress and bounded prior non-null callback returns at the child entry. Attempts, engine loop positions, non-null returns, retained cards and movement have separate meanings. Current public associations require exact receipt identity/incarnation and fresh local bindings; private/stale identities stay null. Incomplete progress/history is unavailable; direct or unsupported history remains unknown. No offered-pool permission, complete legal domain or learning readiness follows.')
    if {'PERMITTED_CHOICE_VIEW_V11', 'PERMITTED_CHOICE_VIEW_V12', 'PERMITTED_CHOICE_VIEW_V13', 'PERMITTED_CHOICE_VIEW_V14', 'PERMITTED_CHOICE_VIEW_V15', 'PERMITTED_CHOICE_VIEW_V16', 'PERMITTED_CHOICE_VIEW_V17'} & input_profiles.keys():
        summary['limitations'].append('V11 identifies audited ordinary, named-list and per-type zone operation branches. Named iteration requests count evaluated named entries, including skipped entries; each type is a separate operation whose retained collection can include earlier types. Branch-local return history is not across-type history. Names, predicates and offered pools receive no additional permission. Heist/Draft remain unsupported sequences; all examples remain incomplete.')
    if {'PERMITTED_CHOICE_VIEW_V12', 'PERMITTED_CHOICE_VIEW_V13', 'PERMITTED_CHOICE_VIEW_V14', 'PERMITTED_CHOICE_VIEW_V15', 'PERMITTED_CHOICE_VIEW_V16', 'PERMITTED_CHOICE_VIEW_V17'} & input_profiles.keys():
        summary['limitations'] = [text.replace('Heist/Draft remain unsupported sequences;', 'V11 alone does not cover Heist/Draft;') for text in summary['limitations']]
        summary['limitations'].append('V12 additionally permits audited Heist and Draft operation/progress requests. Receipts precede between-pick movement; rejected non-null Heist returns still count without retention. Actual per-call origin-list identities are witnessed independently of the outer descriptor, with bounded ordered value equivalence. Generated, private, moved or stale identities gain no pool/name permission; incomplete history and learning readiness remain unchanged.')
    if {'PERMITTED_CHOICE_VIEW_V13', 'PERMITTED_CHOICE_VIEW_V14', 'PERMITTED_CHOICE_VIEW_V15', 'PERMITTED_CHOICE_VIEW_V16', 'PERMITTED_CHOICE_VIEW_V17'} & input_profiles.keys():
        summary['limitations'].append('V13 records audited effect spell-ability operations and selectors at their own entries and permits only the received outer requestedSelections. The original do-while may return one choice for zero/negative requests; the argument is not a legal result bound. Ability references, option indices and parent links are diagnostic. Opaque parameters, titles, spell-choice history and offered pools have no new feature permission; the single prompt and complete legal domain remain unknown. All examples remain incomplete.')
    if {'PERMITTED_CHOICE_VIEW_V14', 'PERMITTED_CHOICE_VIEW_V15', 'PERMITTED_CHOICE_VIEW_V16', 'PERMITTED_CHOICE_VIEW_V17'} & input_profiles.keys():
        summary['limitations'] = [text.replace('Opaque parameters, titles, spell-choice history and offered pools have no new feature permission; the single prompt and complete legal domain remain unknown.', 'V13 alone grants no single prompt; V14 describes its narrow request permission below. Titles, spell-choice history, offered pools and complete legal domains remain unknown.') for text in summary['limitations']]
        summary['limitations'].append('V14 permits the received single spell-ability affected-Player role for audited null/canonical-empty maps or one-key native HashMaps. Current local bindings require exact Player identity; foreign/unrepresented roles are unavailable. Other maps, keys, null/non-Player Affected values and arbitrary parameters remain unsupported, never silently empty. Diagnostic receipts are not features; no option/domain/history or learning readiness follows.')
    if {'PERMITTED_CHOICE_VIEW_V15', 'PERMITTED_CHOICE_VIEW_V16', 'PERMITTED_CHOICE_VIEW_V17'} & input_profiles.keys():
        summary['limitations'] = [text.replace('Titles, spell-choice history, offered pools and complete legal domains remain unknown.', 'V14 alone grants no spell-choice history; V15 declares controller-local progress below. Titles, offered pools and complete legal domains remain unknown.') for text in summary['limitations']]
        summary['limitations'].append('V15 additionally permits controller-owned spell-choice progress and a bounded prior-return occurrence vector at the next selector. Ability identities stay unresolved (null); detached receipts and IDs remain diagnostic. Only an exact admitted base-controller operation can own this history; direct selectors and external engine repetition remain unknown. Incomplete history is unavailable. No offered-pool, ability-definition, complete domain or learning readiness follows.')
    if {'PERMITTED_CHOICE_VIEW_V16', 'PERMITTED_CHOICE_VIEW_V17'} & input_profiles.keys():
        summary['limitations'].append('V16 independently permits bounded received scalar/current-public-role parameters at audited numeric-range and single-entity callbacks. Unknown maps/keys/values remain unsupported, and unproved/private/stale roles are unavailable. Parameters do not grant offered-pool membership, complete counter/effect/cost semantics or additional numeric planning-domain permission. Role collections remain unsupported; all examples remain incomplete.')
    if 'PERMITTED_CHOICE_VIEW_V17' in input_profiles:
        summary['limitations'].append('V17 captures the existing trigger-preparation operation before its work and permits only its received mandatory directive. Acceptance/final targets remain results, and nested selectors retain their own actors/entries. Unknown legacy helper directives stay unavailable. This grants no trigger semantics, option domain or learning readiness; legacy absent operation inputs remain unknown.')
    if accounting is not None:
        summary['limitations'].append('Invocation accounting covers configured original-AI standalone callback wrappers only. Entries are not unique gameplay decisions. Input-unavailable records are retained records; callback errors are not capture loss. Publication return does not prove collector delivery. Cutoff-pending work is not evidence that a late callback never ran. Parent/nested and uninstrumented invocation counts remain unknown. Partial scopes, legacy absence and overflow cannot supply exact missing-start fractions.')
    if captures is not None:
        summary['limitations'].append('Nested target accounting counts capture-method calls within retained parents, not original selector callback outcomes or game-wide nested decisions. Missing whole-parent events remain unknown. Pending capture calls describe each parent cutoff, not workers necessarily still running at run finish. Legacy parent absence and saturation suppress the aggregate retained-parent missing-row fraction.')
    if search_captures is not None:
        summary['limitations'].append('Search accounting counts recording-parent capture attempts before host eligibility, not original search callback outcomes or game-wide decisions. Result-stage interruption/failure on retained rows is separate from capture-method omissions. Missing whole parents remain unknown; mixed legacy data and saturation suppress the aggregate retained-parent missing-row fraction.')
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
    if 'targetChoiceProvenanceSnapshots' in summary:
        yield from lines('', '## Committed target-choice provenance', '',
                         'Origins identify explicit target additions to an unchanged stored plan, carried through engine clones. Unknown origins are not inferred by matching target identities. This does not attribute divided allocations, delegated choices, or downstream effects.')
        for prefix, name in (('Snapshot coverage: ', 'targetChoiceProvenanceSnapshots'), ('Committed target slots: ', 'committedTargetChoiceOrigins')):
            yield from counter_line(prefix, name)
    if 'standaloneChoiceRecords' in summary:
        yield from lines('', '## Standalone subordinate choices', '',
                         f"{summary['standaloneChoiceRecords']} choice lifecycles; {summary['censoredStandaloneChoices']} censored. Choices without a main-phase parent remain in choices.jsonl. No identity-based parent or reward is inferred.")
        for prefix, name in (('Choice types: ', 'standaloneChoiceTypes'), ('Stages: ', 'standaloneChoiceStages'), ('Statuses: ', 'standaloneChoiceStatuses')):
            yield from counter_line(prefix, name)
        if 'standaloneModeOptionLinks' in summary:
            for prefix, name in (('Mode option links: ', 'standaloneModeOptionLinks'), ('Mode definition links: ', 'standaloneModeDefinitionLinks'),
                                 ('Mode-plan availability: ', 'standaloneModePlanCoverage'), ('Mode-plan capture: ', 'standaloneModePlanCapture')):
                yield from counter_line(prefix, name)
            yield from lines(f"Repeated captured mode references: {summary['standaloneRepeatedModeReferences']}. Offered indices and original definition indices have separate meanings; neither callback completion nor mode count is a strategic-quality label.")
        if 'standalonePaymentCoverage' in summary:
            for prefix, name in (('Payment cost kinds: ', 'standalonePaymentCostKinds'), ('Payment input coverage: ', 'standalonePaymentCoverage'),
                                 ('Unrecorded payment details: ', 'standalonePaymentUnrecordedDetails'), ('Mana routine outcomes: ', 'standaloneManaPaymentResults')):
                yield from counter_line(prefix, name)
        if 'standaloneNumericDomainCoverage' in summary:
            for prefix, name in (('Numeric domain coverage: ', 'standaloneNumericDomainCoverage'), ('Numeric return matches: ', 'standaloneNumericMatches'),
                                 ('Numeric value states: ', 'standaloneNumericValueStates')):
                yield from counter_line(prefix, name)
            yield from lines('Numeric domains are observed inputs or heuristic bounds, not reconstructed legal values or mana spent. Outside-prefix/domain returns are retained without changing the AI result.')
    if 'inputContextRecords' in summary:
        yield from lines('', '## Inline choice input contexts', '',
                         f"Recorded {summary['inputContextRecords']} versioned input contexts. Statuses are relative to each declared partial profile. ABSENT means unavailable capability, not an empty view.")
        for prefix, name in (('Profiles: ', 'inputContextProfiles'), ('Capture statuses: ', 'inputContextStatuses'), ('Owners and statuses: ', 'inputContextOwnerStatuses'),
                             ('Boundary timing: ', 'inputContextTimings'), ('State basis: ', 'inputContextStateBases'),
                             ('Omissions: ', 'inputContextOmissions'), ('Component coverage: ', 'inputContextCoverage')):
            yield from counter_line(prefix, name)
        yield from lines('Only inputContext.policy is the permitted feature projection. Diagnostic IDs, timestamps, offered-pool membership/order and future trace facts are excluded; unresolved disclosure facts do not establish current-object identity. Stack coverage is profile-declared and bounded where supported; long-term history remains incomplete. trainingReady remains false.')
        if 'inputContextBindings' in summary:
            yield from counter_line('V4 diagnostic bindings and disclosure associations: ', 'inputContextBindings')
            yield from lines('contextRecords counts V4 contexts; optionEntries counts their represented diagnostic options; disclosureFacts counts their delivered facts. These denominators overlap and are not unique gameplay choices. Missing/uninstrumented choices remain unknown.')
    if 'zoneSelectionProgress' in summary:
        yield from counter_line('Scoped zone-selection progress: ', 'zoneSelectionProgress')
    if 'entitySelectionHistory' in summary:
        yield from counter_line('Scoped entity-selection history: ', 'entitySelectionHistory')
    yield from _audit.markdown(summary['inputCoverageAudit'])
    if 'observationAccounting' in summary:
        yield from lines('', '## Standalone invocation accounting', '')
        yield from counter_line('Accounting: ', 'observationAccounting')
        yield from lines('The denominator is eligible wrapped invocations on the configured original AI controllers, across each game capture cutoff. Missing starts at cutoff include omitted/prepared-but-uncollected work; they are not unique decisions or AI errors. Nested/parent and uninstrumented counts remain unknown.')
    if 'targetCaptureAccounting' in summary:
        yield from lines('', '## Retained-parent target capture calls', '')
        yield from counter_line('Capture calls: ', 'targetCaptureAccounting')
        yield from lines('Counts cover capture-method admission within retained parents. They do not count original selector callback outcomes or reconstruct missing parents. Unavailable views remain retained rows; pending capture-method exits are not evidence that policy callbacks never ran.')
    if 'searchCaptureAccounting' in summary:
        yield from lines('', '## Retained-parent search capture calls', '')
        yield from counter_line('Capture calls: ', 'searchCaptureAccounting')
        yield from lines('Counts cover the capture phase before host eligibility and original callback execution. A retained row with result-stage interruption/failure is not a missing capture. Missing parents and game-wide callback outcomes remain unknown; pending exits describe parent cutoffs only.')
    if 'evaluatedXAssessmentStates' in summary:
        yield from counter_line('Evaluated X assessment snapshots: ', 'evaluatedXAssessmentStates')
    if 'searchTargetApplications' in summary:
        yield from lines('', '## Search target operations', '',
                         f"Recorded {summary['searchTargetApplications']} final search operations; {summary['searchTargetApplicationsOnSelectedCandidates']} concern selected candidates. These are actual revalidation/application observations, not complete target alternatives.")
        yield from counter_line('Operations: ', 'searchTargetOperationTypes')
        yield from counter_line('Outcomes: ', 'searchTargetApplicationStatuses')
        yield from lines(f"Capture omissions: {summary['searchTargetApplicationsOmittedByBudget']} by budget, {summary['searchTargetApplicationsOmittedDuringCapture']} interrupted. Accepted operations do not prove execution or strategic value.")
    yield from lines('', '## Reproducibility', '', 'The companion manifest contains exact deck snapshots, deck hashes, input hashes and run metadata. The following summary is readable without the input logs.', '',
                     '| Run | Seed | Pilot | Engine revision | Dirty | Source SHA256 |', '| --- | --- | --- | --- | --- | --- |')
    for run in runs:
        metadata = run['metadata']
        yield from lines('| ' + ' | '.join(cell(metadata.get(k)) for k in ('runId', 'seed', 'pilotMode', 'engineRevision', 'engineDirty', 'sourceSha256')) + ' |')


def report_markdown(summary, runs):
    return ''.join(report_chunks(summary, runs))


EXPORT_NAMES = ('dataset.jsonl', 'examples.jsonl', 'manifest.json', 'coverage.json', 'coverage.md', 'validation.json')
COMPACT_KEYS = ('runs', 'games', 'decisions', 'executionStatuses', 'outcomeEligibleDecisions', 'censoredDecisions')


def export_names(runs):
    return EXPORT_NAMES + (('choices.jsonl',) if any(r['metadata'].get('choiceEventSchemaVersion') == 1 for r in runs) else ())


def write_export(out, spool, summary, examples):
    def json_file(name, value):
        with (out / name).open('w', encoding='utf-8') as sink:
            # json.dump uses the streaming encoder, including our disk-backed list/dict views.
            json.dump(value, sink, indent=2, ensure_ascii=False)
            sink.write('\n')

    with (out / 'dataset.jsonl').open('w', encoding='utf-8') as dataset:
        for row in spool.rows:
            dataset.write(json.dumps(row, ensure_ascii=False) + '\n')
    if 'choices.jsonl' in export_names(spool.runs):
        with (out / 'choices.jsonl').open('w', encoding='utf-8') as choices:
            for row in _choices.rows(spool.runs):
                choices.write(json.dumps(row, ensure_ascii=False) + '\n')
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
            if row['decision'].get('searchTargetApplicationSchemaVersion') == 1:
                key += (tuple(sorted({(c['operation'], c['status'], c['candidateActionId'] is not None)
                                      for c in row['decision']['searchTargetApplications']})),)
            if row['decision'].get('targetChoiceProvenanceSchemaVersion') in (1, 2):
                origin_kinds = set()
                for action in row['actionExecutions']:
                    for event in action['engineEvents']:
                        if event['type'] != 'STACK_ADDED':
                            continue
                        provenance = event.get('targetChoiceProvenance')
                        if provenance is None:
                            origin_kinds.add('UNAVAILABLE')
                            continue
                        if provenance['abilitiesTruncated']:
                            origin_kinds.add('TRUNCATED')
                        for group in provenance['abilities']:
                            if group['targetsTruncated']:
                                origin_kinds.add('TRUNCATED')
                            origin_kinds.update('UNKNOWN' if origin is None else 'EXPLICIT_SEARCH_APPLICATION' if origin['stage'] == 'SEARCH_TARGET_APPLICATION' else 'EXPLICIT_EVALUATION_ADDITION'
                                                for origin in group['targetChoices'])
                key += (tuple(sorted(origin_kinds)),)
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
    manifest = dict(datasetSchemaVersion=1, inputs=inputs, games=games)
    if 'standaloneChoiceRecords' in summary: manifest['choiceDatasetSchemaVersion'] = 1
    json_file('manifest.json', manifest)
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
            for name in export_names(spool.runs):
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
            names = export_names(spool.runs)
            require(not any((out / name).exists() for name in names), 'output files already exist; choose a fresh output directory')
            require(not any((out / name).resolve() in {p.resolve() for p in paths} for name in names), 'output would overwrite an input')
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
