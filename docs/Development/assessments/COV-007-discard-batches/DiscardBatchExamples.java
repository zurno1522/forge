package forge.ai;
import com.google.gson.GsonBuilder;
import forge.ai.simulation.DeckPlan;
import forge.ai.simulation.ComboPlan;
import forge.game.card.CardCollection;
import forge.game.zone.ZoneType;
import java.util.*;
import java.nio.file.*;
/** Actual discard-policy and adapter excerpts; not complete game envelopes. */
public final class DiscardBatchExamples extends AITest {
 public static void main(String[] args) throws Exception {
  var h=new DiscardBatchExamples();h.initializeModel();var examples=new LinkedHashMap<String,Object>();
  for(var label:List.of("FORCED_ALIAS","COMBO_SELECTION","PROTECTED_NARROWING","MANA_VALUE_HAND","SHAPESHIFTER_HAND","UNCASTABLE_HAND","FROZEN_PLANNING","ORIGINAL_ERROR")) {
   var game=h.initAndCreateGame();var player=game.getPlayers().get(1);var brain=((PlayerControllerAi)player.getController()).getAi();var ability=h.addCardToZone("Lightning Bolt",player,ZoneType.Hand).getFirstSpellAbility();ability.setActivatingPlayer(player);
   var cards=new CardCollection(List.of(h.addCardToZone("Forest",player,ZoneType.Hand),h.addCardToZone("Mountain",player,ZoneType.Hand)));
   if(label.equals("COMBO_SELECTION")||label.equals("PROTECTED_NARROWING")) {
    for(var name:List.of("Reanimate","Animate Dead","Entomb","Griselbrand","Archon of Cruelty","Faithless Looting","Careful Study","Swamp")){for(int i=0;i<4;i++){h.addCardToZone(name,player,ZoneType.Library);}}
    var plan=DeckPlan.forSetting("auto",player);if(!plan.combos.has(ComboPlan.Kind.REANIMATOR)){throw new AssertionError(label);}var field=AiController.class.getDeclaredField("flatMcDeckPlan");field.setAccessible(true);field.set(brain,plan);
   }
   var rows=new ArrayList<Map<String,Object>>();game.setRecordAiDecisions(true);game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c){rows.add(c.data());}});
   switch(label) {
    case "FORCED_ALIAS" -> {if(brain.getCardsToDiscard(2,2,cards,ability)!=cards){throw new AssertionError(label);}}
    case "COMBO_SELECTION" -> {var big=h.addCardToZone("Griselbrand",player,ZoneType.Hand);var spell=h.addCardToZone("Reanimate",player,ZoneType.Hand);var offered=new CardCollection(List.of(big,cards.get(0),spell));if(brain.getCardsToDiscard(1,1,offered,ability).get(0)!=big){throw new AssertionError(label);}}
    case "PROTECTED_NARROWING" -> {cards.add(h.addCardToZone("Reanimate",player,ZoneType.Hand));if(!brain.getCardsToDiscard(0,1,cards,ability).isEmpty()){throw new AssertionError(label);}}
    case "MANA_VALUE_HAND" -> {var bear=h.addCardToZone("Grizzly Bears",player,ZoneType.Hand);ability.getMapParams().put("AILogic","DiscardCMCX");ability.setXManaCostPaid(2);if(brain.getCardsToDiscard(1,1,cards,ability).get(0)!=bear){throw new AssertionError(label);}}
    case "SHAPESHIFTER_HAND" -> {var dragon=h.addCardToZone("Shivan Dragon",player,ZoneType.Hand);ability.getMapParams().put("AILogic","VolrathsShapeshifter");if(brain.getCardsToDiscard(1,1,cards,ability).get(0)!=dragon){throw new AssertionError(label);}}
    case "UNCASTABLE_HAND" -> {var dragon=h.addCardToZone("Shivan Dragon",player,ZoneType.Hand);ability.getMapParams().put("AILogic","DiscardUncastableAndExcess");ability.getMapParams().put("AnyNumber","True");if(!brain.getCardsToDiscard(0,4,cards,ability).contains(dragon)){throw new AssertionError(label);}}
    case "FROZEN_PLANNING" -> {var selected=new CardCollection(cards.get(0));if(AiChoiceRecorder.planning(player,()->AiPaymentChoiceRecorder.discardBatch(player,ability,AiPaymentChoiceRecorder.DiscardBranch.POLICY,1,2,null,cards,()->{cards.clear();return selected;}))!=selected){throw new AssertionError(label);}}
    case "ORIGINAL_ERROR" -> {var expected=new IllegalStateException("original");try{AiPaymentChoiceRecorder.discardBatch(player,ability,AiPaymentChoiceRecorder.DiscardBranch.COMBO,1,2,null,cards,()->{throw expected;});throw new AssertionError(label);}catch(IllegalStateException actual){if(actual!=expected){throw new AssertionError(label);}}}
    default -> throw new AssertionError(label);
   }
   if(game.getEventObservationFailure()!=null){throw new AssertionError(label,game.getEventObservationFailure());}examples.put(label,rows);
  }
  Files.writeString(Path.of(args[0]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(Map.of("fixtureKind","DISCARD_POLICY_AND_ADAPTER_EXCERPTS","completeGameTrace",false,"choices",examples))+"\n");System.exit(0);
 }
}
