"""Independent, optional entry clocks. Legacy absence never acquires a child clock."""
PHASES = {'UNTAP', 'UPKEEP', 'DRAW', 'MAIN1', 'COMBAT_BEGIN', 'COMBAT_DECLARE_ATTACKERS',
          'COMBAT_DECLARE_BLOCKERS', 'COMBAT_FIRST_STRIKE_DAMAGE', 'COMBAT_DAMAGE', 'COMBAT_END',
          'MAIN2', 'END_OF_TURN', 'CLEANUP', 'UNESTABLISHED'}
TIMINGS = {'SELECTOR': 'BEFORE_SELECTOR', 'OPERATION': 'BEFORE_OPERATION',
           'APPLICATION': 'AFTER_SELECTION_BEFORE_APPLICATION', 'RESULT': 'UNESTABLISHED'}


def require(ok, message):
    if not ok: raise ValueError('entry location: ' + message)


def integer(value):
    return isinstance(value, int) and not isinstance(value, bool) and 0 <= value <= (1 << 63)-1


def validate(value, known_players=None, context=None):
    require(isinstance(value, dict) and set(value) == {'schemaVersion', 'status', 'kind', 'timing', 'stateBasis', 'turn', 'phase', 'turnPlayerId', 'reason'}, 'invalid fields')
    require(integer(value['schemaVersion']) and value['schemaVersion'] == 1, 'unsupported version')
    require(value['status'] in ('CAPTURED', 'UNAVAILABLE'), 'invalid status')
    if value['status'] == 'UNAVAILABLE':
        require(all(value[k] == 'UNESTABLISHED' for k in ('kind', 'timing', 'stateBasis'))
                and all(value[k] is None for k in ('turn', 'phase', 'turnPlayerId'))
                and value['reason'] in ('HIDDEN', 'INTERRUPTED', 'UNSUPPORTED', 'CAPTURE_FAILED'), 'invalid unavailable location')
        return
    kind = value['kind']
    require(isinstance(kind, str) and kind in TIMINGS and value['timing'] == TIMINGS[kind], 'kind/timing mismatch')
    require(value['stateBasis'] in (('UNESTABLISHED',) if kind == 'RESULT' else ('RULES_STATE', 'PLANNING_WORKING_STATE')), 'invalid state basis')
    require(value['reason'] is None and integer(value['turn']) and isinstance(value['phase'], str) and value['phase'] in PHASES, 'invalid clock')
    player = value['turnPlayerId']
    require(player is None or integer(player) and (known_players is None or player in known_players), 'unknown turn player')
    if context is not None:
        require((value['timing'], value['stateBasis']) == (context['timing'], context['stateBasis']), 'owning input boundary differs')
        require(kind != 'RESULT' or context['status'] == 'UNAVAILABLE', 'result reconstructs an earlier input')
    # Clock and projection use successive synchronous reads, not an atomic whole-engine snapshot.
    # Never substitute projection/parent clocks for an entry clock or assert they must be equal.
