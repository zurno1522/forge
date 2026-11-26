"""Versioned, partial player-view inputs. Only ``policy`` is a feature projection.

Diagnostic joins and the owning record's offered pool are intentionally outside it.
Validation checks the producer contract; it cannot reconstruct permission grants
from a privileged trace or prove that an uninstrumented heuristic used this view.
"""

import importlib.util
from pathlib import Path

_parameter_spec = importlib.util.spec_from_file_location('forge_received_parameters', Path(__file__).with_name('received_parameters.py'))
_parameters = importlib.util.module_from_spec(_parameter_spec)
_parameter_spec.loader.exec_module(_parameters)

PROFILE = 'PERMITTED_CHOICE_VIEW_V1'
PROFILES = {1: PROFILE, 2: 'PERMITTED_CHOICE_VIEW_V2', 3: 'PERMITTED_CHOICE_VIEW_V3', 4: 'PERMITTED_CHOICE_VIEW_V4',
            5: 'PERMITTED_CHOICE_VIEW_V5', 6: 'PERMITTED_CHOICE_VIEW_V6', 7: 'PERMITTED_CHOICE_VIEW_V7', 8: 'PERMITTED_CHOICE_VIEW_V8', 9: 'PERMITTED_CHOICE_VIEW_V9', 10: 'PERMITTED_CHOICE_VIEW_V10', 11: 'PERMITTED_CHOICE_VIEW_V11', 12: 'PERMITTED_CHOICE_VIEW_V12', 13: 'PERMITTED_CHOICE_VIEW_V13', 14: 'PERMITTED_CHOICE_VIEW_V14', 15: 'PERMITTED_CHOICE_VIEW_V15', 16: 'PERMITTED_CHOICE_VIEW_V16'}
ZONE_BRANCHES = {'ORDINARY_HIDDEN_ORIGIN', 'SAME_NAME_HIDDEN_ORIGIN', 'EACH_TYPE_HIDDEN_ORIGIN', 'HEIST', 'DRAFT'}
TIMINGS = {'BEFORE_SELECTOR', 'BEFORE_OPERATION', 'AFTER_SELECTION_BEFORE_APPLICATION', 'UNESTABLISHED'}
STATE_BASES = {'RULES_STATE', 'PLANNING_WORKING_STATE', 'UNESTABLISHED'}
REASONS = {'HIDDEN', 'UNSUPPORTED', 'OUTSIDE_CAPTURED_PREFIX', 'UNRESOLVED_BINDING',
           'BUDGET_EXHAUSTED', 'INTERRUPTED', 'CAPTURE_FAILED', 'NO_AUDITED_INPUT_BOUNDARY'}
COMPONENTS = {'CONTEXT', 'PLAYER_VIEW', 'ENTITIES', 'MANA', 'COUNTERS', 'TEXT', 'DISCLOSURES', 'PERSPECTIVE'}
ZONES = {'Hand', 'Library', 'Graveyard', 'Battlefield', 'Exile', 'Flashback', 'Command', 'Stack',
         'Sideboard', 'Ante', 'Merged', 'SchemeDeck', 'PlanarDeck', 'AttractionDeck', 'Junkyard',
         'ContraptionDeck', 'StickerSheets', 'Subgame', 'ExtraHand', 'None'}
VIEW_ZONES = {'Battlefield', 'Command', 'Graveyard', 'Exile', 'Hand', 'Library', 'Stack'}
FACES = {'Original', 'FaceDown', 'Flipped', 'Backside', 'Meld', 'LeftSplit', 'RightSplit', 'Secondary',
         'PreparedSpell', 'EmptyRoom', 'SpecializeW', 'SpecializeU', 'SpecializeB', 'SpecializeR', 'SpecializeG'}
MANA_COLORS = {0, 1, 2, 4, 8, 16, 32}


def require(ok, message):
    if not ok:
        raise ValueError('input context: ' + message)


def integer(value):
    return isinstance(value, int) and not isinstance(value, bool)


def text(value):
    return isinstance(value, str) and len(value) <= 256


