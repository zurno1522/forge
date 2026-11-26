package forge.ai;
import com.google.gson.GsonBuilder;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.zone.ZoneType;
import org.mockito.Mockito;
import java.util.*;
import java.nio.file.*;
/** Actual payment/adapter excerpts; one explicitly controlled selector dependency. Not complete game envelopes. */
public final class ManaSourceExamples extends AITest {
 public static void main(String[] args) throws Exception {
  var h=new ManaSourceExamples();h.initializeModel();var examples=new LinkedHashMap<String,Object>();
  for(var label:List.of("ACTUAL_SUCCESSIVE_PAYMENT","CONTROLLED_POST_SELECTION_REJECTION","FROZEN_PLANNING","DECLINED_SOURCE","UNKNOWN_COST","ORIGINAL_ERROR")) {
   var game=h.initAndCreateGame();var player=game.getPlayers().get(1);var ability=h.addCardToZone("Grizzly Bears",player,ZoneType.Hand).getFirstSpellAbility();ability.setActivatingPlayer(player);
   var first=h.addCardToZone("Forest",player,ZoneType.Battlefield);var second=h.addCardToZone("Forest",player,ZoneType.Battlefield);var sources=new ArrayList<forge.game.spellability.SpellAbility>();sources.add(first.getManaAbilities().get(0));sources.add(second.getManaAbilities().get(0));for(var source:sources){source.setActivatingPlayer(player);}
   var rows=new ArrayList<Map<String,Object>>();game.setRecordAiDecisions(true);game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c){rows.add(c.data());}});
   switch(label) {
    case "ACTUAL_SUCCESSIVE_PAYMENT" -> {if(!ComputerUtilMana.payManaCost(ability.getPayCosts(),player,ability,false)||!first.isTapped()||!second.isTapped()){throw new AssertionError(label);}}
    case "CONTROLLED_POST_SELECTION_REJECTION" -> {
     var cantor=h.addCardToZone("Wild Cantor",player,ZoneType.Battlefield);var bad=cantor.getManaAbilities().get(0);bad.setActivatingPlayer(player);var pump=h.addCardToZone("Giant Growth",player,ZoneType.Hand).getFirstSpellAbility();pump.setActivatingPlayer(player);pump.getTargets().add(cantor);var calls=new java.util.concurrent.atomic.AtomicInteger();
     try(var methods=Mockito.mockStatic(ComputerUtilMana.class,Mockito.CALLS_REAL_METHODS)) {
      methods.when(()->ComputerUtilMana.chooseManaAbility(Mockito.any(),Mockito.same(pump),Mockito.same(player),Mockito.any(),Mockito.anyCollection(),Mockito.eq(true))).thenAnswer(call->calls.getAndIncrement()==0?bad:call.callRealMethod());
      if(!ComputerUtilMana.payManaCost(pump.getPayCosts(),player,pump,false)||!cantor.isInPlay()||pump.getPayingManaAbilities().contains(bad)){throw new AssertionError(label);}
     }
    }
    case "FROZEN_PLANNING" -> {var cost=new ManaCostBeingPaid(ManaCost.ONE);var chosen=sources.get(0);if(AiChoiceRecorder.planning(player,()->AiPaymentChoiceRecorder.manaSource(player,ability,cost,ManaCostShard.GREEN,sources,false,true,()->{sources.clear();cost.addManaCost(ManaCost.ONE);player.setLife(7,null);return chosen;}))!=chosen){throw new AssertionError(label);}}
    case "DECLINED_SOURCE" -> AiPaymentChoiceRecorder.manaSource(player,ability,new ManaCostBeingPaid(ManaCost.ONE),ManaCostShard.GENERIC,sources,true,false,()->null);
    case "UNKNOWN_COST" -> AiPaymentChoiceRecorder.manaSource(player,ability,new ManaCostBeingPaid(ManaCost.ONE){},ManaCostShard.GENERIC,sources,true,false,()->sources.get(0));
    case "ORIGINAL_ERROR" -> {var expected=new IllegalStateException("original");try{AiPaymentChoiceRecorder.manaSource(player,ability,new ManaCostBeingPaid(ManaCost.ONE),ManaCostShard.GENERIC,sources,true,false,()->{throw expected;});throw new AssertionError(label);}catch(IllegalStateException actual){if(actual!=expected){throw new AssertionError(label);}}}
    default -> throw new AssertionError(label);
   }
   if(game.getEventObservationFailure()!=null){throw new AssertionError(label,game.getEventObservationFailure());}examples.put(label,rows);
  }
  Files.writeString(Path.of(args[0]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(Map.of("fixtureKind","PAYMENT_ENGINE_AND_ADAPTER_EXCERPTS","completeGameTrace",false,"scenarioNotes",Map.of("CONTROLLED_POST_SELECTION_REJECTION","The selector dependency proposes the targeted sacrifice source once; the native rejection/payment loop runs unchanged."),"choices",examples))+"\n");System.exit(0);
 }
}
