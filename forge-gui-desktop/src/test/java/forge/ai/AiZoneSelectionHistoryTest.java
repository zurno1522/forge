package forge.ai;

import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.effects.ChangeZoneEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.player.ZoneChangeSelectionObserver;
import forge.game.player.ZoneChangeSelectionRequest;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class AiZoneSelectionHistoryTest extends AITest {
    private record Fixture(Game game, Player chooser, SpellAbility ability, CardCollection cards, List<Map<String,Object>> events) {
        ZoneChangeSelectionRequest request() { return new ZoneChangeSelectionRequest(ability, ZoneType.Hand, List.of(ZoneType.Graveyard), chooser, chooser, 2); }
    }
    private Fixture fixture() {
        final Game game=initAndCreateGame(); final Player chooser=game.getPlayers().get(1);
        final Card source=addCardToZone("Time Beetle",chooser,ZoneType.Battlefield);
        final SpellAbility ability=AbilityFactory.getAbility("AB$ ChangeZone | Cost$ 0 | Origin$ Graveyard | Hidden$ True | Destination$ Hand | ChangeType$ Card | ChangeNum$ 2 | Mandatory$ True | Shuffle$ False | NoReveal$ True",source);
        ability.setActivatingPlayer(chooser);
        final CardCollection cards=new CardCollection();
        cards.add(addCardToZone("Grizzly Bears",chooser,ZoneType.Graveyard));cards.add(addCardToZone("Shivan Dragon",chooser,ZoneType.Graveyard));
        final List<Map<String,Object>> events=new ArrayList<>();game.setRecordAiDecisions(true);
        game.observeEventEmission(event->{if(event instanceof AiChoiceEvent c){events.add(c.data());}});
        return new Fixture(game,chooser,ability,cards,events);
    }
    private Card pick(Fixture f,ZoneChangeSelectionRequest request,ZoneChangeSelectionObserver observer,int position,int before,int after,Card result) {
        observer.beforeSelector(f.cards,false,position,before);
        final Card selected=AiEffectChoiceRecorder.zoneChange(f.chooser,f.ability,request.destination(),request.origins(),f.cards,false,request.decider(),()->result);
        observer.returned(selected,after);return selected;
    }
    private CardCollection choose(Fixture f) {
        final var request=f.request();
        return f.chooser.getController().withZoneChangeSelections(request,observer->{
            pick(f,request,observer,0,0,1,f.cards.get(0));pick(f,request,observer,1,1,2,f.cards.get(1));return f.cards;
        });
    }
    private List<Map<String,Object>> picks(Fixture f) { return f.events.stream().filter(c->"CONTROLLER_ZONE_CHANGE_CARD".equals(c.get("boundary"))).toList(); }
    private Map<?,?> input(Map<String,Object> c) { return (Map<?,?>)c.get("inputContext"); }
    private Map<?,?> policy(Map<String,Object> c) { return (Map<?,?>)input(c).get("policy"); }
    private Map<?,?> prompt(Map<String,Object> c) { return (Map<?,?>)policy(c).get("prompt"); }
    private Map<?,?> progress(Map<String,Object> c) { return (Map<?,?>)((Map<?,?>)c.get("options")).get("zoneSelectionProgress"); }
    private String ref(Map<String,Object> c,Card card) {
        return (String)((List<?>)((Map<?,?>)input(c).get("diagnostics")).get("cardBindings")).stream().map(v->(Map<?,?>)v)
                .filter(v->Long.valueOf(card.getId()).equals(v.get("id"))).findFirst().orElseThrow().get("entityRef");
    }

    @Test(groups={"UnitTest","fast"})
    public void successiveSelectorsCaptureOrderedReturnsInTheirOwnImmutableViews() {
        final Fixture f=fixture();Assert.assertSame(choose(f),f.cards);final var picks=picks(f);
        Assert.assertEquals(prompt(picks.get(0)).get("kind"),"ZONE_PICK_PROGRESS");
        Assert.assertEquals(progress(picks.get(0)).get("attemptIndex"),0L);
        Assert.assertEquals(prompt(picks.get(1)).get("attemptIndex"),1L);
        Assert.assertEquals(prompt(picks.get(1)).get("priorNonNullReturnCount"),1L);
        Assert.assertEquals(prompt(picks.get(1)).get("retainedCount"),1);
        Assert.assertEquals(prompt(picks.get(1)).get("previousReturns"),List.of(ref(picks.get(1),f.cards.get(0))));
        Assert.assertTrue(((List<?>)prompt(picks.get(0)).get("previousReturns")).isEmpty());
        Assert.expectThrows(UnsupportedOperationException.class,()->((List<?>)prompt(picks.get(1)).get("previousReturns")).clear());
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void actualRetryAndDuplicateReturnsHaveDifferentAttemptReturnAndRetainedCounts() {
        final Fixture f=fixture();final var controller=Mockito.spy((PlayerControllerAi)f.chooser.getController());f.chooser.dangerouslySetController(controller);
        final AtomicInteger calls=new AtomicInteger();
        Mockito.doAnswer(call->AiEffectChoiceRecorder.zoneChange(f.chooser,call.getArgument(2),call.getArgument(0),call.getArgument(1),call.getArgument(3),call.getArgument(6),call.getArgument(7),()->calls.getAndIncrement()==0?null:f.cards.get(0)))
                .when(controller).chooseSingleCardForZoneChange(Mockito.any(),Mockito.anyList(),Mockito.any(),Mockito.any(),Mockito.any(),Mockito.anyString(),Mockito.anyBoolean(),Mockito.any());
        Mockito.doReturn(false).when(controller).confirmAction(Mockito.any(),Mockito.any(),Mockito.anyString(),Mockito.any());
        new ChangeZoneEffect().resolve(f.ability);final var picks=picks(f);Assert.assertEquals(picks.size(),3);
        Assert.assertEquals(picks.stream().map(c->prompt(c).get("attemptIndex")).toList(),List.of(0L,1L,2L));
        Assert.assertEquals(picks.stream().map(c->prompt(c).get("loopPosition")).toList(),List.of(0,0,1));
        Assert.assertEquals(picks.stream().map(c->prompt(c).get("priorNonNullReturnCount")).toList(),List.of(0L,0L,1L));
        Assert.assertEquals(picks.stream().map(c->prompt(c).get("retainedCount")).toList(),List.of(0,0,1));
        Assert.assertEquals(f.chooser.getCardsIn(ZoneType.Hand).size(),1);Assert.assertEquals(calls.get(),3);
        Assert.assertEquals(((Map<?,?>)f.events.get(f.events.size()-1).get("selection")).get("selectedCount"),1);
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void duplicateReceiptOrderIsPreservedIndependentlyOfRetainedCount() {
        final Fixture f=fixture();final var r=f.request();
        f.chooser.getController().withZoneChangeSelections(r,o->{
            pick(f,r,o,0,0,1,f.cards.get(0));pick(f,r,o,1,1,1,f.cards.get(0));pick(f,r,o,2,1,1,null);return new CardCollection(f.cards.get(0));
        });
        final var third=picks(f).get(2);Assert.assertEquals(prompt(third).get("priorNonNullReturnCount"),2L);
        Assert.assertEquals(prompt(third).get("retainedCount"),1);
        Assert.assertEquals(prompt(third).get("previousReturns"),List.of(ref(third,f.cards.get(0)),ref(third,f.cards.get(0))));
    }

    @Test(groups={"UnitTest","fast"})
    public void aClearedFinalPileDoesNotEraseAnEarlierSuccessfulReturn() {
        final Fixture f=fixture();final var r=f.request();
        f.chooser.getController().withZoneChangeSelections(r,o->{pick(f,r,o,0,0,1,f.cards.get(0));pick(f,r,o,1,1,1,null);return new CardCollection();});
        Assert.assertEquals(prompt(picks(f).get(1)).get("priorNonNullReturnCount"),1L);
        Assert.assertEquals(((Map<?,?>)f.events.get(f.events.size()-1).get("selection")).get("selectedCount"),0);
    }

    @Test(groups={"UnitTest","fast"})
    public void privateAndMovedEarlierObjectsRemainKnownReturnsWithUnresolvedIdentities() {
        for(boolean hidden:List.of(false,true)) {
            final Fixture f=fixture();final var r=f.request();
            if(hidden){f.cards.set(0,addCardToZone("Black Lotus",f.game.getPlayers().get(0),ZoneType.Hand));}
            f.chooser.getController().withZoneChangeSelections(r,o->{
                pick(f,r,o,0,0,1,f.cards.get(0));
                if(!hidden){f.game.getAction().moveTo(ZoneType.Exile,f.cards.get(0),f.ability,Map.of());}
                pick(f,r,o,1,1,2,f.cards.get(1));return f.cards;
            });
            final var second=picks(f).get(1);Assert.assertEquals(prompt(second).get("priorNonNullReturnCount"),1L);
            final var refs=(List<?>)prompt(second).get("previousReturns");Assert.assertEquals(refs.size(),1);Assert.assertNull(refs.get(0));
            Assert.assertFalse(policy(second).toString().contains("Black Lotus"));
        }
    }

    @Test(groups={"UnitTest","fast"})
    public void historyUsesTheNextFreshSnapshotRatherThanOuterLocalReferences() {
        final Fixture f=fixture();final Card padding=addCardToZone("Island",f.chooser,ZoneType.Battlefield);final var r=f.request();
        f.chooser.getController().withZoneChangeSelections(r,o->{pick(f,r,o,0,0,1,f.cards.get(0));f.game.getAction().moveToGraveyard(padding,f.ability);f.chooser.setLife(7,null);pick(f,r,o,1,1,2,f.cards.get(1));return f.cards;});
        final var second=picks(f).get(1);
        Assert.assertEquals(prompt(second).get("previousReturns"),List.of(ref(second,f.cards.get(0))));
        Assert.assertEquals(((Map<?,?>)((List<?>)policy(second).get("players")).get(1)).get("life"),7);
        Assert.assertEquals(((Map<?,?>)((List<?>)policy(f.events.get(0)).get("players")).get(1)).get("life"),20);
    }

    @Test(groups={"UnitTest","fast"})
    public void theReceiptPrefixDoesNotCapEngineReturnsOrReadLaterReceiptGetters() {
        final Fixture f=fixture();final var r=f.request();
        for (int i = 0; i < f.cards.size(); i++) { f.cards.set(i, Mockito.spy(f.cards.get(i))); }
        f.chooser.getController().withZoneChangeSelections(r, o -> {
            for (int i = 0; i < 67; i++) {
                o.beforeSelector(f.cards, false, i, Math.min(i, 2));
                final Card card = f.cards.get(i % 2);
                final Card selected = AiEffectChoiceRecorder.zoneChange(f.chooser, f.ability, r.destination(), r.origins(), f.cards, false, f.chooser, () -> card);
                final long timestamp = card.getGameTimestamp();
                if (i >= 64) { Mockito.doThrow(new AssertionError("receipt getter after prefix cap")).when(card).getGameTimestamp(); }
                try { o.returned(selected, 2); }
                finally { Mockito.doReturn(timestamp).when(card).getGameTimestamp(); }
            }
            return f.cards;
        });
        final var picks=picks(f);Assert.assertEquals(picks.size(),67);
        Assert.assertEquals(((List<?>)prompt(picks.get(64)).get("previousReturns")).size(),64);
        final var last=picks.get(66);Assert.assertEquals(progress(last).get("attemptIndex"),66L);
        Assert.assertEquals(progress(last).get("priorNonNullReturnCount"),66L);
        Assert.assertEquals(progress(last).get("receiptCoverage"),"PREFIX");
        Assert.assertEquals(((List<?>)progress(last).get("priorReturns")).size(),64);
        Assert.assertNull(prompt(last));Assert.assertTrue(input(last).get("omissions").toString().contains("OUTSIDE_CAPTURED_PREFIX"));
    }

    @Test(groups={"UnitTest","fast"})
    public void nestedOperationsHaveTheirOwnProgressAndRestoreTheOuterFrame() {
        final Fixture f=fixture();final var r=f.request();
        f.chooser.getController().withZoneChangeSelections(r,o->{
            pick(f,r,o,0,0,1,f.cards.get(0));
            f.chooser.getController().withZoneChangeSelections(r,nested->{pick(f,r,nested,0,0,1,f.cards.get(1));return f.cards;});
            pick(f,r,o,1,1,2,f.cards.get(1));return f.cards;
        });
        Assert.assertEquals(picks(f).stream().map(c->prompt(c).get("attemptIndex")).toList(),List.of(0L,0L,1L));
        Assert.assertNotEquals(picks(f).get(0).get("parentChoiceId"),picks(f).get(1).get("parentChoiceId"));
        Assert.assertEquals(prompt(picks(f).get(2)).get("priorNonNullReturnCount"),1L);
    }

    @Test(groups={"UnitTest","fast"})
    public void aNestedProbeCannotClaimOneDispatchTwiceAndKeepsV8PlanningPermission() {
        final Fixture f=fixture();final var r=f.request();
        f.chooser.getController().withZoneChangeSelections(r,o->{
            o.beforeSelector(f.cards,false,0,0);
            final Card selected=AiEffectChoiceRecorder.zoneChange(f.chooser,f.ability,r.destination(),r.origins(),f.cards,false,f.chooser,()->{
                AiChoiceRecorder.planning(f.chooser,()->AiEffectChoiceRecorder.zoneChange(f.chooser,f.ability,r.destination(),r.origins(),f.cards,false,f.chooser,()->f.cards.get(1)));
                return f.cards.get(0);
            });o.returned(selected,1);return f.cards;
        });
        Assert.assertNotNull(progress(picks(f).get(0)));final var nested=picks(f).get(1);
        Assert.assertNull(progress(nested));Assert.assertEquals(prompt(nested).get("kind"),"ZONE_CHANGE_REQUEST");
        Assert.assertEquals(input(nested).get("stateBasis"),"PLANNING_WORKING_STATE");
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void aFailedNestedAdmissionMasksTheOlderFrameAndDoesNotBorrowItsHistory() {
        final Fixture f=fixture();final var r=f.request();final AtomicInteger reads=new AtomicInteger();
        final List<ZoneType> origins=new java.util.AbstractList<>() {
            @Override public int size(){return 1;}
            @Override public ZoneType get(int i){if(reads.getAndIncrement()==0){throw new IllegalStateException("nested capture");}return ZoneType.Graveyard;}
        };
        final var nested=new ZoneChangeSelectionRequest(f.ability,ZoneType.Hand,origins,f.chooser,f.chooser,1);
        f.chooser.getController().withZoneChangeSelections(r,o->{
            pick(f,r,o,0,0,1,f.cards.get(0));
            f.chooser.getController().withZoneChangeSelections(nested,n->AiChoiceRecorder.planning(f.chooser,()->{pick(f,nested,n,0,0,1,f.cards.get(1));return f.cards;}));
            pick(f,r,o,1,1,2,f.cards.get(1));return f.cards;
        });
        final var picks=picks(f);Assert.assertNull(progress(picks.get(1)));Assert.assertEquals(prompt(picks.get(1)).get("kind"),"ZONE_CHANGE_REQUEST");
        Assert.assertEquals(prompt(picks.get(2)).get("priorNonNullReturnCount"),1L);Assert.assertNotNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void maximumLongOrdinalsAreEmittedOnceThenBecomeUnknownWithoutWrapping() throws Exception {
        final Fixture f=fixture();final var r=f.request();
        f.chooser.getController().withZoneChangeSelections(r,o->{
            try{final var field=o.getClass().getDeclaredField("attemptIndex");field.setAccessible(true);field.setLong(o,Long.MAX_VALUE-1);}catch(Exception e){throw new AssertionError(e);}
            pick(f,r,o,0,0,0,null);pick(f,r,o,0,0,0,null);return new CardCollection();
        });
        Assert.assertEquals(prompt(picks(f).get(0)).get("attemptIndex"),Long.MAX_VALUE);
        final var second=picks(f).get(1);Assert.assertNull(progress(second).get("attemptIndex"));
        Assert.assertEquals(progress(second).get("progressReason"),"COUNTER_OVERFLOW");Assert.assertNull(prompt(second));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void aMaximumReturnCounterBecomesUnknownRatherThanWrapping() {
        final Fixture f=fixture();final var r=f.request();
        f.chooser.getController().withZoneChangeSelections(r,o->{
            try{for(String name:List.of("attemptIndex","nonNullReturns")){final var field=o.getClass().getDeclaredField(name);field.setAccessible(true);field.setLong(o,Long.MAX_VALUE-1);}final var field=o.getClass().getDeclaredField("nonNullReturns");field.setAccessible(true);field.setLong(o,Long.MAX_VALUE);}catch(Exception e){throw new AssertionError(e);}
            pick(f,r,o,0,0,1,f.cards.get(0));pick(f,r,o,1,1,2,f.cards.get(1));return f.cards;
        });
        final var second=picks(f).get(1);Assert.assertNull(progress(second).get("priorNonNullReturnCount"));
        Assert.assertEquals(progress(second).get("receiptReason"),"COUNTER_OVERFLOW");Assert.assertNull(prompt(second));
    }

    @Test(groups={"UnitTest","fast"})
    public void liveReceiptFailureIsReportedByItsOuterOwnerWithoutChangingTheResult() {
        final Fixture f=fixture();final var ledger=((PlayerControllerAi)f.chooser.getController()).configureObservationAccounting();
        final Card card=Mockito.spy(f.cards.get(0));f.cards.set(0,card);final long timestamp=card.getGameTimestamp();
        final AtomicBoolean gate=new AtomicBoolean(),failed=new AtomicBoolean();final var failure=new IllegalStateException("live receipt");
        Mockito.doAnswer(c->{if(gate.get()&&failed.compareAndSet(false,true)){throw failure;}return timestamp;}).when(card).getGameTimestamp();
        f.game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c&&"COMPLETED".equals(c.data().get("status"))){gate.set(true);}});
        Assert.assertSame(choose(f),f.cards);final var second=picks(f).get(1);
        Assert.assertEquals(progress(second).get("progressCoverage"),"COMPLETE");Assert.assertEquals(progress(second).get("priorNonNullReturnCount"),1L);
        Assert.assertEquals(progress(second).get("receiptReason"),"CAPTURE_FAILED");Assert.assertNull(prompt(second));
        Assert.assertSame(f.game.getEventObservationFailure(),failure);
        Assert.assertEquals(((Map<?,?>)((Map<?,?>)ledger.snapshot().get("boundaries")).get("CONTROLLER_ZONE_CHANGE_SELECTIONS")).get("observationFailures"),1L);
    }

    @DataProvider public Object[][] lateReceipt(){return new Object[][]{{false,false},{false,true},{true,false},{true,true}};}
    @Test(dataProvider="lateReceipt",groups={"UnitTest","fast"})
    @SuppressWarnings("unchecked")
    public void blockedReceiptCannotHoldClosureOrReportALateFailure(boolean parentClosure,boolean fail) throws Exception {
        final Fixture f=fixture();final Card selected=Mockito.spy(f.cards.get(0));f.cards.set(0,selected);final long timestamp=selected.getGameTimestamp();
        final AtomicBoolean gate=new AtomicBoolean();final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Mockito.doAnswer(c->{if(gate.get()){entered.countDown();Assert.assertTrue(release.await(5,TimeUnit.SECONDS));if(fail){throw new IllegalStateException("obsolete receipt");}}return timestamp;}).when(selected).getGameTimestamp();
        f.game.observeEventEmission(e->{if(e instanceof AiChoiceEvent c&&"COMPLETED".equals(c.data().get("status"))){gate.set(true);}});
        final var parent=new AiDecisionRecorder(f.game,f.chooser,1);parent.beginAssessment(f.ability);
        final var field=AiController.class.getDeclaredField("evaluationDecision");field.setAccessible(true);
        final ThreadLocal<AiDecisionRecorder> evaluation=(ThreadLocal<AiDecisionRecorder>)field.get(((PlayerControllerAi)f.chooser.getController()).getAi());
        final var worker=Executors.newSingleThreadExecutor();
        try{
            final var result=worker.submit(()->{if(parentClosure){evaluation.set(parent);}try{return choose(f);}finally{evaluation.remove();}});
            Assert.assertTrue(entered.await(5,TimeUnit.SECONDS));
            if(parentClosure){Assert.assertNotNull(parent.finish(null,"AI_TIMEOUT",null));}else{f.game.setRecordAiDecisions(false);}
            release.countDown();Assert.assertSame(result.get(5,TimeUnit.SECONDS),f.cards);
            Assert.assertEquals(f.events.size(),3);Assert.assertNull(f.game.getEventObservationFailure());
        }finally{release.countDown();worker.shutdownNow();}
    }
    @Test(groups={"UnitTest","fast"})
    public void changedAndUnauditedReceiversDoNotAcquireTheOldHistoryCapability() {
        final Fixture changed = fixture(); final var request = changed.request();
        final var original = changed.chooser.getController();
        original.withZoneChangeSelections(request, observer -> {
            pick(changed, request, observer, 0, 0, 1, changed.cards.get(0));
            changed.chooser.dangerouslySetController(new PlayerControllerAi(changed.game, changed.chooser, changed.chooser.getLobbyPlayer()));
            pick(changed, request, observer, 1, 1, 2, changed.cards.get(1));
            return changed.cards;
        });
        Assert.assertNull(progress(picks(changed).get(1)));
        Assert.assertEquals(prompt(picks(changed).get(1)).get("kind"), "ZONE_CHANGE_REQUEST");
        final Fixture subclass = fixture();
        subclass.chooser.dangerouslySetController(new PlayerControllerAi(subclass.game, subclass.chooser, subclass.chooser.getLobbyPlayer()) { });
        Assert.assertSame(choose(subclass), subclass.cards);
        for (var child : picks(subclass)) {
            Assert.assertNull(progress(child)); Assert.assertEquals(prompt(child).get("kind"), "ZONE_CHANGE_REQUEST");
        }
    }

    @Test(groups={"UnitTest","fast"})
    public void receiptsComeFromActualReturnsEvenWhenEarlierCollectedRowsAreMissing() {
        final Fixture f = fixture(); final var request = f.request();
        f.chooser.getController().withZoneChangeSelections(request, observer -> {
            pick(f, request, observer, 0, 0, 1, f.cards.get(0));
            final Object firstId = picks(f).get(0).get("choiceId");
            f.events.removeIf(row -> firstId.equals(row.get("choiceId")));
            pick(f, request, observer, 1, 1, 2, f.cards.get(1)); return f.cards;
        });
        final var surviving = picks(f).get(0);
        Assert.assertEquals(prompt(surviving).get("attemptIndex"), 1L);
        Assert.assertEquals(prompt(surviving).get("priorNonNullReturnCount"), 1L);
        Assert.assertEquals(prompt(surviving).get("previousReturns"), List.of(ref(surviving, f.cards.get(0))));
        Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void zeroPickOperationsAndLaterDirectRequestsDoNotLeakTheFrame() {
        final Fixture f = fixture(); final var request = f.request();
        final CardCollection empty = new CardCollection();
        Assert.assertSame(f.chooser.getController().withZoneChangeSelections(request, observer -> empty), empty);
        AiEffectChoiceRecorder.zoneChange(f.chooser, f.ability, request.destination(), request.origins(), f.cards, false, f.chooser, () -> f.cards.get(0));
        final var direct = picks(f).get(0);
        Assert.assertNull(progress(direct)); Assert.assertEquals(prompt(direct).get("kind"), "ZONE_CHANGE_REQUEST");
        Assert.assertNull(direct.get("parentChoiceId"));
    }

    @Test(groups={"UnitTest","fast"})
    public void observerFactsFromAnotherThreadCannotChangeTheOwnerThreadsProgress() {
        final Fixture f = fixture(); final var request = f.request();
        f.chooser.getController().withZoneChangeSelections(request, observer -> {
            final var worker = Executors.newSingleThreadExecutor();
            try {
                worker.submit(() -> {
                    observer.beforeSelector(f.cards, false, 7, 0);
                    observer.returned(f.cards.get(1), 1);
                }).get(5, TimeUnit.SECONDS);
            } catch (Exception failure) { throw new AssertionError(failure); }
            finally { worker.shutdownNow(); }
            pick(f, request, observer, 0, 0, 1, f.cards.get(0)); return f.cards;
        });
        final var first = picks(f).get(0);
        Assert.assertEquals(prompt(first).get("attemptIndex"), 0L);
        Assert.assertEquals(prompt(first).get("priorNonNullReturnCount"), 0L);
        Assert.assertTrue(((List<?>) prompt(first).get("previousReturns")).isEmpty());
        Assert.assertNull(f.game.getEventObservationFailure());
    }

}
