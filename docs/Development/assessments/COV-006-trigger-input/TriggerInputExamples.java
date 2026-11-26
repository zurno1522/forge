package forge.ai;
import com.google.gson.GsonBuilder;
import forge.game.ability.AbilityFactory;
import forge.game.phase.PhaseType;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import java.util.*;
import java.nio.file.*;
/** Actual preparation/controller excerpts; not complete game envelopes. */
public final class TriggerInputExamples extends AITest {
 public static void main(String[] args) throws Exception {
  var h=new TriggerInputExamples();h.initializeModel();var examples=new LinkedHashMap<String,Object>();
  for(var label:List.of("SIMULTANEOUS_MANDATORY","IMMEDIATE_OPTIONAL","MODAL_MANDATORY","FALSE_AFTER_MUTATION","UNKNOWN_DIRECTIVE","PLANNING_DELEGATE","ORIGINAL_ERROR")) {
   var game=h.initAndCreateGame();var player=game.getPlayers().get(1);var other=game.getPlayers().get(0);
   var host=h.addCardToZone(label.equals("MODAL_MANDATORY")?"Knight of Autumn":"Flametongue Kavu",player,ZoneType.Battlefield);var target=h.addCardToZone("Grizzly Bears",other,ZoneType.Battlefield);
   var ability=AbilityFactory.getAbility("AB$ DealDamage | Cost$ 0 | ValidTgts$ Creature.OppCtrl | TgtPrompt$ Choose | NumDmg$ 1",host);ability.setActivatingPlayer(player);
   var rows=new ArrayList<Map<String,Object>>();game.setRecordAiDecisions(true);game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c){rows.add(c.data());}});
   switch(label) {
    case "SIMULTANEOUS_MANDATORY","IMMEDIATE_OPTIONAL","MODAL_MANDATORY" -> {
     var trigger=host.getTriggers().get(0);var inner=trigger.ensureAbility();inner.setActivatingPlayer(player);var wrapped=new WrappedAbility(trigger,inner,null);wrapped.setActivatingPlayer(player);
     if(!label.equals("IMMEDIATE_OPTIONAL")){player.getController().orderAndPlaySimultaneousSa(List.of(wrapped));if(game.getStack().isEmpty()){throw new AssertionError(label);}}
     else if(!player.getController().playTrigger(host,wrapped,false)){throw new AssertionError(label);}
    }
    case "FALSE_AFTER_MUTATION" -> {if(AiChoiceRecorder.triggerPreparation(player,ability,false,()->{player.setLife(7,null);game.getPhaseHandler().devModeSet(PhaseType.MAIN2,player);ability.getTargets().add(target);return false;})){throw new AssertionError(label);}}
    case "UNKNOWN_DIRECTIVE" -> AiChoiceRecorder.triggerPreparation(player,ability,()->true);
    case "PLANNING_DELEGATE" -> AiChoiceRecorder.planning(player,()->AiChoiceRecorder.triggerPreparation(player,ability,true,()->{AiNumberChoiceRecorder.controllerRange(other,ability,0,3,()->2);return true;}));
    case "ORIGINAL_ERROR" -> {var expected=new IllegalStateException("original");try{AiChoiceRecorder.triggerPreparation(player,ability,true,()->{throw expected;});throw new AssertionError(label);}catch(IllegalStateException actual){if(actual!=expected){throw new AssertionError(label);}}}
    default -> throw new AssertionError(label);
   }
   if(game.getEventObservationFailure()!=null){throw new AssertionError(label);}examples.put(label,rows);
  }
  Files.writeString(Path.of(args[0]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(Map.of("fixtureKind","ACTUAL_PREPARATION_CONTROLLER_EXCERPTS","completeGameTrace",false,"choices",examples))+"\n");System.exit(0);
 }
}
