"""Typed request arguments at audited callbacks; no option-pool permission."""

SHAPES = {
    'EXILE_RANKING_REQUEST': {'requestedSelections','effect'},
    'DISCARD_PICK_REQUEST': {'strategy','effectiveMinimum','maximum','priorSelectionCount','previousSelections'},
    'DISCARD_BATCH_REQUEST': {'branch','minimum','maximum','manaValue'},
    'SACRIFICE_PICK_REQUEST': {'requestedSelections','priorSelectionCount','previousSelections'},
    'CARD_PAYMENT_REQUEST': {'paymentKind','minimum','maximum'},
    'MANA_SOURCE_REQUEST': {'shard','remainingManaValue','checkCosts','effect'},
    'MANA_POOL_REQUEST': {'testPayment','shard','colorsPaid'},
    'NONE': set(), 'VOTER': {'voter'}, 'TRIGGER_REQUIREMENT': {'mandatory'},
    'COUNTER_AMOUNT': {'target', 'counterType'}, 'COUNTER_TRANSFER_AMOUNT': {'source', 'target', 'counterType'},
    'ENTITY_CARD_ROLE': {'role', 'entity'}, 'ENTITY_TARGET_ROLE': {'target'},
    'ENTITY_ATTACHMENT_ROLES': {'entities'}, 'ENTITY_ATTACKER_ROLES': {'entities'},
    'ENTITY_MANA_VALUE_LIMIT': {'manaValueLimit'}, 'ENTITY_COUNTER_REQUEST': {'counterType', 'amount', 'target'},
}
NUMBER_KINDS = {'NONE', 'VOTER', 'COUNTER_AMOUNT', 'COUNTER_TRANSFER_AMOUNT'}
ENTITY_KINDS = {'NONE', 'ENTITY_CARD_ROLE', 'ENTITY_TARGET_ROLE', 'ENTITY_MANA_VALUE_LIMIT', 'ENTITY_COUNTER_REQUEST', 'ENTITY_ATTACHMENT_ROLES', 'ENTITY_ATTACKER_ROLES'}
TRIGGER_KINDS = {'TRIGGER_REQUIREMENT'}
MANA_KINDS = {'MANA_POOL_REQUEST'}
MANA_SOURCE_KINDS = {'MANA_SOURCE_REQUEST'}
CARD_PAYMENT_KINDS = {'CARD_PAYMENT_REQUEST'}
SACRIFICE_PICK_KINDS = {'SACRIFICE_PICK_REQUEST'}
DISCARD_BATCH_KINDS = {'DISCARD_BATCH_REQUEST'}
DISCARD_BRANCHES = {'POLICY','COMBO','SHAPESHIFTER','MANA_VALUE','UNCASTABLE_EXCESS'}
DISCARD_PICK_KINDS = {'DISCARD_PICK_REQUEST'}
DISCARD_STRATEGIES = {'PREFERENCE','FALLBACK'}
EXILE_RANKING_KINDS = {'EXILE_RANKING_REQUEST'}
MIN_PROFILE = {'EXILE_RANKING_REQUEST': 25, 'DISCARD_PICK_REQUEST': 24, 'DISCARD_BATCH_REQUEST': 23, 'SACRIFICE_PICK_REQUEST': 22, 'CARD_PAYMENT_REQUEST': 21, 'MANA_SOURCE_REQUEST': 20, 'MANA_POOL_REQUEST': 19, 'TRIGGER_REQUIREMENT': 17, 'ENTITY_ATTACHMENT_ROLES': 18, 'ENTITY_ATTACKER_ROLES': 18}
ROLE_LIST_KINDS = {'ENTITY_ATTACHMENT_ROLES', 'ENTITY_ATTACKER_ROLES'}
MAX_ROLE_LIST = 64
MANA_SHARDS = {'B2', 'BG', 'BGP', 'BLACK', 'BLUE', 'BP', 'BR', 'BRP', 'CB', 'CG', 'COLORED_X', 'COLORLESS', 'CR', 'CU', 'CW', 'G2', 'GENERIC', 'GP', 'GREEN', 'GU', 'GUP', 'GW', 'GWP', 'R2', 'RED', 'RG', 'RGP', 'RP', 'RW', 'RWP', 'S', 'U2', 'UB', 'UBP', 'UP', 'UR', 'URP', 'W2', 'WB', 'WBP', 'WHITE', 'WP', 'WU', 'WUP', 'X'}


def require(ok, message):
    if not ok: raise ValueError('received parameters: ' + message)


def integer(value): return isinstance(value, int) and not isinstance(value, bool)


