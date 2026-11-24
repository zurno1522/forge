package forge.ai;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Union reachability of fixed scalar ledgers; never traverses a Game or controller. */
public final class AccountingStateProbe {
    public static void main(String[] args) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int players : List.of(2, 4, 16)) {
            List<AiObservationAccounting> ledgers = new ArrayList<>();
            for (int i = 0; i < players + 2; i++) { ledgers.add(new AiObservationAccounting()); }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("originalAiControllers", players);
            row.put("receiptAndRunLedgers", 2);
            row.put("unionReachableBytes", InputContextPhaseMetrics.reachable(ledgers));
            final var scope = ledgers.get(0).enter();
            row.put("invocationScopeShallowBytes", InputContextPhaseAgent.instrumentation.getObjectSize(scope));
            scope.exit();
            rows.add(row);
        }
        Files.writeString(Path.of(args[0]), new GsonBuilder().setPrettyPrinting().create().toJson(rows) + "\n");
    }
}
