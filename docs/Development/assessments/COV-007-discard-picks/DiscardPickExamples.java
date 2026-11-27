package forge.ai;
import com.google.gson.GsonBuilder;
import forge.game.card.CardCollection;
import forge.game.zone.ZoneType;
import java.util.*;
import java.nio.file.*;
/** Native discard loop and adapter excerpts; not complete game traces. */
public final class DiscardPickExamples extends AITest {
 public static void main(String[] args) throws Exception {
  var h=new DiscardPickExamples();h.initializeModel();var examples=new LinkedHashMap<String,Object>();
  for(var label:List.of("PREFERENCE_HAND_HISTORY","DECLINE_TO_FALLBACK","COUNT_OVERRIDE","LAND_FALLBACK","RANDOM_FALLBACK","FROZEN_PLANNING","OPPONENT_PRIVATE_REFUSAL","AMBIGUOUS_HAND_REFUSAL","OVERSIZED_HISTORY","ORIGINAL_ERROR")) {
   var game=h.initAndCreateGame();var player=game.getPlayers().get(1);var brain=((PlayerControllerAi)player.getController()).getAi();var ability=h.addCardToZone("Lightning Bolt",player,ZoneType.Hand).getFirstSpellAbility();ability.setActivatingPlayer(player);
   var cards=new CardCollection(List.of(h.addCardToZone("Forest",player,ZoneType.Hand),h.addCardToZone("Shivan Dragon",player,ZoneType.Hand),h.addCardToZone("Grizzly Bears",player,ZoneType.Hand)));
   var rows=new ArrayList<Map<String,Object>>();game.setRecordAiDecisions(true);game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c){rows.add(c.data());}});
   switch(label) {
    case "PREFERENCE_HAND_HISTORY" -> {cards.get(0).setSVar("DiscardMe","5");cards.get(2).setSVar("DiscardMe","4");if(brain.getCardsToDiscard(2,2,cards,ability).size()!=2){throw new AssertionError(label);}}
    case "DECLINE_TO_FALLBACK" -> {if(brain.getCardsToDiscard(2,2,cards,ability).size()!=2){throw new AssertionError(label);}}
    case "COUNT_OVERRIDE" -> {ability.getMapParams().put("AILogic","Always");if(brain.getCardsToDiscard(0,1,cards,ability).size()!=1){throw new AssertionError(label);}}
    case "LAND_FALLBACK" -> {cards.clear();for(var name:List.of("Forest","Mountain","Island","Swamp")){cards.add(h.addCardToZone(name,player,ZoneType.Hand));}if(brain.getCardsToDiscard(1,1,cards,ability).size()!=1){throw new AssertionError(label);}}
    case "RANDOM_FALLBACK" -> {cards.clear();for(var name:List.of("Sol Ring","Arcane Signet","Mind Stone")){var card=h.addCardToZone(name,player,ZoneType.Hand);card.setSVar("DoNotDiscardIfAble","True");cards.add(card);}forge.util.MyRandom.setRandom(new Random(78123));if(brain.getCardsToDiscard(1,1,cards,ability).size()!=1){throw new AssertionError(label);}}
    case "FROZEN_PLANNING" -> {var selected=cards.get(1);var prior=new CardCollection(cards.get(0));if(AiChoiceRecorder.planning(player,()->AiPaymentChoiceRecorder.discardPreference(player,ability,cards,2,2,1,prior,()->{cards.clear();prior.clear();return selected;}))!=selected){throw new AssertionError(label);}}
    case "OPPONENT_PRIVATE_REFUSAL" -> {var hidden=h.addCardToZone("Black Lotus",game.getPlayers().get(0),ZoneType.Hand);AiPaymentChoiceRecorder.discardPreference(player,ability,cards,2,2,1,new CardCollection(hidden),()->cards.get(0));}
    case "AMBIGUOUS_HAND_REFUSAL" -> {h.addCardToZone("Forest",player,ZoneType.Hand);AiPaymentChoiceRecorder.discardPreference(player,ability,cards,2,2,1,new CardCollection(cards.get(0)),()->cards.get(1));}
    case "OVERSIZED_HISTORY" -> {var prior=new CardCollection();for(int i=0;i<65;i++){prior.add(h.addCardToZone("Forest",player,ZoneType.Hand));}AiPaymentChoiceRecorder.discardPreference(player,ability,cards,66,66,65,prior,()->cards.get(0));}
    case "ORIGINAL_ERROR" -> {var expected=new IllegalStateException("original");try{AiPaymentChoiceRecorder.discardPreference(player,ability,cards,1,1,0,new CardCollection(),()->{throw expected;});throw new AssertionError(label);}catch(IllegalStateException actual){if(actual!=expected){throw new AssertionError(label);}}}
    default -> throw new AssertionError(label);
   }
   if(game.getEventObservationFailure()!=null){throw new AssertionError(label,game.getEventObservationFailure());}examples.put(label,rows);
  }
  Files.writeString(Path.of(args[0]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(Map.of("fixtureKind","DISCARD_ITERATION_AND_ADAPTER_EXCERPTS","completeGameTrace",false,"choices",examples))+"\n");System.exit(0);
 }
}
