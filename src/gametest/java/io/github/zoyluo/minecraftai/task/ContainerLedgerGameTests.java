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
    /** Corner of the region reserved for this class's arenas (see {@link #fixture}). */
    private static final int ARENA_ORIGIN_X = 1_000_000;
    private static final int ARENA_ORIGIN_Z = 1_000_000;

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

    @GameTest(maxTicks = 400)
    public void aChestTheLedgerRemembersAsFullIsTriedAgainAfterThePlayerEmptiedIt(GameTestHelper context) {
        Fixture fixture = fixture(context, "FullGT", 6);
        AIPlayerEntity bot = fixture.bot;
        BlockPos a = fixture.feet.offset(2, 0, 0); // nearest, will be remembered as full
        BlockPos b = fixture.feet.offset(0, 0, 3); // farther, has room
        chest(fixture.world, a, fullStacks(Items.DIRT));
        chest(fixture.world, b);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 5));
        ContainerLedger ledger = BotMemoryStore.INSTANCE.of(bot.getUUID()).containers();

        Deque<Task> steps = new ArrayDeque<>();
        steps.add(ContainerTask.inspect(a));
        steps.add(ContainerTask.inspect(b));
        steps.add(ContainerTask.deposit(null, Items.COBBLESTONE, 5, false));
        int[] stage = {0};
        runSteps(context, bot, steps, () -> {
            stage[0]++;
            if (stage[0] == 1) {
                require(context, ledger.get(OVERWORLD, a).map(ContainerLedger.Entry::full).orElse(false),
                        "the ledger should remember the packed chest A as full");
                return false;
            }
            if (stage[0] == 2) {
                return false;
            }
            if (stage[0] == 3) {
                require(context, count(fixture.world, b, Items.COBBLESTONE) == 5
                                && count(fixture.world, a, Items.COBBLESTONE) == 0,
                        "a known-full chest must be demoted: the roomy chest B takes the items first");
                // The player empties chest A by hand and breaks chest B; the bot cannot know.
                container(fixture.world, a).clearContent();
                fixture.world.setBlock(b, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 4));
                steps.add(ContainerTask.deposit(null, Items.COBBLESTONE, 4, false));
                return false;
            }
            require(context, count(fixture.world, a, Items.COBBLESTONE) == 4,
                    "the chest remembered as full was ignored although the player emptied it");
            require(context, ledger.get(OVERWORLD, a).map(entry -> !entry.full() && entry.count("minecraft:cobblestone") == 4)
                            .orElse(false),
                    "opening the chest must refresh its ledger entry");
            require(context, ledger.get(OVERWORLD, b).isEmpty(), "the dead entry of the broken chest B must be forgotten");
            return true;
        });
    }

    @GameTest(maxTicks = 400)
    public void staleLedgerEntriesMakeWithdrawFallBackToTheNextCandidateAndForgetTheDeadOne(GameTestHelper context) {
        Fixture fixture = fixture(context, "StaleGT", 7);
        AIPlayerEntity bot = fixture.bot;
        BlockPos broken = fixture.feet.offset(-2, 0, 0); // nearest: will be broken
        BlockPos emptied = fixture.feet.offset(2, 0, 1); // next: will be emptied by hand
        BlockPos good = fixture.feet.offset(0, 0, 4);    // farthest: still holds the coal
        chest(fixture.world, broken, new ItemStack(Items.COAL, 8));
        chest(fixture.world, emptied, new ItemStack(Items.COAL, 8));
        chest(fixture.world, good, new ItemStack(Items.COAL, 8));
        ContainerLedger ledger = BotMemoryStore.INSTANCE.of(bot.getUUID()).containers();

        Deque<Task> steps = new ArrayDeque<>();
        steps.add(ContainerTask.inspect(broken));
        steps.add(ContainerTask.inspect(emptied));
        steps.add(ContainerTask.inspect(good));
        int[] stage = {0};
        runSteps(context, bot, steps, () -> {
            stage[0]++;
            if (stage[0] < 3) {
                return false;
            }
            if (stage[0] == 3) {
                require(context, ledger.find(OVERWORLD, "minecraft:coal", bot.blockPosition(), 8, 4).size() == 3,
                        "all three chests should be remembered as holding coal");
                fixture.world.setBlock(broken, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                container(fixture.world, emptied).clearContent();
                steps.add(ContainerTask.withdraw(null, Items.COAL, 8));
                return false;
            }
            require(context, InventoryAction.countItem(bot, Items.COAL) == 8
                            && count(fixture.world, good, Items.COAL) == 0,
                    "withdraw must fall back past the broken and the emptied chest to the one that still has coal");
            require(context, ledger.get(OVERWORLD, broken).isEmpty(), "the broken chest's dead entry must be forgotten");
            require(context, ledger.get(OVERWORLD, emptied).map(entry -> entry.count("minecraft:coal") == 0).orElse(false),
                    "the emptied chest's entry must be refreshed to what the bot saw when it opened it");
            return true;
        });
    }

    @GameTest(maxTicks = 300)
    public void aChestNextToAnObservedSpawnerIsSkipped(GameTestHelper context) {
        Fixture fixture = fixture(context, "SpawnerGT", 8);
        AIPlayerEntity bot = fixture.bot;
        BlockPos loot = fixture.feet.offset(2, 0, 1);
        BlockPos spawner = fixture.feet.offset(2, 0, 4);
        BlockPos plain = fixture.feet.offset(-4, 0, -4); // more than the spawner radius away from the spawner
        fixture.world.setBlock(spawner, Blocks.SPAWNER.defaultBlockState(), Block.UPDATE_ALL);
        chest(fixture.world, loot);
        chest(fixture.world, plain);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 5));
        require(context, io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveBlock(bot, spawner),
                "fixture: the spawner must be in the bot's sight");

        require(context, ContainerTask.nearestContainer(bot, 8).filter(pos -> pos.equals(plain)).isPresent(),
                "find_container must skip the nearer chest beside the observed spawner: "
                        + ContainerTask.nearestContainer(bot, 8));
        Deque<Task> steps = new ArrayDeque<>();
        steps.add(ContainerTask.deposit(null, Items.COBBLESTONE, 5, false));
        runSteps(context, bot, steps, () -> {
            require(context, count(fixture.world, plain, Items.COBBLESTONE) == 5, "the plain chest did not receive the items");
            require(context, count(fixture.world, loot, Items.COBBLESTONE) == 0, "an automatic deposit went into the spawner chest");
            return true;
        });
    }

    @GameTest(maxTicks = 300)
    public void junkStowNeverPushesItemsIntoANonStorageBlockAtARememberedPosition(GameTestHelper context) {
        Fixture fixture = fixture(context, "JunkTargetGT", 9);
        AIPlayerEntity bot = fixture.bot;
        BlockPos remembered = fixture.feet.offset(3, 0, 0);
        chest(fixture.world, remembered);
        var main = bot.getInventory().getNonEquipmentItems();
        main.set(0, new ItemStack(Items.COBBLESTONE, 64));
        main.set(1, new ItemStack(Items.COBBLESTONE, 64));
        main.set(2, new ItemStack(Items.COBBLESTONE, 64));
        bot.getInventory().setChanged();
        ContainerLedger ledger = BotMemoryStore.INSTANCE.of(bot.getUUID()).containers();

        Deque<Task> steps = new ArrayDeque<>();
        steps.add(ContainerTask.inspect(remembered));
        ContainerTask[] junk = new ContainerTask[1];
        runStepsAllowingFailure(context, bot, steps, task -> {
            if (junk[0] == null) {
                require(context, task.state() == TaskState.COMPLETED && ledger.get(OVERWORLD, remembered).isPresent(),
                        "fixture: the chest should have been opened and remembered");
                // The remembered chest turns into a furnace (which would happily accept cobblestone).
                fixture.world.setBlock(remembered, Blocks.FURNACE.defaultBlockState(), Block.UPDATE_ALL);
                junk[0] = ContainerTask.depositJunk(remembered);
                steps.add(junk[0]);
                return false;
            }
            require(context, task == junk[0] && task.state() == TaskState.FAILED,
                    "a junk stow aimed at a non-storage block must not succeed: " + task.state());
            require(context, container(fixture.world, remembered).isEmpty(),
                    "cobblestone was pushed into the furnace at the remembered position");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 192, "the bot lost cobblestone");
            require(context, ledger.get(OVERWORLD, remembered).isEmpty(),
                    "the stale entry of the position that is no longer storage must be forgotten");
            return true;
        });
    }

    // ---- helpers ----

    private static ItemStack[] fullStacks(Item item) {
        ItemStack[] stacks = new ItemStack[27];
        for (int slot = 0; slot < stacks.length; slot++) {
            stacks[slot] = new ItemStack(item, 64);
        }
        return stacks;
    }

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
        // Each test builds its 21x17 arena in a region reserved for this class: an ABSOLUTE position a
        // million blocks out (no other test or structure is ever placed there, whatever the structure
        // grid looks like) with a per-test slot spaced 64/48 blocks apart, so parallel tests of this
        // batch can neither wipe each other's arena nor be wiped by another class's fixture.
        BlockPos feet = new BlockPos(ARENA_ORIGIN_X + slot * 64, context.absolutePos(new BlockPos(6, 4, 6)).getY(),
                ARENA_ORIGIN_Z + slot * 48);
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
