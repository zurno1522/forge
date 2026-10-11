package forge.view;

import com.google.common.eventbus.Subscribe;
import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.GameEntityView;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.event.*;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.StackItemView;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only translator from Forge game events to the structured simulation trace.
 */
final class SimulationTraceCollector {
    private final Game game;
    private final SimulationTraceWriter writer;
    private final int gameNumber;
    private final Map<LobbyPlayer, Integer> participantIds;
    private final Integer matchId;
    private final Integer gameInMatch;
    private final Integer tournamentRound;
    private final Map<Integer, Integer> abilityToStack = new HashMap<>();
    private final Map<Integer, Integer> mulligansByPlayer = new HashMap<>();
    private int eventIndex = 0;
    private IOException failure;

    SimulationTraceCollector(final Game game, final SimulationTraceWriter writer, final int gameNumber,
            final Map<LobbyPlayer, Integer> participantIds, final Integer matchId,
            final Integer gameInMatch, final Integer tournamentRound) {
        this.game = game;
        this.writer = writer;
        this.gameNumber = gameNumber;
        this.participantIds = participantIds;
        this.matchId = matchId;
        this.gameInMatch = gameInMatch;
        this.tournamentRound = tournamentRound;
    }

    @Subscribe
    public void onGameEvent(final GameEvent event) {
        if (failure != null) {
            return;
        }
        try {
            if (event instanceof GameEventGameStarted e) {
                gameStarted(e);
            } else if (event instanceof GameEventAttackersDeclared e) {
                attackersDeclared(e);
            } else if (event instanceof GameEventBlockersDeclared e) {
                blockersDeclared(e);
            } else if (event instanceof GameEventManaPool e) {
                manaPoolChanged(e);
            } else if (event instanceof GameEventTurnBegan e) {
                turnBegan(e);
            } else if (event instanceof GameEventTurnPhase e) {
                phaseChanged(e);
            } else if (event instanceof GameEventTurnEnded) {
                turnEnded();
            } else if (event instanceof GameEventSpellAbilityCast e) {
                stackAdded(e);
            } else if (event instanceof GameEventSpellResolved e) {
                stackResolved(e);
            } else if (event instanceof GameEventSpellRemovedFromStack e) {
                stackRemoved(e);
            } else if (event instanceof GameEventStackCountered e) {
                stackCountered(e);
            } else if (event instanceof GameEventCardChangeZone e) {
                zoneChange(e);
            } else if (event instanceof GameEventLandPlayed e) {
                landPlayed(e);
            } else if (event instanceof GameEventMulligan e) {
                mulligan(e);
            } else if (event instanceof GameEventStartingHandFinalized e) {
                startingHandFinalized(e);
            } else if (event instanceof GameEventCardsDrawn e) {
                cardsDrawn(e);
            } else if (event instanceof GameEventScry e) {
                scry(e);
            } else if (event instanceof GameEventSurveil e) {
                surveil(e);
            } else if (event instanceof GameEventShuffle e) {
                shuffle(e);
            } else if (event instanceof GameEventCardTapped e) {
                cardTapped(e);
            } else if (event instanceof GameEventPlayerLivesChanged e) {
                livesChanged(e);
            } else if (event instanceof GameEventCardCounters e) {
                cardCounters(e);
            } else if (event instanceof GameEventPlayerCounters e) {
                playerCounters(e);
            } else if (event instanceof GameEventPlayerDamaged e) {
                playerDamaged(e);
            } else if (event instanceof GameEventCardAttachment e) {
                attachmentChanged(e);
            } else if (event instanceof GameEventGameOutcome) {
                gameOutcome();
            } else if (event instanceof GameEventGameFinished) {
                write(scoped("GAME_FINISHED", true));
            }
        } catch (IOException e) {
            failure = e;
        }
    }

    void checkHealthy() throws IOException {
        if (failure != null) {
            throw failure;
        }
    }

    void executionFinished(final String status, final Throwable error) throws IOException {
        checkHealthy();
        final Map<String, Object> record = scoped("GAME_EXECUTION_FINISHED", false);
        record.put("status", status);
        if (error != null) {
            final Map<String, Object> errorRef = new LinkedHashMap<>();
            errorRef.put("type", error.getClass().getSimpleName());
            errorRef.put("message", error.getMessage() == null ? "" : error.getMessage());
            record.put("error", errorRef);
        }
        write(record);
        writer.flush();
    }

