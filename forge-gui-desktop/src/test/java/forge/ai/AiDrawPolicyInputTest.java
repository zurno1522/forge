package forge.ai;

import forge.ai.ability.DrawAi;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class AiDrawPolicyInputTest extends AITest {
    private record Fixture(Game game,Player ai,Player opp,SpellAbility ability,List<Map<String,Object>> events) { }
    private Fixture fixture(String name,int mana,int library){
        final Game game=initAndCreateGame();final Player ai=game.getPlayers().get(1),opp=game.getPlayers().get(0);
        for(int i=0;i<mana;i++){addCardToZone(name.equals("Damnable Pact")?"Swamp":"Island",ai,ZoneType.Battlefield);}for(int i=0;i<library;i++){addCardToZone("Forest",ai,ZoneType.Library);}for(int i=0;i<30;i++){addCardToZone("Mountain",opp,ZoneType.Library);}
        final var ability=addCardToZone(name,ai,ZoneType.Hand).getFirstSpellAbility();ability.setActivatingPlayer(ai);final var events=new ArrayList<Map<String,Object>>();game.setRecordAiDecisions(true);game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c){events.add(c.data());}});return new Fixture(game,ai,opp,ability,events);
    }
    private Map<String,Object> start(Fixture f){return f.events.stream().filter(c->"DRAW_POLICY_RESULT".equals(c.get("choiceType"))).findFirst().orElseThrow();}
    private Map<?,?> context(Map<?,?> c){return (Map<?,?>)c.get("inputContext");}
    private Map<?,?> values(Map<?,?> c){return (Map<?,?>)((Map<?,?>)context(c).get("policy")).get("receivedParameters");}
    private Map<String,Object> end(Fixture f){final var first=start(f);return f.events.stream().filter(c->first.get("choiceId").equals(c.get("choiceId"))&&"COMPLETED".equals(c.get("status"))).findFirst().orElseThrow();}
    private List<Integer> refinements(Fixture f){final var result=new ArrayList<Integer>();for(var c:f.events){if("X_ANNOUNCEMENT_REFINEMENT".equals(c.get("boundary"))){Assert.assertFalse(c.containsKey("entryLocation"));Assert.assertFalse(c.containsKey("inputContext"));Assert.assertEquals(c.get("parentChoiceId"),start(f).get("choiceId"));final Object id=c.get("choiceId");final var terminal=f.events.stream().filter(e->id.equals(e.get("choiceId"))&&"COMPLETED".equals(e.get("status"))).findFirst().orElseThrow();result.add((Integer)((Map<?,?>)terminal.get("selection")).get("value"));}}return result;}

    @Test(groups={"UnitTest","fast"})
    public void fixedDrawHasOnePolicyResultWithoutInventingNumericChoices(){
        final var f=fixture("Divination",4,30);Assert.assertTrue(new DrawAi().chkDrawback(f.ai,f.ability).willingToPlay());Assert.assertEquals(f.events.size(),2);Assert.assertEquals(values(start(f)).get("mandatory"),false);Assert.assertEquals(((Map<?,?>)end(f).get("selection")).get("result"),true);Assert.assertEquals(((Map<?,?>)start(f).get("entryLocation")).get("kind"),"OPERATION");Assert.assertTrue(refinements(f).isEmpty());Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void nativeEntryPrecedesAmountCalculationAndTargetResetWithLibrarySafetyRefinements(){
        final var f=fixture("Blue Sun's Zenith",12,4);f.ability.getTargets().add(f.opp);final var before=new ArrayList<Integer>();final var priorTargets=new ArrayList<Boolean>();
        f.game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c&&"DRAW_POLICY_RESULT".equals(c.data().get("choiceType"))){before.add(f.ability.getXManaCostPaid());priorTargets.add(f.ability.getTargets().getFirstTargetedPlayer()==f.opp);}});
        Assert.assertTrue(new DrawAi().chkDrawback(f.ai,f.ability).willingToPlay());Assert.assertEquals(before.size(),1);Assert.assertNull(before.get(0));Assert.assertEquals(priorTargets,List.of(true));Assert.assertEquals(refinements(f),List.of(2,3));Assert.assertEquals(f.ability.getXManaCostPaid(),Integer.valueOf(3));Assert.assertSame(f.ability.getTargets().getFirstTargetedPlayer(),f.ai);Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void dredgeBreakthroughPreservesItsZeroAssignmentBeforeGenericDrawWork(){
        final var f=fixture("Breakthrough",4,30);Assert.assertTrue(new DrawAi().chkDrawback(f.ai,f.ability).willingToPlay());Assert.assertEquals(refinements(f),List.of(0));Assert.assertEquals(f.ability.getXManaCostPaid(),Integer.valueOf(0));Assert.assertFalse(f.events.stream().anyMatch(c->"MAX_X_CALCULATION".equals(c.get("boundary"))));Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void opponentLifeLossRefinesTheActualRootAndSelectsThatOpponent(){
        final var f=fixture("Damnable Pact",20,30);f.opp.setLife(3,null);Assert.assertTrue(new DrawAi().chkDrawback(f.ai,f.ability).willingToPlay());Assert.assertEquals(refinements(f),List.of(8,3));Assert.assertEquals(f.ability.getXManaCostPaid(),Integer.valueOf(3));Assert.assertSame(f.ability.getTargets().getFirstTargetedPlayer(),f.opp);Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void negativeLifeGainUsesItsSeparateNativeRefinementBranch(){
        final var f=fixture("Damnable Pact",20,30);f.opp.setLife(3,null);addCardToZone("Tainted Remedy",f.ai,ZoneType.Battlefield);final var gain=(AbilitySub)AbilityFactory.getAbility("DB$ GainLife | Defined$ Targeted | LifeAmount$ X",f.ability.getHostCard());f.ability.setSubAbility(gain);Assert.assertTrue(ComputerUtil.lifegainNegative(f.opp,f.ability.getHostCard()));
        Assert.assertTrue(new DrawAi().chkDrawback(f.ai,f.ability).willingToPlay());Assert.assertEquals(refinements(f),List.of(8,3));Assert.assertSame(f.ability.getTargets().getFirstTargetedPlayer(),f.opp);Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void cachedDrawbackUsesTheReceivedSubabilityAndAppliesHandSafetyWithoutRecomputingMaxX(){
        final var f=fixture("Drown in Dreams",15,30);addCardToZone("Forest",f.ai,ZoneType.Hand);final var sub=(AbilitySub)AbilityFactory.getAbility("DB$ Draw | NumCards$ X | ValidTgts$ Player",f.ability.getHostCard());f.ability.setSubAbility(sub);sub.setActivatingPlayer(f.ai);f.ability.setXManaCostPaid(10);
        Assert.assertTrue(new DrawAi().chkDrawback(f.ai,sub).willingToPlay());Assert.assertEquals(refinements(f),List.of(10,6));Assert.assertEquals(f.ability.getXManaCostPaid(),Integer.valueOf(6));Assert.assertSame(sub.getTargets().getFirstTargetedPlayer(),f.ai);Assert.assertFalse(f.events.stream().anyMatch(c->"MAX_X_CALCULATION".equals(c.get("boundary"))));Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void falseHelperReturnAfterXAndTargetMutationsRemainsACompletedBooleanResult(){
        final var f=fixture("Blue Sun's Zenith",3,30);f.ability.getTargets().add(f.opp);Assert.assertFalse(new DrawAi().chkDrawback(f.ai,f.ability).willingToPlay());Assert.assertEquals(f.ability.getXManaCostPaid(),Integer.valueOf(0));Assert.assertTrue(f.ability.getTargets().isEmpty());Assert.assertEquals(refinements(f),List.of(0));Assert.assertEquals(((Map<?,?>)end(f).get("selection")).get("result"),false);Assert.assertEquals(end(f).get("status"),"COMPLETED");Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void mandatoryDispatchRetainsTheExactHelperFlagInsteadOfInventingPlayability(){
        final var f=fixture("Divination",4,1);Assert.assertTrue(new DrawAi().doTriggerNoCostWithSubs(f.ai,f.ability,true).willingToPlay());Assert.assertEquals(values(start(f)).get("mandatory"),true);Assert.assertEquals(((Map<?,?>)end(f).get("selection")).get("result"),true);Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void planningInputsFreezeBeforeOriginalMutationAndErrorsPreserveIdentity(){
        final var f=fixture("Divination",4,30);final var calls=new AtomicInteger();Assert.assertFalse(AiChoiceRecorder.planning(f.ai,()->AiDrawChoiceRecorder.policy(f.ai,f.ability,false,()->{calls.incrementAndGet();f.ai.setLife(7,null);return false;})));final var c=start(f);Assert.assertEquals(context(c).get("stateBasis"),"PLANNING_WORKING_STATE");final var players=(List<?>)((Map<?,?>)context(c).get("policy")).get("players");Assert.assertEquals(((Map<?,?>)players.get(1)).get("life"),20);Assert.assertEquals(calls.get(),1);
        final var error=new IllegalStateException("original");Assert.assertSame(Assert.expectThrows(IllegalStateException.class,()->AiDrawChoiceRecorder.policy(f.ai,f.ability,false,()->{throw error;})),error);Assert.assertEquals(f.events.get(3).get("status"),"ERROR");AiDrawChoiceRecorder.policy(f.ai,f.ability,false,()->true);Assert.assertNull(f.events.get(4).get("parentChoiceId"));Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] bypass(){return new Object[][]{{"OFF"},{"COPY"},{"HIDDEN"}};}
    @Test(dataProvider="bypass",groups={"UnitTest","fast"})
    public void bypassedDrawPolicyDoesNotTraverseInputAndCallsItsOriginalBodyOnce(String mode){
        final var f=fixture("Divination",4,30);if(mode.equals("OFF")){f.game.setRecordAiDecisions(false);}if(mode.equals("COPY")){f.game.markAsSimulationCopy();}if(mode.equals("HIDDEN")){f.ability.getHostCard().turnFaceDown();}final var state=((PlayerControllerAi)f.ai.getController()).inputContextState();final long before=state.remainingWork();final var calls=new AtomicInteger();Assert.assertTrue(AiDrawChoiceRecorder.policy(f.ai,f.ability,false,()->{calls.incrementAndGet();return true;}));Assert.assertEquals(calls.get(),1);Assert.assertEquals(state.remainingWork(),before);Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void scalarRequestUsesTwoWorkUnitsAndOneNewFixedCell(){
        final var f=fixture("Divination",4,30);final var pc=(PlayerControllerAi)f.ai.getController();final var state=pc.inputContextState();long before=state.remainingWork();AiInputContext.capture(f.ai,f.ability,"BEFORE_OPERATION","RULES_STATE",()->true);final long base=before-state.remainingWork();before=state.remainingWork();AiInputContext.capture(f.ai,f.ability,"BEFORE_OPERATION","RULES_STATE",()->true,null,null,new AiReceivedParameters.Carrier(new AtomicReference<>(AiReceivedParameters.drawPolicy(false))));Assert.assertEquals(before-state.remainingWork(),base+2);
        final var ledger=pc.configureObservationAccounting();new DrawAi().chkDrawback(f.ai,f.ability);final var cell=(Map<?,?>)((Map<?,?>)ledger.snapshot().get("boundaries")).get("DRAW_AMOUNT_TARGET_POLICY");Assert.assertEquals(cell.get("entered"),1L);Assert.assertEquals(cell.get("finished"),1L);Assert.assertEquals(cell.get("observationFailures"),0L);Assert.assertNull(f.game.getEventObservationFailure());
    }
}