def fields(value, expected, message):
    require(isinstance(value, dict) and set(value) == set(expected.split()), message)


def validate(value, chooser_id, known_players=None, *, parameters_allowed=False):
    """Validate a present context, never substituting a default for legacy absence."""
    fields(value, 'schemaVersion profile status timing stateBasis perspective coverage policy diagnostics omissions',
           'invalid envelope fields')
    version = value['schemaVersion']
    require(integer(version) and version in PROFILES, 'unsupported schema version')
    require(value['profile'] == PROFILES[version], 'unsupported profile')
    require(value['status'] in ('CAPTURED', 'PARTIAL', 'UNAVAILABLE'), 'invalid capture status')
    require(value['timing'] in TIMINGS and value['stateBasis'] in STATE_BASES, 'invalid timing/state basis')
    perspective = value['perspective']
    fields(perspective, 'chooserPlayerId controllingPlayerId', 'invalid perspective fields')
    require(integer(perspective['chooserPlayerId']) and perspective['chooserPlayerId'] == chooser_id,
            'chooser differs from owning record')
    require(integer(perspective['controllingPlayerId']), 'invalid controlling player')
    if known_players is not None:
        require(all(perspective[k] in known_players for k in perspective), 'unknown perspective player')
    coverage = value['coverage']
    fields(coverage, 'playerView stack disclosures history options' + (' prompt' if version >= 3 else '') + (' receivedParameters' if version >= 16 else ''), 'invalid coverage fields')
    require(coverage['playerView'] in ('PERMITTED_CURRENT_FIELDS_V1', 'UNAVAILABLE'), 'unknown player-view coverage')
    require(coverage['stack'] in ({'UNSUPPORTED'} if version == 1 else {'PUBLIC_ROOT_STACK_V1', 'UNAVAILABLE'}) and coverage['history'] == 'INCOMPLETE'
            and coverage['options'] == 'DIAGNOSTIC_ONLY', 'unsupported coverage claim')
    require(coverage['disclosures'] in ('STACK_RESOLUTION', 'CURRENT_PROMPT', 'UNSUPPORTED_PRIOR_SCOPE', 'UNAVAILABLE'),
            'unknown disclosure coverage')
    omissions = value['omissions']
    require(isinstance(omissions, list) and len(omissions) <= 16, 'invalid omissions')
    for omission in omissions:
        fields(omission, 'component reason', 'invalid omission fields')
        require(omission['component'] in COMPONENTS | ({'STACK'} if version >= 2 else set()) | ({'PROMPT'} if version >= 3 else set()) | ({'PARAMETERS'} if version >= 16 else set())
                and omission['reason'] in (REASONS | ({'COUNTER_OVERFLOW'} if version >= 10 else set())), 'unknown omission')
    require(bool(omissions) == (value['status'] != 'CAPTURED'), 'capture status/omission mismatch')
    diagnostic = value['diagnostics']
    fields(diagnostic, 'playerBindings cardBindings' + (' stackBindings' if version >= 2 else '')
           + (' optionBindings' if version >= 4 else ''), 'invalid diagnostic fields')
    require(isinstance(diagnostic['playerBindings'], list) and isinstance(diagnostic['cardBindings'], list),
            'invalid binding collections')
    policy = value['policy']
    require((policy is None) == (value['status'] == 'UNAVAILABLE'), 'capture status/policy mismatch')
    require((coverage['playerView'] == 'UNAVAILABLE') == (policy is None), 'player-view coverage/policy mismatch')
    require((coverage['disclosures'] == 'UNAVAILABLE') == (policy is None), 'disclosure coverage/policy mismatch')
    if policy is None:
        require(not diagnostic['playerBindings'] and not diagnostic['cardBindings'], 'unavailable context has bindings')
        if version >= 2:
            require(coverage['stack'] == 'UNAVAILABLE' and diagnostic['stackBindings'] == [], 'unavailable stack has bindings')
        if version >= 3: require(coverage['prompt'] == 'UNAVAILABLE', 'unavailable prompt claims coverage')
        if version >= 16: require(coverage['receivedParameters'] == 'UNAVAILABLE', 'unavailable parameters claim coverage')
        if version >= 4:
            require(diagnostic['optionBindings'] == dict(coverage='UNAVAILABLE', items=[], truncated=True), 'unavailable option links have data')
        return
    if version >= 2: require(coverage['stack'] == 'PUBLIC_ROOT_STACK_V1', 'stack coverage/policy mismatch')
    require(perspective['controllingPlayerId'] == chooser_id, 'unsupported controlled perspective has policy')
    fields(policy, 'turn phase turnPlayer chooser players cards disclosedFacts' + (' stack' if version >= 2 else '')
           + (' prompt' if version >= 3 else '') + (' receivedParameters' if version >= 16 else ''), 'invalid policy fields')
    require(integer(policy['turn']) and policy['turn'] >= 0 and text(policy['phase']), 'invalid turn/phase')
    players = policy['players']
    require(isinstance(players, list) and 0 < len(players) <= 16, 'invalid player prefix')
    refs = []
    for index, player in enumerate(players):
        fields(player, 'entityRef life handCount libraryCount floatingManaColors', 'invalid policy player')
        ref = 'p' + str(index)
        require(player['entityRef'] == ref, 'nonlocal or out-of-order player reference')
        refs.append(ref)
        require(integer(player['life']), 'invalid life total')
        require(all(integer(player[k]) and player[k] >= 0 for k in ('handCount', 'libraryCount')), 'invalid zone count')
        mana = player['floatingManaColors']
        require(isinstance(mana, list) and len(mana) <= 128
                and all(integer(color) and color in MANA_COLORS for color in mana), 'invalid mana prefix')
    bindings = diagnostic['playerBindings']
    require(len(bindings) == len(refs), 'player binding count mismatch')
    player_ids = set()
    chooser_ref = None
    for ref, binding in zip(refs, bindings):
        fields(binding, 'entityRef id', 'invalid player binding fields')
        require(binding['entityRef'] == ref and integer(binding['id']) and binding['id'] not in player_ids,
                'invalid player binding identity')
        if known_players is not None: require(binding['id'] in known_players, 'unknown bound player')
        player_ids.add(binding['id'])
        if binding['id'] == chooser_id: chooser_ref = ref
    require(chooser_ref is not None and policy['chooser'] == chooser_ref, 'policy chooser binding mismatch')
    require(policy['turnPlayer'] is None or policy['turnPlayer'] in refs, 'unknown policy turn player')
    cards = policy['cards']
    require(isinstance(cards, list) and len(cards) <= 128, 'invalid card prefix')
    card_refs = []
    for index, card in enumerate(cards):
        fields(card, 'entityRef owner controller zone tapped faceDown identityKnown name types manaCost power toughness counters',
               'invalid policy card fields')
        ref = 'c' + str(index)
        require(card['entityRef'] == ref, 'nonlocal or out-of-order card reference')
        card_refs.append(ref)
        require(all(card[k] is None or card[k] in refs for k in ('owner', 'controller')), 'unknown card player reference')
        require(card['zone'] in VIEW_ZONES, 'unsupported card zone')
        require(all(isinstance(card[k], bool) for k in ('tapped', 'faceDown', 'identityKnown')), 'invalid card flags')
        require(text(card['name']) if card['identityKnown'] else card['name'] is None, 'hidden identity/name mismatch')
        require(card['identityKnown'] or card['faceDown'], 'face-up identity unavailable')
        require(text(card['types']) and text(card['manaCost']), 'invalid public face text')
        require(all(card[k] is None or integer(card[k]) for k in ('power', 'toughness')), 'invalid public face P/T')
        counters = card['counters']
        require(isinstance(counters, dict) and len(counters) <= 32
                and all(text(key) and integer(count) and count >= 0 for key, count in counters.items()), 'invalid counters')
    bindings = diagnostic['cardBindings']
    require(len(bindings) == len(card_refs), 'card binding count mismatch')
    card_ids = set()
    for ref, binding in zip(card_refs, bindings):
        fields(binding, 'entityRef id gameTimestamp', 'invalid card binding fields')
        require(binding['entityRef'] == ref and integer(binding['id']) and integer(binding['gameTimestamp']),
                'invalid card binding identity')
        identity = (binding['id'], binding['gameTimestamp'])
        require(identity not in card_ids, 'duplicate card incarnation binding')
        card_ids.add(identity)
    facts = policy['disclosedFacts']
    require(isinstance(facts, list) and len(facts) <= 32, 'invalid disclosed prefix')
    for index, fact in enumerate(facts):
        fields(fact, 'entityRef source scope zone presentedFace name types manaCost currentObjectAssociation',
               'invalid disclosure fields')
        require(fact['entityRef'] == 'd' + str(index), 'nonlocal or out-of-order disclosure reference')
        require(fact['source'] in ('CONTROLLER_CARD_REVEAL', 'CONTROLLER_VIEW_REVEAL')
                and fact['scope'] in ('STACK_RESOLUTION', 'CURRENT_PROMPT'), 'invalid disclosure provenance')
        require(fact['zone'] is None or fact['zone'] in ZONES, 'invalid disclosed zone')
        require(fact['presentedFace'] in FACES, 'invalid presented face')
        require(all(text(fact[k]) for k in ('name', 'types', 'manaCost')), 'invalid disclosed face text')
        association = fact['currentObjectAssociation']
        require(association == 'UNRESOLVED' or version >= 4 and isinstance(association, str)
                and association in card_refs, 'unsupported disclosure object association')
        if association != 'UNRESOLVED':
            card = cards[card_refs.index(association)]
            require(fact['source'] == 'CONTROLLER_CARD_REVEAL' and value['stateBasis'] == 'RULES_STATE'
                    and card['zone'] in ('Battlefield', 'Command', 'Graveyard', 'Exile', 'Stack')
                    and not card['faceDown'] and card['identityKnown'] and fact['zone'] == card['zone']
                    and all(fact[key] == card[key] for key in ('name', 'types', 'manaCost')), 'invalid public disclosure association')
            require(sum(all(other[key] == field for key, field in card.items() if key != 'entityRef') for other in cards) == 1
                    and not any(item['component'] in ('ENTITIES', 'PLAYER_VIEW', 'TEXT', 'COUNTERS') for item in omissions),
                    'ambiguous or incomplete disclosure association')
        require(coverage['disclosures'] == fact['scope'], 'disclosure scope/coverage mismatch')
    if version >= 2:
        validate_stack(policy['stack'], diagnostic['stackBindings'], players, cards, omissions, value['stateBasis'])
    if version >= 3:
        validate_prompt(policy['prompt'], coverage['prompt'], refs, omissions, value['stateBasis'], version)
    if version >= 16:
        _parameters.projection(policy['receivedParameters'], coverage['receivedParameters'], refs, cards, omissions, parameters_allowed)
    if version >= 4:
        validate_option_links(diagnostic['optionBindings'], refs, cards)


