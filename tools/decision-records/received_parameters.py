"""Typed request arguments at two audited callbacks; no option-pool permission."""

SHAPES = {
    'NONE': set(), 'VOTER': {'voter'},
    'COUNTER_AMOUNT': {'target', 'counterType'}, 'COUNTER_TRANSFER_AMOUNT': {'source', 'target', 'counterType'},
    'ENTITY_CARD_ROLE': {'role', 'entity'}, 'ENTITY_TARGET_ROLE': {'target'},
    'ENTITY_MANA_VALUE_LIMIT': {'manaValueLimit'}, 'ENTITY_COUNTER_REQUEST': {'counterType', 'amount', 'target'},
}
NUMBER_KINDS = {'NONE', 'VOTER', 'COUNTER_AMOUNT', 'COUNTER_TRANSFER_AMOUNT'}
ENTITY_KINDS = set(SHAPES) - NUMBER_KINDS | {'NONE'}


def require(ok, message):
    if not ok: raise ValueError('received parameters: ' + message)


def integer(value): return isinstance(value, int) and not isinstance(value, bool)


def roles(kind):
    if kind == 'VOTER': return {'voter': {'PLAYER'}}
    if kind == 'ENTITY_CARD_ROLE': return {'entity': {'CARD'}}
    return {key: {'CARD', 'PLAYER'} for key in SHAPES[kind] & {'source', 'target'}}


def scalars(kind, values):
    if 'counterType' in values: require(isinstance(values['counterType'], str) and len(values['counterType']) <= 256, 'invalid counter type name')
    if kind == 'ENTITY_CARD_ROLE': require(values['role'] in ('Attach', 'Attacker', 'Partner'), 'invalid card role')
    for key in ('amount', 'manaValueLimit'):
        if key in values:
            require(key == 'amount' and values[key] is None or integer(values[key]) and -(1 << 31) <= values[key] < (1 << 31), 'invalid received integer')


def receipt(value, family, typed_reference):
    require(isinstance(value, dict) and set(value) == {'schemaVersion','coverage','kind','values','reason'}
            and integer(value['schemaVersion']) and value['schemaVersion'] == 1, 'invalid diagnostic fields')
    kind = value['kind']; allowed = NUMBER_KINDS if family == 'NUMBER' else ENTITY_KINDS
    if value['coverage'] == 'UNSUPPORTED':
        require(kind is None and value['values'] is None and value['reason'] == 'UNSUPPORTED', 'unsupported shape carries values'); return
    require(isinstance(kind, str) and kind in allowed, 'unknown or wrong-family shape')
    if value['coverage'] == 'UNAVAILABLE':
        require(value['values'] is None and value['reason'] == 'OUTSIDE_CAPTURED_PREFIX', 'invalid bounded refusal'); return
    require(value['coverage'] == 'COMPLETE' and value['reason'] is None
            and isinstance(value['values'], dict) and set(value['values']) == SHAPES[kind], 'invalid complete shape')
    values = value['values']; scalars(kind, values)
    for key, kinds in roles(kind).items():
        ref = values[key]
        if kind == 'ENTITY_COUNTER_REQUEST' and ref is None: continue
        require(isinstance(ref, dict) and ref.get('type') in kinds, 'invalid received entity type')
        typed_reference(ref, ref['type'])


def projection(value, coverage, player_refs, cards, omissions, allowed):
    require(coverage in ('RECEIVED_CALLBACK_PARAMETERS_V1','UNSUPPORTED','UNAVAILABLE'), 'unknown capability')
    if not allowed: require(coverage == 'UNSUPPORTED' and value is None, 'parameters at unaudited boundary')
    require((value is not None) == (coverage == 'RECEIVED_CALLBACK_PARAMETERS_V1'), 'capability/projection mismatch')
    if value is None:
        if coverage == 'UNAVAILABLE': require(any(o['component'] == 'PARAMETERS' for o in omissions), 'unavailable component lacks reason')
        return
    require(isinstance(value, dict) and isinstance(value.get('kind'), str) and value['kind'] in SHAPES, 'unknown shape')
    kind = value['kind']; require(set(value) == {'kind'} | SHAPES[kind], 'invalid projected fields'); scalars(kind, value)
    public = {c['entityRef'] for c in cards if c['zone'] in ('Battlefield','Command','Graveyard','Stack') or c['zone'] == 'Exile' and not c['faceDown']}
    for key, kinds in roles(kind).items():
        ref = value[key]
        if kind == 'ENTITY_COUNTER_REQUEST' and ref is None: continue
        require(isinstance(ref, str) and ('PLAYER' in kinds and ref in player_refs or 'CARD' in kinds and ref in public), 'private or unresolved received role')


def arguments(choice):
    context = choice['inputContext']; value = (context['policy'] or {}).get('receivedParameters')
    if context['policy'] is None: return
    received = choice['options']['callbackParameters']
    require(context['coverage']['receivedParameters'] != 'UNSUPPORTED', 'audited parameter capability silently absent')
    if value is None:
        reasons = {o['reason'] for o in context['omissions'] if o['component'] == 'PARAMETERS'}
        require(('UNRESOLVED_BINDING' in reasons and any(received['values'][key] is not None for key in roles(received['kind']))) if received['coverage'] == 'COMPLETE' else received['reason'] in reasons, 'parameter refusal differs from receipt')
        return
    require(received['coverage'] == 'COMPLETE' and value['kind'] == received['kind'], 'unsupported/different received shape claims features')
    diagnostic = context['diagnostics']; kind = value['kind']
    bindings = {b['entityRef']: dict(type='PLAYER', id=b['id']) for b in diagnostic['playerBindings']}
    bindings.update({b['entityRef']: dict(type='CARD', id=b['id'], gameTimestamp=b['gameTimestamp']) for b in diagnostic['cardBindings']})
    for key, raw in received['values'].items():
        if key in roles(kind):
            require((None if value[key] is None else bindings[value[key]]) == raw, 'received role identity/incarnation differs')
        else: require(value[key] == raw, 'received scalar differs')
