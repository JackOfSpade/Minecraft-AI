package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.brain.ChatTranscript;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The chat warning for nearly broken gear (behaviour.gear.durabilityWarnings): a diamond or netherite item, a shield, bow,
 * crossbow, trident, mace, elytra or fishing rod that drops below 10 percent of its durability makes the bot say so, at once, once
 * per crossing, and it never interrupts anything (same task, no pause, nothing re-equipped). An iron item says nothing. The wear
 * is applied with vanilla's own hurtAndBreak, one use per tick, exactly as block breaks and hits spend it.
 */
public final class DurabilityWarningGameTests {
    private static final String ENV = "minecraftai-gametest:durability_warning_game_tests_";
    private static final String NEEDLE = "is about to break";

    @GameTest(environment = ENV + "diamond_pickaxe_crossing_warns_once_and_the_task_continues", maxTicks = 200)
    public void diamondPickaxeCrossingWarnsOnceAndTheTaskContinues(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "DurWarnPickGT");
        bot.getInventory().clearContent();
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        int max = pick.getMaxDamage();
        pick.setDamageValue(max - 160); // 160 of 1561 uses left: 10.25 percent, just above the threshold
        bot.getInventory().setItem(0, pick);
        bot.getInventory().setSelectedSlot(0);
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_durability_warning"));
        require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == work, "the fixture task did not start");
        require(context, count(chat(bot)) == 0, "the fixture already had a warning line: " + chat(bot));

        AtomicInteger tick = new AtomicInteger();
        AtomicInteger crossedAt = new AtomicInteger(-1);
        context.failIfEver(() -> {
            int now = tick.incrementAndGet();
            ItemStack held = bot.getMainHandItem();
            require(context, held.is(Items.DIAMOND_PICKAXE), "the pickaxe was swapped or lost at tick " + now + ": " + held);
            require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == work && work.state() == TaskState.RUNNING
                            && !TaskManager.INSTANCE.hasPaused(bot),
                    "the task was interrupted at tick " + now + " (remaining " + (max - held.getDamageValue()) + "): "
                            + TaskManager.INSTANCE.getActive(bot).map(Task::name).orElse("none"));
            int lines = count(chat(bot));
            int remaining = max - held.getDamageValue();
            if (now < 60) {
                if (crossedAt.get() < 0) {
                    boolean low = remaining * 100 < 10 * max;
                    // Instant: the bot's own tick runs between two of these checks, so the line may already stand in the very check
                    // that first sees the wear below the line, but never before it.
                    require(context, lines == (low ? 1 : 0), "expected " + (low ? "the" : "no") + " warning line at remaining " + remaining
                            + " of " + max + ": " + chat(bot));
                    if (low) {
                        crossedAt.set(now);
                    }
                } else if (now >= crossedAt.get() + 1) {
                    // Instantly: within a tick of the crossing exactly one line stands, and it stays one while the wear goes on.
                    require(context, lines == 1, "expected exactly one warning line, got " + lines + " at tick " + now + ": " + chat(bot));
                }
                held.hurtAndBreak(1, bot, EquipmentSlot.MAINHAND);
            } else if (now == 60) {
                require(context, crossedAt.get() > 0 && lines == 1, "the crossing produced " + lines + " lines: " + chat(bot));
                String line = lastLine(chat(bot));
                require(context, line.contains("My diamond pickaxe is about to break (") && line.contains("/" + max + " left)"),
                        "unexpected warning text: " + line);
                // Repaired to 50 percent: the warning is armed again but says nothing now.
                held.setDamageValue(max / 2);
            } else if (now == 63) {
                require(context, lines == 1, "the repair produced a line: " + chat(bot));
                // Worn below the threshold a second time: one more line.
                held.setDamageValue(max - 150);
            } else if (now == 66) {
                require(context, lines == 2, "the second crossing did not warn again exactly once: " + chat(bot));
                finish(context, bot);
            }
        });
    }

    /**
     * The literal case of the rule, with real work: a bot chops three logs with a diamond axe that has 10.05 percent left. The first
     * log takes it below the line, exactly one warning line follows, and the gather is never paused, replaced or ended by the wear: it
     * completes with the same axe (the earlier rule would have started a resupply and swapped the tool out at that point).
     */
    @GameTest(environment = ENV + "real_chopping_crossing_warns_once_and_the_gather_completes", maxTicks = 900)
    public void realChoppingCrossingWarnsOnceAndTheGatherCompletes(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "DurWarnChopGT");
        bot.getInventory().clearContent();
        ItemStack axe = new ItemStack(Items.DIAMOND_AXE);
        int max = axe.getMaxDamage();
        axe.setDamageValue(max - 157); // 157 of 1561 uses left: 10.06 percent; one chop takes it to 156 (9.99 percent)
        bot.getInventory().setItem(0, axe);
        bot.getInventory().setSelectedSlot(0);
        BlockPos feet = bot.blockPosition().immutable();
        for (int dx = 2; dx <= 4; dx++) {
            bot.level().setBlock(feet.east(dx), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        }
        GatherQuotaTask gather = new GatherQuotaTask(Items.OAK_LOG, 3);
        TaskManager.INSTANCE.assign(bot, gather, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_durability_warning_chop"));
        require(context, count(chat(bot)) == 0, "the fixture already had a warning line: " + chat(bot));
        AtomicInteger tick = new AtomicInteger();
        context.failIfEver(() -> {
            int now = tick.incrementAndGet();
            if (gather.state() == TaskState.COMPLETED) {
                ItemStack kept = ItemStack.EMPTY;
                for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
                    if (stack.is(Items.DIAMOND_AXE)) {
                        kept = stack;
                    }
                }
                require(context, !kept.isEmpty() && (max - kept.getDamageValue()) * 100 < 10 * max,
                        "the gather completed but the diamond axe is not the worn one below the line: " + kept);
                require(context, count(chat(bot)) == 1, "expected exactly one warning line, got " + count(chat(bot)) + ": " + chat(bot));
                require(context, lastLine(chat(bot)).contains("My diamond axe is about to break ("),
                        "unexpected warning text: " + lastLine(chat(bot)));
                finish(context, bot);
                return;
            }
            require(context, gather.state() == TaskState.RUNNING && TaskManager.INSTANCE.getActive(bot).orElse(null) == gather
                            && !TaskManager.INSTANCE.hasPaused(bot),
                    "the gather was interrupted by the wear at tick " + now + ": state=" + gather.state() + " active="
                            + TaskManager.INSTANCE.getActive(bot).map(Task::name).orElse("none") + " " + gather.describe());
            require(context, count(chat(bot)) <= 1, "more than one warning line: " + chat(bot));
        });
    }

    @GameTest(environment = ENV + "iron_pickaxe_crossing_says_nothing", maxTicks = 120)
    public void ironPickaxeCrossingSaysNothing(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "DurWarnIronGT");
        bot.getInventory().clearContent();
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        int max = pick.getMaxDamage();
        pick.setDamageValue(max - 30);
        bot.getInventory().setItem(0, pick);
        bot.getInventory().setSelectedSlot(0);
        ItemStack stone = new ItemStack(Items.STONE_PICKAXE);
        stone.setDamageValue(stone.getMaxDamage() - 5);
        bot.getInventory().setItem(1, stone);
        ItemStack iron = new ItemStack(Items.IRON_CHESTPLATE);
        iron.setDamageValue(iron.getMaxDamage() - 5);
        bot.setItemSlot(EquipmentSlot.CHEST, iron);
        AtomicInteger tick = new AtomicInteger();
        context.failIfEver(() -> {
            int now = tick.incrementAndGet();
            ItemStack held = bot.getMainHandItem();
            require(context, count(chat(bot)) == 0, "an iron, stone or iron-armor item warned at tick " + now + ": " + chat(bot));
            if (now < 24) {
                held.hurtAndBreak(1, bot, EquipmentSlot.MAINHAND);
            } else if (now == 24) {
                require(context, held.is(Items.IRON_PICKAXE) && (max - held.getDamageValue()) * 100 < 10 * max,
                        "the iron pickaxe never crossed 10 percent: " + held);
            } else if (now == 40) {
                finish(context, bot);
            }
        });
    }

    @GameTest(environment = ENV + "several_items_crossing_together_all_speak_at_once", maxTicks = 120)
    public void severalItemsCrossingTogetherAllSpeakAtOnce(GameTestHelper context) {
        AIPlayerEntity bot = spawnPlatform(context, "DurWarnManyGT");
        bot.getInventory().clearContent();
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
                EquipmentSlot.OFFHAND}) {
            bot.setItemSlot(slot, ItemStack.EMPTY);
        }
        // Everything eligible sits just above the threshold; the iron sword is no eligible item at all.
        List<Item> eligible = List.of(Items.DIAMOND_SWORD, Items.NETHERITE_PICKAXE, Items.BOW, Items.CROSSBOW, Items.FISHING_ROD,
                Items.TRIDENT, Items.MACE, Items.DIAMOND_AXE);
        for (int i = 0; i < eligible.size(); i++) {
            bot.getInventory().setItem(i, nearThreshold(eligible.get(i), true));
        }
        bot.getInventory().setItem(9, nearThreshold(Items.IRON_SWORD, true));
        bot.setItemSlot(EquipmentSlot.HEAD, nearThreshold(Items.DIAMOND_HELMET, true));
        bot.setItemSlot(EquipmentSlot.CHEST, nearThreshold(Items.ELYTRA, true));
        bot.setItemSlot(EquipmentSlot.LEGS, nearThreshold(Items.NETHERITE_LEGGINGS, true));
        bot.setItemSlot(EquipmentSlot.OFFHAND, nearThreshold(Items.SHIELD, true));
        int expected = eligible.size() + 4;
        bot.getInventory().setSelectedSlot(0);
        HoldingTask work = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, work, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_durability_warning_many"));
        AtomicInteger tick = new AtomicInteger();
        AtomicInteger crossedAt = new AtomicInteger(-1);
        context.failIfEver(() -> {
            int now = tick.incrementAndGet();
            require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == work && !TaskManager.INSTANCE.hasPaused(bot),
                    "the task was interrupted at tick " + now);
            if (now < 5) {
                require(context, count(chat(bot)) == 0, "a warning came before any item crossed: " + chat(bot));
                return;
            }
            if (now == 5) {
                for (int i = 0; i < eligible.size(); i++) {
                    crossBelow(bot.getInventory().getItem(i));
                }
                crossBelow(bot.getInventory().getItem(9));
                for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                        EquipmentSlot.OFFHAND}) {
                    crossBelow(bot.getItemBySlot(slot));
                }
                crossedAt.set(now);
                return;
            }
            if (now == crossedAt.get() + 1) {
                // Instant: one tick after they crossed together, every line is already in the chat (no spacing, no queue).
                String all = chat(bot);
                require(context, count(all) == expected,
                        "expected " + expected + " warning lines one tick after the crossing, got " + count(all) + ": " + all);
                for (String needle : new String[]{"My diamond sword", "My netherite pickaxe", "My bow", "My crossbow", "My fishing rod",
                        "My trident", "My mace", "My diamond axe", "My diamond helmet", "My elytra", "My netherite leggings",
                        "My shield"}) {
                    require(context, all.contains(needle + " is about to break ("), "no line for " + needle + ": " + all);
                }
                require(context, !all.contains("iron sword"), "the iron sword warned: " + all);
                return;
            }
            if (now > crossedAt.get() + 1 && now < 30) {
                require(context, count(chat(bot)) == expected, "a line was repeated or added: " + count(chat(bot)));
                return;
            }
            if (now >= 30) {
                finish(context, bot);
            }
        });
    }

    // ------------------------------------------------------------------------------------------------------------ helpers

    /** The item at 11 percent durability remaining. */
    private static ItemStack nearThreshold(Item item, boolean unused) {
        ItemStack stack = new ItemStack(item);
        int max = stack.getMaxDamage();
        stack.setDamageValue(max - (int) Math.ceil(max * 0.11D));
        return stack;
    }

    /** Puts a stack just below 10 percent remaining (the way a burst of wear would). */
    private static void crossBelow(ItemStack stack) {
        int max = stack.getMaxDamage();
        stack.setDamageValue(max - (int) Math.floor(max * 0.09D));
    }

    private static String chat(AIPlayerEntity bot) {
        return ChatTranscript.renderRecentChat(bot.getUUID());
    }

    private static int count(String transcript) {
        int count = 0;
        for (int from = transcript.indexOf(NEEDLE); from >= 0; from = transcript.indexOf(NEEDLE, from + NEEDLE.length())) {
            count++;
        }
        return count;
    }

    private static String lastLine(String transcript) {
        String[] lines = transcript.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].contains(NEEDLE)) {
                return lines[i];
            }
        }
        return "";
    }

    private static AIPlayerEntity spawnPlatform(GameTestHelper context, String name) {
        var world = context.getLevel();
        world.setDayTime(1000L);
        BlockPos feet = context.absolutePos(new BlockPos(3, 2, 3));
        for (int dx = -3; dx <= 4; dx++) {
            for (int dz = -3; dz <= 5; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlock(cell.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        ChatTranscript.clear(bot.getUUID());
        return bot;
    }

    private static void finish(GameTestHelper context, AIPlayerEntity bot) {
        ChatTranscript.clear(bot.getUUID());
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), bot.getGameProfile().name());
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    /** A task that just runs: the "work" a warning must never interrupt. */
    private static final class HoldingTask extends AbstractTask {
        @Override
        public String name() {
            return "holding_work";
        }

        @Override
        public String describe() {
            return "Holding work that a durability warning must not interrupt";
        }

        @Override
        public double progress() {
            return 0.5D;
        }

        @Override
        protected void onStart(AIPlayerEntity bot) {
        }

        @Override
        protected void onTick(AIPlayerEntity bot) {
        }
    }
}