def validate_option_links(links, players, cards):
    fields(links, 'coverage items truncated', 'invalid option link fields')
    require(links['coverage'] in ('UNSUPPORTED', 'UNAVAILABLE', 'DIAGNOSTIC_PUBLIC_CURRENT_V1')
            and isinstance(links['items'], list) and len(links['items']) <= 256 and isinstance(links['truncated'], bool), 'invalid option links')
    if links['coverage'] != 'DIAGNOSTIC_PUBLIC_CURRENT_V1':
        require(links['items'] == [] and links['truncated'] == (links['coverage'] == 'UNAVAILABLE'), 'invalid absent option links')
    public = {card['entityRef'] for card in cards if card['zone'] in ('Battlefield', 'Command', 'Graveyard', 'Stack')
              or card['zone'] == 'Exile' and not card['faceDown']}
    for index, link in enumerate(links['items']):
        fields(link, 'optionIndex entityRef', 'invalid option link')
        require(integer(link['optionIndex']) and link['optionIndex'] == index, 'invalid option link ordinal')
        require(link['entityRef'] is None or isinstance(link['entityRef'], str)
                and link['entityRef'] in public | set(players), 'private or unknown option link')


def validate_moving_zones(branch, destination, origins):
    require(branch != 'HEIST' or destination == 'Exile' and origins == ['Library'], 'invalid Heist zones')
    require(branch != 'DRAFT' or destination == 'None' and origins == [], 'invalid Draft zones')


