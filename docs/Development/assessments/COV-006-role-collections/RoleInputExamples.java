package forge.ai;
import com.google.gson.GsonBuilder;
import forge.game.ability.AbilityFactory;
import forge.game.ability.effects.AttachEffect;
import forge.game.ability.effects.MustBlockEffect;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollection;
import java.util.*;
import java.nio.file.*;
/** Actual role request/controller excerpts, not complete game envelopes. */
public final class RoleInputExamples extends AITest {
 public static void main(String[] args) throws Exception {
  var h=new RoleInputExamples();h.initializeModel();var examples=new LinkedHashMap<String,Object>();
  for(var label:List.of("ATTACH_EFFECT","MUST_BLOCK_EFFECT","DUPLICATE_PLANNING","PRIVATE_MEMBER","OVERSIZED_REQUEST")) {
   var game=h.initAndCreateGame();var player=game.getPlayers().get(1);var other=game.getPlayers().get(0);
   var host=h.addCardToZone("Lightning Greaves",player,ZoneType.Battlefield);var card=h.addCardToZone("Grizzly Bears",player,ZoneType.Battlefield);
   var ability=AbilityFactory.getAbility("AB$ Attach | Cost$ 0 | ValidTgts$ Creature.YouCtrl",host);ability.setActivatingPlayer(player);
   var rows=new ArrayList<Map<String,Object>>();game.setRecordAiDecisions(true);game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c){rows.add(c.data());}});
   switch(label) {
    case "ATTACH_EFFECT" -> {ability.getTargets().add(card);new AttachEffect().resolve(ability);if(host.getAttachedTo()!=card){throw new AssertionError(label);}}
    case "MUST_BLOCK_EFFECT" -> {var attacker=h.addCardToZone("Shivan Dragon",player,ZoneType.Battlefield);var blocker=h.addCardToZone("Hill Giant",other,ZoneType.Battlefield);var block=AbilityFactory.getAbility("AB$ MustBlock | Cost$ 0 | Choices$ Creature.OppCtrl",attacker);block.setActivatingPlayer(player);new MustBlockEffect().resolve(block);if(!blocker.getMustBlockCards().contains(attacker)){throw new AssertionError(label);}}
    default -> {
     var params=new HashMap<String,Object>();var roles=new ArrayList<forge.game.card.Card>();roles.add(card);
     if(label.equals("DUPLICATE_PLANNING")){roles.add(host);roles.add(card);}
     if(label.equals("PRIVATE_MEMBER")){roles.add(h.addCardToZone("Black Lotus",other,ZoneType.Hand));}
     if(label.equals("OVERSIZED_REQUEST")){roles=new ArrayList<>(Collections.nCopies(65,card));}
     params.put("Attachments",roles);
     java.util.function.Supplier<forge.game.card.Card> select=()->AiEffectChoiceRecorder.single(player,ability,new FCollection<>(List.of(card)),false,null,params,()->card);
     if((label.equals("DUPLICATE_PLANNING")?AiChoiceRecorder.planning(player,select):select.get())!=card){throw new AssertionError(label);}
    }
   }
   if(game.getEventObservationFailure()!=null){throw new AssertionError(label,game.getEventObservationFailure());}examples.put(label,rows);
  }
  Files.writeString(Path.of(args[0]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(Map.of("fixtureKind","ACTUAL_ROLE_REQUEST_CONTROLLER_EXCERPTS","completeGameTrace",false,"choices",examples))+"\n");System.exit(0);
 }
}