def roles(kind):
    if kind == 'VOTER': return {'voter': {'PLAYER'}}
    if kind == 'ENTITY_CARD_ROLE': return {'entity': {'CARD'}}
    return {key: {'CARD', 'PLAYER'} for key in SHAPES[kind] & {'source', 'target'}}


def list_roles(kind): return {'previousSelections'} if kind in ('SACRIFICE_PICK_REQUEST','DISCARD_PICK_REQUEST') else {'entities'} if kind in ROLE_LIST_KINDS else set()

def role_array(value):
    require(isinstance(value, list) and len(value) <= MAX_ROLE_LIST, 'invalid whole role list')


def scalars(kind, values):
    if kind == 'EXILE_RANKING_REQUEST':
        require(integer(values['requestedSelections']) and -(1 << 31) <= values['requestedSelections'] < (1 << 31) and isinstance(values['effect'],bool), 'invalid exile ranking request')
    if kind == 'DISCARD_PICK_REQUEST':
        require(isinstance(values['strategy'],str) and values['strategy'] in DISCARD_STRATEGIES
                and all(integer(values[k]) and -(1 << 31) <= values[k] < (1 << 31) for k in ('effectiveMinimum','maximum','priorSelectionCount'))
                and 0 <= values['priorSelectionCount'] < values['effectiveMinimum']
                and isinstance(values['previousSelections'],list) and len(values['previousSelections']) == values['priorSelectionCount'], 'invalid discard progress')
    if kind == 'DISCARD_BATCH_REQUEST':
        require(isinstance(values['branch'], str) and values['branch'] in DISCARD_BRANCHES
                and all(integer(values[k]) and -(1 << 31) <= values[k] < (1 << 31) for k in ('minimum','maximum')), 'invalid discard batch request')
        require(integer(values['manaValue']) and -(1 << 31) <= values['manaValue'] < (1 << 31) if values['branch']=='MANA_VALUE' else values['manaValue'] is None, 'invalid branch mana value')
    if kind == 'SACRIFICE_PICK_REQUEST':
        require(integer(values['requestedSelections']) and integer(values['priorSelectionCount'])
                and 0 <= values['priorSelectionCount'] < values['requestedSelections'] < (1 << 31)
                and isinstance(values['previousSelections'], list) and len(values['previousSelections']) == values['priorSelectionCount'], 'invalid sacrifice progress')
    if kind == 'CARD_PAYMENT_REQUEST':
        require(values['paymentKind'] in ('DISCARD','SACRIFICE') and all(integer(values[key]) and -(1 << 31) <= values[key] < (1 << 31) for key in ('minimum','maximum')), 'invalid card payment request')
    if kind == 'MANA_SOURCE_REQUEST':
        require(isinstance(values['shard'], str) and values['shard'] in MANA_SHARDS
                and integer(values['remainingManaValue']) and -(1 << 31) <= values['remainingManaValue'] < (1 << 31)
                and isinstance(values['checkCosts'], bool) and isinstance(values['effect'], bool), 'invalid source request')
    if kind == 'MANA_POOL_REQUEST':
        require(isinstance(values['testPayment'], bool) and isinstance(values['shard'], str) and values['shard'] in MANA_SHARDS
                and integer(values['colorsPaid']) and -128 <= values['colorsPaid'] <= 127, 'invalid mana request')
    if 'mandatory' in values: require(isinstance(values['mandatory'], bool), 'invalid mandatory directive')
    if 'counterType' in values: require(isinstance(values['counterType'], str) and len(values['counterType']) <= 256, 'invalid counter type name')
    if kind == 'ENTITY_CARD_ROLE': require(values['role'] in ('Attach', 'Attacker', 'Partner'), 'invalid card role')
    for key in ('amount', 'manaValueLimit'):
        if key in values:
            require(key == 'amount' and values[key] is None or integer(values[key]) and -(1 << 31) <= values[key] < (1 << 31), 'invalid received integer')


