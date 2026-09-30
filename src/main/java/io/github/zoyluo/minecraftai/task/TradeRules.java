package io.github.zoyluo.minecraftai.task;

import java.util.List;

/** The pure parts of a villager trade: who refuses, and how much of a result an inventory can take. */
final class TradeRules {
    private TradeRules() {
    }

    /**
     * Mirrors the gate of {@code Villager#mobInteract}: an unavailable, sleeping or busy villager does not open its trades
     * (the click is handed to the default handler instead), a baby only shakes its head, and a villager with no offers (a
     * nitwit, an unemployed one) has nothing to trade. Returns the failure reason, or null when the villager will trade.
     */
    static String refusal(boolean alive, boolean baby, boolean sleeping, boolean busyWithAnotherPlayer, boolean hasOffers) {
        if (!alive) {
            return "villager_lost";
        }
        if (sleeping) {
            return "villager_asleep";
        }
        if (busyWithAnotherPlayer) {
            return "villager_busy";
        }
        if (baby) {
            return "villager_baby";
        }
        if (!hasOffers) {
            return "villager_has_no_offers";
        }
        return null;
    }

    /**
     * How many items of one stack type fit: {@code emptySlots} slots of {@code maxStack} each, plus the room left in every
     * slot that already holds the same item with the same components ({@code matchingCounts}).
     */
    static int insertable(int maxStack, List<Integer> matchingCounts, int emptySlots) {
        long room = (long) Math.max(0, emptySlots) * maxStack;
        for (int count : matchingCounts) {
            room += Math.max(0, maxStack - count);
        }
        return (int) Math.min(Integer.MAX_VALUE, room);
    }
}
