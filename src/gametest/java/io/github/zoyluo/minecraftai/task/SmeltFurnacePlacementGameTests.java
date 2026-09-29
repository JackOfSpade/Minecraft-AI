package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.craft.RecipeRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reproduces the unsupported first-air furnace placement seen after DigDown returns to the surface. */
public final class SmeltFurnacePlacementGameTests {
    @GameTest(environment = "minecraftai-gametest:smelt_furnace_placement_game_tests_unsupported_air_retry_cannot_replace_active_clearing_pickaxe", maxTicks = 800)
    public void unsupportedAirRetryCannotReplaceActiveClearingPickaxe(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 5, -48));
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.setBlock(start.offset(dx, -1, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        // seed 3000's exact failure shape: NORTH is unsupported, floating air with no
        // clickable support, so placing the furnace will legitimately fail here first; EAST is
        // iron ore that needs the stone pickaxe to clear it. After the old state machine started
        // digging EAST, the next tick would retry NORTH first and swap the pickaxe back for the
        // furnace. SOUTH/WEST use indestructible blocks to lock in the only clearable cell, so
        // the direction-iteration order can't weaken the test case.
        world.setBlock(start.north(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.north().below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.north().below(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.east(), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.south(), Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(start.west(), Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);

        String name = "SmeltUnsupportedAirGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());
        // Fill main, and put the furnace in the offhand. The first findItem lookup will promote
        // it losslessly; once clearing begins, put it back in the offhand to precisely reproduce
        // the boundary case where the active pick gets swapped out by findItem on the next tick.
        for (var filler : new net.minecraft.world.item.Item[]{
                Items.DIRT, Items.SAND, Items.GRAVEL, Items.COBBLESTONE,
                Items.ANDESITE, Items.DIORITE, Items.GRANITE,
                Items.OAK_LOG, Items.BIRCH_LOG}) {
            InventoryAction.giveItem(bot, new ItemStack(filler, 64));
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.RAW_IRON));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        for (int slot = 0; slot < bot.getInventory().getNonEquipmentItems().size(); slot++) {
            if (bot.getInventory().getNonEquipmentItems().get(slot).isEmpty()) {
                bot.getInventory().getNonEquipmentItems().set(slot, new ItemStack(Items.NETHERRACK));
            }
        }
        bot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.FURNACE));
        bot.getInventory().setChanged();
        require(context, bot.getInventory().getNonEquipmentItems().stream().noneMatch(stack -> stack.is(Items.FURNACE))
                        && bot.getOffhandItem().is(Items.FURNACE)
                        && InventoryAction.findItem(bot, Items.STONE_PICKAXE).orElse(-1) > 8
                        && bot.getInventory().getNonEquipmentItems().stream().noneMatch(ItemStack::isEmpty),
                "fixture did not start full-main with offhand furnace and carried pickaxe");

        SmeltTask task = new SmeltTask(Items.RAW_IRON, Items.IRON_INGOT, 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_smelt_enclosed_furnace"));
        boolean[] furnaceRehomedDuringClear = {false};
        context.failIfEver(() -> {
            if (world.getBlockState(start.east()).is(Blocks.IRON_ORE)
                    && !bot.getActionPack().isMiningIdle()) {
                if (!furnaceRehomedDuringClear[0]) {
                    int furnaceMainSlot = -1;
                    for (int slot = 0; slot < bot.getInventory().getNonEquipmentItems().size(); slot++) {
                        if (bot.getInventory().getNonEquipmentItems().get(slot).is(Items.FURNACE)) {
                            furnaceMainSlot = slot;
                            break;
                        }
                    }
                    require(context, furnaceMainSlot >= 0,
                            "portable furnace was unavailable when active clearing began");
                    ItemStack furnace = bot.getInventory().getNonEquipmentItems().get(furnaceMainSlot);
                    ItemStack displaced = bot.getOffhandItem();
                    bot.getInventory().getNonEquipmentItems().set(furnaceMainSlot, displaced);
                    bot.setItemSlot(EquipmentSlot.OFFHAND, furnace);
                    bot.getInventory().setChanged();
                    furnaceRehomedDuringClear[0] = true;
                }
                require(context, bot.getMainHandItem().is(Items.STONE_PICKAXE),
                        "unsupported-air retry replaced the active clearing pick with "
                                + bot.getMainHandItem().getItem());
                require(context, InventoryAction.countItem(bot, Items.FURNACE) == 1,
                        "active-clear offhand handoff lost or duplicated the furnace");
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("enclosed-furnace smelt ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, world.getBlockState(start.north()).isAir(),
                    "unsupported NORTH air shaft was unexpectedly filled");
            require(context, world.getBlockState(start.east()).is(Blocks.FURNACE),
                    "smelt did not clear the EAST iron ore and place the local furnace");
            require(context, furnaceRehomedDuringClear[0],
                    "fixture never reached the active-clear offhand furnace boundary");
            int pickaxeDamage = java.util.stream.Stream.concat(
                            bot.getInventory().getNonEquipmentItems().stream(), java.util.stream.Stream.of(bot.getItemBySlot(EquipmentSlot.OFFHAND)))
                    .filter(stack -> stack.is(Items.STONE_PICKAXE))
                    .mapToInt(ItemStack::getDamageValue)
                    .sum();
            require(context, pickaxeDamage > 0,
                    "furnace cell was not physically cleared with the carried stone pickaxe");
            require(context, InventoryAction.countItem(bot, Items.IRON_INGOT) == 1,
                    "enclosed furnace did not physically produce one iron ingot");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 64,
                    "full-main offhand exchange lost the displaced selected stack");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:smelt_furnace_placement_game_tests_skips_unsupported_stair_mouth_and_cooks_on_supported_side", maxTicks = 800)
    public void skipsUnsupportedStairMouthAndCooksOnSupportedSide(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 5, -48));

        // Keep the bot's own floor and the EAST furnace floor. NORTH is a two-block-deep air shaft,
        // matching the returned DigDown stair mouth that used to be selected and failed first.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        world.setBlock(start.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos supportedFurnace = start.east();
        world.setBlock(supportedFurnace.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

        String name = "SmeltFurnacePlacementGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());
        InventoryAction.giveItem(bot, new ItemStack(Items.FURNACE));
        InventoryAction.giveItem(bot, new ItemStack(Items.MUTTON));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL));

        SmeltTask task = new SmeltTask(1, true);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_smelt_furnace_placement"));
        context.failIfEver(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("stair-mouth smelt ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, world.getBlockState(start.north()).isAir(),
                    "unsupported NORTH stair mouth was unexpectedly filled");
            require(context, world.getBlockState(supportedFurnace).is(Blocks.FURNACE),
                    "furnace was not placed on the supported EAST side");
            require(context, InventoryAction.countItem(bot, Items.COOKED_MUTTON) == 1,
                    "smelt completed without one physically cooked mutton");
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:smelt_furnace_placement_game_tests_crafts_local_furnace_instead_of_chasing_far_remembered_surface_furnace", maxTicks = 800)
    public void craftsLocalFurnaceInsteadOfChasingFarRememberedSurfaceFurnace(GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 4, 8));
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.setBlock(start.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(start.offset(dx, 0, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(start.offset(dx, 1, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(start.offset(dx, 2, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        BlockPos farSurfaceFurnace = start.offset(0, 40, 0);
        world.setBlock(farSurfaceFurnace, Blocks.FURNACE.defaultBlockState(), Block.UPDATE_ALL);

        String name = "SmeltLocalFurnaceGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        io.github.zoyluo.minecraftai.memory.BotMemoryStore.INSTANCE.of(bot.getUUID())
                .markPlace("furnace", world, farSurfaceFurnace);
        // Exact seed-3000 regression: the bot first reached the remote furnace with five
        // cobblestone, mined four more while trying to get there, then could plan a furnace but
        // could not insert its output.  Consuming eight from a stack of nine leaves the source
        // slot occupied, and every other main slot was full.  The local recovery must physically
        // drop a disposable whole stack, retry the craft, and never chase the remembered furnace.
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 9));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.RAW_IRON));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 8));
        for (int slot = 0; slot < bot.getInventory().getNonEquipmentItems().size(); slot++) {
            if (bot.getInventory().getNonEquipmentItems().get(slot).isEmpty()) {
                bot.getInventory().getNonEquipmentItems().set(slot, new ItemStack(Items.NETHERRACK, 64));
            }
        }
        bot.getInventory().setChanged();
        require(context, bot.getInventory().getNonEquipmentItems().stream().noneMatch(ItemStack::isEmpty),
                "fixture did not start with a full main inventory");

        SmeltTask task = new SmeltTask(Items.RAW_IRON, Items.IRON_INGOT, 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_smelt_local_furnace"));
        AtomicBoolean observedPhysicalDisposal = new AtomicBoolean();
        context.failIfEver(() -> {
            if (!world.getEntitiesOfClass(ItemEntity.class, new AABB(start).inflate(8.0D),
                    entity -> entity.getItem().is(Items.DIRT)
                            && entity.getItem().getCount() == 8).isEmpty()) {
                observedPhysicalDisposal.set(true);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("local-furnace smelt ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            boolean localFurnace = BlockPos.betweenClosedStream(start.offset(-2, -1, -2), start.offset(2, 2, 2))
                    .anyMatch(pos -> world.getBlockState(pos).is(Blocks.FURNACE));
            require(context, localFurnace, "a local furnace was not crafted and placed");
            require(context, world.getBlockState(farSurfaceFurnace).is(Blocks.FURNACE),
                    "far remembered furnace was unexpectedly removed");
            require(context, bot.blockPosition().distSqr(start) <= 4.0D,
                    "bot chased the far furnace instead of smelting locally: " + bot.blockPosition());
            require(context, InventoryAction.countItem(bot, Items.IRON_INGOT) == 1,
                    "local furnace did not physically produce one iron ingot");
            require(context, observedPhysicalDisposal.get(),
                    "capacity recovery did not create an ordinary dirt ItemEntity");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 1,
                    "local furnace craft did not consume exactly eight of nine cobblestone");
            io.github.zoyluo.minecraftai.memory.BotMemoryStore.INSTANCE.remove(bot.getUUID());
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    @GameTest(environment = "minecraftai-gametest:smelt_furnace_placement_game_tests_crafts_local_furnace_when_remembered_surface_furnace_is_beyond_lookup_radius", maxTicks = 800)
    public void craftsLocalFurnaceWhenRememberedSurfaceFurnaceIsBeyondLookupRadius(
            GameTestHelper context) {
        var world = context.getLevel();
        BlockPos start = context.absolutePos(new BlockPos(8, 4, 8));
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.setBlock(start.offset(dx, -1, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlock(start.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        // SmeltTask intentionally ignores remembered furnaces beyond 96 blocks. This reproduces
        // seed3000's surface furnace at distance 100.7 while keeping the exact block loaded.
        BlockPos farSurfaceFurnace = start.above(97);
        world.setBlock(farSurfaceFurnace,
                Blocks.FURNACE.defaultBlockState(), Block.UPDATE_ALL);

        String name = "SmeltBeyondMemoryGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(start),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());
        io.github.zoyluo.minecraftai.memory.BotMemoryStore.INSTANCE.of(bot.getUUID())
                .markPlace("furnace", world, farSurfaceFurnace);

        // Preserve non-zero baselines so the test catches absolute-vs-incremental accounting bugs.
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 12));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.RAW_IRON, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_INGOT, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL));
        require(context, surfaceCraftMaterialCount(bot) == 0,
                "fixture unexpectedly started with a surface craft material");

        SmeltTask task = new SmeltTask(Items.RAW_IRON, Items.IRON_INGOT, 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_smelt_beyond_memory_furnace"));
        context.failIfEver(() -> {
            require(context, surfaceCraftMaterialCount(bot) == 0,
                    "local furnace repair acquired a log/plank underground");
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("beyond-memory smelt ended as " + task.state()
                        + ":" + task.failureReason()));
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            boolean localFurnace = BlockPos.betweenClosedStream(start.offset(-2, -1, -2), start.offset(2, 2, 2))
                    .anyMatch(pos -> world.getBlockState(pos).is(Blocks.FURNACE));
            require(context, localFurnace, "missing locally crafted furnace beyond memory radius");
            require(context, world.getBlockState(farSurfaceFurnace).is(Blocks.FURNACE),
                    "far remembered furnace was changed or removed");
            require(context, bot.blockPosition().distSqr(start) <= 4.0D,
                    "bot chased the out-of-radius furnace: " + bot.blockPosition());
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 4,
                    "local furnace did not consume exactly eight cobblestone");
            require(context, InventoryAction.countItem(bot, Items.RAW_IRON) == 1,
                    "incremental smelt consumed the wrong raw-iron baseline");
            require(context, InventoryAction.countItem(bot, Items.IRON_INGOT) == 3,
                    "incremental smelt did not preserve the two-ingot baseline");
            require(context, InventoryAction.countItem(bot, Items.CRAFTING_TABLE) == 1,
                    "local furnace craft consumed the carried crafting table");
            require(context, InventoryAction.countItem(bot, Items.FURNACE) == 0,
                    "local furnace remained duplicated in inventory after placement");
            io.github.zoyluo.minecraftai.memory.BotMemoryStore.INSTANCE.remove(bot.getUUID());
            AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
            context.succeed();
        });
    }

    private static int surfaceCraftMaterialCount(AIPlayerEntity bot) {
        return java.util.stream.Stream.concat(RecipeRegistry.LOGS.stream(), RecipeRegistry.PLANKS.stream())
                .mapToInt(item -> InventoryAction.countItem(bot, item))
                .sum();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}
