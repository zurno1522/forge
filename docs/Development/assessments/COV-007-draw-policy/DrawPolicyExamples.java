package forge.ai;
import com.google.gson.GsonBuilder;
import forge.ai.ability.DrawAi;
import forge.game.ability.AbilityFactory;
import forge.game.spellability.AbilitySub;
import forge.game.zone.ZoneType;
import java.util.*;
import java.nio.file.*;
/** Native Draw helper and adapter excerpts; not complete game traces. */
public final class DrawPolicyExamples extends AITest {
 public static void main(String[] args) throws Exception {
  var h=new DrawPolicyExamples();h.initializeModel();var examples=new LinkedHashMap<String,Object>();
  for(var label:List.of("FIXED_DRAW","LIBRARY_SAFETY","DREDGE_ZERO","OPPONENT_LIFE","NEGATIVE_GAIN","CACHED_HAND_SAFETY","FALSE_AFTER_X","MANDATORY_FIXED","FROZEN_PLANNING","ORIGINAL_ERROR")) {
   var game=h.initAndCreateGame();var player=game.getPlayers().get(1);var opponent=game.getPlayers().get(0);
   String name=label.equals("DREDGE_ZERO")?"Breakthrough":label.equals("OPPONENT_LIFE")||label.equals("NEGATIVE_GAIN")?"Damnable Pact":label.equals("CACHED_HAND_SAFETY")?"Drown in Dreams":label.equals("LIBRARY_SAFETY")||label.equals("FALSE_AFTER_X")?"Blue Sun's Zenith":"Divination";
   int mana=label.equals("FALSE_AFTER_X")?3:20,library=label.equals("LIBRARY_SAFETY")?4:label.equals("MANDATORY_FIXED")?1:30;
   for(int i=0;i<mana;i++){h.addCardToZone(name.equals("Damnable Pact")?"Swamp":"Island",player,ZoneType.Battlefield);}for(int i=0;i<library;i++){h.addCardToZone("Forest",player,ZoneType.Library);}for(int i=0;i<30;i++){h.addCardToZone("Mountain",opponent,ZoneType.Library);}
   var ability=h.addCardToZone(name,player,ZoneType.Hand).getFirstSpellAbility();ability.setActivatingPlayer(player);var rows=new ArrayList<Map<String,Object>>();game.setRecordAiDecisions(true);game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c){rows.add(c.data());}});
   switch(label) {
    case "FIXED_DRAW","LIBRARY_SAFETY","DREDGE_ZERO" -> {if(label.equals("LIBRARY_SAFETY")){ability.getTargets().add(opponent);}if(!new DrawAi().chkDrawback(player,ability).willingToPlay()){throw new AssertionError(label);}}
    case "OPPONENT_LIFE","NEGATIVE_GAIN" -> {opponent.setLife(3,null);if(label.equals("NEGATIVE_GAIN")){h.addCardToZone("Tainted Remedy",player,ZoneType.Battlefield);ability.setSubAbility((AbilitySub)AbilityFactory.getAbility("DB$ GainLife | Defined$ Targeted | LifeAmount$ X",ability.getHostCard()));if(!ComputerUtil.lifegainNegative(opponent,ability.getHostCard())){throw new AssertionError(label);}}if(!new DrawAi().chkDrawback(player,ability).willingToPlay()||ability.getXManaCostPaid()!=3||ability.getTargets().getFirstTargetedPlayer()!=opponent){throw new AssertionError(label);}}
    case "CACHED_HAND_SAFETY" -> {h.addCardToZone("Forest",player,ZoneType.Hand);var sub=(AbilitySub)AbilityFactory.getAbility("DB$ Draw | NumCards$ X | ValidTgts$ Player",ability.getHostCard());ability.setSubAbility(sub);sub.setActivatingPlayer(player);ability.setXManaCostPaid(10);if(!new DrawAi().chkDrawback(player,sub).willingToPlay()||ability.getXManaCostPaid()!=6){throw new AssertionError(label);}}
    case "FALSE_AFTER_X" -> {ability.getTargets().add(opponent);if(new DrawAi().chkDrawback(player,ability).willingToPlay()||ability.getXManaCostPaid()!=0||!ability.getTargets().isEmpty()){throw new AssertionError(label);}}
    case "MANDATORY_FIXED" -> {if(!new DrawAi().doTriggerNoCostWithSubs(player,ability,true).willingToPlay()){throw new AssertionError(label);}}
    case "FROZEN_PLANNING" -> {if(AiChoiceRecorder.planning(player,()->AiDrawChoiceRecorder.policy(player,ability,false,()->{player.setLife(7,null);return false;}))){throw new AssertionError(label);}}
    case "ORIGINAL_ERROR" -> {var expected=new IllegalStateException("original");try{AiDrawChoiceRecorder.policy(player,ability,false,()->{throw expected;});throw new AssertionError(label);}catch(IllegalStateException actual){if(actual!=expected){throw new AssertionError(label);}}}
    default -> throw new AssertionError(label);
   }
   if(game.getEventObservationFailure()!=null){throw new AssertionError(label,game.getEventObservationFailure());}examples.put(label,rows);
  }
  Files.writeString(Path.of(args[0]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(Map.of("fixtureKind","DRAW_POLICY_NATIVE_AND_ADAPTER_EXCERPTS","completeGameTrace",false,"choices",examples))+"\n");System.exit(0);
 }
}
