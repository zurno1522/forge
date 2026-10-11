package forge.view;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SimulationTraceWriterTest {
    @Test(groups = { "UnitTest", "fast" })
    public void writesOneJsonObjectPerLineWithStableNestedShapes() throws Exception {
        final Path file = Files.createTempFile("forge-sim-trace-", ".jsonl");
        try {
            final Map<String, Object> player = new LinkedHashMap<>();
            player.put("id", 1);
            player.put("name", "AI \"One\"");

            final Map<String, Object> record = new LinkedHashMap<>();
            record.put("type", "TEST");
            record.put("game", 1);
            record.put("eventIndex", 2);
            record.put("player", player);
            record.put("values", List.of("A", "B"));
            record.put("optional", null);

            try (SimulationTraceWriter writer = SimulationTraceWriter.open(file.toString())) {
                writer.write(record);
            }

            Assert.assertEquals(Files.readString(file),
                    "{\"type\":\"TEST\",\"game\":1,\"eventIndex\":2,"
                    + "\"player\":{\"id\":1,\"name\":\"AI \\\"One\\\"\"},"
                    + "\"values\":[\"A\",\"B\"],\"optional\":null}"
                    + System.lineSeparator());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test(groups = { "UnitTest", "fast" })
    public void openingTraceOverwritesExistingFile() throws Exception {
        final Path file = Files.createTempFile("forge-sim-trace-", ".jsonl");
        try {
            Files.writeString(file, "old content");
            try (SimulationTraceWriter writer = SimulationTraceWriter.open(file.toString())) {
                writer.write(Map.of("type", "RUN_STARTED"));
            }

            Assert.assertEquals(Files.readString(file),
                    "{\"type\":\"RUN_STARTED\"}" + System.lineSeparator());
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
