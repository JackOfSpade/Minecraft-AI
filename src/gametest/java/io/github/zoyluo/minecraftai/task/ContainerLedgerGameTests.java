package io.github.zoyluo.minecraftai.task;

import com.google.gson.JsonObject;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.brain.ToolDefinition;
import io.github.zoyluo.minecraftai.brain.ToolRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import io.github.zoyluo.minecraftai.memory.ContainerLedger;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The bot's container ledger and the storage rules behind it, on a real server: what it saw when it
 * last opened a container answers "where is X" and steers a withdraw to the right chest; deposits
 * only ever go into storage blocks whose lid can open; and a bot never learns what is inside a
 * container it has not opened (no remote peeking).
 */
public final class ContainerLedgerGameTests {
    private static final String OVERWORLD = "minecraft:overworld";

    @GameTest(maxTicks = 400)
    public void depositRemembersWhereItemsAreAndWithdrawPicksTheRightChestAmongSeveral(GameTestHelper context) {
        Fixture fixture = fixture(context, "LedgerGT", 0);
        AIPlayerEntity bot = fixture.bot;
        // A is the nearest chest and holds 3 oak logs the bot has never seen; B and C get filled by the bot.
        BlockPos a = fixture.feet.offset(2, 0, 0);
        BlockPos b = fixture.feet.offset(0, 0, 3);
        BlockPos c = fixture.feet.offset(-3, 0, 0);
        chest(fixture.world, a, new ItemStack(Items.OAK_LOG, 3));
        chest(fixture.world, b);
        chest(fixture.world, c);
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 10));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL, 8));

        Deque<Task> steps = new ArrayDeque<>();
        steps.add(ContainerTask.deposit(b, Items.OAK_LOG, 10, false));
        steps.add(ContainerTask.deposit(c, Items.COAL, 8, false));
        boolean[] verifiedLedger = {false};
        runSteps(context, bot, steps, () -> {
            if (!steps.isEmpty()) {
                return false; // still depositing
            }
            if (!verifiedLedger[0]) {
                verifiedLedger[0] = true;
                ContainerLedger ledger = BotMemoryStore.INSTANCE.of(bot.getUUID()).containers();
                var logs = ledger.find(OVERWORLD, "minecraft:oak_log", bot.blockPosition(), 1, 3);
                require(context, logs.size() == 1 && logs.get(0).pos().equals(b) && logs.get(0).count("minecraft:oak_log") == 10,
                        "ledger must point at chest B only (A was never opened): " + logs);
                require(context, ledger.get(OVERWORLD, a).isEmpty(), "the unopened chest A must not be in the ledger");
                require(context, ledger.find(OVERWORLD, "minecraft:coal", bot.blockPosition(), 1, 3).get(0).pos().equals(c),
                        "ledger must point at chest C for coal");
                // The read-only LLM tool answers from the same ledger.
                ToolDefinition tool = new ToolRegistry().get("find_item_in_storage").orElse(null);
                require(context, tool != null, "find_item_in_storage is not registered");
                JsonObject args = new JsonObject();
                args.addProperty("item", "minecraft:oak_log");
                ToolDefinition.ToolResult answer = tool.handler().invoke(bot, args);
                require(context, answer.ok() && answer.message().contains("\"count\":10")
                                && answer.message().contains("\"z\":" + b.getZ()),
                        "find_item_in_storage must report chest B with 10 logs: " + answer.message());
                JsonObject unknown = new JsonObject();
                unknown.addProperty("item", "minecraft:diamond");
                require(context, !tool.handler().invoke(bot, unknown).ok(),
                        "an item the bot never saw in a container must not be reported");
                steps.add(ContainerTask.withdraw(null, Items.OAK_LOG, 10));
                return false;
            }
            require(context, InventoryAction.countItem(bot, Items.OAK_LOG) == 10,
                    "withdraw did not bring back exactly the 10 deposited logs");
            require(context, count(fixture.world, b, Items.OAK_LOG) == 0, "chest B should have been emptied of logs");
            require(context, count(fixture.world, a, Items.OAK_LOG) == 3,
                    "the nearer, never-opened chest A must be left alone when the ledger knows where the logs are");
            require(context, count(fixture.world, c, Items.COAL) == 8, "chest C must be untouched");
            return true;
        });
    }

    @GameTest(maxTicks = 200)
    public void depositNeverGoesIntoAFurnaceOrHopperNextToAChest(GameTestHelper context) {
        Fixture fixture = fixture(context, "TargetGT", 1);
        AIPlayerEntity bot = fixture.bot;
        BlockPos furnace = fixture.feet.offset(1, 0, 2);
        BlockPos hopper = fixture.feet.offset(-1, 0, 2);
        BlockPos chest = fixture.feet.offset(0, 0, 3);
        fixture.world.setBlock(furnace, Blocks.FURNACE.defaultBlockState(), Block.UPDATE_ALL);
        fixture.world.setBlock(hopper, Blocks.HOPPER.defaultBlockState(), Block.UPDATE_ALL);
        chest(fixture.world, chest);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 5));

        require(context, ContainerTask.nearestContainer(bot, 8).filter(pos -> pos.equals(chest)).isPresent(),
                "find_container must skip the nearer furnace and hopper: " + ContainerTask.nearestContainer(bot, 8));
        Deque<Task> steps = new ArrayDeque<>();
        steps.add(ContainerTask.deposit(null, Items.COBBLESTONE, 5, false));
        runSteps(context, bot, steps, () -> {
            require(context, count(fixture.world, chest, Items.COBBLESTONE) == 5, "the chest did not receive the items");
            require(context, container(fixture.world, furnace).isEmpty() && container(fixture.world, hopper).isEmpty(),
                    "an automatic deposit went into the furnace or hopper");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 0, "items were not removed from the bot");
            return true;
        });
    }

    @GameTest(maxTicks = 200)
    public void chestWithABlockedLidIsSkippedAndAnExplicitTargetIsRefused(GameTestHelper context) {
        Fixture fixture = fixture(context, "LidGT", 2);
        AIPlayerEntity bot = fixture.bot;
        BlockPos blocked = fixture.feet.offset(2, 0, 0);
        BlockPos open = fixture.feet.offset(0, 0, 4);
        chest(fixture.world, blocked);
        fixture.world.setBlock(blocked.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        chest(fixture.world, open);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 5));

        require(context, ContainerTask.nearestContainer(bot, 8).filter(pos -> pos.equals(open)).isPresent(),
                "find_container must skip the chest under a solid block: " + ContainerTask.nearestContainer(bot, 8));
        Deque<Task> steps = new ArrayDeque<>();
        ContainerTask explicit = ContainerTask.deposit(blocked, Items.COBBLESTONE, 5, false);
        steps.add(explicit);
        boolean[] explicitChecked = {false};
        runStepsAllowingFailure(context, bot, steps, task -> {
            if (task == explicit) {
                require(context, task.state() == TaskState.FAILED, "an explicit deposit into a blocked chest must fail");
                require(context, count(fixture.world, blocked, Items.COBBLESTONE) == 0
                                && InventoryAction.countItem(bot, Items.COBBLESTONE) == 5,
                        "a blocked chest must not accept items");
                explicitChecked[0] = true;
                steps.add(ContainerTask.deposit(null, Items.COBBLESTONE, 5, false));
                return false;
            }
            require(context, task.state() == TaskState.COMPLETED, "automatic deposit failed: " + task.failureReason());
            require(context, count(fixture.world, open, Items.COBBLESTONE) == 5, "the open chest did not receive the items");
            require(context, count(fixture.world, blocked, Items.COBBLESTONE) == 0, "the blocked chest received items");
            return true;
        });
    }

    @GameTest(maxTicks = 500)
    public void containerContentsAreNotReadBeforeTheBotOpensIt(GameTestHelper context) {
        Fixture fixture = fixture(context, "PeekGT", 3);
        AIPlayerEntity bot = fixture.bot;
        BotMemoryStore.INSTANCE.of(bot.getUUID()).markPlace("base", fixture.world, fixture.feet);
        // The near chest is empty; the far chest (visible, but out of reach) holds the bread. A bot that
        // peeked into closed containers would head straight for the far one and never open the near one.
        BlockPos near = fixture.feet.offset(-2, 0, 0);
        BlockPos far = fixture.feet.offset(7, 0, 0);
        chest(fixture.world, near);
        chest(fixture.world, far, new ItemStack(Items.BREAD, 12));
        ContainerLedger ledger = BotMemoryStore.INSTANCE.of(bot.getUUID()).containers();

        ResupplyTask task = ResupplyTask.food();
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_no_peek"));
        long[] farSeen = {-1L};
        context.failIfEver(() -> {
            Optional<ContainerLedger.Entry> farEntry = ledger.get(OVERWORLD, far);
            if (farEntry.isPresent() && farSeen[0] < 0) {
                farSeen[0] = farEntry.get().lastVerified();
                require(context, bot.getEyePosition().distanceToSqr(far.getCenter()) <= 25.0D,
                        "the far chest entered the ledger while the bot was out of reach");
            }
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.COMPLETED, "food resupply failed: " + task.failureReason());
            ContainerLedger.Entry nearEntry = ledger.get(OVERWORLD, near).orElse(null);
            ContainerLedger.Entry farFinal = ledger.get(OVERWORLD, far).orElse(null);
            require(context, nearEntry != null && nearEntry.count("minecraft:bread") == 0,
                    "the near chest was never opened: the bot skipped it because it peeked into the far one");
            require(context, farFinal != null && nearEntry.lastVerified() < farFinal.lastVerified(),
                    "the near chest must be opened before the far one (rank by distance, not by hidden contents)");
            require(context, InventoryAction.countItem(bot, Items.BREAD) > 0, "no bread was withdrawn");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 700)
    public void nearlyFullIdleBotStowsOnlyTheJunkSurplusIntoItsBaseChest(GameTestHelper context) {
        Fixture fixture = fixture(context, "JunkGT", 4);
        AIPlayerEntity bot = fixture.bot;
        BotMemoryStore.INSTANCE.of(bot.getUUID()).markPlace("base", fixture.world, fixture.feet);
        BlockPos chest = fixture.feet.offset(3, 0, 0);
        chest(fixture.world, chest);
        var main = bot.getInventory().getNonEquipmentItems();
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        pickaxe.setDamageValue(10);
        main.set(0, pickaxe);
        main.set(1, new ItemStack(Items.BREAD, 5));
        main.set(2, new ItemStack(Items.COBBLESTONE, 64));
        main.set(3, new ItemStack(Items.COBBLESTONE, 64));
        main.set(4, new ItemStack(Items.COBBLESTONE, 64));
        main.set(5, new ItemStack(Items.DIRT, 64));
        for (int slot = 6; slot < main.size(); slot++) {
            main.set(slot, new ItemStack(slot % 2 == 0 ? Items.STICK : Items.COAL, 64));
        }
        bot.getInventory().setChanged();
        require(context, io.github.zoyluo.minecraftai.action.InventoryPolicy.nearlyFull(bot), "fixture is not nearly full");
        int keepStone = io.github.zoyluo.minecraftai.MinecraftAiConfig.get().storage().junkKeepStone();
        int keepOther = io.github.zoyluo.minecraftai.MinecraftAiConfig.get().storage().junkKeepOther();

        context.failIfEver(() -> {
            int cobbleInChest = count(fixture.world, chest, Items.COBBLESTONE);
            if (cobbleInChest == 0 || TaskManager.INSTANCE.getActive(bot).isPresent()) {
                return; // not started, or still walking/depositing
            }
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == keepStone
                            && cobbleInChest == 192 - keepStone,
                    "cobblestone surplus was not stowed down to the throwaway budget: bot="
                            + InventoryAction.countItem(bot, Items.COBBLESTONE) + " chest=" + cobbleInChest);
            require(context, InventoryAction.countItem(bot, Items.DIRT) == keepOther
                            && count(fixture.world, chest, Items.DIRT) == 64 - keepOther,
                    "dirt surplus was not stowed down to the throwaway budget");
            require(context, InventoryAction.countItem(bot, Items.IRON_PICKAXE) == 1
                            && InventoryAction.countItem(bot, Items.BREAD) == 5
                            && InventoryAction.countItem(bot, Items.STICK) == 64 * 15
                            && InventoryAction.countItem(bot, Items.COAL) == 64 * 15
                            && count(fixture.world, chest, Items.STICK) == 0
                            && count(fixture.world, chest, Items.COAL) == 0
                            && count(fixture.world, chest, Items.BREAD) == 0,
                    "a keep item (tool, food, sticks, coal) was stowed");
            require(context, BotMemoryStore.INSTANCE.of(bot.getUUID()).containers()
                            .get(OVERWORLD, chest).map(entry -> entry.count("minecraft:cobblestone") == 192 - keepStone).orElse(false),
                    "the ledger does not reflect the stowed cobblestone");
            cleanup(context, fixture);
        });
    }

    @GameTest(maxTicks = 300)
    public void depositWithNoContainerPlacesTheCarriedChestAndUsesIt(GameTestHelper context) {
        Fixture fixture = fixture(context, "PlaceGT", 5);
        AIPlayerEntity bot = fixture.bot;
        InventoryAction.giveItem(bot, new ItemStack(Items.CHEST, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 5));
        require(context, ContainerTask.nearestContainer(bot, 8).isEmpty(), "fixture already has a container");

        Deque<Task> steps = new ArrayDeque<>();
        steps.add(ContainerTask.deposit(null, Items.COBBLESTONE, 5, false));
        runSteps(context, bot, steps, () -> {
            BlockPos placed = ContainerTask.nearestContainer(bot, 8).orElse(null);
            require(context, placed != null, "no chest was placed");
            require(context, count(fixture.world, placed, Items.COBBLESTONE) == 5, "the placed chest did not receive the items");
            require(context, InventoryAction.countItem(bot, Items.CHEST) == 0
                            && InventoryAction.countItem(bot, Items.COBBLESTONE) == 0,
                    "the chest item or the cobblestone is still in the inventory");
            return true;
        });
    }

    // ---- helpers ----

    /** Ticks each queued task in turn; {@code afterTask} returns true when everything is verified. */
    private static void runSteps(GameTestHelper context, AIPlayerEntity bot, Deque<Task> steps,
                                 java.util.function.BooleanSupplier afterStep) {
        Task[] current = new Task[1];
        boolean[] done = {false};
        context.failIfEver(() -> {
            if (done[0]) {
                return;
            }
            if (current[0] == null) {
                current[0] = steps.poll();
                if (current[0] == null) {
                    done[0] = true;
                    despawn(bot);
                    context.succeed();
                    return;
                }
                current[0].start(bot);
            }
            if (current[0].state() == TaskState.RUNNING) {
                current[0].tick(bot);
            }
            if (current[0].state() == TaskState.RUNNING) {
                return;
            }
            require(context, current[0].state() == TaskState.COMPLETED,
                    current[0].name() + " failed: " + current[0].failureReason());
            current[0] = null;
            boolean finished = afterStep.getAsBoolean();
            if (finished) {
                done[0] = true;
                despawn(bot);
                context.succeed();
            }
        });
    }

    private static void runStepsAllowingFailure(GameTestHelper context, AIPlayerEntity bot, Deque<Task> steps,
                                                java.util.function.Predicate<Task> afterStep) {
        Task[] current = new Task[1];
        boolean[] done = {false};
        context.failIfEver(() -> {
            if (done[0]) {
                return;
            }
            if (current[0] == null) {
                current[0] = steps.poll();
                if (current[0] == null) {
                    return;
                }
                current[0].start(bot);
            }
            if (current[0].state() == TaskState.RUNNING) {
                current[0].tick(bot);
            }
            if (current[0].state() == TaskState.RUNNING) {
                return;
            }
            Task finished = current[0];
            current[0] = null;
            if (afterStep.test(finished)) {
                done[0] = true;
                despawn(bot);
                context.succeed();
            }
        });
    }

    private static void chest(ServerLevel world, BlockPos pos, ItemStack... contents) {
        world.setBlock(pos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        Container container = (Container) world.getBlockEntity(pos);
        for (int slot = 0; slot < contents.length; slot++) {
            container.setItem(slot, contents[slot]);
        }
    }

    private static Container container(ServerLevel world, BlockPos pos) {
        return (Container) world.getBlockEntity(pos);
    }

    private static int count(ServerLevel world, BlockPos pos, Item item) {
        Container container = container(world, pos);
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static Fixture fixture(GameTestHelper context, String name, int slot) {
        ServerLevel world = context.getLevel();
        world.setDayTime(1000L);
        // Each test gets its own arena 200+ blocks from its structure and at least 64 blocks from the other
        // tests' arenas: this batch runs in parallel next to each other, and a 21x17 arena would
        // otherwise be wiped by a neighbour's fixture clearing.
        BlockPos feet = context.absolutePos(new BlockPos(6, 4, 6)).offset(slot * 64, 0, 200 + slot * 48);
        for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlock(cell.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        BotMemoryStore.INSTANCE.of(bot.getUUID()).containers().inDimension(OVERWORLD)
                .forEach(entry -> BotMemoryStore.INSTANCE.of(bot.getUUID()).containers().forget(OVERWORLD, entry.pos()));
        return new Fixture(world, bot, feet, name);
    }

    private static void despawn(AIPlayerEntity bot) {
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), bot.getName().getString());
    }

    private static void cleanup(GameTestHelper context, Fixture fixture) {
        AIPlayerManager.INSTANCE.despawn(fixture.world.getServer(), fixture.name);
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    private record Fixture(ServerLevel world, AIPlayerEntity bot, BlockPos feet, String name) {
    }
}