    int nextEventIndex() {
        return eventIndex + 1;
    }

    private void gameStarted(final GameEventGameStarted event) throws IOException {
        final Map<String, Object> record = scoped("GAME_STARTED", false);
        record.put("gameType", event.gameType().name());
        if (matchId != null) {
            record.put("matchId", matchId);
            record.put("gameInMatch", gameInMatch);
        }
        if (tournamentRound != null) {
            record.put("tournamentRound", tournamentRound);
        }
        record.put("firstPlayer", playerRef(event.firstTurn()));

        final List<Map<String, Object>> players = new ArrayList<>();
        for (final Player player : game.getPlayers()) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            final Integer participantId = participantIds.get(player.getRegisteredPlayer().getPlayer());
            if (participantId != null) {
                entry.put("participantId", participantId);
            }
            entry.put("player", playerRef(player.getView()));
            players.add(entry);
        }
        record.put("players", players);
        write(record);
    }

    private void attackersDeclared(final GameEventAttackersDeclared event) throws IOException {
        final Map<String, Object> record = scoped("ATTACKERS_DECLARED", true);
        record.put("player", playerRef(event.player()));

        final List<Map<String, Object>> groups = new ArrayList<>();
        for (final Map.Entry<GameEntityView, Collection<CardView>> entry : event.attackersMap().asMap().entrySet()) {
            final Map<String, Object> group = new LinkedHashMap<>();
            group.put("defender", entityRef(entry.getKey()));
            final List<Map<String, Object>> attackers = new ArrayList<>();
            for (final CardView attacker : entry.getValue()) {
                attackers.add(cardRef(attacker));
            }
            group.put("attackers", attackers);
            groups.add(group);
        }
        record.put("groups", groups);
        write(record);
    }

    private void blockersDeclared(final GameEventBlockersDeclared event) throws IOException {
        final Map<String, Object> record = scoped("BLOCKERS_DECLARED", true);
        record.put("defendingPlayer", playerRef(event.defendingPlayer()));

        final List<Map<String, Object>> attackedEntities = new ArrayList<>();
        for (final Map.Entry<GameEntityView, com.google.common.collect.Multimap<CardView, CardView>> entry
                : event.blockers().entrySet()) {
            final Map<String, Object> attacked = new LinkedHashMap<>();
            attacked.put("attackedEntity", entityRef(entry.getKey()));

            final List<Map<String, Object>> blocks = new ArrayList<>();
            for (final Map.Entry<CardView, Collection<CardView>> block
                    : entry.getValue().asMap().entrySet()) {
                final Map<String, Object> blockEntry = new LinkedHashMap<>();
                blockEntry.put("attacker", cardRef(block.getKey()));
                final List<Map<String, Object>> blockers = new ArrayList<>();
                for (final CardView blocker : block.getValue()) {
                    blockers.add(cardRef(blocker));
                }
                blockEntry.put("blockers", blockers);
                blocks.add(blockEntry);
            }
            attacked.put("blocks", blocks);
            attackedEntities.add(attacked);
        }
        record.put("attackedEntities", attackedEntities);
        write(record);
    }

    private void manaPoolChanged(final GameEventManaPool event) throws IOException {
        final Map<String, Object> record = scoped("MANA_POOL_CHANGED", true);
        record.put("player", playerRef(event.player()));
        record.put("change", event.mode().name());

        final Player player = findPlayer(event.player().getId());
        final List<Map<String, Object>> mana = new ArrayList<>();
        if (player != null) {
            for (final Mana floating : player.getManaPool()) {
                mana.add(manaRef(floating, null));
            }
        }
        record.put("mana", mana);
        write(record);
    }

    private void turnBegan(final GameEventTurnBegan event) throws IOException {
        final Map<String, Object> record = scoped("TURN_BEGAN", false);
        record.put("turn", event.turnNumber());
        record.put("player", playerRef(event.turnOwner()));
        write(record);
    }

    private void phaseChanged(final GameEventTurnPhase event) throws IOException {
        final Map<String, Object> record = scoped("PHASE_CHANGED", false);
        if (game.getPhaseHandler().getTurn() > 0) {
            record.put("turn", game.getPhaseHandler().getTurn());
        }
        record.put("phase", event.phase().name());
        record.put("player", playerRef(event.playerTurn()));
        write(record);
    }

    private void turnEnded() throws IOException {
        final Map<String, Object> record = scoped("TURN_ENDED", false);
        if (game.getPhaseHandler().getTurn() > 0) {
            record.put("turn", game.getPhaseHandler().getTurn());
        }
        write(record);
    }

    private void stackAdded(final GameEventSpellAbilityCast event) throws IOException {
        final StackItemView item = event.si();
        final Map<String, Object> record = scoped("STACK_ADDED", true);
        record.put("stackId", item.getId());
        record.put("abilityId", event.sa().getId());
        record.put("kind", event.sa().isSpell() ? "SPELL" : item.isTrigger() ? "TRIGGERED_ABILITY" : "ACTIVATED_ABILITY");
        record.put("copied", false);
        record.put("player", playerRef(item.getActivatingPlayer()));
        record.put("source", cardRef(item.getSourceCard()));

        final List<Map<String, Object>> targets = new ArrayList<>();
        if (item.getTargetCards() != null) {
            for (final CardView target : item.getTargetCards()) {
                final Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("type", "CARD");
                ref.put("card", cardRef(target));
                targets.add(ref);
            }
        }
        if (item.getTargetPlayers() != null) {
            for (final PlayerView target : item.getTargetPlayers()) {
                final Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("type", "PLAYER");
                ref.put("player", playerRef(target));
                targets.add(ref);
            }
        }
        if (!targets.isEmpty()) {
            record.put("targets", targets);
        }
        if (event.targetDescription() != null && !event.targetDescription().isEmpty()) {
            record.put("targetDescription", event.targetDescription());
        }

        final SpellAbilityStackInstance live = findStackItem(item.getId());
        if (live != null) {
            final SpellAbility sa = live.getSpellAbility();
            record.put("copied", sa.isCopied());
            abilityToStack.put(sa.getId(), item.getId());
            addCastDetails(record, sa);
        } else {
            abilityToStack.put(event.sa().getId(), item.getId());
        }
        write(record);
    }

    private void addCastDetails(final Map<String, Object> record, final SpellAbility sa) {
        final Card host = sa.getHostCard();

        if (sa.isSpell() && !sa.isCopied() && host != null && host.getCastFrom() != null) {
            record.put("castFrom", zoneRef(host.getCastFrom()));
        }

        if (sa.isCopied()) {
            if (sa.isAbility() && sa.getOriginalAbility() != null) {
                record.put("copiedFromAbilityId", sa.getOriginalAbility().getId());
            }
            if (sa.isSpell() && host != null && host.getCopiedPermanent() != null) {
                record.put("copiedFromSource", cardRef(host.getCopiedPermanent().getView()));
            }
        }

        if (!sa.isCopied() && !sa.getPayingMana().isEmpty()) {
            final List<Map<String, Object>> manaSpent = new ArrayList<>();
            for (final Mana mana : sa.getPayingMana()) {
                manaSpent.add(manaRef(mana, sa));
            }
            record.put("manaSpent", manaSpent);
        }

        final Map<String, Object> choices = new LinkedHashMap<>();
        if (sa.getXManaCostPaid() != null) {
            choices.put("x", sa.getXManaCostPaid());
        }
        if (sa.getChosenList() != null && !sa.getChosenList().isEmpty()) {
            final List<Map<String, Object>> modes = new ArrayList<>();
            for (final AbilitySub mode : sa.getChosenList()) {
                final Map<String, Object> modeRef = new LinkedHashMap<>();
                modeRef.put("abilityId", mode.getId());
                modeRef.put("description", mode.getDescription());
                modes.add(modeRef);
            }
            choices.put("modes", modes);
        }
        final List<Map<String, Object>> optionalCosts = new ArrayList<>();
        for (final OptionalCost cost : sa.getOptionalCosts()) {
            final Map<String, Object> costRef = new LinkedHashMap<>();
            costRef.put("type", cost.name());
            costRef.put("name", cost.getName());
            optionalCosts.add(costRef);
        }
        if (!optionalCosts.isEmpty()) {
            choices.put("optionalCosts", optionalCosts);
        }
        if (sa.getAlternativeCost() != null) {
            choices.put("alternativeCost", sa.getAlternativeCost().name());
        }
        if (sa.hasParam("WithoutManaCost")) {
            choices.put("withoutManaCost", true);
        }
        if (!choices.isEmpty()) {
            record.put("choices", choices);
        }
    }

    private void stackResolved(final GameEventSpellResolved event) throws IOException {
        final Map<String, Object> record = scoped("STACK_RESOLVED", true);
        record.put("abilityId", event.spell().getId());
        final Integer stackId = abilityToStack.get(event.spell().getId());
        if (stackId != null) {
            record.put("stackId", stackId);
        }
        record.put("source", cardRef(event.spell().getHostCard()));
        record.put("fizzled", event.hasFizzled());
        if (event.stackDescription() != null && !event.stackDescription().isEmpty()) {
            record.put("description", event.stackDescription());
        }
        write(record);
    }

    private void stackRemoved(final GameEventSpellRemovedFromStack event) throws IOException {
        final Map<String, Object> record = scoped("STACK_REMOVED", true);
        record.put("abilityId", event.sa().getId());
        final Integer stackId = abilityToStack.remove(event.sa().getId());
        if (stackId != null) {
            record.put("stackId", stackId);
        }
        record.put("source", cardRef(event.sa().getHostCard()));
        write(record);
    }

    private void stackCountered(final GameEventStackCountered event) throws IOException {
        final Map<String, Object> record = scoped("STACK_COUNTERED", true);
        record.put("stackId", event.stackId());
        record.put("abilityId", event.counteredAbility().getId());
        record.put("source", cardRef(event.counteredAbility().getHostCard()));
        record.put("cause", causeRef(event.cause()));
        write(record);
    }

    private void zoneChange(final GameEventCardChangeZone event) throws IOException {
        final Map<String, Object> record = scoped("ZONE_CHANGE", true);
        record.put("card", cardRef(event.card()));
        if (event.from() != null) {
            record.put("from", zoneRef(event.from()));
        }
        if (event.to() != null) {
            record.put("to", zoneRef(event.to()));
            if (event.to().zoneType() == ZoneType.Battlefield) {
                final Map<String, Object> battlefieldState = new LinkedHashMap<>();
                battlefieldState.put("tapped", event.card().isTapped());
                record.put("battlefieldState", battlefieldState);
            }
        }
        write(record);
    }

    private void landPlayed(final GameEventLandPlayed event) throws IOException {
        final Map<String, Object> record = scoped("LAND_PLAYED", true);
        record.put("player", playerRef(event.player()));
        record.put("land", cardRef(event.land()));
        write(record);
    }

    private void mulligan(final GameEventMulligan event) throws IOException {
        final int count = mulligansByPlayer.merge(event.player().getId(), 1, Integer::sum);
        final Map<String, Object> record = scoped("MULLIGAN", false);
        record.put("player", playerRef(event.player()));
        record.put("mulligans", count);
        write(record);
    }

    private void startingHandFinalized(final GameEventStartingHandFinalized event) throws IOException {
        final Map<String, Object> record = scoped("STARTING_HAND_FINALIZED", false);
        record.put("player", playerRef(event.player()));
        record.put("mulligans", mulligansByPlayer.getOrDefault(event.player().getId(), 0));
        record.put("handSize", event.handSize());

        final Player player = findPlayer(event.player().getId());
        if (player != null) {
            final List<Map<String, Object>> cards = new ArrayList<>();
            for (final Card card : player.getCardsIn(ZoneType.Hand)) {
                cards.add(cardRef(card.getView()));
            }
            record.put("cards", cards);
        }
        write(record);
    }

    private void cardsDrawn(final GameEventCardsDrawn event) throws IOException {
        final Map<String, Object> record = scoped("CARDS_DRAWN", true);
        record.put("player", playerRef(event.player()));
        final List<Map<String, Object>> cards = new ArrayList<>();
        for (final CardView card : event.cards()) {
            cards.add(cardRef(card));
        }
        record.put("cards", cards);
        write(record);
    }

    private void scry(final GameEventScry event) throws IOException {
        final Map<String, Object> record = scoped("SCRY", true);
        record.put("player", playerRef(event.player()));
        record.put("toTop", event.toTop());
        record.put("toBottom", event.toBottom());
        write(record);
    }

    private void surveil(final GameEventSurveil event) throws IOException {
        final Map<String, Object> record = scoped("SURVEIL", true);
        record.put("player", playerRef(event.player()));
        record.put("toLibrary", event.toLibrary());
        record.put("toGraveyard", event.toGraveyard());
        write(record);
    }

    private void shuffle(final GameEventShuffle event) throws IOException {
        final Map<String, Object> record = scoped("SHUFFLE", true);
        record.put("player", playerRef(event.player()));
        write(record);
    }

    private void cardTapped(final GameEventCardTapped event) throws IOException {
        final Map<String, Object> record = scoped("CARD_TAPPED", true);
        record.put("card", cardRef(event.card()));
        record.put("tapped", event.tapped());
        write(record);
    }

    private void livesChanged(final GameEventPlayerLivesChanged event) throws IOException {
        if (event.oldLives() == event.newLives()) {
            return;
        }
        final boolean gained = event.newLives() > event.oldLives();
        final Map<String, Object> record = scoped(gained ? "LIFE_GAINED" : "LIFE_LOST", true);
        record.put("player", playerRef(event.player()));
        record.put("amount", Math.abs(event.newLives() - event.oldLives()));
        record.put("oldLife", event.oldLives());
        record.put("newLife", event.newLives());
        write(record);
    }

    private void cardCounters(final GameEventCardCounters event) throws IOException {
        final Map<String, Object> record = scoped("CARD_COUNTERS_CHANGED", true);
        record.put("card", cardRef(event.card()));
        record.put("counterType", event.type().toString());
        record.put("oldValue", event.oldValue());
        record.put("newValue", event.newValue());
        write(record);
    }

    private void playerCounters(final GameEventPlayerCounters event) throws IOException {
        final Map<String, Object> record = scoped("PLAYER_COUNTERS_CHANGED", true);
        record.put("player", playerRef(event.receiver()));
        record.put("counterType", event.type().toString());
        record.put("oldValue", event.oldValue());
        record.put("newValue", event.oldValue() + event.amount());
        write(record);
    }

    private void playerDamaged(final GameEventPlayerDamaged event) throws IOException {
        final Map<String, Object> record = scoped("PLAYER_DAMAGED", true);
        record.put("player", playerRef(event.target()));
        record.put("source", cardRef(event.source()));
        record.put("amount", event.amount());
        record.put("combat", event.combat());
        record.put("infect", event.infect());
        write(record);
    }

    private void attachmentChanged(final GameEventCardAttachment event) throws IOException {
        final Map<String, Object> record = scoped("ATTACHMENT_CHANGED", true);
        record.put("attachment", cardRef(event.equipment()));
        if (event.oldEntity() != null) {
            record.put("from", entityRef(event.oldEntity()));
        }
        if (event.newTarget() != null) {
            record.put("to", entityRef(event.newTarget()));
        }
        write(record);
    }

    private void gameOutcome() throws IOException {
        if (game.getOutcome() == null) {
            return;
        }
        final Map<String, Object> record = scoped("GAME_OUTCOME", false);
        record.put("endReason", game.getOutcome().getWinCondition().name());
        record.put("draw", game.getOutcome().isDraw());
        record.put("lastTurnNumber", game.getOutcome().getLastTurnNumber());

        final List<Map<String, Object>> winners = new ArrayList<>();
        for (final Player player : game.getPlayers()) {
            if (player.getOutcome() != null && player.getOutcome().hasWon()) {
                final Map<String, Object> winner = new LinkedHashMap<>();
                final Integer participantId = participantIds.get(player.getRegisteredPlayer().getPlayer());
                if (participantId != null) {
                    winner.put("participantId", participantId);
                }
                winner.put("player", playerRef(player.getView()));
                winners.add(winner);
            }
        }
        record.put("winners", winners);
        write(record);
    }

    private Player findPlayer(final int playerId) {
        for (final Player player : game.getPlayers()) {
            if (player.getId() == playerId) {
                return player;
            }
        }
        return null;
    }

    private SpellAbilityStackInstance findStackItem(final int stackId) {
        for (final SpellAbilityStackInstance item : game.getStack()) {
            if (item.getId() == stackId) {
                return item;
            }
        }
        return null;
    }

    private Map<String, Object> scoped(final String type, final boolean includeContext) {
        final Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", type);
        record.put("game", gameNumber);
        record.put("eventIndex", ++eventIndex);
        if (includeContext && game.getPhaseHandler().getTurn() > 0 && game.getPhaseHandler().getPlayerTurn() != null) {
            record.put("turn", game.getPhaseHandler().getTurn());
            if (game.getPhaseHandler().getPhase() != null) {
                record.put("phase", game.getPhaseHandler().getPhase().name());
            }
        }
        return record;
    }

    private void write(final Map<String, Object> record) throws IOException {
        writer.write(record);
    }

    private static Map<String, Object> playerRef(final PlayerView player) {
        if (player == null) {
            return null;
        }
        final Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", player.getId());
        ref.put("name", player.getName());
        return ref;
    }

    private static Map<String, Object> causeRef(final forge.game.spellability.SpellAbilityView ability) {
        final Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("abilityId", ability.getId());
        ref.put("source", cardRef(ability.getHostCard()));
        return ref;
    }

    private static Map<String, Object> manaRef(final Mana mana, final SpellAbility paidAbility) {
        final Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("payer", playerRef(mana.getPlayer().getView()));
        ref.put("color", mana.toString());
        ref.put("source", cardRef(mana.getSourceCard().getView()));
        ref.put("snow", mana.isSnow());

        if (mana.getManaAbility() == null) {
            ref.put("manaRestriction", "");
            ref.put("extraManaRestriction", "");
            ref.put("persistent", false);
            ref.put("combatOnly", false);
            ref.put("cannotCounterPaidSpell", false);
            ref.put("addsCounters", false);
            ref.put("addsKeywords", false);
            ref.put("triggersWhenSpent", false);
        } else {
            ref.put("manaRestriction", mana.getManaAbility().getManaRestrictions());
            ref.put("extraManaRestriction", mana.getManaAbility().getExtraManaRestriction());
            ref.put("persistent", mana.isPersistentMana());
            ref.put("combatOnly", mana.isCombatMana());
            ref.put("cannotCounterPaidSpell", paidAbility == null
                    ? mana.getManaAbility().isCannotCounterPaidWith()
                    : mana.addsNoCounterMagic(paidAbility));
            ref.put("addsCounters", mana.addsCounters(paidAbility));
            ref.put("addsKeywords", mana.addsKeywords(paidAbility));
            ref.put("triggersWhenSpent", mana.triggersWhenSpent());
        }
        return ref;
    }

    private static Map<String, Object> cardRef(final CardView card) {
        if (card == null) {
            return null;
        }
        final Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", card.getId());
        ref.put("name", card.getName());
        ref.put("gamePieceType", card.getGamePieceType().name());
        return ref;
    }

    private static Map<String, Object> zoneRef(final ZoneView zone) {
        final Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("zone", zone.zoneType().name());
        ref.put("player", playerRef(zone.player()));
        return ref;
    }

    private static Map<String, Object> zoneRef(final Zone zone) {
        final Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("zone", zone.getZoneType().name());
        ref.put("player", zone.getPlayer() == null ? null : playerRef(zone.getPlayer().getView()));
        return ref;
    }

    private static Map<String, Object> entityRef(final GameEntityView entity) {
        final Map<String, Object> ref = new LinkedHashMap<>();
        if (entity instanceof CardView card) {
            ref.put("type", "CARD");
            ref.put("card", cardRef(card));
        } else if (entity instanceof PlayerView player) {
            ref.put("type", "PLAYER");
            ref.put("player", playerRef(player));
        } else {
            ref.put("type", "ENTITY");
            ref.put("id", entity.getId());
            ref.put("name", entity.getName());
        }
        return ref;
    }
}
