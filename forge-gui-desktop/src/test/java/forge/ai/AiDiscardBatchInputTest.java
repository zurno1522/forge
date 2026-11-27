package forge.ai;

import forge.ai.simulation.DeckPlan;
import forge.ai.simulation.ComboPlan;
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
import java.util.concurrent.atomic.AtomicReference;

public class AiDiscardBatchInputTest extends AITest {
    private record Fixture(Game game, Player ai, AiController brain, SpellAbility ability, CardCollection offered, List<Map<String, Object>> events) { }
    private Fixture fixture() {
        final Game game = initAndCreateGame(); final Player ai = game.getPlayers().get(1); final var ability = addCardToZone("Lightning Bolt", ai, ZoneType.Hand).getFirstSpellAbility(); ability.setActivatingPlayer(ai);
        final var offered = new CardCollection(List.of(addCardToZone("Forest", ai, ZoneType.Hand), addCardToZone("Mountain", ai, ZoneType.Hand)));
        final var events = new ArrayList<Map<String, Object>>(); game.setRecordAiDecisions(true); game.observeEventEmission(e -> { if (e instanceof AiChoiceEvent c) { events.add(c.data()); } }); return new Fixture(game, ai, ((PlayerControllerAi) ai.getController()).getAi(), ability, offered, events);
    }
    private List<Map<String, Object>> batches(Fixture f) { return f.events.stream().filter(c -> "PAYMENT_CARD_BATCH".equals(c.get("choiceType"))).toList(); }
    private Map<String, Object> batch(Fixture f, String branch) { return batches(f).stream().filter(c -> branch.equals(((Map<?, ?>) c.get("options")).get("branch"))).findFirst().orElseThrow(); }
    private Map<?, ?> parameters(Map<?, ?> start) { return (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) start.get("inputContext")).get("policy")).get("receivedParameters"); }
    private int poolSize(Map<?, ?> start) { return ((List<?>) ((Map<?, ?>) ((Map<?, ?>) start.get("options")).get("cards")).get("items")).size(); }
    private void reanimator(Fixture f) throws Exception {
        for (String name : List.of("Reanimate","Animate Dead","Entomb","Griselbrand","Archon of Cruelty","Faithless Looting","Careful Study","Swamp")) { for (int i = 0; i < 4; i++) { addCardToZone(name, f.ai, ZoneType.Library); } }
        final var plan = DeckPlan.forSetting("auto", f.ai); Assert.assertTrue(plan.combos.has(ComboPlan.Kind.REANIMATOR));
        final var field = AiController.class.getDeclaredField("flatMcDeckPlan"); field.setAccessible(true); field.set(f.brain, plan);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualForcedAliasHasOnePolicyOperationAndNoInventedAtomicPick() {
        final Fixture f = fixture(); Assert.assertSame(f.brain.getCardsToDiscard(2, 2, f.offered, f.ability), f.offered);
        Assert.assertEquals(batches(f).size(), 1); final var start = batch(f,"POLICY"); Assert.assertEquals(((Map<?, ?>) start.get("entryLocation")).get("kind"), "OPERATION"); Assert.assertEquals(poolSize(start), 2);
        Assert.assertFalse(f.events.stream().anyMatch(c -> "PAYMENT_CARD_PICK".equals(c.get("choiceType")))); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualComboSelectsItsBatchWithoutInvokingTheFallbackPolicy() throws Exception {
        final Fixture f = fixture(); reanimator(f); final Card big = addCardToZone("Griselbrand", f.ai, ZoneType.Hand); final Card spell = addCardToZone("Reanimate", f.ai, ZoneType.Hand);
        final var offered = new CardCollection(List.of(big, f.offered.get(0), spell)); final var result = f.brain.getCardsToDiscard(1,1,offered,f.ability);
        Assert.assertEquals(result.size(),1); Assert.assertSame(result.get(0),big); Assert.assertEquals(batches(f).size(),1); Assert.assertEquals(poolSize(batch(f,"COMBO")),3); Assert.assertEquals(offered.size(),3); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void nativeComboDeclineThenProtectedNarrowingHasItsOwnSmallerPolicyPool() throws Exception {
        final Fixture f = fixture(); reanimator(f); final Card protectedCard = addCardToZone("Reanimate", f.ai, ZoneType.Hand); f.offered.add(protectedCard);
        Assert.assertTrue(f.brain.getCardsToDiscard(0,1,f.offered,f.ability).isEmpty()); Assert.assertEquals(poolSize(batch(f,"COMBO")),3); Assert.assertEquals(poolSize(batch(f,"POLICY")),2);
        final var combo = batch(f,"COMBO"); Assert.assertTrue(f.events.stream().anyMatch(c -> combo.get("choiceId").equals(c.get("choiceId")) && "DECLINED".equals(c.get("status")))); Assert.assertTrue(f.offered.contains(protectedCard)); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void manaValueBranchUsesTheActualHandInsteadOfTheOuterSuppliedPool() {
        final Fixture f = fixture(); final Card bear = addCardToZone("Grizzly Bears",f.ai,ZoneType.Hand); f.ability.getMapParams().put("AILogic","DiscardCMCX"); f.ability.setXManaCostPaid(2);
        final var result=f.brain.getCardsToDiscard(1,1,f.offered,f.ability); Assert.assertEquals(result.size(),1); Assert.assertSame(result.get(0),bear);
        final var start=batch(f,"MANA_VALUE"); Assert.assertEquals(parameters(start).get("manaValue"),2); Assert.assertNull(((Map<?,?>)start.get("options")).get("cards")); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void shapeshifterAndUncastableBranchesKeepTheirNativeBatchBehavior() {
        final Fixture f = fixture(); final Card dragon=addCardToZone("Shivan Dragon",f.ai,ZoneType.Hand); f.ability.getMapParams().put("AILogic","VolrathsShapeshifter");
        Assert.assertSame(f.brain.getCardsToDiscard(1,1,f.offered,f.ability).get(0),dragon); Assert.assertNull(((Map<?,?>)batch(f,"SHAPESHIFTER").get("options")).get("cards"));
        f.events.clear(); f.ability.getMapParams().put("AILogic","DiscardUncastableAndExcess"); f.ability.getMapParams().put("AnyNumber","True");
        Assert.assertTrue(f.brain.getCardsToDiscard(0,4,f.offered,f.ability).contains(dragon)); Assert.assertTrue(dragon.isInZone(ZoneType.Hand)); Assert.assertNull(((Map<?,?>)batch(f,"UNCASTABLE_EXCESS").get("options")).get("cards")); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void originalResultIdentityAndFrozenScopeValuesSurviveCallbackMutationAndErrors() {
        final Fixture f = fixture(); final var selected=new CardCollection(f.offered.get(0));
        Assert.assertSame(AiChoiceRecorder.planning(f.ai,()->AiPaymentChoiceRecorder.discardBatch(f.ai,f.ability,AiPaymentChoiceRecorder.DiscardBranch.POLICY,1,2,null,f.offered,()->{f.offered.clear();f.ai.setLife(7,null);return selected;})),selected);
        final var start=batch(f,"POLICY"); Assert.assertEquals(poolSize(start),2); Assert.assertEquals(parameters(start).get("minimum"),1); Assert.assertEquals(((Map<?,?>)start.get("inputContext")).get("stateBasis"),"PLANNING_WORKING_STATE");
        final var error=new IllegalStateException("original"); Assert.assertSame(Assert.expectThrows(IllegalStateException.class,()->AiPaymentChoiceRecorder.discardBatch(f.ai,f.ability,AiPaymentChoiceRecorder.DiscardBranch.COMBO,1,2,null,f.offered,()->{throw error;})),error); Assert.assertEquals(f.events.get(f.events.size()-1).get("status"),"ERROR"); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @DataProvider public Object[][] bypass() { return new Object[][] {{"OFF"},{"COPY"},{"HIDDEN"}}; }
    @Test(dataProvider="bypass",groups={"UnitTest","fast"})
    public void bypassedBatchDoesNotInspectItsSuppliedPool(String mode) {
        final Fixture f=fixture(); final var offered=Mockito.spy(f.offered); Mockito.doThrow(new AssertionError("bypassed pool")).when(offered).iterator();
        if(mode.equals("OFF")){f.game.setRecordAiDecisions(false);} if(mode.equals("COPY")){f.game.markAsSimulationCopy();} if(mode.equals("HIDDEN")){f.ability.getHostCard().turnFaceDown();}
        Assert.assertSame(AiPaymentChoiceRecorder.discardBatch(f.ai,f.ability,AiPaymentChoiceRecorder.DiscardBranch.POLICY,1,2,null,offered,()->f.offered),f.offered); Assert.assertNull(f.game.getEventObservationFailure());
    }

    @Test(groups={"UnitTest","fast"})
    public void requestUsesFiveWorkUnitsAndOneNewAccountingCell() {
        final Fixture f=fixture(); final var pc=(PlayerControllerAi)f.ai.getController(); final var state=pc.inputContextState(); long before=state.remainingWork();
        AiInputContext.capture(f.ai,f.ability,"BEFORE_OPERATION","RULES_STATE",()->true); final long base=before-state.remainingWork();before=state.remainingWork();
        AiInputContext.capture(f.ai,f.ability,"BEFORE_OPERATION","RULES_STATE",()->true,null,null,new AiReceivedParameters.Carrier(new AtomicReference<>(AiReceivedParameters.discardBatch(AiPaymentChoiceRecorder.DiscardBranch.POLICY,1,2,null)))); Assert.assertEquals(before-state.remainingWork(),base+5);
        final var ledger=pc.configureObservationAccounting(); AiPaymentChoiceRecorder.discardBatch(f.ai,f.ability,AiPaymentChoiceRecorder.DiscardBranch.POLICY,1,2,null,f.offered,()->f.offered);
        final var cell=(Map<?,?>)((Map<?,?>)ledger.snapshot().get("boundaries")).get("PAYMENT_DISCARD_BATCH"); Assert.assertEquals(cell.get("entered"),1L);Assert.assertEquals(cell.get("finished"),1L);Assert.assertEquals(cell.get("observationFailures"),0L);
    }
}
