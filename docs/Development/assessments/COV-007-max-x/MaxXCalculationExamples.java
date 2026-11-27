package forge.ai;
import com.google.gson.GsonBuilder;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.TargetRestrictions;
import forge.game.ability.AbilityFactory;
import forge.game.zone.ZoneType;
import java.util.*;
import java.nio.file.*;
/** Native maximum-X and adapter excerpts; not complete game traces. */
public final class MaxXCalculationExamples extends AITest {
 public static void main(String[] args) throws Exception {
  var h=new MaxXCalculationExamples();h.initializeModel();var examples=new LinkedHashMap<String,Object>();
  for(var label:List.of("FIREBALL","DOUBLE_X","ZERO","AIXMAX_SUBABILITY","TARGET_X","NON_MANA_X","NON_FREE_GUARD","CACHED_CHARM","FROZEN_PLANNING","ORIGINAL_ERROR")) {
   var game=h.initAndCreateGame();var player=game.getPlayers().get(1);int mana=label.equals("ZERO")?1:4;for(int i=0;i<mana;i++){h.addCardToZone("Mountain",player,ZoneType.Battlefield);}
   String name=label.equals("DOUBLE_X")?"Hangarback Walker":label.equals("CACHED_CHARM")?"Drown in Dreams":label.equals("NON_FREE_GUARD")||label.equals("NON_MANA_X")?"Lightning Bolt":"Fireball";
   var ability=h.addCardToZone(name,player,ZoneType.Hand).getFirstSpellAbility();ability.setActivatingPlayer(player);var rows=new ArrayList<Map<String,Object>>();game.setRecordAiDecisions(true);game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c){rows.add(c.data());}});
   switch(label) {
    case "FIREBALL","DOUBLE_X","ZERO" -> {int expected=label.equals("FIREBALL")?3:label.equals("DOUBLE_X")?2:0;if(ComputerUtilCost.setMaxXValue(ability,player,false)!=expected){throw new AssertionError(label);}}
    case "AIXMAX_SUBABILITY" -> {var sub=(AbilitySub)AbilityFactory.getAbility("DB$ Draw | NumCards$ X | Defined$ You | AIXMax$ 2",ability.getHostCard());ability.setSubAbility(sub);sub.setActivatingPlayer(player);if(ComputerUtilCost.setMaxXValue(sub,player,false)!=2){throw new AssertionError(label);}}
    case "TARGET_X" -> {h.addCardToZone("Grizzly Bears",player,ZoneType.Battlefield);ability.setTargetRestrictions(new TargetRestrictions(Map.of("ValidTgts","Creature","TargetMin","X","TargetMax","X")));if(ComputerUtilCost.setMaxXValue(ability,player,false)!=1){throw new AssertionError(label);}}
    case "NON_MANA_X" -> {ability.setPayCosts(new Cost("Sac<X/Creature>",true));ability.setSVar("X","Count$xPaid");Card bear=h.addCardToZone("Grizzly Bears",player,ZoneType.Battlefield),dragon=h.addCardToZone("Shivan Dragon",player,ZoneType.Battlefield);bear.setSVar("SacMe","6");dragon.setSVar("SacMe","5");if(ComputerUtilCost.setMaxXValue(ability,player,false)!=2||!bear.isInPlay()||!dragon.isInPlay()){throw new AssertionError(label);}}
    case "NON_FREE_GUARD" -> {if(ComputerUtilCost.setMaxXValue(ability,player,false)!=0||!rows.isEmpty()){throw new AssertionError(label);}}
    case "CACHED_CHARM" -> {ability.setXManaCostPaid(4);if(ComputerUtilCost.setMaxXValue(ability,player,false)!=4||!rows.isEmpty()){throw new AssertionError(label);}}
    case "FROZEN_PLANNING" -> {if(AiChoiceRecorder.planning(player,()->AiNumberChoiceRecorder.calculateMaxX(player,ability,true,()->{player.setLife(7,null);return -2;}))!=-2){throw new AssertionError(label);}}
    case "ORIGINAL_ERROR" -> {var expected=new IllegalStateException("original");try{AiNumberChoiceRecorder.calculateMaxX(player,ability,false,()->{throw expected;});throw new AssertionError(label);}catch(IllegalStateException actual){if(actual!=expected){throw new AssertionError(label);}}}
    default -> throw new AssertionError(label);
   }
   if(game.getEventObservationFailure()!=null){throw new AssertionError(label,game.getEventObservationFailure());}examples.put(label,rows);
  }
  Files.writeString(Path.of(args[0]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(Map.of("fixtureKind","MAX_X_NATIVE_AND_ADAPTER_EXCERPTS","completeGameTrace",false,"choices",examples))+"\n");System.exit(0);
 }
}
