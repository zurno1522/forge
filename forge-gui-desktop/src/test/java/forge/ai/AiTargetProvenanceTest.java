package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetChoices;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class AiTargetProvenanceTest extends AITest {
    private record Fixture(Game game, Player ai, AiController brain, SpellAbility ability, Card bear, Card dragon,
                           AiDecisionRecorder recorder) { }

    private Fixture fixture() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Card source = addCardToZone("Prodigal Pyromancer", ai, ZoneType.Battlefield);
        source.setSickness(false);
        final SpellAbility ability = source.getSpellAbilities().stream().filter(SpellAbility::isActivatedAbility).findFirst().orElseThrow();
        ability.setActivatingPlayer(ai);
        final Card bear = addCardToZone("Grizzly Bears", game.getPlayers().get(0), ZoneType.Battlefield);
        final Card dragon = addCardToZone("Shivan Dragon", game.getPlayers().get(0), ZoneType.Battlefield);
        game.setRecordAiDecisions(true);
        return new Fixture(game, ai, ((PlayerControllerAi) ai.getController()).getAi(), ability, bear, dragon,
                new AiDecisionRecorder(game, ai, 1));
    }

    @SuppressWarnings("unchecked")
    private AiTargetChoiceRecorder.Selection<Card> select(final Fixture f, final Card target) throws Exception {
        f.recorder.beginAssessment(f.ability);
        final var field = AiController.class.getDeclaredField("evaluationDecision");
        field.setAccessible(true);
        final ThreadLocal<AiDecisionRecorder> context = (ThreadLocal<AiDecisionRecorder>) field.get(f.brain);
        context.set(f.recorder);
        try { return AiTargetChoiceRecorder.select(f.ai, f.ability, List.of(f.bear, f.dragon), "DAMAGE_KILLABLES", pool -> target); }
        finally { context.remove(); }
    }

    private void publish(final Fixture f) {
        f.recorder.evaluated(f.ability, "WillPlay");
        f.brain.publishDecision(f.recorder, List.of(f.ability), "COMPLETED", null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> origins(final Fixture f, final SpellAbility actual) {
        final Map<String, Object> snapshot = f.brain.getTargetChoiceProvenance(actual);
        return (List<Map<String, Object>>) ((Map<?, ?>) ((List<?>) snapshot.get("abilities")).get(0)).get("targetChoices");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void realActivationCarriesItsExplicitAdditionThroughTheEngineExecutionCopy() throws Exception {
        final Fixture f = fixture();
        Assert.assertTrue(select(f, f.bear).addToTargets(f.ability));
        publish(f);
        final AtomicReference<Map<String, Object>> observed = new AtomicReference<>();
        final var subscription = f.game.observeEventEmission(event -> {
            if (event instanceof GameEventSpellAbilityCast) {
                final SpellAbility actual = f.game.getStack().peekAbility();
                observed.set(f.brain.getTargetChoiceProvenance(actual));
                Assert.assertNotSame(actual, f.ability);
                Assert.assertSame(actual.getImmediateOriginalAbility(), f.ability);
            }
        });
        try { ((PlayerControllerAi) f.ai.getController()).playChosenSpellAbility(f.ability); }
        finally { subscription.close(); }
        Assert.assertNotNull(observed.get());
        final Map<?, ?> group = (Map<?, ?>) ((List<?>) observed.get().get("abilities")).get(0);
        final Map<?, ?> origin = (Map<?, ?>) ((List<?>) group.get("targetChoices")).get(0);
        Assert.assertEquals(origin.get("choiceId"), f.ai.getId() + ":1:target:0");
        Assert.assertEquals(origin.get("chooserPlayerId"), f.ai.getId());
        Assert.assertEquals(origin.get("stage"), "CANDIDATE_EVALUATION");
        Assert.assertNull(f.brain.getTargetChoiceProvenance(f.game.getStack().peekAbility()), "Attempt ownership ends after execution");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void repeatedAssessmentWithTheSameTargetDoesNotAttributeTheDiscardedPlan() throws Exception {
        final Fixture f = fixture();
        select(f, f.bear).addToTargets(f.ability);
        f.recorder.evaluated(f.ability, "WillPlay");
        f.ability.resetTargets();
        select(f, f.bear).addToTargets(f.ability);
        publish(f);
        final var attempt = f.brain.actionTracker().begin(f.ability);
        Assert.assertEquals(origins(f, f.ability).get(0).get("choiceId"), f.ai.getId() + ":1:target:1");
        f.brain.actionTracker().finish(attempt, "FAILED", "TEST_FINISHED");
    }

    @DataProvider public Object[][] planChanges() {
        return new Object[][] {{"reset"}, {"remove-readd"}, {"set-same"}, {"iterator"}, {"incarnation"}, {"delegated"}};
    }

    @Test(dataProvider = "planChanges", groups = {"UnitTest", "fast"})
    public void equalTargetIdentitiesDoNotRestoreAnUnobservedOrDelegatedOrigin(final String change) throws Exception {
        final Fixture f = fixture();
        final var choice = select(f, f.bear);
        if (change.equals("delegated")) { f.ability.setTargetingPlayer(f.game.getPlayers().get(0)); }
        choice.addToTargets(f.ability);
        switch (change) {
            case "reset" -> { f.ability.resetTargets(); f.ability.getTargets().add(f.bear); }
            case "remove-readd" -> { f.ability.getTargets().remove(f.bear); f.ability.getTargets().add(f.bear); }
            case "set-same" -> f.ability.getTargets().set(0, f.bear);
            case "iterator" -> { final var it = f.ability.getTargets().listIterator(); it.next(); it.set(f.bear); }
            case "incarnation" -> f.bear.setGameTimestamp(f.bear.getGameTimestamp() + 1);
        }
        publish(f);
        final var attempt = f.brain.actionTracker().begin(f.ability);
        Assert.assertNull(origins(f, f.ability).get(0));
        f.brain.actionTracker().finish(attempt, "FAILED", "TEST_FINISHED");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void substitutionDoesNotInventAnOriginAndMultipleAdditionsKeepTheirIndividualOrigins() throws Exception {
        final Fixture f = fixture();
        select(f, f.bear).addToTargets(f.ability, f.dragon);
        select(f, f.bear).addToTargets(f.ability);
        publish(f);
        final var attempt = f.brain.actionTracker().begin(f.ability);
        final var origins = origins(f, f.ability);
        Assert.assertEquals(origins.size(), 2);
        Assert.assertNull(origins.get(0));
        Assert.assertEquals(origins.get(1).get("choiceId"), f.ai.getId() + ":1:target:1");
        f.brain.actionTracker().finish(attempt, "FAILED", "TEST_FINISHED");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void cloneBranchRemainsAttributableWhenTheSourcePlanChangesButCopiesAndSiblingsAreExcluded() throws Exception {
        final Fixture f = fixture();
        select(f, f.bear).addToTargets(f.ability);
        publish(f);
        final var attempt = f.brain.actionTracker().begin(f.ability);
        final SpellAbility actual = f.ability.copy();
        actual.setOriginalAbility(f.ability);
        f.ability.getTargets().clear();
        Assert.assertNotNull(origins(f, actual).get(0));
        actual.setCopied(true);
        Assert.assertNull(f.brain.getTargetChoiceProvenance(actual));
        final SpellAbility sibling = f.ability.copy();
        Assert.assertNull(f.brain.getTargetChoiceProvenance(sibling));
        f.brain.actionTracker().finish(attempt, "FAILED", "TEST_FINISHED");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void sharingAPlanWithADifferentSelectedCandidateDoesNotAttributeItsRejectedCandidate() throws Exception {
        final Fixture f = fixture();
        select(f, f.bear).addToTargets(f.ability);
        f.recorder.evaluated(f.ability, "CantPlayAi");
        final SpellAbility differentCandidate = f.ability.copy();
        f.brain.publishDecision(f.recorder, List.of(differentCandidate), "COMPLETED", null);
        final var attempt = f.brain.actionTracker().begin(differentCandidate);
        Assert.assertNull(origins(f, differentCandidate).get(0));
        f.brain.actionTracker().finish(attempt, "FAILED", "TEST_FINISHED");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void lateAdditionStillChangesTheTargetPlanWithoutRewritingThePublishedDecision() throws Exception {
        final Fixture f = fixture();
        final var selection = select(f, f.bear);
        final AiDecisionEvent event = f.recorder.finish(null, "AI_TIMEOUT", null);
        Assert.assertTrue(selection.addToTargets(f.ability));
        Assert.assertSame(f.ability.getTargets().get(0), f.bear);
        final var snapshot = f.recorder.targetProvenance(f.ability).capture(f.ability);
        final Map<?, ?> group = (Map<?, ?>) ((List<?>) snapshot.get("abilities")).get(0);
        Assert.assertNull(((List<?>) group.get("targetChoices")).get(0));
        Assert.assertNull(f.game.getEventObservationFailure());
        Assert.assertTrue(((List<?>) event.data().get("selectedActions")).isEmpty());
    }

    @DataProvider public Object[][] targetMutations() {
        return new Object[][] {{"add"}, {"indexed-add"}, {"addAll"}, {"indexed-addAll"}, {"remove-object"},
                {"remove-index"}, {"removeAll"}, {"retainAll"}, {"clear"}, {"set"}, {"replace-card"},
                {"iterator-remove"}, {"listIterator-set"}, {"listIterator-remove"}, {"listIterator-add"},
                {"replaceAll"}, {"sort"}, {"removeIf"}};
    }

    @Test(dataProvider = "targetMutations", groups = {"UnitTest", "fast"})
    public void everyMutableListEntryPointInvalidatesThePlanMarkerIncludingIteratorsObtainedBeforeObservation(final String mutation) {
        final Fixture f = fixture();
        final TargetChoices plan = f.ability.getTargets();
        plan.add(f.bear);
        final var iterator = plan.iterator(); iterator.next();
        final var listIterator = plan.listIterator(); listIterator.next();
        final Object before = plan.getSelectionStateIdentity();
        final TargetChoices copy = plan.clone();
        final Consumer<TargetChoices> mutate = switch (mutation) {
            case "add" -> p -> p.add(f.dragon);
            case "indexed-add" -> p -> p.add(0, f.dragon);
            case "addAll" -> p -> p.addAll(List.of(f.dragon));
            case "indexed-addAll" -> p -> p.addAll(0, List.of(f.dragon));
            case "remove-object" -> p -> p.remove(f.bear);
            case "remove-index" -> p -> p.remove(0);
            case "removeAll" -> p -> p.removeAll(List.of(f.bear));
            case "retainAll" -> p -> p.retainAll(List.of());
            case "clear" -> TargetChoices::clear;
            case "set" -> p -> p.set(0, f.bear);
            case "replace-card" -> p -> p.replaceTargetCard(f.bear, new forge.game.card.CardCollection(f.dragon));
            case "iterator-remove" -> p -> iterator.remove();
            case "listIterator-set" -> p -> listIterator.set(f.bear);
            case "listIterator-remove" -> p -> listIterator.remove();
            case "listIterator-add" -> p -> listIterator.add(f.dragon);
            case "replaceAll" -> p -> p.replaceAll(c -> c);
            case "sort" -> p -> p.sort((a, b) -> 0);
            case "removeIf" -> p -> p.removeIf(c -> true);
            default -> throw new IllegalArgumentException(mutation);
        };
        mutate.accept(plan);
        Assert.assertNotSame(plan.getSelectionStateIdentity(), before, mutation);
        Assert.assertSame(copy.getSelectionStateIdentity(), before);
        Assert.assertEquals(copy.size(), 1);
        Assert.assertSame(copy.get(0), f.bear);
    }
}
