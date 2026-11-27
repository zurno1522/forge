package forge.ai;

import forge.ai.simulation.ComboPlan;
import forge.ai.simulation.DeckPlan;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class AiExileRankingInputTest extends AITest {
    private record Fixture(Game game,Player ai,SpellAbility ability,CardCollection cards,List<Map<String,Object>> events) { }
    private Fixture fixture(){
        final Game game=initAndCreateGame();final Player ai=game.getPlayers().get(1);final SpellAbility ability=addCardToZone("Lightning Bolt",ai,ZoneType.Hand).getFirstSpellAbility();ability.setActivatingPlayer(ai);
        final var cards=new CardCollection(List.of(addCardToZone("Shivan Dragon",ai,ZoneType.Battlefield),addCardToZone("Llanowar Elves",ai,ZoneType.Battlefield)));
        final var events=new ArrayList<Map<String,Object>>();game.setRecordAiDecisions(true);game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c){events.add(c.data());}});return new Fixture(game,ai,ability,cards,events);
    }
    private Map<String,Object> start(Fixture f,String boundary){return f.events.stream().filter(c->boundary.equals(c.get("boundary"))).findFirst().orElseThrow();}
    private Map<?,?> values(Map<?,?> c){return (Map<?,?>)((Map<?,?>)((Map<?,?>)c.get("inputContext")).get("policy")).get("receivedParameters");}
    private List<?> pool(Map<?,?> c){return (List<?>)((Map<?,?>)((Map<?,?>)c.get("options")).get("cards")).get("items");}
    private CardCollection choose(Fixture f,int amount,boolean effect){return ComputerUtil.chooseExileFromList(f.ai,f.cards,f.ability.getHostCard(),amount,f.ability,effect);}
    private SpellAbility craft(Fixture f){final var source=addCardToZone("Clay-Fired Bricks",f.ai,ZoneType.Battlefield);final var ability=source.getSpellAbilities().stream().filter(SpellAbility::isCraft).findFirst().orElseThrow();ability.setActivatingPlayer(f.ai);return ability;}

    @Test(groups={"UnitTest","fast"})
    public void actualPowerRankingKeepsLegacyApplicationWithoutUpgradingItsEntry(){
        final var f=fixture();final Card first=f.cards.get(0),chosen=f.cards.get(1);final var result=choose(f,1,false);Assert.assertEquals(result.size(),1);Assert.assertSame(result.get(0),chosen);Assert.assertSame(f.cards.get(0),first);Assert.assertTrue(chosen.isInPlay());
        final var ranking=start(f,"PAYMENT_EXILE_RANKING");final var application=start(f,"PAYMENT_EXILE_POOL");Assert.assertEquals(((Map<?,?>)pool(ranking).get(0)).get("id"),first.getId());Assert.assertEquals(((Map<?,?>)pool(application).get(0)).get("id"),chosen.getId());Assert.assertEquals(application.get("parentChoiceId"),ranking.get("choiceId"));
        Assert.assertEquals(values(ranking).get("requestedSelections"),1);Assert.assertEquals(values(ranking).get("effect"),false);Assert.assertFalse(application.containsKey("entryLocation"));Assert.assertFalse(application.containsKey("inputContext"));Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void entryPrecedesTargetSafetyFiltering(){
        final var f=fixture();f.ability.getTargets().add(f.cards.get(1));Assert.assertSame(choose(f,1,false).get(0),f.cards.get(0));Assert.assertEquals(pool(start(f,"PAYMENT_EXILE_RANKING")).size(),2);Assert.assertEquals(pool(start(f,"PAYMENT_EXILE_POOL")).size(),1);Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void nativeCraftRemovesHighTierBattlefieldCardsAndRanksGraveyardBeforeCheaperPermanents(){
        final var f=fixture();final var ability=craft(f);final Card high=addCardToZone("Worn Powerstone",f.ai,ZoneType.Battlefield),cheap=addCardToZone("Sol Ring",f.ai,ZoneType.Battlefield),grave=addCardToZone("Mind Stone",f.ai,ZoneType.Graveyard),other=addCardToZone("Darksteel Ingot",f.ai,ZoneType.Graveyard);
        final var supplied=new CardCollection(List.of(high,cheap,other,grave));final var result=ComputerUtil.chooseExileFromList(f.ai,supplied,ability.getHostCard(),2,ability,false);Assert.assertEquals(result,new CardCollection(List.of(grave,other)));Assert.assertSame(supplied.get(0),high);Assert.assertEquals(pool(start(f,"PAYMENT_EXILE_RANKING")).size(),4);Assert.assertEquals(pool(start(f,"PAYMENT_EXILE_POOL")).size(),3);Assert.assertTrue(high.isInPlay());Assert.assertTrue(grave.isInZone(ZoneType.Graveyard));Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void nativeCraftAndInsufficientDeclinesDoNotInventAnApplication(){
        final var f=fixture();Assert.assertNull(choose(f,3,false));Assert.assertEquals(f.events.get(1).get("status"),"DECLINED");Assert.assertFalse(f.events.stream().anyMatch(c->"PAYMENT_EXILE_POOL".equals(c.get("boundary"))));
        f.events.clear();final var ability=craft(f);final var cards=new CardCollection(addCardToZone("Worn Powerstone",f.ai,ZoneType.Battlefield));Assert.assertNull(ComputerUtil.chooseExileFromList(f.ai,cards,ability.getHostCard(),1,ability,false));Assert.assertEquals(f.events.size(),2);Assert.assertEquals(f.events.get(1).get("status"),"DECLINED");Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void protectedComboCostDeclinesWhileEffectRetainsItsOriginalFallback() throws Exception {
        final var f=fixture();for(String name:List.of("Reanimate","Animate Dead","Entomb","Griselbrand","Archon of Cruelty","Faithless Looting","Careful Study","Swamp")){for(int i=0;i<4;i++){addCardToZone(name,f.ai,ZoneType.Library);}}
        final var plan=DeckPlan.forSetting("auto",f.ai);Assert.assertTrue(plan.combos.has(ComboPlan.Kind.REANIMATOR));final var brain=((PlayerControllerAi)f.ai.getController()).getAi();final var field=AiController.class.getDeclaredField("flatMcDeckPlan");field.setAccessible(true);field.set(brain,plan);
        final Card protectedCard=addCardToZone("Reanimate",f.ai,ZoneType.Hand);final var cards=new CardCollection(protectedCard);Assert.assertNull(ComputerUtil.chooseExileFromList(f.ai,cards,f.ability.getHostCard(),1,f.ability,false));Assert.assertEquals(f.events.size(),2);
        f.events.clear();Assert.assertSame(ComputerUtil.chooseExileFromList(f.ai,cards,f.ability.getHostCard(),1,f.ability,true).get(0),protectedCard);Assert.assertEquals(values(start(f,"PAYMENT_EXILE_RANKING")).get("effect"),true);Assert.assertTrue(protectedCard.isInZone(ZoneType.Hand));Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void nativeZeroAndNegativeCountsArePreservedWithoutClamping(){
        final var f=fixture();Assert.assertTrue(choose(f,0,false).isEmpty());Assert.assertEquals(values(start(f,"PAYMENT_EXILE_RANKING")).get("requestedSelections"),0);Assert.assertTrue(f.events.stream().anyMatch(c->"PAYMENT_EXILE_POOL".equals(c.get("boundary"))));
        f.events.clear();Assert.expectThrows(IllegalArgumentException.class,()->choose(f,-1,false));Assert.assertEquals(values(start(f,"PAYMENT_EXILE_RANKING")).get("requestedSelections"),-1);Assert.assertEquals(f.events.stream().filter(c->"ERROR".equals(c.get("status"))).count(),2L);Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void frozenPlanningEntryPreservesOriginalResultAndErrorIdentities(){
        final var f=fixture();final var selected=new CardCollection(f.cards.get(0));final var calls=new AtomicInteger();Assert.assertSame(AiChoiceRecorder.planning(f.ai,()->AiPaymentChoiceRecorder.exileRanking(f.ai,f.ability,f.cards,Integer.MIN_VALUE,true,()->{calls.incrementAndGet();f.cards.clear();return selected;})),selected);
        final var c=start(f,"PAYMENT_EXILE_RANKING");Assert.assertEquals(pool(c).size(),2);Assert.assertEquals(values(c).get("requestedSelections"),Integer.MIN_VALUE);Assert.assertEquals(((Map<?,?>)c.get("inputContext")).get("stateBasis"),"PLANNING_WORKING_STATE");Assert.assertEquals(calls.get(),1);
        final var error=new IllegalStateException("original");Assert.assertSame(Assert.expectThrows(IllegalStateException.class,()->AiPaymentChoiceRecorder.exileRanking(f.ai,f.ability,f.cards,1,false,()->{throw error;})),error);Assert.assertEquals(f.events.get(f.events.size()-1).get("status"),"ERROR");Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] bypass(){return new Object[][]{{"OFF"},{"COPY"},{"HIDDEN"}};}
    @Test(dataProvider="bypass",groups={"UnitTest","fast"})
    public void bypassedRankingDoesNotReadItsDiagnosticPool(String mode){
        final var f=fixture();final var unknown=Mockito.spy(f.cards);Mockito.doThrow(new AssertionError("bypassed iterator")).when(unknown).iterator();if(mode.equals("OFF")){f.game.setRecordAiDecisions(false);}if(mode.equals("COPY")){f.game.markAsSimulationCopy();}if(mode.equals("HIDDEN")){f.ability.getHostCard().turnFaceDown();}
        Assert.assertSame(AiPaymentChoiceRecorder.exileRanking(f.ai,f.ability,unknown,1,false,()->f.cards),f.cards);Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void receivedScalarsUseThreeWorkUnitsAndOneNewFixedCell(){
        final var f=fixture();final var pc=(PlayerControllerAi)f.ai.getController();final var state=pc.inputContextState();long before=state.remainingWork();AiInputContext.capture(f.ai,f.ability,"BEFORE_OPERATION","RULES_STATE",()->true);final long base=before-state.remainingWork();before=state.remainingWork();
        AiInputContext.capture(f.ai,f.ability,"BEFORE_OPERATION","RULES_STATE",()->true,null,null,new AiReceivedParameters.Carrier(new AtomicReference<>(AiReceivedParameters.exileRanking(1,false))));Assert.assertEquals(before-state.remainingWork(),base+3);
        final var ledger=pc.configureObservationAccounting();choose(f,1,false);final var cell=(Map<?,?>)((Map<?,?>)ledger.snapshot().get("boundaries")).get("PAYMENT_EXILE_RANKING");Assert.assertEquals(cell.get("entered"),1L);Assert.assertEquals(cell.get("finished"),1L);Assert.assertEquals(cell.get("observationFailures"),0L);Assert.assertNull(f.game.getEventObservationFailure());
    }
}
