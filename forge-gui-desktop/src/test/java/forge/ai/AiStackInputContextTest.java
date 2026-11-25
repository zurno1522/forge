package forge.ai;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AiStackInputContextTest extends AITest {
    private Game game() {
        final Game game = initAndCreateGame(); game.setRecordAiDecisions(true); return game;
    }
    private Map<String, Object> capture(final Player chooser) {
        return AiInputContext.capture(chooser, null, "BEFORE_SELECTOR", "RULES_STATE", () -> true);
    }
    private Map<?, ?> policy(final Map<String, Object> context) { return (Map<?, ?>) context.get("policy"); }
    private Map<?, ?> stack(final Map<String, Object> context) { return (Map<?, ?>) policy(context).get("stack"); }
    private List<?> entries(final Map<String, Object> context) { return (List<?>) stack(context).get("entries"); }
    private Map<?, ?> entry(final Map<String, Object> context, final int i) { return (Map<?, ?>) entries(context).get(i); }
    private String sourceName(final Map<String, Object> context, final int i) {
        final Object ref = entry(context, i).get("source");
        return (String) ((List<?>) policy(context).get("cards")).stream().map(value -> (Map<?, ?>) value)
                .filter(value -> value.get("entityRef").equals(ref)).findFirst().orElseThrow().get("name");
    }
    private SpellAbility spell(final String name, final Player owner) {
        final SpellAbility ability = addCardToZone(name, owner, ZoneType.Hand).getFirstSpellAbility();
        ability.setActivatingPlayer(owner);
        ability.setHostCard(owner.getGame().getAction().moveToStack(ability.getHostCard(), ability));
        return ability;
    }

    @Test(groups = {"UnitTest", "fast"})
    public void emptyStackIsCapturedAsEmptyInItsOwnVersionedProfile() {
        final Game game = game();
        final Map<String, Object> context = capture(game.getPlayers().get(1));
        Assert.assertEquals(context.get("schemaVersion"), 7);
        Assert.assertEquals(context.get("profile"), "PERMITTED_CHOICE_VIEW_V7");
        Assert.assertTrue(entries(context).isEmpty());
        Assert.assertEquals(stack(context).get("isResolving"), false);
        Assert.assertEquals(stack(context).get("resolvingCoverage"), "CAPTURED");
        Assert.assertNull(stack(context).get("resolving"));
        Assert.assertFalse(AiInputContext.legacyObservation(game, game.getPlayers().get(1)).containsKey("stack"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void responseOrderAndSpellTargetsUseLocalReferencesAndRemainFrozenAfterMutation() {
        final Game game = game(); final Player ai = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        final SpellAbility bolt = spell("Lightning Bolt", opponent); bolt.getTargets().add(ai);
        game.getStack().addAndUnfreeze(bolt);
        final SpellAbility counter = spell("Counterspell", ai); counter.getTargets().add(game.getStack().peekAbility());
        game.getStack().addAndUnfreeze(counter);
        final Map<String, Object> context = capture(ai);
        Assert.assertEquals(entries(context).size(), 2);
        Assert.assertEquals(sourceName(context, 0), "Counterspell");
        Assert.assertEquals(sourceName(context, 1), "Lightning Bolt");
        Assert.assertEquals(entry(context, 0).get("controller"), "p1");
        Assert.assertEquals(((Map<?, ?>) ((List<?>) entry(context, 0).get("targets")).get(0)).get("entityRef"), "s1");
        Assert.assertEquals(((Map<?, ?>) ((List<?>) entry(context, 1).get("targets")).get(0)).get("entityRef"), "p1");
        final Map<?, ?> binding = (Map<?, ?>) ((List<?>) ((Map<?, ?>) context.get("diagnostics")).get("stackBindings")).get(0);
        Assert.assertEquals(binding.get("id"), game.getStack().peek().getId());
        Assert.assertFalse(policy(context).toString().contains("abilityId"));
        counter.resetTargets(); game.getStack().remove(game.getStack().peek());
        Assert.assertEquals(entries(context).size(), 2);
        Assert.assertEquals(((List<?>) entry(context, 0).get("targets")).size(), 1);
        Assert.assertEquals(entries(capture(ai)).size(), 1);
        Assert.expectThrows(UnsupportedOperationException.class, () -> stack(context).clear());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void rootXAndDividedValuesAreObservedWithoutEvaluatingTheAbility() {
        final Game game = game(); final Player ai = game.getPlayers().get(1);
        final Card target = addCardToZone("Grizzly Bears", game.getPlayers().get(0), ZoneType.Battlefield);
        final SpellAbility fireball = spell("Fireball", ai); fireball.setXManaCostPaid(7);
        fireball.getTargets().add(target); fireball.getTargets().add(game.getPlayers().get(0));
        fireball.getTargets().addDividedAllocation(target, 2);
        fireball.getTargets().addDividedAllocation(game.getPlayers().get(0), 5);
        game.getStack().addAndUnfreeze(fireball);
        final Map<String, Object> before = capture(ai);
        Assert.assertEquals(entry(before, 0).get("rootX"), 7);
        final List<?> targets = (List<?>) entry(before, 0).get("targets");
        Assert.assertEquals(((Map<?, ?>) targets.get(0)).get("dividedAmount"), 2);
        Assert.assertEquals(((Map<?, ?>) targets.get(1)).get("dividedAmount"), 5);
        Assert.assertEquals(target.getDamage(), 0);
        fireball.setXManaCostPaid(1); fireball.resetTargets();
        Assert.assertEquals(entry(before, 0).get("rootX"), 7);
        Assert.assertEquals(entry(capture(ai), 0).get("rootX"), 1);
        Assert.assertEquals(targets.size(), 2);
    }

    @Test(groups = {"UnitTest", "fast"})
    public void actualResolutionLinksThePendingEntryAndClearsTheLinkAfterCompletion() {
        final Game game = game(); final Player ai = game.getPlayers().get(1);
        final Card host = addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield);
        final List<Map<String, Object>> contexts = new ArrayList<>();
        final SpellAbility root = new SpellAbility.EmptySa(host) {
            @Override public void resolve() { contexts.add(capture(ai)); }
        };
        game.getStack().add(root); game.getStack().resolveStack();
        Assert.assertEquals(stack(contexts.get(0)).get("isResolving"), true);
        Assert.assertEquals(stack(contexts.get(0)).get("resolving"), "s0");
        Assert.assertEquals(stack(contexts.get(0)).get("resolvingCoverage"), "CAPTURED");
        Assert.assertEquals(stack(capture(ai)).get("isResolving"), false);
        Assert.assertTrue(entries(capture(ai)).isEmpty());
        Assert.assertEquals(stack(contexts.get(0)).get("resolving"), "s0");
    }

    @Test(groups = {"UnitTest", "fast"})
    public void hiddenFacesAndTheirEngineOnlyAbilityParametersCannotAlterPolicy() {
        Map<?, ?> baseline = null;
        for (final boolean alternate : List.of(false, true)) {
            final Game game = game(); final Player owner = game.getPlayers().get(1), viewer = game.getPlayers().get(0);
            final Card source = addCardToZone(alternate ? "Shivan Dragon" : "Lightning Bolt", owner, ZoneType.Battlefield);
            source.turnFaceDown(true);
            final SpellAbility ability = new SpellAbility.EmptySa(source);
            ability.setCopied(true);
            ability.setXManaCostPaid(alternate ? 99 : 1);
            if (alternate) { ability.getTargets().add(owner); }
            game.getStack().add(ability);
            final Map<String, Object> context = capture(viewer);
            Assert.assertNull(entry(context, 0).get("rootX"));
            Assert.assertNull(entry(context, 0).get("targets"));
            Assert.assertEquals(entry(context, 0).get("targetsCoverage"), "UNAVAILABLE");
            if (baseline == null) { baseline = policy(context); } else { Assert.assertEquals(policy(context), baseline); }
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void privateTargetsAndStaleSourcesDoNotGainAssociationsFromEngineIdentity() {
        final Game game = game(); final Player ai = game.getPlayers().get(1);
        final Card source = addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield);
        final Card hidden = addCardToZone("Black Lotus", game.getPlayers().get(0), ZoneType.Hand);
        final SpellAbility ability = new SpellAbility.EmptySa(source);
        ability.setCopied(true); // Permit the deliberately privileged reference through engine admission.
        // Privileged references must stay unavailable even when an engine caller supplies one.
        ability.getTargets().add(hidden); game.getStack().add(ability);
        final Map<String, Object> context = capture(ai);
        Assert.assertNull(entry(context, 0).get("targets"));
        Assert.assertFalse(policy(context).toString().contains("Black Lotus"));
        final Card stale = CardCopyService.getLKICopy(source);
        game.getAction().moveToHand(source, null);
        final SpellAbility oldSource = new SpellAbility.EmptySa(stale); game.getStack().add(oldSource);
        Assert.assertNull(entry(capture(ai), 0).get("source"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void boundedStackAndTargetPrefixesNeverInventReferencesOutsideCapturedEntities() {
        final Game game = game(); final Player ai = game.getPlayers().get(1);
        final Card source = addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield);
        final SpellAbility bottom = new SpellAbility.EmptySa(source); game.getStack().add(bottom);
        for (int i = 0; i < AiInputContext.MAX_STACK; i++) { game.getStack().add(new SpellAbility.EmptySa(source)); }
        game.getStack().peekAbility().getTargets().add(bottom);
        final Map<String, Object> context = capture(ai);
        Assert.assertEquals(entries(context).size(), AiInputContext.MAX_STACK);
        Assert.assertEquals(stack(context).get("truncated"), true);
        Assert.assertNull(entry(context, 0).get("targets"));
        Assert.assertTrue(context.get("omissions").toString().contains("OUTSIDE_CAPTURED_PREFIX"));
        final Game other = game(); final Player actor = other.getPlayers().get(1);
        final Card host = addCardToZone("Grizzly Bears", actor, ZoneType.Battlefield);
        final SpellAbility many = new SpellAbility.EmptySa(host);
        many.setCopied(true);
        for (int i = 0; i <= AiInputContext.MAX_STACK_TARGETS; i++) {
            many.getTargets().add(addCardToZone("Memnite", actor, ZoneType.Battlefield));
        }
        other.getStack().add(many);
        Assert.assertNull(entry(capture(actor), 0).get("targets"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void closedGamePublishesNoStaleStackPayload() {
        final Game game = game(); final Player ai = game.getPlayers().get(1);
        game.getStack().add(new SpellAbility.EmptySa(addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield)));
        game.closeEventObservations();
        final Map<String, Object> context = capture(ai);
        Assert.assertEquals(context.get("status"), "UNAVAILABLE");
        Assert.assertNull(context.get("policy"));
        Assert.assertTrue(((List<?>) ((Map<?, ?>) context.get("diagnostics")).get("stackBindings")).isEmpty());
    }

    @Test(groups = {"UnitTest", "fast"})
    public void hypotheticalRootPlansDoNotBecomePublicStackInputs() {
        final Game game = game(); final Player ai = game.getPlayers().get(1);
        final SpellAbility root = new SpellAbility.EmptySa(addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield));
        game.getStack().add(root);
        final Map<String, Object> before = AiInputContext.capture(ai, null, "BEFORE_SELECTOR", "PLANNING_WORKING_STATE", () -> true);
        game.getStack().peekAbility().setXManaCostPaid(100);
        game.getStack().peekAbility().getTargets().add(game.getPlayers().get(0));
        final Map<String, Object> after = AiInputContext.capture(ai, null, "BEFORE_SELECTOR", "PLANNING_WORKING_STATE", () -> true);
        Assert.assertEquals(policy(before), policy(after));
        Assert.assertNull(entry(after, 0).get("rootX"));
        Assert.assertNull(entry(after, 0).get("targets"));
    }

    @Test(groups = {"UnitTest", "fast"})
    public void stagingQueueDoesNotChangeTheActiveStackProjectionOrItsTraversalBudget() {
        Map<?, ?> baseline = null; Long baselineWork = null;
        for (final int queued : List.of(0, 20)) {
            final Game game = game(); final Player ai = game.getPlayers().get(1);
            final SpellAbility active = spell("Lightning Bolt", ai);
            active.getTargets().add(game.getPlayers().get(0));
            game.getStack().addAndUnfreeze(active);
            game.getStack().freezeStack(null);
            for (int i = 0; i < queued; i++) {
                final SpellAbility pending = spell("Lightning Bolt", ai);
                pending.getTargets().add(game.getPlayers().get(0));
                game.getStack().add(pending);
            }
            Assert.assertEquals(game.getStack().size(), 1);
            final var state = ((PlayerControllerAi) ai.getController()).inputContextState();
            final long before = state.remainingWork();
            final Map<String, Object> context = capture(ai);
            final long used = before - state.remainingWork();
            if (baseline == null) { baseline = policy(context); baselineWork = used; }
            else {
                Assert.assertEquals(policy(context), baseline);
                Assert.assertEquals(used, baselineWork.longValue());
            }
        }
    }

    @Test(groups = {"UnitTest", "fast"})
    public void retiredRealSpellSourceCannotBeRecreatedFromItsOldZonePointer() {
        final Game game = game(); final Player ai = game.getPlayers().get(1);
        final SpellAbility bolt = spell("Lightning Bolt", ai); bolt.getTargets().add(game.getPlayers().get(0));
        game.getStack().addAndUnfreeze(bolt);
        Assert.assertNotNull(entry(capture(ai), 0).get("source"));
        final Card retired = bolt.getHostCard();
        game.getAction().moveToHand(retired, null);
        Assert.assertEquals(game.getStack().size(), 1);
        Assert.assertNull(entry(capture(ai), 0).get("source"));
    }
}
