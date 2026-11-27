package forge.ai;
import com.google.gson.GsonBuilder;
import forge.ai.ability.DamageDealAi;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;
import java.util.*;
import java.nio.file.*;
/** Native Damage policy and adapter excerpts; not complete game traces. */
public final class DamagePolicyExamples extends AITest {
 private static final class Probe extends DamageDealAi {
  AiAbilityDecision assess(forge.game.player.Player p,forge.game.spellability.SpellAbility s){return super.canPlay(p,s);}
  AiAbilityDecision trigger(forge.game.player.Player p,forge.game.spellability.SpellAbility s,boolean m){return super.doTriggerNoCost(p,s,m);}
 }
 public static void main(String[] args) throws Exception {
  var h=new DamagePolicyExamples();h.initializeModel();var examples=new LinkedHashMap<String,Object>();
  for(var label:List.of("PLAY_KILL","SORIN","TRIGGER_OPTIONAL","TRIGGER_MANDATORY","LIFE_DRAIN","LIFE_DRAIN_FALSE","FIXED_DAMAGE","ZERO_ASSESSMENT","FROZEN_PLANNING","ORIGINAL_ERROR")) {
   var game=h.initAndCreateGame();var player=game.getPlayers().get(1);var opponent=game.getPlayers().get(0);
   String name=label.equals("SORIN")?"Sorin, Grim Nemesis":label.startsWith("LIFE_DRAIN")?"Drain Life":label.equals("FIXED_DAMAGE")||label.equals("FROZEN_PLANNING")||label.equals("ORIGINAL_ERROR")?"Lightning Bolt":"Fireball";
   int mana=label.equals("SORIN")?0:label.equals("ZERO_ASSESSMENT")||label.equals("LIFE_DRAIN_FALSE")?1:10;for(int i=0;i<mana;i++){h.addCardToZone(name.equals("Drain Life")?"Swamp":"Mountain",player,ZoneType.Battlefield);}
   var source=h.addCardToZone(name,player,label.equals("SORIN")?ZoneType.Battlefield:ZoneType.Hand);var root=label.equals("SORIN")?source.getSpellAbilities().stream().filter(s->s.getApi()==ApiType.DealDamage).findFirst().orElseThrow():source.getFirstSpellAbility();root.setActivatingPlayer(player);var ability=name.equals("Drain Life")?root.findSubAbilityByType(ApiType.DealDamage):root;ability.setActivatingPlayer(player);
   if(label.equals("SORIN")){source.setCounters(CounterEnumType.LOYALTY,6);}Card target=null;if(label.equals("PLAY_KILL")||label.equals("SORIN")||label.startsWith("TRIGGER_")){target=h.addCardToZone("Shivan Dragon",opponent,ZoneType.Battlefield);ability.setTargetRestrictions(new TargetRestrictions(Map.of("ValidTgts","Creature")));}
   var rows=new ArrayList<Map<String,Object>>();game.setRecordAiDecisions(true);game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c){rows.add(c.data());}});
   switch(label) {
    case "PLAY_KILL","SORIN" -> {if(label.equals("PLAY_KILL")){ability.getTargets().add(player);}if(!new Probe().assess(player,ability).willingToPlay()||ability.getTargetCard()!=target||root.getXManaCostPaid()!=5){throw new AssertionError(label);}}
    case "TRIGGER_OPTIONAL","TRIGGER_MANDATORY" -> {if(!new Probe().trigger(player,ability,label.equals("TRIGGER_MANDATORY")).willingToPlay()||root.getXManaCostPaid()!=5){throw new AssertionError(label);}}
    case "LIFE_DRAIN" -> {if(!new DamageDealAi().chkDrawback(player,ability).willingToPlay()||root.getXManaCostPaid()!=8||root.getTargets().getFirstTargetedPlayer()!=opponent){throw new AssertionError(label);}}
    case "LIFE_DRAIN_FALSE" -> {root.getTargets().add(player);if(new DamageDealAi().chkDrawback(player,ability).willingToPlay()||root.getTargets().getFirstTargetedPlayer()!=player){throw new AssertionError(label);}}
    case "FIXED_DAMAGE" -> new Probe().assess(player,ability);
    case "ZERO_ASSESSMENT" -> {if(new Probe().assess(player,ability).willingToPlay()||root.getXManaCostPaid()!=0){throw new AssertionError(label);}}
    case "FROZEN_PLANNING" -> {var expected=new AiAbilityDecision(-3,AiPlayDecision.CantPlayAi);if(AiChoiceRecorder.planning(player,()->AiDamageChoiceRecorder.trigger(player,ability,true,()->{player.setLife(7,null);return expected;}))!=expected){throw new AssertionError(label);}}
    case "ORIGINAL_ERROR" -> {var expected=new IllegalStateException("original");try{AiDamageChoiceRecorder.canPlay(player,ability,()->{throw expected;});throw new AssertionError(label);}catch(IllegalStateException actual){if(actual!=expected){throw new AssertionError(label);}}}
    default -> throw new AssertionError(label);
   }
   if(game.getEventObservationFailure()!=null){throw new AssertionError(label,game.getEventObservationFailure());}examples.put(label,rows);
  }
  Files.writeString(Path.of(args[0]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(Map.of("fixtureKind","DAMAGE_POLICY_NATIVE_AND_ADAPTER_EXCERPTS","completeGameTrace",false,"choices",examples))+"\n");System.exit(0);
 }
}
