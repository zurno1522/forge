package forge.ai;
import com.google.gson.GsonBuilder;
import forge.game.zone.ZoneType;
import java.util.*;
import java.nio.file.*;
/** Actual copy-target controller excerpts; not complete game envelopes. */
public final class CopyInputExamples extends AITest {
 public static void main(String[] args) throws Exception {
  var h=new CopyInputExamples();h.initializeModel();var examples=new LinkedHashMap<String,Object>();
  for(var label:List.of("RESELECT","ILLEGAL_FALLBACK","PLANNING_DELEGATE")) {
   var game=h.initAndCreateGame();var player=game.getPlayers().get(1);var other=game.getPlayers().get(0);
   var giant=h.addCardToZone("Hill Giant",other,ZoneType.Battlefield);var bears=h.addCardToZone("Grizzly Bears",other,ZoneType.Battlefield);
   var host=h.addCardToZone("Unsummon",player,ZoneType.Hand);var original=host.getFirstSpellAbility();original.setActivatingPlayer(player);original.getTargets().add(giant);
   original.setHostCard(game.getAction().moveToStack(host,original));game.getStack().add(original);var copy=original.copy(player);copy.resetTargets();copy.getTargets().add(giant);
   var rows=new ArrayList<Map<String,Object>>();game.setRecordAiDecisions(true);game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c){rows.add(c.data());}});
   switch(label) {
    case "RESELECT" -> {((PlayerControllerAi)player.getController()).chooseNewTargetsForCopy(copy);if(copy.getTargets().getFirstTargetedCard()!=bears){throw new AssertionError(label);}}
    case "ILLEGAL_FALLBACK" -> {game.getAction().moveToGraveyard(giant,null);game.getAction().moveToGraveyard(bears,null);var old=copy.getTargets();((PlayerControllerAi)player.getController()).chooseNewTargetsForCopy(copy);if(copy.getTargets()!=old||copy.canTarget(giant)){throw new AssertionError(label);}}
    case "PLANNING_DELEGATE" -> {copy.getMapParams().put("TargetingPlayer","Opponent");var own=h.addCardToZone("Shivan Dragon",player,ZoneType.Battlefield);AiChoiceRecorder.planning(player,()->{((PlayerControllerAi)player.getController()).chooseNewTargetsForCopy(copy);return true;});if(copy.getTargets().getFirstTargetedCard()!=own||copy.getTargetingPlayer()!=other){throw new AssertionError(label);}}
    default -> throw new AssertionError(label);
   }
   if(game.getEventObservationFailure()!=null){throw new AssertionError(label,game.getEventObservationFailure());}examples.put(label,rows);
  }
  Files.writeString(Path.of(args[0]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(Map.of("fixtureKind","ACTUAL_COPY_TARGET_CONTROLLER_EXCERPTS","completeGameTrace",false,"choices",examples))+"\n");System.exit(0);
 }
}
