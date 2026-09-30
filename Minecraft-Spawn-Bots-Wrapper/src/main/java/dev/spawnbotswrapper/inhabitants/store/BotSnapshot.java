package dev.spawnbotswrapper.inhabitants.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What a live inhabitant carried and how it was doing at some moment: every inventory slot (armor and offhand
 * included), the selected slot, health, hunger, active effects, experience, fire and air. Persisted with its
 * {@link BotRecord} so that a bot that is despawned (dormancy) or restarted comes back with what it had USED UP:
 * arrows fired stay fired, a worn chestplate stays damaged, a wounded bot stays wounded. Without it the original
 * profile would dress the bot again from scratch (a full quiver, pristine gear, full health), which a normal
 * player cannot do.
 * <p>
 * Plain data on purpose (Gson-bound like {@link BotRecord}, no Minecraft types): an item stack is the JSON text of
 * vanilla's own item codec, an effect the JSON text of vanilla's effect codec, so the store stays independent of the
 * game and a stack written today is read back by the same vanilla codec (count, damage and every data component).
 * Equality is by value, so "did anything change since the last snapshot" is {@link #equals}.
 */
public final class BotSnapshot {
    public static final int CURRENT_VERSION = 1;

    /** One non-empty inventory slot: the slot number of vanilla's player inventory and the stack's codec JSON. */
    public static final class Entry {
        public int slot;
        public String stack;

        public Entry() {
        }

        public Entry(int slot, String stack) {
            this.slot = slot;
            this.stack = stack;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Entry e && e.slot == slot && Objects.equals(e.stack, stack);
        }

        @Override
        public int hashCode() {
            return Objects.hash(slot, stack);
        }
    }

    public int version = CURRENT_VERSION;
    /** Every non-empty slot, ascending; a slot that is not listed is empty. */
    public List<Entry> stacks = new ArrayList<>();
    public int selectedSlot;
    public float health;
    public int foodLevel;
    public float saturation;
    public float exhaustion;
    /** One JSON text per active effect (vanilla's effect codec). */
    public List<String> effects = new ArrayList<>();
    public int xpLevel;
    public float xpProgress;
    public int xpTotal;
    public int fireTicks;
    public int airSupply;

    public BotSnapshot() {
    }

    /**
     * Repairs what a hand-edited or truncated file can leave null and drops entries that cannot be used, so a
     * damaged snapshot degrades to "less restored" instead of an exception at wake-up time. Returns this.
     */
    public BotSnapshot normalised() {
        if (stacks == null) {
            stacks = new ArrayList<>();
        }
        stacks.removeIf(e -> e == null || e.stack == null || e.stack.isEmpty() || e.slot < 0);
        if (effects == null) {
            effects = new ArrayList<>();
        }
        effects.removeIf(e -> e == null || e.isEmpty());
        return this;
    }

    /** The stack text of one slot, or null when the slot is empty. */
    public String stackAt(int slot) {
        for (Entry e : stacks) {
            if (e.slot == slot) {
                return e.stack;
            }
        }
        return null;
    }

    /** True when it carries something a wake-up can use: a stack, or a plausible pulse (health above zero). */
    public boolean usable() {
        return health > 0.0f || !stacks.isEmpty();
    }

    /** One line for the log. */
    public String describe() {
        return stacks.size() + " stack(s), health " + health + ", food " + foodLevel + ", " + effects.size() + " effect(s)";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BotSnapshot s)) {
            return false;
        }
        return version == s.version && selectedSlot == s.selectedSlot
                && Float.compare(health, s.health) == 0 && foodLevel == s.foodLevel
                && Float.compare(saturation, s.saturation) == 0 && Float.compare(exhaustion, s.exhaustion) == 0
                && xpLevel == s.xpLevel && Float.compare(xpProgress, s.xpProgress) == 0 && xpTotal == s.xpTotal
                && fireTicks == s.fireTicks && airSupply == s.airSupply
                && Objects.equals(stacks, s.stacks) && Objects.equals(effects, s.effects);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, stacks, selectedSlot, health, foodLevel, saturation, exhaustion, effects, xpLevel,
                xpProgress, xpTotal, fireTicks, airSupply);
    }

    /**
     * Whether {@code next} differs from {@code previous} enough to be worth writing again: any change of the inventory,
     * selected slot, health, hunger, effects or experience. Fire and air ticks and the exhaustion accumulator move
     * every few ticks while the bot lives its life; they are kept in the newest snapshot that is written for another
     * reason but never cause a write of their own.
     */
    public static boolean worthPersisting(BotSnapshot previous, BotSnapshot next) {
        if (next == null) {
            return false;
        }
        if (previous == null) {
            return true;
        }
        return previous.selectedSlot != next.selectedSlot
                || Float.compare(previous.health, next.health) != 0
                || previous.foodLevel != next.foodLevel
                || Float.compare(previous.saturation, next.saturation) != 0
                || previous.xpLevel != next.xpLevel || previous.xpTotal != next.xpTotal
                || !Objects.equals(previous.stacks, next.stacks)
                || !Objects.equals(previous.effects, next.effects);
    }
}
