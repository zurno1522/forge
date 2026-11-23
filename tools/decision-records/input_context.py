"""Versioned, partial player-view inputs. Only ``policy`` is a feature projection.

Diagnostic joins and the owning record's offered pool are intentionally outside it.
Validation checks the producer contract; it cannot reconstruct permission grants
from a privileged trace or prove that an uninstrumented heuristic used this view.
"""

PROFILE = 'PERMITTED_CHOICE_VIEW_V1'
PROFILES = {1: PROFILE, 2: 'PERMITTED_CHOICE_VIEW_V2', 3: 'PERMITTED_CHOICE_VIEW_V3'}
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


def validate(value, chooser_id, known_players=None):
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
    fields(coverage, 'playerView stack disclosures history options' + (' prompt' if version >= 3 else ''), 'invalid coverage fields')
    require(coverage['playerView'] in ('PERMITTED_CURRENT_FIELDS_V1', 'UNAVAILABLE'), 'unknown player-view coverage')
    require(coverage['stack'] in ({'UNSUPPORTED'} if version == 1 else {'PUBLIC_ROOT_STACK_V1', 'UNAVAILABLE'}) and coverage['history'] == 'INCOMPLETE'
            and coverage['options'] == 'DIAGNOSTIC_ONLY', 'unsupported coverage claim')
    require(coverage['disclosures'] in ('STACK_RESOLUTION', 'CURRENT_PROMPT', 'UNSUPPORTED_PRIOR_SCOPE', 'UNAVAILABLE'),
            'unknown disclosure coverage')
    omissions = value['omissions']
    require(isinstance(omissions, list) and len(omissions) <= 16, 'invalid omissions')
    for omission in omissions:
        fields(omission, 'component reason', 'invalid omission fields')
        require(omission['component'] in COMPONENTS | ({'STACK'} if version >= 2 else set()) | ({'PROMPT'} if version >= 3 else set())
                and omission['reason'] in REASONS, 'unknown omission')
    require(bool(omissions) == (value['status'] != 'CAPTURED'), 'capture status/omission mismatch')
    diagnostic = value['diagnostics']
    fields(diagnostic, 'playerBindings cardBindings' + (' stackBindings' if version >= 2 else ''), 'invalid diagnostic fields')
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
        return
    if version >= 2: require(coverage['stack'] == 'PUBLIC_ROOT_STACK_V1', 'stack coverage/policy mismatch')
    require(perspective['controllingPlayerId'] == chooser_id, 'unsupported controlled perspective has policy')
    fields(policy, 'turn phase turnPlayer chooser players cards disclosedFacts' + (' stack' if version >= 2 else '')
           + (' prompt' if version >= 3 else ''), 'invalid policy fields')
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
        require(fact['currentObjectAssociation'] == 'UNRESOLVED', 'unsupported disclosure object association')
        require(coverage['disclosures'] == fact['scope'], 'disclosure scope/coverage mismatch')
    if version >= 2:
        validate_stack(policy['stack'], diagnostic['stackBindings'], players, cards, omissions, value['stateBasis'])
    if version >= 3:
        validate_prompt(policy['prompt'], coverage['prompt'], refs, omissions, value['stateBasis'])


def validate_prompt(prompt, coverage, player_refs, omissions, state_basis):
    require(coverage in ('CONTROLLER_REQUEST_V1', 'UNSUPPORTED', 'UNAVAILABLE'), 'invalid prompt coverage')
    require((prompt is not None) == (coverage == 'CONTROLLER_REQUEST_V1'), 'prompt coverage/policy mismatch')
    if prompt is None:
        require(coverage != 'UNAVAILABLE' or any(item['component'] == 'PROMPT' for item in omissions),
                'unavailable prompt has no omission')
        return
    require(state_basis == 'RULES_STATE', 'planning prompt claims committed request arguments')
    require(not any(item['component'] == 'PROMPT' for item in omissions), 'captured prompt has an omission')
    require(isinstance(prompt, dict), 'invalid typed prompt')
    kind = prompt.get('kind')
    if kind == 'NUMBER_RANGE':
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
