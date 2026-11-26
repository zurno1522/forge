package forge.ai;

import com.google.common.collect.ImmutableMap;
import forge.game.player.Player;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** One received, bounded parameter shape; arbitrary maps/values are never enumerated. */
record AiSpellRequestInput(boolean supported, Player affectedPlayer, Long affectedPlayerId) {
    static AiSpellRequestInput capture(final Map<String, Object> params) {
        if (params == null || params == Map.<String, Object>of()
                || params == Collections.<String, Object>emptyMap() || params == ImmutableMap.<String, Object>of()) {
            return new AiSpellRequestInput(true, null, null);
        }
        if (params.getClass() != HashMap.class) { return new AiSpellRequestInput(false, null, null); }
        final int count = params.size();
        if (count == 0) { return new AiSpellRequestInput(true, null, null); }
        if (count != 1 || !params.containsKey("Affected")) { return new AiSpellRequestInput(false, null, null); }
        if (!(params.get("Affected") instanceof Player affected)) { return new AiSpellRequestInput(false, null, null); }
        return new AiSpellRequestInput(true, affected, affected.getId());
    }

    Map<String, Object> diagnostics() {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1); result.put("coverage", supported ? "COMPLETE" : "UNSUPPORTED");
        result.put("affectedPlayerId", affectedPlayerId); return result;
    }
}
