#!/usr/bin/env python3
"""Validate Forge decision streams/full traces; export a dataset and standalone report.

Standard library only. This is an analysis dataset, not a complete RL observation/action space.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import sys


class InvalidData(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise InvalidData(message)


def integer(value):
    return isinstance(value, int) and not isinstance(value, bool)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()


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
    return d


ACTION_EVENT_TYPES = ('STACK_ADDED', 'STACK_RESOLVED', 'STACK_REMOVED', 'STACK_COUNTERED', 'STACK_CLEARED', 'LAND_PLAYED')
ACTION_EVENT_FIELDS = ('type', 'game', 'eventIndex', 'turn', 'phase', 'decisionId', 'actionId', 'stackId', 'abilityId', 'fizzled', 'finalizedTargets', 'finalizedModes')


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
    require(not any(e['type'] == kind for e in action['engineEvents']), 'duplicate linked engine event')
    if kind == 'LAND_PLAYED':
        require(action['identity']['kind'] == 'LAND', 'land event linked to non-land selection')
    else:
        require(action['identity']['kind'] != 'LAND' and integer(record.get('stackId')), 'invalid linked stack identity')
        if kind != 'STACK_CLEARED':
            require(integer(record.get('abilityId')), 'linked stack event lacks ability identity')
        if kind == 'STACK_ADDED':
            action['stackIdentity'] = (record['stackId'], record['abilityId'])
        else:
            require(action['stackIdentity'] is not None, 'linked stack event without stack addition')
            stack_id, ability_id = action['stackIdentity']
            require(record['stackId'] == stack_id and record.get('abilityId', ability_id) == ability_id, 'linked stack identity mismatch')
    if kind == 'STACK_RESOLVED':
        require(isinstance(record.get('fizzled'), bool), 'resolution lacks fizzled flag')
    event = {key: record[key] for key in ACTION_EVENT_FIELDS if key in record}
    event['postOutcome'] = game['outcome'] is not None
    action['engineEvents'].append(event)


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


def load_run(path):
    """Validate completely before returning anything that can be written to the dataset."""
    metadata = None
    finished = None
    games = {}
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
                    game = games.setdefault(number, dict(lastIndex=0, start=None, outcome=None, finish=None, execution=None, decisions=[], players={}, actions={}))
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
                        require(all(old['decision']['decisionId'] != d['decisionId'] for old in game['decisions']), 'duplicate decision id')
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
        if execution['status'] != 'EXCEPTION' or game['decisions']:
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
    require(sorted(games) == list(range(1, len(games) + 1)), f'{path}: attempted game sequence has gaps')
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
    for number, game in sorted(run['games'].items()):
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


def make_coverage(runs, rows):
    summary = dict(runs=len(runs), games=sum(len(r['games']) for r in runs),
                   decisions=len(rows), sourceRecords=sum(r['records'] for r in runs), trainingReady=False)
    summary['executionStatuses'] = dict(collections.Counter(g['execution']['status'] for r in runs for g in r['games'].values()))
    for key, values in (
        ('selections', (r['decision']['selection'] for r in rows)),
        ('selectedActionKinds', (a['kind'] for r in rows for a in r['decision']['selectedActions'])),
        ('phases', (r['decision']['observation']['phase'] for r in rows)),
        ('decisionStatuses', (r['decision']['status'] for r in rows)),
        ('outcomeLabels', (r['outcomeLabel'] or 'CENSORED' for r in rows)),
        ('pilotModes', (r['pilotMode'] for r in rows)),
        ('assessments', (c['assessment'] for r in rows for c in r['decision']['candidates'])),
        ('assessmentDetails', (c['assessmentDetail']['reason'] for r in rows for c in r['decision']['candidates'] if 'assessmentDetail' in c)),
        ('cardAiHandlers', (c['assessmentDetail']['handler'] for r in rows for c in r['decision']['candidates'] if 'handler' in c.get('assessmentDetail', {}))),
        ('handlerAssessments', (c['assessmentDetail']['handlerAssessment'] for r in rows for c in r['decision']['candidates'] if 'handlerAssessment' in c.get('assessmentDetail', {}))),
        ('rejectedCards', (c.get('sourceName', 'IDENTITY_MASKED') for r in rows for c in r['decision']['candidates'] if c['assessment'] not in ('WillPlay', 'LAND_OPTION'))),
        ('rejectedActionKinds', (c['kind'] for r in rows for c in r['decision']['candidates'] if c['assessment'] not in ('WillPlay', 'LAND_OPTION'))),
        ('rejectedCardKinds', (c.get('sourceName', 'IDENTITY_MASKED') + ' [' + c['kind'] + ']' for r in rows for c in r['decision']['candidates'] if c['assessment'] not in ('WillPlay', 'LAND_OPTION'))),
        ('searchReasons', (r['decision']['search']['reason'] for r in rows if 'search' in r['decision'])),
    ):
        summary[key] = dict(collections.Counter(values).most_common())
    summary['outcomeEligibleDecisions'] = sum(r['outcomeEligible'] for r in rows)
    summary['censoredDecisions'] = len(rows) - summary['outcomeEligibleDecisions']
    summary['decisionsAfterOutcome'] = sum(r['postOutcome'] for r in rows)
    summary['decisionsWithAssessments'] = sum(bool(r['decision']['candidates']) for r in rows)
    summary['selectedActionsWithoutAssessment'] = sum(not any(matches_selected(a, c) for c in r['decision']['candidates']) for r in rows for a in r['decision']['selectedActions'])
    summary['actionExecutionStatuses'] = dict(collections.Counter(a['status'] for r in rows for a in r['actionExecutions']))
    summary['linkedEngineEvents'] = dict(collections.Counter(e['type'] for r in rows for a in r['actionExecutions'] for e in a['engineEvents']))
    summary['actionEngineEventCoverage'] = dict(collections.Counter(a['engineEventCoverage'] for r in rows for a in r['actionExecutions']))
    additions = [e for r in rows for a in r['actionExecutions'] for e in a['engineEvents'] if e['type'] == 'STACK_ADDED']
    summary['finalizedTargetSnapshots'] = dict(collections.Counter('OBSERVED' if e.get('finalizedTargets') is not None else 'UNAVAILABLE' for e in additions))
    summary['finalizedTargetTypes'] = dict(collections.Counter(t['type'] for e in additions if e.get('finalizedTargets') is not None
                                                             for group in e['finalizedTargets']['abilities'] for t in group['targets']))
    summary['finalizedModeSnapshots'] = dict(collections.Counter('OBSERVED' if e.get('finalizedModes') is not None else 'UNAVAILABLE' for e in additions))
    summary['selectedModeCounts'] = dict(collections.Counter(len(e['finalizedModes']['modes']) for e in additions
                                                           if e.get('finalizedModes') is not None and e['finalizedModes']['modal']))
    summary['searchRecords'] = sum('search' in r['decision'] for r in rows)
    summary['searchDecidedByRollouts'] = sum(r['decision'].get('search', {}).get('decidedByRollouts', False) for r in rows)
    summary['runsSummary'] = [dict(runId=r['metadata']['runId'], decks=[p.get('deckName') for p in r['metadata']['participants']],
                                 pilotMode=r['metadata']['pilotMode'], seed=r['metadata'].get('seed'), status=r['finished']['status'],
                                 decisions=sum(len(g['decisions']) for g in r['games'].values())) for r in runs]
    summary['groups'] = []
    grouped = collections.defaultdict(list)
    for row in rows:
        grouped[(row['deckName'], row['deckSha256'], row['pilotMode'])].append(row)
    for (name, deck_hash, pilot), group in sorted(grouped.items()):
        summary['groups'].append(dict(deck=name, deckSha256=deck_hash, pilotMode=pilot, decisions=len(group),
                                     passes=sum(r['decision']['selection'] == 'PASS_PRIORITY' for r in group),
                                     withAssessments=sum(bool(r['decision']['candidates']) for r in group),
                                     censored=sum(not r['outcomeEligible'] for r in group)))
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
        'Legacy land records and some direct combo selections can lack assessment rows.',
        'UNKNOWN engine/pilot metadata in legacy inputs is retained explicitly rather than guessed.',
        'Handler labels are preserved from Forge: MissingPhaseRestrictions means a phase check declined the play, not necessarily missing card configuration.',
    ]
    return summary


def report_markdown(summary, runs):
    def cell(value):
        return str(value).replace('|', '\\|').replace('\n', ' ')
    lines = ['# Decision coverage report', '',
             f"Validated **{summary['decisions']} decisions** from **{summary['games']} games** in **{summary['runs']} runs**.",
             f"Execution results: `{json.dumps(summary['executionStatuses'], sort_keys=True)}`. "
             f"Outcome-eligible decisions: {summary['outcomeEligibleDecisions']}; censored: {summary['censoredDecisions']}.", '',
             '**This is a coverage and data-integrity report, not a rating of deck strength or strategic correctness.**', '',
             '| Deck | Pilot | Decisions | Passes | With assessments | Censored |',
             '| --- | --- | ---: | ---: | ---: | ---: |']
    for group in summary['groups']:
        lines.append('| ' + ' | '.join(cell(group[k]) for k in ('deck', 'pilotMode', 'decisions', 'passes', 'withAssessments', 'censored')) + ' |')
    lines += ['', '## Assessment observations', '', '| Assessment | Count |', '| --- | ---: |']
    for reason, count in summary['assessments'].items():
        lines.append(f'| {cell(reason)} | {count} |')
    lines += ['', 'Counts can include repeated evaluation of the same ability. Passing can reflect a strategic choice or an unapproved action, not necessarily absence of legal plays.', '',
              '## Rejection details', '',
              'Where available: `' + json.dumps(summary['assessmentDetails'], sort_keys=True) + '`.',
              'Card AI handlers: `' + json.dumps(summary['cardAiHandlers'], sort_keys=True) + '`.',
              'Handler assessments: `' + json.dumps(summary['handlerAssessments'], sort_keys=True) + '`.',
              'Legacy inputs without detail remain unspecified. LAND_OPTION rows are shortlist entries, not rejections.', '',
              '## Coverage gaps', '',
              'Decision statuses: `' + json.dumps(summary['decisionStatuses'], sort_keys=True) + '`.',
              'Selected action kinds: `' + json.dumps(summary['selectedActionKinds'], sort_keys=True) + '`.',
              'Phases: `' + json.dumps(summary['phases'], sort_keys=True) + '`.',
              'Selected action execution results: `' + json.dumps(summary['actionExecutionStatuses'], sort_keys=True) + '`.',
              'Linked engine events: `' + json.dumps(summary['linkedEngineEvents'], sort_keys=True) + '`.',
              'Engine-event coverage: `' + json.dumps(summary['actionEngineEventCoverage'], sort_keys=True) + '`.',
              'Finalized target snapshots: `' + json.dumps(summary['finalizedTargetSnapshots'], sort_keys=True) + '`.',
              'Finalized target references (including sub-abilities): `' + json.dumps(summary['finalizedTargetTypes'], sort_keys=True) + '`.',
              'Finalized mode snapshots: `' + json.dumps(summary['finalizedModeSnapshots'], sort_keys=True) + '`.',
              'Selected mode counts per modal action: `' + json.dumps(summary['selectedModeCounts'], sort_keys=True) + '`.',
              f"{summary['decisionsWithAssessments']} decisions have assessment rows. {summary['selectedActionsWithoutAssessment']} selected actions lack a matching assessment row.",
              f"{summary['decisionsAfterOutcome']} in-flight decisions completed after an outcome was announced; these are retained in order and censored.",
              f"{summary['searchRecords']} decisions include search summaries; {summary['searchDecidedByRollouts']} report that rollouts decided the choice.", '',
              'Search reasons: `' + json.dumps(summary['searchReasons'], sort_keys=True) + '`.', '',
              'Frequently rejected card assessments: `' + json.dumps(dict(list(summary['rejectedCardKinds'].items())[:15]), ensure_ascii=False) + '`.', '']
    lines += ['- ' + text for text in summary['limitations']]
    lines += ['', '## Runs', '', '| Decks | Pilot | Seed | Result | Decisions |', '| --- | --- | --- | --- | ---: |']
    for run in summary['runsSummary']:
        lines.append('| ' + ' | '.join(cell(' vs. '.join(run['decks']) if k == 'decks' else run[k]) for k in ('decks', 'pilotMode', 'seed', 'status', 'decisions')) + ' |')
    lines += ['', '## Reproducibility', '', 'The companion manifest contains exact deck snapshots, deck hashes, input hashes and run metadata. The following summary is readable without the input logs.', '',
              '| Run | Seed | Pilot | Engine revision | Dirty | Source SHA256 |', '| --- | --- | --- | --- | --- | --- |']
    for run in runs:
        m = run['metadata']
        lines.append('| ' + ' | '.join(cell(m.get(k)) for k in ('runId', 'seed', 'pilotMode', 'engineRevision', 'engineDirty', 'sourceSha256')) + ' |')
    return '\n'.join(lines) + '\n'


def export(paths, output_dir=None, examples=12):
    paths = [Path(p) for p in paths]
    require(len({p.resolve() for p in paths}) == len(paths), 'same input supplied twice')
    runs = [load_run(path) for path in paths]
    ids = [r['metadata']['runId'] for r in runs]
    require(len(set(ids)) == len(ids), 'duplicate runId: choose either full trace or decision stream, not both')
    rows = [row for run in runs for row in dataset_rows(run)]
    summary = make_coverage(runs, rows)
    if output_dir is not None:
        out = Path(output_dir)
        names = ('dataset.jsonl', 'examples.jsonl', 'manifest.json', 'coverage.json', 'coverage.md', 'validation.json')
        require(not any((out / name).exists() for name in names), 'output files already exist; choose a fresh output directory')
        require(not any((out / name).resolve() in {p.resolve() for p in paths} for name in names), 'output would overwrite an input')
        out.mkdir(parents=True, exist_ok=True)
        (out / 'dataset.jsonl').write_text(''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in rows), encoding='utf-8')
        # Include distinct pilot/selection/outcome/search/execution groups before filling remaining slots.
        sample = []
        seen = set()
        for row in rows:
            key = (row['pilotMode'], row['decision']['selection'], row['outcomeEligible'], bool(row['decision'].get('search')),
                   tuple(a['status'] for a in row['actionExecutions']))
            if key not in seen:
                seen.add(key)
                sample.append(row)
        for row in rows:
            if len(sample) >= examples:
                break
            if row not in sample:
                sample.append(row)
        (out / 'examples.jsonl').write_text(''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in sample[:examples]), encoding='utf-8')
        contexts = [dict(runId=run['metadata']['runId'], game=number, gameStarted=game['start'],
                         executionStatus=game['execution']['status'], recordedOutcome=game['outcome'],
                         administrativeOutcome=game['execution']['status'] != 'NORMAL')
                    for run in runs for number, game in sorted(run['games'].items())]
        manifest = dict(datasetSchemaVersion=1, inputs=[r['metadata'] for r in runs], games=contexts)
        (out / 'manifest.json').write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
        (out / 'coverage.json').write_text(json.dumps(summary, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
        (out / 'coverage.md').write_text(report_markdown(summary, runs), encoding='utf-8')
        (out / 'validation.json').write_text(json.dumps(dict(valid=True, inputs=len(runs), decisions=len(rows), checks=['run/game boundaries', 'event order', 'schemas and identities', 'engine-event references', 'visibility constraints', 'status/outcome agreement', 'timeout/error censoring']), indent=2) + '\n', encoding='utf-8')
    return summary


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
        summary = export(args.inputs, args.output_dir, args.examples)
    except (InvalidData, OSError) as exc:
        print(f'Validation/export failed: {exc}', file=sys.stderr)
        return 1
    print(json.dumps({k: summary[k] for k in ('runs', 'games', 'decisions', 'executionStatuses', 'outcomeEligibleDecisions', 'censoredDecisions')}, indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main())