def receipt(value, family, typed_reference, version=16):
    require(isinstance(value, dict) and set(value) == {'schemaVersion','coverage','kind','values','reason'}
            and integer(value['schemaVersion']) and value['schemaVersion'] == 1, 'invalid diagnostic fields')
    kind = value['kind']; allowed = NUMBER_KINDS if family == 'NUMBER' else TRIGGER_KINDS if family == 'TRIGGER' else MANA_KINDS if family == 'MANA' else MANA_SOURCE_KINDS if family == 'MANA_SOURCE' else CARD_PAYMENT_KINDS if family == 'CARD_PAYMENT' else SACRIFICE_PICK_KINDS if family == 'SACRIFICE_PICK' else DISCARD_BATCH_KINDS if family == 'DISCARD_BATCH' else DISCARD_PICK_KINDS if family == 'DISCARD_PICK' else EXILE_RANKING_KINDS if family == 'EXILE_RANKING' else ENTITY_KINDS
    if value['coverage'] == 'UNSUPPORTED':
        require(kind is None and value['values'] is None and value['reason'] == 'UNSUPPORTED', 'unsupported shape carries values'); return
    require(isinstance(kind, str) and kind in allowed, 'unknown or wrong-family shape')
    require(version >= MIN_PROFILE.get(kind, 16), 'shape requires a newer profile')
    if value['coverage'] == 'UNAVAILABLE':
        require(value['values'] is None and value['reason'] == 'OUTSIDE_CAPTURED_PREFIX', 'invalid bounded refusal'); return
    require(value['coverage'] == 'COMPLETE' and value['reason'] is None
            and isinstance(value['values'], dict) and set(value['values']) == SHAPES[kind], 'invalid complete shape')
    values = value['values']; scalars(kind, values)
    for key in list_roles(kind):
        role_array(values[key])
        for ref in values[key]: typed_reference(ref, 'CARD')
    for key, kinds in roles(kind).items():
        ref = values[key]
        if kind == 'ENTITY_COUNTER_REQUEST' and ref is None: continue
        require(isinstance(ref, dict) and ref.get('type') in kinds, 'invalid received entity type')
        typed_reference(ref, ref['type'])


def projection(value, coverage, player_refs, cards, omissions, allowed, version=16, chooser=None):
    require(coverage in ('RECEIVED_CALLBACK_PARAMETERS_V1','UNSUPPORTED','UNAVAILABLE'), 'unknown capability')
    if not allowed: require(coverage == 'UNSUPPORTED' and value is None, 'parameters at unaudited boundary')
    require((value is not None) == (coverage == 'RECEIVED_CALLBACK_PARAMETERS_V1'), 'capability/projection mismatch')
    if value is None:
        if coverage == 'UNAVAILABLE': require(any(o['component'] == 'PARAMETERS' for o in omissions), 'unavailable component lacks reason')
        return
    require(isinstance(value, dict) and isinstance(value.get('kind'), str) and value['kind'] in SHAPES, 'unknown shape')
    kind = value['kind']; require(version >= MIN_PROFILE.get(kind, 16), 'shape requires a newer profile')
    require(set(value) == {'kind'} | SHAPES[kind], 'invalid projected fields'); scalars(kind, value)
    public = {c['entityRef'] for c in cards if c['zone'] in ('Battlefield','Command','Graveyard','Stack') or c['zone'] == 'Exile' and not c['faceDown']}
    for key in list_roles(kind):
        role_array(value[key])
        permitted = public | {c['entityRef'] for c in cards if kind == 'DISCARD_PICK_REQUEST' and c['zone']=='Hand' and c['identityKnown'] and c['owner']==chooser and c['controller']==chooser}
        for ref in value[key]:
            require(isinstance(ref, str) and ref in permitted, 'private or unresolved list role')
            if kind == 'DISCARD_PICK_REQUEST' and ref not in public:
                card=next(c for c in cards if c['entityRef']==ref)
                require(sum(all(other[k]==v for k,v in card.items() if k!='entityRef') for other in cards)==1, 'ambiguous private-hand role')
        if kind in ('SACRIFICE_PICK_REQUEST','DISCARD_PICK_REQUEST'): require(len(set(value[key])) == len(value[key]), 'duplicate prior sacrifice feature')
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
        require(('UNRESOLVED_BINDING' in reasons and (any(received['values'][key] is not None for key in roles(received['kind'])) or any(received['values'][key] for key in list_roles(received['kind'])))) if received['coverage'] == 'COMPLETE' else received['reason'] in reasons, 'parameter refusal differs from receipt')
        return
    require(received['coverage'] == 'COMPLETE' and value['kind'] == received['kind'], 'unsupported/different received shape claims features')
    diagnostic = context['diagnostics']; kind = value['kind']
    bindings = {b['entityRef']: dict(type='PLAYER', id=b['id']) for b in diagnostic['playerBindings']}
    bindings.update({b['entityRef']: dict(type='CARD', id=b['id'], gameTimestamp=b['gameTimestamp']) for b in diagnostic['cardBindings']})
    for key, raw in received['values'].items():
        if key in list_roles(kind):
            require([bindings[ref] for ref in value[key]] == raw, 'received role list identity/incarnation/order differs')
        elif key in roles(kind):
            require((None if value[key] is None else bindings[value[key]]) == raw, 'received role identity/incarnation differs')
        else: require(value[key] == raw, 'received scalar differs')