def validate_zone_branch(branch, version):
    require(isinstance(branch, str) and branch in ZONE_BRANCHES, 'invalid zone operation branch')
    require(branch == 'ORDINARY_HIDDEN_ORIGIN' or version >= 11, 'new operation branch requires V11')
    require(branch not in ('HEIST', 'DRAFT') or version >= 12, 'moving operation branch requires V12')


def validate_prompt(prompt, coverage, player_refs, omissions, state_basis, version):
    permitted = {'CONTROLLER_REQUEST_V1'} | ({'RECEIVED_MODE_BOUNDS_V1'} if version >= 5 else set())
    if version >= 6: permitted.add('RECEIVED_ENTITY_BOUNDS_V1')
    if version >= 7: permitted.add('RECEIVED_ENTITY_SEQUENCE_V1')
    if version >= 8: permitted.add('RECEIVED_ZONE_REQUEST_V1')
    if version >= 9: permitted.add('RECEIVED_ZONE_OPERATION_V1')
    if version >= 13: permitted.add('RECEIVED_SPELL_OPERATION_V1')
    if version >= 14: permitted.add('RECEIVED_SPELL_REQUEST_V1')
    if version >= 15: permitted.add('RECEIVED_SPELL_PROGRESS_V1')
    if version >= 10: permitted.add('RECEIVED_ZONE_PROGRESS_V1')
    require(coverage in permitted | {'UNSUPPORTED', 'UNAVAILABLE'}, 'invalid prompt coverage')
    require((prompt is not None) == (coverage in permitted), 'prompt coverage/policy mismatch')
    if prompt is None:
        require(coverage != 'UNAVAILABLE' or any(item['component'] == 'PROMPT' for item in omissions),
                'unavailable prompt has no omission')
        return
    require(not any(item['component'] == 'PROMPT' for item in omissions), 'captured prompt has an omission')
    require(isinstance(prompt, dict), 'invalid typed prompt')
    kind = prompt.get('kind')
    mode = kind == 'MODE_BOUNDS'
    entities = kind == 'ENTITY_BOUNDS'
    sequence = kind == 'ENTITY_PICK_SEQUENCE'
    zone = kind == 'ZONE_CHANGE_REQUEST'
    zone_progress = kind == 'ZONE_PICK_PROGRESS'
    operation = kind == 'ZONE_CHANGE_OPERATION_REQUEST'
    spell_operation = kind == 'SPELL_ABILITY_OPERATION_REQUEST'
    spell_request = kind == 'SPELL_ABILITY_REQUEST'
    spell_progress = kind == 'SPELL_ABILITY_PICK_PROGRESS'
    require(state_basis == 'RULES_STATE' or (mode and version >= 5 or entities and version >= 6 or sequence and version >= 7 or zone and version >= 8 or operation and version >= 9 or zone_progress and version >= 10 or spell_operation and version >= 13 or spell_request and version >= 14 or spell_progress and version >= 15) and state_basis == 'PLANNING_WORKING_STATE',
            'planning prompt claims committed request arguments')
    require(coverage == ('RECEIVED_MODE_BOUNDS_V1' if mode else 'RECEIVED_ENTITY_BOUNDS_V1' if entities else 'RECEIVED_ENTITY_SEQUENCE_V1' if sequence else 'RECEIVED_ZONE_REQUEST_V1' if zone else 'RECEIVED_ZONE_OPERATION_V1' if operation else 'RECEIVED_ZONE_PROGRESS_V1' if zone_progress else 'RECEIVED_SPELL_OPERATION_V1' if spell_operation else 'RECEIVED_SPELL_REQUEST_V1' if spell_request else 'RECEIVED_SPELL_PROGRESS_V1' if spell_progress else 'CONTROLLER_REQUEST_V1'), 'prompt kind/coverage mismatch')
    if spell_progress:
        require(version >= 15, 'spell progress requires V15')
        fields(prompt, 'kind affectedPlayer requestedSelections attemptIndex priorNonNullReturnCount retainedCount previousReturns', 'invalid spell progress prompt fields')
        require(prompt['affectedPlayer'] is None or isinstance(prompt['affectedPlayer'], str) and prompt['affectedPlayer'] in player_refs, 'unknown affected player')
        require(integer(prompt['requestedSelections']) and -(1 << 31) <= prompt['requestedSelections'] < (1 << 31), 'invalid spell request')
        require(all(integer(prompt[k]) and 0 <= prompt[k] <= (1 << 63)-1 for k in ('attemptIndex','priorNonNullReturnCount'))
                and integer(prompt['retainedCount']) and 0 <= prompt['retainedCount'] < (1 << 31)
                and prompt['attemptIndex'] == prompt['priorNonNullReturnCount'] == prompt['retainedCount'], 'invalid complete spell progress')
        require(isinstance(prompt['previousReturns'], list) and len(prompt['previousReturns']) <= 64
                and len(prompt['previousReturns']) == prompt['priorNonNullReturnCount']
                and all(ref is None for ref in prompt['previousReturns']), 'spell receipt identities are not permitted features')
    elif spell_request:
        require(version >= 14, 'spell request requires V14')
        fields(prompt, 'kind affectedPlayer', 'invalid single spell request fields')
        require(prompt['affectedPlayer'] is None or isinstance(prompt['affectedPlayer'], str) and prompt['affectedPlayer'] in player_refs, 'unknown affected player')
    elif spell_operation:
        require(version >= 13, 'spell operation requires V13')
        fields(prompt, 'kind requestedSelections', 'invalid spell operation request fields')
        require(integer(prompt['requestedSelections']) and -(1 << 31) <= prompt['requestedSelections'] < (1 << 31), 'invalid spell selection request')
    elif operation:
        require(version >= 9, 'zone operation requires V9')
        fields(prompt, 'kind requestedIterations destination origins sourcePlayer decider' + (' branch' if version >= 11 else ''), 'invalid operation request fields')
        if version >= 11: validate_zone_branch(prompt['branch'], version)
        require(integer(prompt['requestedIterations']) and -(1 << 31) <= prompt['requestedIterations'] < (1 << 31), 'invalid operation iteration request')
        require(isinstance(prompt['destination'], str) and prompt['destination'] in ZONES
                and isinstance(prompt['origins'], list) and len(prompt['origins']) <= len(ZONES)
                and all(isinstance(z, str) and z in ZONES for z in prompt['origins']), 'invalid operation request zones')
        require(all(prompt[k] is None or isinstance(prompt[k], str) and prompt[k] in player_refs for k in ('sourcePlayer','decider')), 'unknown operation request role')
    elif zone or zone_progress:
        require(version >= 8, 'zone request requires V8')
        fields(prompt, 'kind minimumPicks maximumPicks optional destination origins decider' + (' sourcePlayer requestedIterations attemptIndex loopPosition priorNonNullReturnCount retainedCount previousReturns' + (' branch' if version >= 11 else '') if zone_progress else ''), 'invalid zone request fields')
        require(isinstance(prompt['optional'], bool) and integer(prompt['minimumPicks']) and integer(prompt['maximumPicks'])
                and prompt['minimumPicks'] == (0 if prompt['optional'] else 1) and prompt['maximumPicks'] == 1, 'invalid zone pick constraints')
        require(isinstance(prompt['destination'], str) and prompt['destination'] in ZONES
                and isinstance(prompt['origins'], list) and len(prompt['origins']) <= len(ZONES)
                and all(isinstance(origin, str) and origin in ZONES for origin in prompt['origins']), 'invalid request zones')
        require(prompt['decider'] is None or isinstance(prompt['decider'], str) and prompt['decider'] in player_refs, 'unknown request decider')
        if zone_progress:
            require(version >= 10 and (prompt['sourcePlayer'] is None or isinstance(prompt['sourcePlayer'], str) and prompt['sourcePlayer'] in player_refs), 'invalid progress source player')
            if version >= 11: validate_zone_branch(prompt['branch'], version)
            require(integer(prompt['requestedIterations']) and -(1 << 31) <= prompt['requestedIterations'] < (1 << 31), 'invalid progress iteration request')
            require(all(integer(prompt[k]) and 0 <= prompt[k] <= (1 << 63)-1 for k in ('attemptIndex','priorNonNullReturnCount'))
                    and prompt['priorNonNullReturnCount'] <= prompt['attemptIndex'], 'invalid progress long counters')
            require(all(integer(prompt[k]) and 0 <= prompt[k] < (1 << 31) for k in ('loopPosition','retainedCount')), 'invalid engine progress counts')
            require(isinstance(prompt['previousReturns'],list) and len(prompt['previousReturns']) <= 64
                    and len(prompt['previousReturns']) == prompt['priorNonNullReturnCount']
                    and all(ref is None or isinstance(ref,str) for ref in prompt['previousReturns']), 'invalid previous return vector')
    elif sequence:
        require(version >= 7, 'selection history requires V7')
        fields(prompt, 'kind minimumPicks maximumPicks optional relatedPlayer minimumTotal maximumTotal priorSelectionCount previousSelections', 'invalid sequence prompt fields')
        require(isinstance(prompt['optional'], bool) and integer(prompt['minimumPicks']) and integer(prompt['maximumPicks'])
                and prompt['minimumPicks'] == (0 if prompt['optional'] else 1) and prompt['maximumPicks'] == 1, 'invalid sequence local constraints')
        require(all(integer(prompt[k]) and -(1 << 31) <= prompt[k] < (1 << 31) for k in ('minimumTotal', 'maximumTotal')), 'invalid sequence total bounds')
        require(integer(prompt['priorSelectionCount']) and 0 <= prompt['priorSelectionCount'] <= 64
                and isinstance(prompt['previousSelections'], list) and len(prompt['previousSelections']) == prompt['priorSelectionCount'], 'invalid sequence history count')
        require(all(ref is None or isinstance(ref, str) for ref in prompt['previousSelections']), 'invalid sequence local references')
    elif mode or entities:
        require(version >= (5 if mode else 6), 'bounds require their declared profile')
        fields(prompt, 'kind minimumPicks maximumPicks ' + ('allowRepeat' if mode else 'relatedPlayer'), 'invalid received bounds fields')
        require(all(integer(prompt[key]) and -(1 << 31) <= prompt[key] < (1 << 31) for key in ('minimumPicks', 'maximumPicks'))
                and (not mode or isinstance(prompt['allowRepeat'], bool)), 'invalid received bounds')
    elif kind == 'NUMBER_RANGE':
        fields(prompt, 'kind minimum maximum', 'invalid range prompt fields')
        require(integer(prompt['minimum']) and integer(prompt['maximum']), 'invalid request range')
    elif kind == 'NUMBER_OPTIONS':
        fields(prompt, 'kind values relatedPlayer', 'invalid number options prompt fields')
        require(isinstance(prompt['values'], list) and len(prompt['values']) <= 64
                and all(integer(value) for value in prompt['values']), 'invalid request numeric list')
    elif kind == 'SINGLE_ENTITY':
        fields(prompt, 'kind minimumPicks maximumPicks optional relatedPlayer', 'invalid single entity prompt fields')
        require(isinstance(prompt['optional'], bool) and integer(prompt['minimumPicks']) and integer(prompt['maximumPicks'])
                and prompt['minimumPicks'] == (0 if prompt['optional'] else 1) and prompt['maximumPicks'] == 1,
                'invalid single entity pick constraints')
    else:
        require(False, 'unsupported typed prompt kind')
    if (operation or zone_progress) and version >= 12:
        validate_moving_zones(prompt['branch'], prompt['destination'], prompt['origins'])
    if 'relatedPlayer' in prompt:
        require(prompt['relatedPlayer'] is None or prompt['relatedPlayer'] in player_refs, 'unknown request player')


