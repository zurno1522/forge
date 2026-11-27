package forge.ai;

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

public class AiDiscardPickInputTest extends AITest {
    private record Fixture(Game game, Player ai, AiController brain, SpellAbility ability, CardCollection cards, List<Map<String,Object>> events) { }
    private Fixture fixture() {
        final Game game=initAndCreateGame(); final Player ai=game.getPlayers().get(1);
        final SpellAbility ability=addCardToZone("Lightning Bolt",ai,ZoneType.Hand).getFirstSpellAbility(); ability.setActivatingPlayer(ai);
        final var cards=new CardCollection(List.of(addCardToZone("Forest",ai,ZoneType.Hand),addCardToZone("Shivan Dragon",ai,ZoneType.Hand),addCardToZone("Grizzly Bears",ai,ZoneType.Hand)));
        final var events=new ArrayList<Map<String,Object>>(); game.setRecordAiDecisions(true);game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c){events.add(c.data());}});
        return new Fixture(game,ai,((PlayerControllerAi)ai.getController()).getAi(),ability,cards,events);
    }
    private List<Map<String,Object>> picks(Fixture f) {return f.events.stream().filter(c->"PAYMENT_CARD_PICK".equals(c.get("choiceType"))).toList();}
    private Map<?,?> context(Map<?,?> c){return (Map<?,?>)c.get("inputContext");}
    private Map<?,?> values(Map<?,?> c){return (Map<?,?>)((Map<?,?>)context(c).get("policy")).get("receivedParameters");}
    private Map<?,?> raw(Map<?,?> c){return (Map<?,?>)((Map<?,?>)c.get("options")).get("callbackParameters");}
    private Card pick(Fixture f,int min,int count,CardCollection prior,java.util.function.Supplier<Card> cb){return AiPaymentChoiceRecorder.discardPreference(f.ai,f.ability,f.cards,min,min,count,prior,cb);}
    private List<?> pool(Map<?,?> c){return (List<?>)((Map<?,?>)((Map<?,?>)c.get("options")).get("cards")).get("items");}

    @Test(groups={"UnitTest","fast"})
    public void nativePreferencePicksShrinkTheirPoolAndKeepOwnHandProposalHistory() {
        final var f=fixture();final Card first=f.cards.get(0),second=f.cards.get(2); first.setSVar("DiscardMe","5");second.setSVar("DiscardMe","4");
        final var result=f.brain.getCardsToDiscard(2,2,f.cards,f.ability);Assert.assertEquals(result,new CardCollection(List.of(first,second)));Assert.assertEquals(f.cards.size(),1);
        final var starts=picks(f);Assert.assertEquals(starts.size(),2);
        for(int i=0;i<2;i++){final var c=starts.get(i);Assert.assertEquals(c.get("boundary"),"PAYMENT_DISCARD_PREFERENCE");Assert.assertEquals(values(c).get("priorSelectionCount"),i);Assert.assertEquals(pool(c).size(),3-i);Assert.assertEquals(((List<?>)values(c).get("previousSelections")).size(),i);Assert.assertEquals(values(c).get("effectiveMinimum"),2);}
        Assert.assertTrue(first.isInZone(ZoneType.Hand));Assert.assertTrue(second.isInZone(ZoneType.Hand));Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void declinedPreferenceFallsThroughToNativeFallbackSortingAndMutations() {
        final var f=fixture();final Card dragon=f.cards.get(1),bear=f.cards.get(2);final var result=f.brain.getCardsToDiscard(2,2,f.cards,f.ability);
        Assert.assertEquals(result,new CardCollection(List.of(dragon,bear)));Assert.assertEquals(f.cards.size(),1);Assert.assertEquals(f.cards.get(0).getName(),"Forest");
        final var starts=picks(f);Assert.assertEquals(starts.size(),3);Assert.assertEquals(starts.get(0).get("boundary"),"PAYMENT_DISCARD_PREFERENCE");
        Assert.assertTrue(f.events.stream().anyMatch(c->starts.get(0).get("choiceId").equals(c.get("choiceId"))&&"DECLINED".equals(c.get("status"))));
        for(int i=0;i<2;i++){final var c=starts.get(i+1);Assert.assertEquals(c.get("boundary"),"PAYMENT_DISCARD_FALLBACK");Assert.assertEquals(context(c).get("timing"),"BEFORE_OPERATION");Assert.assertEquals(values(c).get("priorSelectionCount"),i);Assert.assertEquals(pool(c).size(),3-i);final var end=f.events.stream().filter(e->c.get("choiceId").equals(e.get("choiceId"))&&"COMPLETED".equals(e.get("status"))).findFirst().orElseThrow();Assert.assertEquals(((Map<?,?>)end.get("selection")).get("id"),result.get(i).getId());}
        Assert.assertEquals(((Map<?,?>)pool(starts.get(1)).get(0)).get("id"),f.cards.get(0).getId());Assert.assertTrue(dragon.isInZone(ZoneType.Hand));Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] overrides(){return new Object[][]{{"Always"},{"UnlessAtLife.20"}};}
    @Test(dataProvider="overrides",groups={"UnitTest","fast"})
    public void iterationsReceiveEffectiveCountsAfterNativeLogicOverrides(String logic){
        final var f=fixture();f.ability.getMapParams().put("AILogic",logic);final var result=f.brain.getCardsToDiscard(0,1,f.cards,f.ability);Assert.assertEquals(result.size(),1);
        for(final var c:picks(f)){Assert.assertEquals(values(c).get("effectiveMinimum"),1);Assert.assertEquals(values(c).get("maximum"),1);}Assert.assertFalse(picks(f).isEmpty());Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void opponentPreferenceAndRejectedProtectedPreferenceKeepTheirNativeBranches(){
        final var f=fixture();final Card first=f.cards.get(0);first.setSVar("DiscardMeByOpp","1");f.ability.setActivatingPlayer(f.game.getPlayers().get(0));Assert.assertSame(f.brain.getCardsToDiscard(1,1,f.cards,f.ability).get(0),first);
        f.events.clear();final Card dragon=f.cards.get(0);dragon.setSVar("DiscardMe","6");dragon.setSVar("DoNotDiscardIfAble","True");f.ability.setActivatingPlayer(f.ai);
        Assert.assertFalse(f.brain.getCardsToDiscard(1,1,f.cards,f.ability).contains(dragon));Assert.assertEquals(picks(f).size(),2);Assert.assertTrue(f.events.stream().anyMatch(c->"DECLINED".equals(c.get("status"))));Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void forcedZeroNegativeAndSpecialBatchesDoNotInventIterationPicks(){
        final var f=fixture();Assert.assertSame(f.brain.getCardsToDiscard(3,3,f.cards,f.ability),f.cards);f.brain.getCardsToDiscard(0,1,f.cards,f.ability);f.brain.getCardsToDiscard(-1,1,f.cards,f.ability);
        f.ability.getMapParams().put("AILogic","VolrathsShapeshifter");f.brain.getCardsToDiscard(1,1,f.cards,f.ability);Assert.assertTrue(picks(f).isEmpty());Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void frozenPlanningHistoryAndOriginalCallbackIdentityDoNotOwnNativeLists(){
        final var f=fixture();final var prior=new CardCollection(f.cards.get(0));final Card selected=f.cards.get(1);final var calls=new AtomicInteger();
        Assert.assertSame(AiChoiceRecorder.planning(f.ai,()->pick(f,2,1,prior,()->{calls.incrementAndGet();prior.clear();f.cards.clear();return selected;})),selected);
        final var c=picks(f).get(0);Assert.assertEquals(calls.get(),1);Assert.assertEquals(context(c).get("stateBasis"),"PLANNING_WORKING_STATE");Assert.assertEquals(((List<?>)values(c).get("previousSelections")).size(),1);Assert.assertEquals(pool(c).size(),3);Assert.assertTrue(prior.isEmpty());Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void ownHandAndPublicRolesAreAllowedOnlyForDiscardHistory(){
        final var f=fixture();final Card hand=f.cards.get(0),publicCard=addCardToZone("Llanowar Elves",f.ai,ZoneType.Battlefield);
        for(Card card:List.of(hand,publicCard)){f.events.clear();pick(f,2,1,new CardCollection(card),()->f.cards.get(1));Assert.assertNotNull(values(picks(f).get(0)));}
        f.events.clear();AiPaymentChoiceRecorder.sacrificePick(f.ai,f.ability,new CardCollection(publicCard),2,1,new CardCollection(hand),()->publicCard);Assert.assertNull(values(picks(f).get(0)));Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void opponentPrivateForeignAndLkiPriorMembersNeverGainBindings(){
        final var f=fixture();final Card other=addCardToZone("Black Lotus",f.game.getPlayers().get(0),ZoneType.Hand);other.addMayLookTemp(f.ai);Assert.assertTrue(other.getView().canBeShownTo(f.ai.getView()));final Card foreign=Mockito.spy(createCard("Forest",f.ai));Mockito.doReturn(f.cards.get(0).getId()).when(foreign).getId();Mockito.doReturn(f.cards.get(0).getGameTimestamp()).when(foreign).getGameTimestamp();final Card lki=Mockito.spy(createCard("Forest",f.ai));Mockito.doReturn(true).when(lki).isLKI();
        for(Card bad:List.of(other,foreign,lki)){f.events.clear();pick(f,2,1,new CardCollection(bad),()->f.cards.get(1));Assert.assertNull(values(picks(f).get(0)));Assert.assertTrue(context(picks(f).get(0)).get("omissions").toString().contains("UNRESOLVED_BINDING"));}Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void identicalOwnHandCardsCannotUseHiddenOrderToBindPriorHistory(){
        final var f=fixture();final Card first=f.cards.get(0);addCardToZone("Forest",f.ai,ZoneType.Hand);final var prior=new CardCollection(first);
        pick(f,2,1,prior,()->f.cards.get(1));final var one=picks(f).get(0);Assert.assertNull(values(one));Assert.assertTrue(context(one).get("omissions").toString().contains("UNRESOLVED_BINDING"));
        f.ai.getZone(ZoneType.Hand).reorder(first, f.ai.getCardsIn(ZoneType.Hand).size()-1);f.events.clear();pick(f,2,1,prior,()->f.cards.get(1));final var two=picks(f).get(0);
        Assert.assertNull(values(two));Assert.assertEquals(context(two).get("policy"),context(one).get("policy"));Assert.assertEquals(context(two).get("omissions"),context(one).get("omissions"));Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void staleReceiptCannotRebindANewIncarnation(){
        final var f=fixture();final var input=AiReceivedParameters.discardPick(AiPaymentChoiceRecorder.DiscardStrategy.PREFERENCE,2,2,1,new CardCollection(f.cards.get(0)));
        final var old=input.roleLists().get("previousSelections").get(0);final var stale=new java.util.LinkedHashMap<String,Object>(old.reference());stale.put("gameTimestamp",((Long)stale.get("gameTimestamp"))+1);
        final var receipt=new AiReceivedParameters.Input(input.kind(),input.coverage(),input.reason(),input.values(),input.roles(),Map.of("previousSelections",List.of(new AiOptionInput.Entry(old.object(),stale))));
        final var c=AiInputContext.capture(f.ai,f.ability,"BEFORE_SELECTOR","RULES_STATE",()->true,null,null,new AiReceivedParameters.Carrier(new AtomicReference<>(receipt)));Assert.assertNull(((Map<?,?>)c.get("policy")).get("receivedParameters"));Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void wholeHistoryLimitAndInvalidProgressRefuseBeforeIdentityGetters(){
        final var f=fixture();final var prior=new CardCollection();for(int i=0;i<64;i++){final Card c=addCardToZone("Forest",f.ai,ZoneType.Hand);final var counters=com.google.common.collect.HashMultiset.<forge.game.card.CounterType>create();counters.add(forge.game.card.CounterEnumType.CHARGE,i+1);c.setCounters(counters);prior.add(c);}pick(f,65,64,prior,()->f.cards.get(0));Assert.assertEquals(((List<?>)values(picks(f).get(0)).get("previousSelections")).size(),64);
        final var tooMany=new CardCollection();for(int i=0;i<65;i++){final Card c=Mockito.spy(createCard("Forest",f.ai));tooMany.add(c);Mockito.doThrow(new AssertionError("identity read")).when(c).getId();}Assert.assertEquals(AiReceivedParameters.discardPick(AiPaymentChoiceRecorder.DiscardStrategy.FALLBACK,66,66,65,tooMany).coverage(),"UNAVAILABLE");
        final var unknown=new CardCollection(){@Override public int size(){throw new AssertionError("unknown size");}};
        for(final var input:List.of(AiReceivedParameters.discardPick(AiPaymentChoiceRecorder.DiscardStrategy.PREFERENCE,2,2,0,tooMany),AiReceivedParameters.discardPick(AiPaymentChoiceRecorder.DiscardStrategy.PREFERENCE,2,2,-1,tooMany),AiReceivedParameters.discardPick(AiPaymentChoiceRecorder.DiscardStrategy.PREFERENCE,2,2,0,unknown))){Assert.assertEquals(input.coverage(),"UNSUPPORTED");}Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void declineAndOriginalErrorsPreserveResultsAndLeaveNoScope(){
        final var f=fixture();Assert.assertNull(pick(f,1,0,new CardCollection(),()->null));Assert.assertEquals(f.events.get(1).get("status"),"DECLINED");final var error=new IllegalStateException("original");Assert.assertSame(Assert.expectThrows(IllegalStateException.class,()->pick(f,1,0,new CardCollection(),()->{throw error;})),error);Assert.assertEquals(f.events.get(3).get("status"),"ERROR");pick(f,1,0,new CardCollection(),()->f.cards.get(0));Assert.assertNull(picks(f).get(2).get("parentChoiceId"));Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] bypass(){return new Object[][]{{"OFF"},{"COPY"},{"HIDDEN"}};}
    @Test(dataProvider="bypass",groups={"UnitTest","fast"})
    public void bypassedPreferenceAndFallbackDoNotReadPriorOrTerminalMetadata(String mode){
        final var f=fixture();final var unknown=new CardCollection(){@Override public int size(){throw new AssertionError("bypassed size");}};
        if(mode.equals("OFF")){f.game.setRecordAiDecisions(false);}if(mode.equals("COPY")){f.game.markAsSimulationCopy();}if(mode.equals("HIDDEN")){f.ability.getHostCard().turnFaceDown();}
        Assert.assertSame(pick(f,1,0,unknown,()->f.cards.get(0)),f.cards.get(0));Assert.assertSame(AiPaymentChoiceRecorder.discardFallback(f.ai,f.ability,f.cards,1,1,0,unknown,()->unknown),unknown);Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] fallbackBranches(){return new Object[][]{{"LAND"},{"DUPLICATE"}};}
    @Test(dataProvider="fallbackBranches",groups={"UnitTest","fast"})
    public void nativeLandAndDuplicateFallbacksRetainTheirOriginalProposals(String branch){
        final var f=fixture();f.cards.clear();
        for(String name:branch.equals("LAND")?List.of("Forest","Mountain","Island","Swamp"):List.of("Sol Ring","Arcane Signet","Mind Stone")){final Card card=addCardToZone(name,f.ai,ZoneType.Hand);if(branch.equals("DUPLICATE")){card.setSVar("DoNotDiscardIfAble","True");}f.cards.add(card);}
        if(branch.equals("DUPLICATE")){addCardToZone("Sol Ring",f.ai,ZoneType.Hand);}final Card first=f.cards.get(0);
        Assert.assertSame(f.brain.getCardsToDiscard(1,1,f.cards,f.ability).get(0),first);Assert.assertFalse(f.cards.contains(first));Assert.assertEquals(picks(f).size(),2);Assert.assertEquals(picks(f).get(1).get("boundary"),"PAYMENT_DISCARD_FALLBACK");Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void observingRandomFallbackPreservesChosenCardAndRandomTail(){
        final java.util.Random saved=forge.util.MyRandom.getRandom();String chosen=null;long tail=0;
        try{for(boolean record:List.of(false,true)){final var f=fixture();f.cards.clear();for(String name:List.of("Sol Ring","Arcane Signet","Mind Stone")){final Card card=addCardToZone(name,f.ai,ZoneType.Hand);card.setSVar("DoNotDiscardIfAble","True");f.cards.add(card);}f.game.setRecordAiDecisions(record);forge.util.MyRandom.setRandom(new java.util.Random(78123));final String actual=f.brain.getCardsToDiscard(1,1,f.cards,f.ability).get(0).getName();final long next=forge.util.MyRandom.getRandom().nextLong();if(!record){chosen=actual;tail=next;}else{Assert.assertEquals(actual,chosen);Assert.assertEquals(next,tail);Assert.assertEquals(picks(f).size(),2);}Assert.assertNull(f.game.getEventObservationFailure());}}finally{forge.util.MyRandom.setRandom(saved);}
    }

    @Test(groups={"UnitTest","fast"})
    public void workChargesSixPlusHistoryAndUsesTwoNewSourceCells(){
        final var f=fixture();final var pc=(PlayerControllerAi)f.ai.getController();final var state=pc.inputContextState();long before=state.remainingWork();AiInputContext.capture(f.ai,f.ability,"BEFORE_SELECTOR","RULES_STATE",()->true);final long base=before-state.remainingWork();before=state.remainingWork();
        AiInputContext.capture(f.ai,f.ability,"BEFORE_SELECTOR","RULES_STATE",()->true,null,null,new AiReceivedParameters.Carrier(new AtomicReference<>(AiReceivedParameters.discardPick(AiPaymentChoiceRecorder.DiscardStrategy.PREFERENCE,2,2,1,new CardCollection(f.cards.get(0))))));Assert.assertEquals(before-state.remainingWork(),base+11);
        final var ledger=pc.configureObservationAccounting();pick(f,1,0,new CardCollection(),()->f.cards.get(0));AiPaymentChoiceRecorder.discardFallback(f.ai,f.ability,f.cards,1,1,0,new CardCollection(),()->new CardCollection(f.cards.get(0)));
        for(String name:List.of("PAYMENT_DISCARD_PREFERENCE","PAYMENT_DISCARD_FALLBACK")){final var c=(Map<?,?>)((Map<?,?>)ledger.snapshot().get("boundaries")).get(name);Assert.assertEquals(c.get("entered"),1L);Assert.assertEquals(c.get("finished"),1L);Assert.assertEquals(c.get("observationFailures"),0L);}Assert.assertNull(f.game.getEventObservationFailure());
    }
}
