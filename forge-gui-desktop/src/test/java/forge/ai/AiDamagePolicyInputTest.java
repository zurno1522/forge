package forge.ai;

import forge.ai.ability.DamageDealAi;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class AiDamagePolicyInputTest extends AITest {
    private static final class Probe extends DamageDealAi {
        AiAbilityDecision assess(Player p,SpellAbility sa){return super.canPlay(p,sa);}
        AiAbilityDecision trigger(Player p,SpellAbility sa,boolean mandatory){return super.doTriggerNoCost(p,sa,mandatory);}
    }
    private record Fixture(Game game,Player ai,Player opp,Card source,SpellAbility root,SpellAbility ability,List<Map<String,Object>> events) { }
    private Fixture fixture(String name,int mana){
        final Game game=initAndCreateGame();final Player ai=game.getPlayers().get(1),opp=game.getPlayers().get(0);for(int i=0;i<mana;i++){addCardToZone(name.equals("Drain Life")?"Swamp":"Mountain",ai,ZoneType.Battlefield);}
        final Card source=addCardToZone(name,ai,name.equals("Sorin, Grim Nemesis")?ZoneType.Battlefield:ZoneType.Hand);final SpellAbility root=name.equals("Sorin, Grim Nemesis")?source.getSpellAbilities().stream().filter(sa->sa.getApi()==ApiType.DealDamage).findFirst().orElseThrow():source.getFirstSpellAbility();root.setActivatingPlayer(ai);
        final SpellAbility ability=name.equals("Drain Life")?root.findSubAbilityByType(ApiType.DealDamage):root;ability.setActivatingPlayer(ai);if(name.equals("Sorin, Grim Nemesis")){source.setCounters(CounterEnumType.LOYALTY,6);}
        final var events=new ArrayList<Map<String,Object>>();game.setRecordAiDecisions(true);game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c){events.add(c.data());}});return new Fixture(game,ai,opp,source,root,ability,events);
    }
    private Map<String,Object> start(Fixture f,String op){return f.events.stream().filter(c->"DAMAGE_POLICY_RESULT".equals(c.get("choiceType"))&&op.equals(((Map<?,?>)c.get("options")).get("operation"))).findFirst().orElseThrow();}
    private Map<?,?> context(Map<?,?> c){return (Map<?,?>)c.get("inputContext");}
    private Map<?,?> values(Map<?,?> c){return (Map<?,?>)((Map<?,?>)context(c).get("policy")).get("receivedParameters");}
    private Map<String,Object> end(Fixture f,String op){final var start=start(f,op);return f.events.stream().filter(c->start.get("choiceId").equals(c.get("choiceId"))&&"COMPLETED".equals(c.get("status"))).findFirst().orElseThrow();}
    private List<Integer> refinements(Fixture f,String op){final var result=new ArrayList<Integer>();for(final var c:f.events){if("X_ANNOUNCEMENT_REFINEMENT".equals(c.get("boundary"))){Assert.assertEquals(c.get("parentChoiceId"),start(f,op).get("choiceId"));Assert.assertFalse(c.containsKey("entryLocation"));Assert.assertFalse(c.containsKey("inputContext"));final var terminal=f.events.stream().filter(e->c.get("choiceId").equals(e.get("choiceId"))&&"COMPLETED".equals(e.get("status"))).findFirst().orElseThrow();result.add((Integer)((Map<?,?>)terminal.get("selection")).get("value"));}}return result;}
    private Card dragon(Fixture f){final Card card=addCardToZone("Shivan Dragon",f.opp,ZoneType.Battlefield);f.ability.setTargetRestrictions(new TargetRestrictions(Map.of("ValidTgts","Creature")));return card;}

    @Test(groups={"UnitTest","fast"})
    public void playAssessmentEntryPrecedesXCalculationAndTargetResetThenRetainsCheaperKillPayment(){
        final var f=fixture("Fireball",10);final Card target=dragon(f);f.ability.getTargets().add(f.ai);final var initial=new ArrayList<Integer>();final var targets=new ArrayList<Boolean>();f.game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c&&"DAMAGE_POLICY_RESULT".equals(c.data().get("choiceType"))){initial.add(f.root.getXManaCostPaid());targets.add(f.ability.getTargets().getFirstTargetedPlayer()==f.ai);}});
        Assert.assertTrue(new Probe().assess(f.ai,f.ability).willingToPlay());Assert.assertEquals(initial.size(),1);Assert.assertNull(initial.get(0));Assert.assertEquals(targets,List.of(true));Assert.assertSame(f.ability.getTargetCard(),target);Assert.assertEquals(refinements(f,"CAN_PLAY"),List.of(5));Assert.assertEquals(f.root.getXManaCostPaid(),Integer.valueOf(5));Assert.assertNull(values(start(f,"CAN_PLAY")).get("mandatory"));Assert.assertEquals(((Map<?,?>)end(f,"CAN_PLAY").get("selection")).get("decision"),"WillPlay");Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void sorinRefinesFromTheNativeLoyaltyProbeWithoutSpendingCounters(){
        final var f=fixture("Sorin, Grim Nemesis",0);final Card target=dragon(f);Assert.assertTrue(new Probe().assess(f.ai,f.ability).willingToPlay());Assert.assertEquals(refinements(f,"CAN_PLAY"),List.of(5));Assert.assertSame(f.ability.getTargetCard(),target);Assert.assertEquals(f.root.getXManaCostPaid(),Integer.valueOf(5));Assert.assertEquals(f.source.getCounters(CounterEnumType.LOYALTY),6);Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] flags(){return new Object[][]{{false},{true}};}
    @Test(dataProvider="flags",groups={"UnitTest","fast"})
    public void triggerAssessmentPreservesBothMandatoryFlagsAndNativePaymentRefinement(boolean mandatory){
        final var f=fixture("Fireball",10);final Card target=dragon(f);Assert.assertTrue(new Probe().trigger(f.ai,f.ability,mandatory).willingToPlay());Assert.assertEquals(values(start(f,"TRIGGER")).get("mandatory"),mandatory);Assert.assertEquals(refinements(f,"TRIGGER"),List.of(5));Assert.assertSame(f.ability.getTargetCard(),target);Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void lifeDrainUsesReceivedSubabilityButRefinesAndTargetsTheOriginalRoot(){
        final var f=fixture("Drain Life",10);Assert.assertTrue(new DamageDealAi().chkDrawback(f.ai,f.ability).willingToPlay());Assert.assertEquals(refinements(f,"LIFE_DRAIN"),List.of(8));Assert.assertEquals(f.root.getXManaCostPaid(),Integer.valueOf(8));Assert.assertSame(f.root.getTargets().getFirstTargetedPlayer(),f.opp);Assert.assertEquals(((Map<?,?>)end(f,"LIFE_DRAIN").get("selection")).get("result"),true);Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void lifeDrainFailurePreservesTargetsAndCompletesWithFalseWithoutInventingAnAssignment(){
        final var f=fixture("Drain Life",1);f.root.getTargets().add(f.ai);Assert.assertFalse(new DamageDealAi().chkDrawback(f.ai,f.ability).willingToPlay());Assert.assertTrue(refinements(f,"LIFE_DRAIN").isEmpty());Assert.assertSame(f.root.getTargets().getFirstTargetedPlayer(),f.ai);Assert.assertEquals(((Map<?,?>)end(f,"LIFE_DRAIN").get("selection")).get("result"),false);Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void fixedDamageAndZeroRatedAssessmentRetainNativeDecisionData(){
        final var f=fixture("Lightning Bolt",4);final var actual=new Probe().assess(f.ai,f.ability);final var result=(Map<?,?>)end(f,"CAN_PLAY").get("selection");Assert.assertEquals(result.get("rating"),actual.rating());Assert.assertEquals(result.get("decision"),actual.decision().name());Assert.assertTrue(refinements(f,"CAN_PLAY").isEmpty());Assert.assertNull(f.game.getEventObservationFailure());
        final var low=fixture("Fireball",1);final var rejected=new Probe().assess(low.ai,low.ability);Assert.assertFalse(rejected.willingToPlay());Assert.assertEquals(rejected.rating(),0);Assert.assertEquals(((Map<?,?>)end(low,"CAN_PLAY").get("selection")).get("rating"),0);Assert.assertEquals(low.root.getXManaCostPaid(),Integer.valueOf(0));Assert.assertTrue(refinements(low,"CAN_PLAY").isEmpty());Assert.assertNull(low.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void decisionIdentityFrozenPlanningFlagsAndOriginalErrorsSurviveObservation(){
        final var f=fixture("Lightning Bolt",4);final var nativeResult=new AiAbilityDecision(-3,AiPlayDecision.CantPlayAi);final var calls=new AtomicInteger();Assert.assertSame(AiChoiceRecorder.planning(f.ai,()->AiDamageChoiceRecorder.trigger(f.ai,f.ability,true,()->{calls.incrementAndGet();f.ai.setLife(7,null);return nativeResult;})),nativeResult);Assert.assertEquals(calls.get(),1);final var c=start(f,"TRIGGER");Assert.assertEquals(values(c).get("mandatory"),true);Assert.assertEquals(context(c).get("stateBasis"),"PLANNING_WORKING_STATE");final var players=(List<?>)((Map<?,?>)context(c).get("policy")).get("players");Assert.assertEquals(((Map<?,?>)players.get(1)).get("life"),20);
        final var error=new IllegalStateException("original");Assert.assertSame(Assert.expectThrows(IllegalStateException.class,()->AiDamageChoiceRecorder.canPlay(f.ai,f.ability,()->{throw error;})),error);Assert.assertEquals(f.events.get(3).get("status"),"ERROR");AiDamageChoiceRecorder.lifeDrain(f.ai,f.ability,()->false);Assert.assertNull(f.events.get(4).get("parentChoiceId"));Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] bypass(){return new Object[][]{{"OFF"},{"COPY"},{"HIDDEN"}};}
    @Test(dataProvider="bypass",groups={"UnitTest","fast"})
    public void bypassedAssessmentsPreserveResultsAndSkipInputTraversal(String mode){
        final var f=fixture("Lightning Bolt",4);if(mode.equals("OFF")){f.game.setRecordAiDecisions(false);}if(mode.equals("COPY")){f.game.markAsSimulationCopy();}if(mode.equals("HIDDEN")){f.source.turnFaceDown();}final var state=((PlayerControllerAi)f.ai.getController()).inputContextState();final long before=state.remainingWork();final var nativeResult=new AiAbilityDecision(100,AiPlayDecision.WillPlay);final var calls=new AtomicInteger();Assert.assertSame(AiDamageChoiceRecorder.canPlay(f.ai,f.ability,()->{calls.incrementAndGet();return nativeResult;}),nativeResult);Assert.assertFalse(AiDamageChoiceRecorder.lifeDrain(f.ai,f.ability,()->{calls.incrementAndGet();return false;}));Assert.assertEquals(calls.get(),2);Assert.assertEquals(state.remainingWork(),before);Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void observationPreservesNativeRngTailAndDamageChoice(){
        final Random saved=MyRandom.getRandom();String reason=null;long tail=0;Integer x=null;
        try{for(boolean record:List.of(false,true)){final var f=fixture("Fireball",10);dragon(f);f.game.setRecordAiDecisions(record);MyRandom.setRandom(new Random(78129));final var result=new Probe().assess(f.ai,f.ability);final long after=MyRandom.getRandom().nextLong();if(!record){reason=result.decision().name();tail=after;x=f.root.getXManaCostPaid();}else{Assert.assertEquals(result.decision().name(),reason);Assert.assertEquals(after,tail);Assert.assertEquals(f.root.getXManaCostPaid(),x);}Assert.assertNull(f.game.getEventObservationFailure());}}finally{MyRandom.setRandom(saved);}
    }

    @Test(groups={"UnitTest","fast"})
    public void receivedScopeCostsThreeUnitsAndUsesThreeFixedCells(){
        final var f=fixture("Lightning Bolt",4);final var pc=(PlayerControllerAi)f.ai.getController();final var state=pc.inputContextState();long before=state.remainingWork();AiInputContext.capture(f.ai,f.ability,"BEFORE_OPERATION","RULES_STATE",()->true);final long base=before-state.remainingWork();before=state.remainingWork();AiInputContext.capture(f.ai,f.ability,"BEFORE_OPERATION","RULES_STATE",()->true,null,null,new AiReceivedParameters.Carrier(new AtomicReference<>(AiReceivedParameters.damagePolicy(AiDamageChoiceRecorder.Operation.CAN_PLAY,null))));Assert.assertEquals(before-state.remainingWork(),base+3);
        final var ledger=pc.configureObservationAccounting();final var decision=new AiAbilityDecision(0,AiPlayDecision.CantPlayAi);AiDamageChoiceRecorder.canPlay(f.ai,f.ability,()->decision);AiDamageChoiceRecorder.trigger(f.ai,f.ability,false,()->decision);AiDamageChoiceRecorder.lifeDrain(f.ai,f.ability,()->false);
        for(String op:List.of("CAN_PLAY","TRIGGER","LIFE_DRAIN")){final var cell=(Map<?,?>)((Map<?,?>)ledger.snapshot().get("boundaries")).get("DAMAGE_"+op+"_POLICY");Assert.assertEquals(cell.get("entered"),1L);Assert.assertEquals(cell.get("finished"),1L);Assert.assertEquals(cell.get("observationFailures"),0L);}Assert.assertNull(f.game.getEventObservationFailure());
    }
}
