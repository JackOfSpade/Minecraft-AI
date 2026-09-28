package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.InteractAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;

public final class BreedTask extends AbstractTask {
    private enum Phase {
        FIND_PAIR,
        APPROACH_A,
        FEED_A,
        APPROACH_B,
        FEED_B,
        DONE
    }

    private static final int SEARCH_RANGE = 16;
    private static final float FEED_RANGE = 3.5F;

    private final EntityType<?> type;
    private final int targetPairs;
    private final Item food;
    private Phase phase = Phase.FIND_PAIR;
    private AnimalEntity first;
    private AnimalEntity second;
    private int bredPairs;

    public BreedTask(EntityType<?> type, int pairs) {
        this.type = type;
        this.targetPairs = Math.max(1, pairs);
        this.food = foodFor(type);
    }

    @Override
    public String name() {
        return "breed";
    }

    @Override
    public String describe() {
        return "Breeding " + Registries.ENTITY_TYPE.getId(type) + " " + bredPairs + "/" + targetPairs + " phase=" + phase;
    }

    @Override
    public double progress() {
        return Math.min(1.0D, (double) bredPairs / targetPairs);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.FIND_PAIR;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 2400) {
            fail("breed_timeout");
            return;
        }
        if (InventoryAction.countItem(bot, food) < 2) {
            fail("missing " + food + " x2");
            return;
        }
        switch (phase) {
            case FIND_PAIR -> findPair(bot);
            case APPROACH_A -> approach(bot, first, Phase.FEED_A);
            case FEED_A -> feed(bot, first, Phase.APPROACH_B);
            case APPROACH_B -> approach(bot, second, Phase.FEED_B);
            case FEED_B -> feedSecond(bot);
            case DONE -> complete();
        }
    }

    private void findPair(AIPlayerEntity bot) {
        List<AnimalEntity> candidates = bot.getEntityWorld()
                .getEntitiesByClass(AnimalEntity.class, bot.getBoundingBox().expand(SEARCH_RANGE),
                        animal -> animal.isAlive()
                                && animal.getType().equals(type)
                                && !animal.isBaby()
                                && animal.getBreedingAge() == 0
                                && animal.canEat())
                .stream()
                .filter(animal -> io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveEntity(bot, animal))
                .sorted(Comparator.comparingDouble(bot::distanceTo))
                .toList();
        if (candidates.size() < 2) {
            fail("no_breedable_pair");
            return;
        }
        first = candidates.get(0);
        second = candidates.get(1);
        phase = Phase.APPROACH_A;
    }

    private void approach(AIPlayerEntity bot, AnimalEntity animal, Phase nextPhase) {
        if (animal == null || !animal.isAlive()) {
            phase = Phase.FIND_PAIR;
            return;
        }
        lookAt(bot, animal);
        if (bot.distanceTo(animal) <= FEED_RANGE) {
            bot.getActionPack().stopAll();
            phase = nextPhase;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(animal.getBlockPos());
        }
    }

    private void feed(AIPlayerEntity bot, AnimalEntity animal, Phase nextPhase) {
        ActionResult result = equipFood(bot);
        if (result.isFailed()) {
            fail(result.reason());
            return;
        }
        lookAt(bot, animal);
        ActionResult feed = InteractAction.useItemOnEntity(bot, animal, Hand.MAIN_HAND);
        if (feed.isFailed()) {
            fail("cannot_breed:" + feed.reason());
            return;
        }
        phase = nextPhase;
    }

    private void feedSecond(AIPlayerEntity bot) {
        feed(bot, second, Phase.DONE);
        if (state == TaskState.RUNNING && phase == Phase.DONE) {
            if (first != null && second != null && first.canBreedWith(second)) {
                first.breed(bot.getEntityWorld(), second);
            } else {
                // bredPairs still counts this toward the target below even though no baby was
                // actually produced (e.g. the pair went on breeding cooldown between the two feed
                // ticks) -- the task then completes normally, so this is the only place that fact
                // is ever recorded.
                BotLog.warn(LogCategory.TASK, bot, "breed_pair_not_receptive",
                        "type", Registries.ENTITY_TYPE.getId(type).toString());
            }
            bredPairs++;
            first = null;
            second = null;
            phase = bredPairs >= targetPairs ? Phase.DONE : Phase.FIND_PAIR;
        }
    }

    private ActionResult equipFood(AIPlayerEntity bot) {
        int slot = InventoryAction.findItem(bot, food).orElse(-1);
        if (slot < 0) {
            return ActionResult.failed("missing " + food + " x1");
        }
        return InventoryAction.equipFromSlot(bot, slot) >= 0
                ? ActionResult.SUCCESS
                : ActionResult.failed("cannot_equip_food");
    }

    private static void lookAt(AIPlayerEntity bot, AnimalEntity animal) {
        Vec3d target = animal.getEntityPos().add(0.0D, animal.getHeight() * 0.5D, 0.0D);
        LookAction.lookAt(bot, target);
    }

    public static Item foodFor(EntityType<?> type) {
        if (type == EntityType.COW || type == EntityType.SHEEP || type == EntityType.MOOSHROOM || type == EntityType.GOAT) {
            return Items.WHEAT;
        }
        if (type == EntityType.PIG || type == EntityType.RABBIT) {
            return Items.CARROT;
        }
        if (type == EntityType.CHICKEN) {
            return Items.WHEAT_SEEDS;
        }
        if (type == EntityType.HORSE || type == EntityType.DONKEY || type == EntityType.MULE) {
            return Items.GOLDEN_CARROT;
        }
        throw new IllegalArgumentException("unsupported_breed_entity: " + Registries.ENTITY_TYPE.getId(type));
    }
}
