package io.github.zoyluo.minecraftai.goal;

import io.github.zoyluo.minecraftai.action.HarvestCore;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;

public final class GoalPredicates {
    public static final Set<String> ARMOR_CAPABILITIES = Set.of("helmet", "chestplate", "leggings", "boots", "sword");

    private GoalPredicates() {
    }

    public static GoalPredicate forGoal(Goal goal) {
        return switch (goal) {
            case Goal.HaveItem haveItem -> new GoalPredicate.ItemCount(
                    BuiltInRegistries.ITEM.getKey(haveItem.item()).toString(), haveItem.count());
            case Goal.HavePickaxeTier pickaxe -> new GoalPredicate.PickaxeTier(pickaxe.tier());
            case Goal.MineOre mineOre -> {
                Set<String> drops = HarvestCore.expectedDropsFor(mineOre.ores()).stream()
                        .map(item -> BuiltInRegistries.ITEM.getKey(item).toString())
                        .collect(Collectors.toSet());
                yield mineOre.isTimedCollection()
                        ? new GoalPredicate.TimedCollection(drops, mineOre.initialDropCount(), "ore_drops")
                        : new GoalPredicate.AnyItemCount(drops, mineOre.targetDropCount(), "ore_drops");
            }
            case Goal.HarvestCrop crop -> {
                String produce = BuiltInRegistries.ITEM.getKey(crop.produce()).toString();
                yield crop.isTimedCollection()
                        ? new GoalPredicate.TimedCollection(Set.of(produce), crop.initialProduceCount(), "crop_produce")
                        : new GoalPredicate.ItemCount(produce, crop.targetProduceCount());
            }
            case Goal.Armor ignored -> new GoalPredicate.ArmorSet(ARMOR_CAPABILITIES);
            case Goal.Workstation ignored -> new GoalPredicate.Workstation();
            case Goal.Stockpile stockpile -> new GoalPredicate.Stockpile(
                    BuiltInRegistries.ITEM.getKey(stockpile.item()).toString(), stockpile.count());
            case Goal.Food food -> new GoalPredicate.FoodUnits(food.cookedCount());
            case Goal.Build build -> new GoalPredicate.Structure(build.blueprint());
            case Goal.Fulfill fulfill -> new GoalPredicate.Fulfillment(fulfill.allocations(), Set.of());
        };
    }

    /** Evaluates a goal against the live snapshot plus any mission-owned delivery receipts. */
    public static GoalEvaluation evaluate(Goal goal,
                                          GoalSnapshot snapshot,
                                          Set<Goal.Allocation> completedDeliveries) {
        if (goal instanceof Goal.Fulfill fulfill) {
            return new GoalPredicate.Fulfillment(
                    fulfill.allocations(), completedDeliveries).evaluate(snapshot);
        }
        return forGoal(goal).evaluate(snapshot);
    }
}
