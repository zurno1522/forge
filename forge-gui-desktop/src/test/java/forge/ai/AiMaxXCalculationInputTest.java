package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class AiMaxXCalculationInputTest extends AITest {
    private record Fixture(Game game,Player ai,SpellAbility ability,List<Map<String,Object>> events) { }
    private Fixture fixture(String name,int mana){
        final Game game=initAndCreateGame();final Player ai=game.getPlayers().get(1);for(int i=0;i<mana;i++){addCardToZone("Mountain",ai,ZoneType.Battlefield);}final var ability=addCardToZone(name,ai,ZoneType.Hand).getFirstSpellAbility();ability.setActivatingPlayer(ai);
        final var events=new ArrayList<Map<String,Object>>();game.setRecordAiDecisions(true);game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c){events.add(c.data());}});return new Fixture(game,ai,ability,events);
    }
    private Map<String,Object> start(Fixture f,String boundary){return f.events.stream().filter(c->boundary.equals(c.get("boundary"))).findFirst().orElseThrow();}
    private Map<?,?> values(Map<?,?> c){return (Map<?,?>)((Map<?,?>)((Map<?,?>)c.get("inputContext")).get("policy")).get("receivedParameters");}

    @DataProvider public Object[][] xCosts(){return new Object[][]{{"Fireball",4,3},{"Hangarback Walker",4,2},{"Fireball",1,0}};}
    @Test(dataProvider="xCosts",groups={"UnitTest","fast"})
    public void nativeManaDoubleXAndZeroKeepTheirOriginalRootValuesAndFinalAssignment(String card,int mana,int expected){
        final var f=fixture(card,mana);Assert.assertEquals(ComputerUtilCost.setMaxXValue(f.ability,f.ai,false),expected);Assert.assertEquals(f.ability.getXManaCostPaid(),Integer.valueOf(expected));
        final var calc=start(f,"MAX_X_CALCULATION");final var assign=start(f,"MAX_X_HEURISTIC");Assert.assertEquals(((Map<?,?>)calc.get("entryLocation")).get("kind"),"OPERATION");Assert.assertEquals(values(calc).get("effect"),false);Assert.assertEquals(assign.get("parentChoiceId"),calc.get("choiceId"));Assert.assertFalse(assign.containsKey("entryLocation"));Assert.assertFalse(assign.containsKey("inputContext"));
        final var end=f.events.stream().filter(c->calc.get("choiceId").equals(c.get("choiceId"))&&"COMPLETED".equals(c.get("status"))).findFirst().orElseThrow();Assert.assertEquals(((Map<?,?>)end.get("selection")).get("value"),expected);Assert.assertEquals(((Map<?,?>)end.get("selection")).get("domainMatch"),"UNAVAILABLE");Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void ownEntryPrecedesAIXMaxProvisionalMutationWhileTheOldAssignmentKeepsItsLaterValue(){
        final var f=fixture("Fireball",4);final var sub=(forge.game.spellability.AbilitySub)forge.game.ability.AbilityFactory.getAbility("DB$ Draw | NumCards$ X | Defined$ You | AIXMax$ 2",f.ability.getHostCard());f.ability.setSubAbility(sub);sub.setActivatingPlayer(f.ai);final Integer initial=f.ability.getXManaCostPaid();final var seen=new ArrayList<Integer>();
        f.game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c&&"STARTED".equals(c.data().get("status"))&&"MAX_X_CALCULATION".equals(c.data().get("boundary"))){seen.add(f.ability.getXManaCostPaid());}});
        Assert.assertEquals(ComputerUtilCost.setMaxXValue(sub,f.ai,false),2);Assert.assertEquals(seen.size(),1);Assert.assertEquals(seen.get(0),initial);Assert.assertEquals(((Map<?,?>)start(f,"MAX_X_HEURISTIC").get("options")).get("previousValue"),3);Assert.assertEquals(f.ability.getXManaCostPaid(),Integer.valueOf(2));Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] guards(){return new Object[][]{{"NO_X"},{"NULL_COST"},{"NOT_FREE"},{"CACHED_CHARM"}};}
    @Test(dataProvider="guards",groups={"UnitTest","fast"})
    public void nativeNonFreeAndCachedGuardsDoNotInventCalculations(String guard){
        final var f=fixture(guard.equals("NO_X")?"Lightning Bolt":guard.equals("CACHED_CHARM")?"Drown in Dreams":"Fireball",4);if(guard.equals("NULL_COST")){f.ability.setPayCosts(null);}if(guard.equals("NOT_FREE")){f.ability.setSVar("X","Number$3");}if(guard.equals("CACHED_CHARM")){f.ability.setXManaCostPaid(4);}
        final var pc=(PlayerControllerAi)f.ai.getController();final long work=pc.inputContextState().remainingWork();final var ledger=pc.configureObservationAccounting();Assert.assertEquals(ComputerUtilCost.setMaxXValue(f.ability,f.ai,false),guard.equals("CACHED_CHARM")?4:0);Assert.assertTrue(f.events.isEmpty());Assert.assertEquals(pc.inputContextState().remainingWork(),work);
        Assert.assertFalse(((Map<?,?>)ledger.snapshot().get("boundaries")).containsKey("MAX_X_CALCULATION"));Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void nativeTargetXLimitStillCapsTheReturnedValue(){
        final var f=fixture("Fireball",4);addCardToZone("Grizzly Bears",f.ai,ZoneType.Battlefield);f.ability.setTargetRestrictions(new TargetRestrictions(Map.of("ValidTgts","Creature","TargetMin","X","TargetMax","X")));
        Assert.assertEquals(ComputerUtilCost.setMaxXValue(f.ability,f.ai,false),1);Assert.assertEquals(f.ability.getXManaCostPaid(),Integer.valueOf(1));Assert.assertNotNull(start(f,"MAX_X_CALCULATION"));Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void nonManaXPreferenceProbesLimitAValueWithoutSacrificingOrInventingPaymentPicks(){
        final var f=fixture("Lightning Bolt",0);f.ability.setPayCosts(new Cost("Sac<X/Creature>",true));f.ability.setSVar("X","Count$xPaid");final Card bear=addCardToZone("Grizzly Bears",f.ai,ZoneType.Battlefield),dragon=addCardToZone("Shivan Dragon",f.ai,ZoneType.Battlefield);bear.setSVar("SacMe","6");dragon.setSVar("SacMe","5");
        Assert.assertEquals(ComputerUtilCost.setMaxXValue(f.ability,f.ai,false),2);Assert.assertTrue(bear.isInPlay());Assert.assertTrue(dragon.isInPlay());Assert.assertFalse(f.events.stream().anyMatch(c->"PAYMENT_SACRIFICE_PICK".equals(c.get("boundary"))));Assert.assertEquals(((Map<?,?>)start(f,"MAX_X_HEURISTIC").get("options")).get("maximum"),2);Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void inheritedPlanningOriginalErrorsAndSignedReturnsKeepTheirNativeMeaning(){
        final var f=fixture("Fireball",1);final var calls=new AtomicInteger();Assert.assertEquals(AiChoiceRecorder.planning(f.ai,()->AiNumberChoiceRecorder.calculateMaxX(f.ai,f.ability,true,()->{calls.incrementAndGet();return -2;})),Integer.valueOf(-2));final var c=start(f,"MAX_X_CALCULATION");Assert.assertEquals(values(c).get("effect"),true);Assert.assertEquals(((Map<?,?>)c.get("inputContext")).get("stateBasis"),"PLANNING_WORKING_STATE");Assert.assertEquals(calls.get(),1);Assert.assertEquals(((Map<?,?>)f.events.get(1).get("selection")).get("value"),-2);
        final var error=new IllegalStateException("original");Assert.assertSame(Assert.expectThrows(IllegalStateException.class,()->AiNumberChoiceRecorder.calculateMaxX(f.ai,f.ability,false,()->{throw error;})),error);Assert.assertEquals(f.events.get(3).get("status"),"ERROR");AiNumberChoiceRecorder.calculateMaxX(f.ai,f.ability,false,()->0);Assert.assertNull(f.events.get(4).get("parentChoiceId"));Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] bypass(){return new Object[][]{{"OFF"},{"COPY"},{"HIDDEN"}};}
    @Test(dataProvider="bypass",groups={"UnitTest","fast"})
    public void bypassedCalculationsKeepCallbackOnceWithoutInputTraversal(String mode){
        final var f=fixture("Fireball",1);if(mode.equals("OFF")){f.game.setRecordAiDecisions(false);}if(mode.equals("COPY")){f.game.markAsSimulationCopy();}if(mode.equals("HIDDEN")){f.ability.getHostCard().turnFaceDown();}final var state=((PlayerControllerAi)f.ai.getController()).inputContextState();final long before=state.remainingWork();final var calls=new AtomicInteger();Assert.assertEquals(AiNumberChoiceRecorder.calculateMaxX(f.ai,f.ability,false,()->{calls.incrementAndGet();return 3;}),3);Assert.assertEquals(calls.get(),1);Assert.assertEquals(state.remainingWork(),before);Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void scalarRequestUsesTwoWorkUnitsAndOneNewFixedCell(){
        final var f=fixture("Fireball",1);final var pc=(PlayerControllerAi)f.ai.getController();final var state=pc.inputContextState();long before=state.remainingWork();AiInputContext.capture(f.ai,f.ability,"BEFORE_OPERATION","RULES_STATE",()->true);final long base=before-state.remainingWork();before=state.remainingWork();
        AiInputContext.capture(f.ai,f.ability,"BEFORE_OPERATION","RULES_STATE",()->true,null,null,new AiReceivedParameters.Carrier(new AtomicReference<>(AiReceivedParameters.maxXCalculation(false))));Assert.assertEquals(before-state.remainingWork(),base+2);
        final var ledger=pc.configureObservationAccounting();AiNumberChoiceRecorder.calculateMaxX(f.ai,f.ability,false,()->0);final var cell=(Map<?,?>)((Map<?,?>)ledger.snapshot().get("boundaries")).get("MAX_X_CALCULATION");Assert.assertEquals(cell.get("entered"),1L);Assert.assertEquals(cell.get("finished"),1L);Assert.assertEquals(cell.get("observationFailures"),0L);Assert.assertNull(f.game.getEventObservationFailure());
    }
}
