package forge.ai;
import com.google.gson.GsonBuilder;
import forge.game.card.CardCollection;
import forge.game.cost.CostSacrifice;
import forge.game.zone.ZoneType;
import java.util.*;
import java.nio.file.*;
/** Actual card policies and controlled adapter excerpts; not complete game envelopes. */
public final class CardRequestExamples extends AITest {
 public static void main(String[] args) throws Exception {
  var h=new CardRequestExamples();h.initializeModel();var examples=new LinkedHashMap<String,Object>();
  for(var label:List.of("FORCED_DISCARD_ALIAS","ACTUAL_SACRIFICE_PROPOSAL","FROZEN_PLANNING","REVERSED_RECEIVED_BOUNDS","EXILE_LEGACY","ORIGINAL_ERROR")) {
   var game=h.initAndCreateGame();var player=game.getPlayers().get(1);var pc=(PlayerControllerAi)player.getController();var ability=h.addCardToZone("Lightning Bolt",player,ZoneType.Hand).getFirstSpellAbility();ability.setActivatingPlayer(player);
   var cards=new CardCollection(List.of(h.addCardToZone("Forest",player,ZoneType.Hand),h.addCardToZone("Mountain",player,ZoneType.Hand)));
   var rows=new ArrayList<Map<String,Object>>();game.setRecordAiDecisions(true);game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c){rows.add(c.data());}});
   switch(label) {
    case "FORCED_DISCARD_ALIAS" -> {if(pc.getAi().getCardsToDiscard(2,2,cards,ability)!=cards){throw new AssertionError(label);}}
    case "ACTUAL_SACRIFICE_PROPOSAL" -> {var a=h.addCardToZone("Llanowar Elves",player,ZoneType.Battlefield);var b=h.addCardToZone("Grizzly Bears",player,ZoneType.Battlefield);var result=new AiCostDecision(player,ability,false).decide(new CostSacrifice("2","Creature",null));if(result.cards.size()!=2||!a.isInPlay()||!b.isInPlay()){throw new AssertionError(label);}}
    case "FROZEN_PLANNING" -> {var selected=new CardCollection(cards.get(0));if(AiChoiceRecorder.planning(player,()->AiPaymentChoiceRecorder.cards(player,ability,"PAYMENT_DISCARD_POOL",cards,1,2,()->{cards.clear();return selected;}))!=selected){throw new AssertionError(label);}}
    case "REVERSED_RECEIVED_BOUNDS" -> AiPaymentChoiceRecorder.cards(player,ability,"PAYMENT_SACRIFICE_POOL",cards,3,1,()->cards);
    case "EXILE_LEGACY" -> AiPaymentChoiceRecorder.cards(player,ability,"PAYMENT_EXILE_POOL",cards,1,1,()->cards);
    case "ORIGINAL_ERROR" -> {var expected=new IllegalStateException("original");try{AiPaymentChoiceRecorder.cards(player,ability,"PAYMENT_DISCARD_POOL",cards,1,2,()->{throw expected;});throw new AssertionError(label);}catch(IllegalStateException actual){if(actual!=expected){throw new AssertionError(label);}}}
    default -> throw new AssertionError(label);
   }
   if(game.getEventObservationFailure()!=null){throw new AssertionError(label,game.getEventObservationFailure());}examples.put(label,rows);
  }
  Files.writeString(Path.of(args[0]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(Map.of("fixtureKind","CARD_POLICY_AND_ADAPTER_EXCERPTS","completeGameTrace",false,"choices",examples))+"\n");System.exit(0);
 }
}
