/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.game.spellability;

import java.util.Collection;
import java.util.List;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.Map;

import com.google.common.collect.ForwardingList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.util.IterableUtil;
import forge.util.collect.FCollection;

/**
 * <p>
 * Target_Choices class.
 * </p>
 *
 * @author Forge
 * @version $Id$
 */
public class TargetChoices extends ForwardingList<GameObject> implements Cloneable {

    private final FCollection<GameObject> targets = new FCollection<>();

    // Optional observational identity of the ordered target list. No engine objects or wire IDs.
    // Clones share an unchanged state's identity; every mutation attempt invalidates it.
    private transient Object selectionStateIdentity;

    /** Opaque ordered-list identity for observers; excludes divided-allocation accounting. */
    public final Object getSelectionStateIdentity() {
        if (selectionStateIdentity == null) { selectionStateIdentity = new Object(); }
        return selectionStateIdentity;
    }

    private final Map<Card, Player> cardControllers = Maps.newHashMap();

    private final Map<GameObject, Integer> dividedMap = Maps.newHashMap();

    public final boolean forEachControllerChanged(Card c) {
        return !c.getController().equals(cardControllers.get(c));
    }

    public final boolean add(final GameObject o) {
        selectionStateIdentity = null;
        if (o instanceof Player || o instanceof Card || o instanceof SpellAbility) {
            if (o instanceof Card c) {
                cardControllers.put(c, c.getController());
            }
            return super.add(o);
        }
        return false;
    }

    @Override
    public boolean removeAll(Collection<?> collection) {
        selectionStateIdentity = null;
        boolean result = super.removeAll(collection);
        for (Object e : collection) {
            this.dividedMap.remove(e);
        }
        for (Object e : collection) {
            this.cardControllers.remove(e);
        }
        return result;
    }

    @Override
    public boolean remove(Object object) {
        selectionStateIdentity = null;
        boolean result = super.remove(object);
        dividedMap.remove(object);
        cardControllers.remove(object);
        return result;
    }

    public final CardCollectionView getTargetCards() {
        return new CardCollection(IterableUtil.filter(targets, Card.class));
    }

    public final Iterable<Player> getTargetPlayers() {
        return IterableUtil.filter(targets, Player.class);
    }

    public final Iterable<SpellAbility> getTargetSpells() {
        return IterableUtil.filter(targets, SpellAbility.class);
    }

    public final Iterable<GameEntity> getTargetEntities() {
        return IterableUtil.filter(targets, GameEntity.class);
    }

    public final boolean isTargetingAnyCard() {
        return targets.stream().anyMatch(Card.class::isInstance);
    }

    public final boolean isTargetingAnyPlayer() {
        return targets.stream().anyMatch(Player.class::isInstance);
    }

    public final boolean isTargetingAnySpell() {
        return targets.stream().anyMatch(SpellAbility.class::isInstance);
    }

    public final Card getFirstTargetedCard() {
        return Iterables.getFirst(getTargetCards(), null);
    }

    public final Player getFirstTargetedPlayer() {
        return Iterables.getFirst(getTargetPlayers(), null);
    }

    public final SpellAbility getFirstTargetedSpell() {
        return Iterables.getFirst(getTargetSpells(), null);
    }

    public final void replaceTargetCard(final Card old, final CardCollectionView replace) {
        selectionStateIdentity = null;
        targets.remove(old);
        targets.addAll(replace);
    }

    @Override
    public TargetChoices clone() {
        TargetChoices tc = new TargetChoices();
        tc.targets.addAll(targets);
        tc.dividedMap.putAll(dividedMap);
        tc.cardControllers.putAll(cardControllers);
        tc.selectionStateIdentity = selectionStateIdentity;
        return tc;
    }
    @Override
    protected List<GameObject> delegate() {
        return targets;
    }

    @Override public void add(int index, GameObject target) {
        selectionStateIdentity = null;
        super.add(index, target);
    }
    @Override public boolean addAll(Collection<? extends GameObject> values) {
        selectionStateIdentity = null;
        return super.addAll(values);
    }
    @Override public boolean addAll(int index, Collection<? extends GameObject> values) {
        selectionStateIdentity = null;
        return super.addAll(index, values);
    }
    @Override public GameObject set(int index, GameObject target) {
        selectionStateIdentity = null;
        return super.set(index, target);
    }
    @Override public GameObject remove(int index) {
        selectionStateIdentity = null;
        return super.remove(index);
    }
    @Override public boolean retainAll(Collection<?> values) {
        selectionStateIdentity = null;
        return super.retainAll(values);
    }
    @Override public void clear() {
        selectionStateIdentity = null;
        super.clear();
    }

    @Override public Iterator<GameObject> iterator() {
        final Iterator<GameObject> source = super.iterator();
        return new Iterator<>() {
            @Override public boolean hasNext() { return source.hasNext(); }
            @Override public GameObject next() { return source.next(); }
            @Override public void remove() { selectionStateIdentity = null; source.remove(); }
        };
    }
    @Override public ListIterator<GameObject> listIterator() { return listIterator(0); }
    @Override public ListIterator<GameObject> listIterator(int index) {
        final ListIterator<GameObject> source = super.listIterator(index);
        return new ListIterator<>() {
            @Override public boolean hasNext() { return source.hasNext(); }
            @Override public GameObject next() { return source.next(); }
            @Override public boolean hasPrevious() { return source.hasPrevious(); }
            @Override public GameObject previous() { return source.previous(); }
            @Override public int nextIndex() { return source.nextIndex(); }
            @Override public int previousIndex() { return source.previousIndex(); }
            @Override public void remove() { selectionStateIdentity = null; source.remove(); }
            @Override public void set(GameObject value) { selectionStateIdentity = null; source.set(value); }
            @Override public void add(GameObject value) { selectionStateIdentity = null; source.add(value); }
        };
    }

    @Override
    public boolean contains(Object o) {
        if (o instanceof Card) {
            return IterableUtil.any(IterableUtil.filter(targets, Card.class), c -> c.equalsWithGameTimestamp((Card) o));
        }
        return super.contains(o);
    }

    public final void addDividedAllocation(final GameObject tgt, final Integer portionAllocated) {
        this.dividedMap.put(tgt, portionAllocated);
    }
    public Integer getDividedValue(GameObject c) {
        return dividedMap.get(c);
    }

    public Collection<Integer> getDividedValues() {
        return dividedMap.values();
    }

    public int getTotalDividedValue() {
        int result = 0;
        for (Integer i : getDividedValues()) {
            if (i != null)
                result += i;
        }
        return result;
    }
}
