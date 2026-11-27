package forge.ai;
import com.google.gson.GsonBuilder;
import forge.ai.simulation.DeckPlan;
import forge.ai.simulation.ComboPlan;
import forge.game.card.CardCollection;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.*;
import java.nio.file.*;
/** Native exile-policy and adapter excerpts; not complete game traces. */
public final class ExileRankingExamples extends AITest {
 public static void main(String[] args) throws Exception {
  var h=new ExileRankingExamples();h.initializeModel();var examples=new LinkedHashMap<String,Object>();
  for(var label:List.of("POWER_ORDER","TARGET_FILTER","CRAFT_ORDER","CRAFT_DECLINE","INSUFFICIENT","PROTECTED_COST","PROTECTED_EFFECT","ZERO","NEGATIVE_ERROR","FROZEN_PLANNING","ORIGINAL_ERROR")) {
   var game=h.initAndCreateGame();var player=game.getPlayers().get(1);var ability=h.addCardToZone("Lightning Bolt",player,ZoneType.Hand).getFirstSpellAbility();ability.setActivatingPlayer(player);
   var cards=new CardCollection(List.of(h.addCardToZone("Shivan Dragon",player,ZoneType.Battlefield),h.addCardToZone("Llanowar Elves",player,ZoneType.Battlefield)));
   if(label.startsWith("PROTECTED")) {
    for(var name:List.of("Reanimate","Animate Dead","Entomb","Griselbrand","Archon of Cruelty","Faithless Looting","Careful Study","Swamp")){for(int i=0;i<4;i++){h.addCardToZone(name,player,ZoneType.Library);}}
    var plan=DeckPlan.forSetting("auto",player);if(!plan.combos.has(ComboPlan.Kind.REANIMATOR)){throw new AssertionError(label);}var brain=((PlayerControllerAi)player.getController()).getAi();var field=AiController.class.getDeclaredField("flatMcDeckPlan");field.setAccessible(true);field.set(brain,plan);
    cards.clear();cards.add(h.addCardToZone("Reanimate",player,ZoneType.Hand));
   }
   var rows=new ArrayList<Map<String,Object>>();game.setRecordAiDecisions(true);game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c){rows.add(c.data());}});
   switch(label) {
    case "POWER_ORDER" -> {if(ComputerUtil.chooseExileFromList(player,cards,ability.getHostCard(),1,ability,false).get(0)!=cards.get(1)){throw new AssertionError(label);}}
    case "TARGET_FILTER" -> {ability.getTargets().add(cards.get(1));if(ComputerUtil.chooseExileFromList(player,cards,ability.getHostCard(),1,ability,false).get(0)!=cards.get(0)){throw new AssertionError(label);}}
    case "CRAFT_ORDER","CRAFT_DECLINE" -> {var source=h.addCardToZone("Clay-Fired Bricks",player,ZoneType.Battlefield);SpellAbility craft=source.getSpellAbilities().stream().filter(SpellAbility::isCraft).findFirst().orElseThrow();craft.setActivatingPlayer(player);cards.clear();cards.add(h.addCardToZone("Worn Powerstone",player,ZoneType.Battlefield));if(label.equals("CRAFT_ORDER")){cards.add(h.addCardToZone("Sol Ring",player,ZoneType.Battlefield));cards.add(h.addCardToZone("Darksteel Ingot",player,ZoneType.Graveyard));cards.add(h.addCardToZone("Mind Stone",player,ZoneType.Graveyard));var result=ComputerUtil.chooseExileFromList(player,cards,source,2,craft,false);if(!result.get(0).getName().equals("Mind Stone")||!result.get(1).getName().equals("Darksteel Ingot")){throw new AssertionError(label);}}else if(ComputerUtil.chooseExileFromList(player,cards,source,1,craft,false)!=null){throw new AssertionError(label);}}
    case "INSUFFICIENT" -> {if(ComputerUtil.chooseExileFromList(player,cards,ability.getHostCard(),3,ability,false)!=null){throw new AssertionError(label);}}
    case "PROTECTED_COST" -> {if(ComputerUtil.chooseExileFromList(player,cards,ability.getHostCard(),1,ability,false)!=null){throw new AssertionError(label);}}
    case "PROTECTED_EFFECT" -> {if(ComputerUtil.chooseExileFromList(player,cards,ability.getHostCard(),1,ability,true).get(0)!=cards.get(0)){throw new AssertionError(label);}}
    case "ZERO" -> {if(!ComputerUtil.chooseExileFromList(player,cards,ability.getHostCard(),0,ability,false).isEmpty()){throw new AssertionError(label);}}
    case "NEGATIVE_ERROR" -> {try{ComputerUtil.chooseExileFromList(player,cards,ability.getHostCard(),-1,ability,false);throw new AssertionError(label);}catch(IllegalArgumentException expected){}}
    case "FROZEN_PLANNING" -> {var selected=new CardCollection(cards.get(0));if(AiChoiceRecorder.planning(player,()->AiPaymentChoiceRecorder.exileRanking(player,ability,cards,Integer.MIN_VALUE,true,()->{cards.clear();return selected;}))!=selected){throw new AssertionError(label);}}
    case "ORIGINAL_ERROR" -> {var expected=new IllegalStateException("original");try{AiPaymentChoiceRecorder.exileRanking(player,ability,cards,1,false,()->{throw expected;});throw new AssertionError(label);}catch(IllegalStateException actual){if(actual!=expected){throw new AssertionError(label);}}}
    default -> throw new AssertionError(label);
   }
   if(game.getEventObservationFailure()!=null){throw new AssertionError(label,game.getEventObservationFailure());}examples.put(label,rows);
  }
  Files.writeString(Path.of(args[0]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(Map.of("fixtureKind","EXILE_RANKING_AND_ADAPTER_EXCERPTS","completeGameTrace",false,"choices",examples))+"\n");System.exit(0);
 }
}
