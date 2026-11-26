package forge.ai;
import com.google.gson.GsonBuilder;
import forge.game.ability.AbilityFactory;
import forge.game.card.CounterEnumType;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollection;
import java.util.*;
import java.nio.file.*;
/** Actual controller/audited-adapter messages, not complete game envelopes. */
public final class ReceivedParameterExamples extends AITest {
 public static void main(String[] args) throws Exception {
  var h=new ReceivedParameterExamples();h.initializeModel();var examples=new LinkedHashMap<String,Object>();
  for(var label:List.of("VOTER_SELF","VOTER_OTHER","COUNTER_AMOUNT","COUNTER_TRANSFER","ENTITY_ROLE","ENTITY_COUNTER","UNSUPPORTED_MAP","PRIVATE_ROLE")) {
   var game=h.initAndCreateGame();var player=game.getPlayers().get(1);var other=game.getPlayers().get(0);
   var host=h.addCardToZone("Time Beetle",player,ZoneType.Battlefield);var card=h.addCardToZone("Grizzly Bears",player,ZoneType.Battlefield);
   var api=label.equals("COUNTER_AMOUNT")?"RemoveCounter":label.startsWith("ENTITY")||label.equals("PRIVATE_ROLE")?"TimeTravel":"Vote";
   var ability=AbilityFactory.getAbility("AB$ "+api+" | Cost$ 0",host);ability.setActivatingPlayer(player);
   var params=new HashMap<String,Object>();var rows=new ArrayList<Map<String,Object>>();game.setRecordAiDecisions(true);
   game.observeEventEmission(event->{if(event instanceof AiChoiceEvent choice){rows.add(choice.data());}});
   switch(label) {
    case "VOTER_SELF","VOTER_OTHER" -> {params.put("Voter",label.equals("VOTER_SELF")?player:other);int chosen=player.getController().chooseNumber(ability,"Choose",0,3,params);if(chosen!=(label.equals("VOTER_SELF")?3:0)){throw new AssertionError(label);}}
    case "COUNTER_AMOUNT" -> {other.setCounters(CounterEnumType.POISON,3);params.put("Target",other);params.put("CounterType",CounterEnumType.POISON);if(player.getController().chooseNumber(ability,"Choose",0,3,params)!=0){throw new AssertionError(label);}}
    case "COUNTER_TRANSFER" -> {params.put("Source",card);params.put("Target",other);params.put("CounterType",CounterEnumType.CHARGE);AiNumberChoiceRecorder.controllerRange(player,ability,0,3,params,()->2);}
    case "ENTITY_ROLE" -> {params.put("Partner",card);if(player.getController().chooseSingleEntityForEffect(new FCollection<>(List.of(card)),null,ability,"Choose",false,null,params)!=card){throw new AssertionError(label);}}
    case "ENTITY_COUNTER" -> {params.put("CounterType",CounterEnumType.LOYALTY);params.put("Amount",2);params.put("Target",card);player.getController().chooseSingleEntityForEffect(new FCollection<>(List.of(card)),null,ability,"Choose",false,null,params);}
    case "UNSUPPORTED_MAP" -> {var opaque=new AbstractMap<String,Object>(){@Override public Set<Entry<String,Object>> entrySet(){throw new AssertionError("unknown map enumerated");}};AiNumberChoiceRecorder.controllerRange(player,ability,0,3,opaque,()->2);}
    case "PRIVATE_ROLE" -> {params.put("Attach",h.addCardToZone("Black Lotus",other,ZoneType.Hand));player.getController().chooseSingleEntityForEffect(new FCollection<>(List.of(card)),null,ability,"Choose",false,null,params);}
    default -> throw new AssertionError(label);
   }
   if(game.getEventObservationFailure()!=null){throw new AssertionError(label);}examples.put(label,rows);
  }
  Files.writeString(Path.of(args[0]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(Map.of("fixtureKind","ACTUAL_CONTROLLER_AND_AUDITED_ADAPTER_EXCERPTS","completeGameTrace",false,"choices",examples))+"\n");System.exit(0);
 }
}
