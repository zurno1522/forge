package forge.ai;
import com.google.gson.GsonBuilder;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.cost.Cost;
import forge.game.cost.CostPayment;
import forge.game.mana.*;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import java.util.*;
import java.nio.file.*;
/** Actual engine/AI payment excerpts; not complete game envelopes. */
public final class ManaPoolInputExamples extends AITest {
 public static void main(String[] args) throws Exception {
  var h=new ManaPoolInputExamples();h.initializeModel();var examples=new LinkedHashMap<String,Object>();
  for(var label:List.of("REAL_ENGINE_SPENDING","TEST_ENGINE_UNDER_RULES_PARENT","AI_AFFORDABILITY","LEGACY_UNKNOWN","NON_TEST_IN_PLANNING","NULL_SHARD","ORIGINAL_ERROR")) {
   var game=h.initAndCreateGame();var player=game.getPlayers().get(1);var pc=(PlayerControllerAi)player.getController();
   var ability=h.addCardToZone("Lightning Bolt",player,ZoneType.Hand).getFirstSpellAbility();ability.setActivatingPlayer(player);var land=h.addCardToZone("Mountain",player,ZoneType.Battlefield);
   var red=new Mana(MagicColor.RED,land,null,player);var green=new Mana(MagicColor.GREEN,land,null,player);player.getManaPool().addMana(red);player.getManaPool().addMana(green);var options=new ArrayList<>(List.of(red,green));
   var rows=new ArrayList<Map<String,Object>>();game.setRecordAiDecisions(true);game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c){rows.add(c.data());}});
   var request=new ManaPoolChoiceContext(ability,ManaCostShard.GENERIC,false,(byte)-1);
   switch(label) {
    case "REAL_ENGINE_SPENDING" -> {var cost=new ManaCostBeingPaid(ManaCost.ONE);var spent=new ArrayList<Mana>();if(!player.getManaPool().payManaCostFromPool(cost,ability,false,spent)||spent.size()!=1||spent.get(0)!=red||player.getManaPool().totalMana()!=1){throw new AssertionError(label);}}
    case "TEST_ENGINE_UNDER_RULES_PARENT" -> {var cost=new ManaCostBeingPaid(ManaCost.ONE);var spent=new ArrayList<Mana>();if(!AiChoiceRecorder.targeting(player,ability,"EXECUTION",()->player.getManaPool().payManaCostFromPool(cost,ability,true,spent))||!spent.isEmpty()||player.getManaPool().totalMana()!=2){throw new AssertionError(label);}}
    case "AI_AFFORDABILITY" -> {if(!ComputerUtilMana.canPayManaCost(new Cost("1",false),ability,player,0,false)||player.getManaPool().totalMana()!=2){throw new AssertionError(label);}}
    case "LEGACY_UNKNOWN" -> AiChoiceRecorder.targeting(player,ability,"EXECUTION",()->{if(CostPayment.getMana(player,ManaCostShard.GENERIC,ability,(byte)-1,Map.of())!=red){throw new AssertionError(label);}return true;});
    case "NON_TEST_IN_PLANNING" -> AiChoiceRecorder.planning(player,()->pc.chooseManaFromPool(options,request));
    case "NULL_SHARD" -> pc.chooseManaFromPool(options,new ManaPoolChoiceContext(ability,null,false,(byte)0));
    case "ORIGINAL_ERROR" -> {var expected=new IllegalStateException("original");try{AiPaymentChoiceRecorder.withPoolRequest(player,pc,options,request,()->AiPaymentChoiceRecorder.manaFromPool(player,options,()->{player.setLife(7,null);game.getPhaseHandler().devModeSet(PhaseType.MAIN2,player);throw expected;}));throw new AssertionError(label);}catch(IllegalStateException actual){if(actual!=expected){throw new AssertionError(label);}}}
    default -> throw new AssertionError(label);
   }
   if(game.getEventObservationFailure()!=null){throw new AssertionError(label,game.getEventObservationFailure());}examples.put(label,rows);
  }
  Files.writeString(Path.of(args[0]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(Map.of("fixtureKind","ACTUAL_MANA_POOL_ENGINE_CONTROLLER_EXCERPTS","completeGameTrace",false,"choices",examples))+"\n");System.exit(0);
 }
}
