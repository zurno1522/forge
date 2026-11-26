package forge.ai;
import com.google.gson.GsonBuilder;
import forge.util.MyRandom;
import java.util.*;
import java.nio.file.*;
public class ScavengeDiagnostic {
 public static void main(String[] args) throws Exception {
  var test=new FlatMonteCarloActivatedScenarioTest(); test.initializeModel(); var results=new ArrayList<Map<String,Object>>();
  var field=FlatMonteCarloActivatedScenarioTest.class.getDeclaredField("game");field.setAccessible(true);
  for(long seed=1;seed<=12;seed++) {
   MyRandom.setRandom(new Random(seed)); var row=new LinkedHashMap<String,Object>();row.put("seed",seed);long start=System.nanoTime();
   try {test.scavengeOntoACreature();row.put("passed",true);} catch(AssertionError failure) {row.put("passed",false);row.put("assertion",failure.getMessage());}
   var game=(forge.game.Game)field.get(test);row.put("recordingAiDecisions",game.isRecordingAiDecisions());row.put("elapsedNanos",System.nanoTime()-start);results.add(row);
  }
  Files.writeString(Path.of(args[0]),new GsonBuilder().setPrettyPrinting().create().toJson(results)+"\n");System.exit(0);
 }
}