def validate_stack(stack, bindings, players, cards, omissions, state_basis):
    fields(stack, 'entries truncated isResolving resolving resolvingCoverage', 'invalid stack fields')
    entries = stack['entries']
    require(isinstance(entries, list) and len(entries) <= 8 and isinstance(stack['truncated'], bool), 'invalid stack prefix')
    require(isinstance(bindings, list) and len(bindings) == len(entries), 'invalid stack bindings')
    missing = {item['reason'] for item in omissions if item['component'] == 'STACK'}
    require(not stack['truncated'] or 'OUTSIDE_CAPTURED_PREFIX' in missing, 'stack prefix has no omission')
    player_refs = {p['entityRef'] for p in players}
    public_cards = {c['entityRef']: c for c in cards if c['zone'] in ('Battlefield', 'Command', 'Graveyard', 'Stack')
                    or c['zone'] == 'Exile' and not c['faceDown']}
    stack_refs = {'s' + str(i) for i in range(len(entries))}
    ids = set()
    for i, (entry, binding) in enumerate(zip(entries, bindings)):
        ref = 's' + str(i)
        fields(binding, 'entityRef id abilityId', 'invalid stack binding fields')
        require(binding['entityRef'] == ref and integer(binding['id']) and integer(binding['abilityId'])
                and binding['id'] not in ids, 'invalid stack binding identity')
        ids.add(binding['id'])
        fields(entry, 'entityRef kind controller source rootX targets targetsCoverage', 'invalid stack entry fields')
        require(entry['entityRef'] == ref and entry['kind'] in ('SPELL', 'ABILITY'), 'invalid stack entry')
        require(entry['controller'] is None or isinstance(entry['controller'], str) and entry['controller'] in player_refs, 'unknown stack controller')
        require(entry['controller'] is not None or 'UNRESOLVED_BINDING' in missing, 'missing controller has no omission')
        source = entry['source']
        require(source is None or isinstance(source, str) and source in public_cards, 'private or unresolved stack source')
        known = source is not None and public_cards[source]['identityKnown']
        if not known:
            require(entry['rootX'] is None and entry['targets'] is None, 'hidden source leaks ability details')
        if state_basis != 'RULES_STATE':
            require(entry['rootX'] is None and entry['targets'] is None, 'planning stack claims committed parameters')
        require(entry['rootX'] is None or integer(entry['rootX']), 'invalid stack X value')
        targets = entry['targets']
        require(entry['targetsCoverage'] == ('UNAVAILABLE' if targets is None else 'ROOT_TARGETS_ONLY'), 'invalid root target coverage')
        if targets is None:
            require(bool(missing), 'unavailable targets have no omission')
            continue
        require(isinstance(targets, list) and len(targets) <= 16, 'invalid root target prefix')
        for target in targets:
            fields(target, 'entityRef dividedAmount', 'invalid stack target fields')
            require(isinstance(target['entityRef'], str) and target['entityRef'] in player_refs | public_cards.keys() | stack_refs, 'unknown or private stack target')
            require(target['dividedAmount'] is None or integer(target['dividedAmount']), 'invalid target allocation')
    require(isinstance(stack['isResolving'], bool), 'invalid resolving flag')
    resolving = stack['resolving']
    require(resolving is None or isinstance(resolving, str) and resolving in stack_refs, 'unknown resolving entry')
    require(stack['resolvingCoverage'] == ('UNAVAILABLE' if stack['isResolving'] and resolving is None else 'CAPTURED'), 'invalid resolving coverage')
    require(stack['isResolving'] or resolving is None, 'inactive resolving entry')
    require(not stack['isResolving'] or resolving is not None or 'UNRESOLVED_BINDING' in missing, 'missing resolving omission')
