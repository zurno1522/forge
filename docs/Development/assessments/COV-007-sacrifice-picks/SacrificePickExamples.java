package forge.ai;
import com.google.gson.GsonBuilder;
import forge.game.card.CardCollection;
import forge.game.cost.CostSacrifice;
import forge.game.zone.ZoneType;
import java.util.*;
import java.nio.file.*;
/** Native sacrifice-loop and adapter excerpts; not complete game envelopes. */
public final class SacrificePickExamples extends AITest {
 public static void main(String[] args) throws Exception {
  var h=new SacrificePickExamples();h.initializeModel();var examples=new LinkedHashMap<String,Object>();
  for(var label:List.of("ACTUAL_TWO_PICKS","FROZEN_PLANNING","PRIVATE_PRIOR","OVERSIZED_PRIOR","DECLINE","ORIGINAL_ERROR")) {
   var game=h.initAndCreateGame();var player=game.getPlayers().get(1);var ability=h.addCardToZone("Lightning Bolt",player,ZoneType.Hand).getFirstSpellAbility();ability.setActivatingPlayer(player);
   var cards=new CardCollection(List.of(h.addCardToZone("Llanowar Elves",player,ZoneType.Battlefield),h.addCardToZone("Grizzly Bears",player,ZoneType.Battlefield)));
   var rows=new ArrayList<Map<String,Object>>();game.setRecordAiDecisions(true);game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c){rows.add(c.data());}});
   switch(label) {
    case "ACTUAL_TWO_PICKS" -> {var result=new AiCostDecision(player,ability,false).decide(new CostSacrifice("2","Creature",null));if(result.cards.size()!=2||!cards.stream().allMatch(forge.game.card.Card::isInPlay)){throw new AssertionError(label);}}
    case "FROZEN_PLANNING" -> {var prior=new CardCollection(cards.get(0));var chosen=cards.get(1);if(AiChoiceRecorder.planning(player,()->AiPaymentChoiceRecorder.sacrificePick(player,ability,cards,2,1,prior,()->{prior.clear();cards.clear();return chosen;}))!=chosen){throw new AssertionError(label);}}
    case "PRIVATE_PRIOR" -> {var prior=new CardCollection(h.addCardToZone("Black Lotus",player,ZoneType.Hand));AiPaymentChoiceRecorder.sacrificePick(player,ability,cards,2,1,prior,()->cards.get(0));}
    case "OVERSIZED_PRIOR" -> {var prior=new CardCollection();for(int i=0;i<65;i++){prior.add(h.createCard("Grizzly Bears",player));}AiPaymentChoiceRecorder.sacrificePick(player,ability,cards,66,65,prior,()->cards.get(0));}
    case "DECLINE" -> AiPaymentChoiceRecorder.sacrificePick(player,ability,cards,1,0,new CardCollection(),()->null);
    case "ORIGINAL_ERROR" -> {var expected=new IllegalStateException("original");try{AiPaymentChoiceRecorder.sacrificePick(player,ability,cards,1,0,new CardCollection(),()->{throw expected;});throw new AssertionError(label);}catch(IllegalStateException actual){if(actual!=expected){throw new AssertionError(label);}}}
    default -> throw new AssertionError(label);
   }
   if(game.getEventObservationFailure()!=null){throw new AssertionError(label,game.getEventObservationFailure());}examples.put(label,rows);
  }
  Files.writeString(Path.of(args[0]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(Map.of("fixtureKind","SACRIFICE_LOOP_AND_ADAPTER_EXCERPTS","completeGameTrace",false,"choices",examples))+"\n");System.exit(0);
 }
}
