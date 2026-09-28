package io.github.zoyluo.minecraftai.network;

import io.github.zoyluo.minecraftai.auth.BotAuthorizationGate;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationPolicy;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.brain.BotRuntimeOptions;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.goal.GoalExecutor;
import io.github.zoyluo.minecraftai.memory.BotMemory;
import io.github.zoyluo.minecraftai.memory.BotMemoryStore;
import io.github.zoyluo.minecraftai.network.payload.BotChatS2C;
import io.github.zoyluo.minecraftai.network.payload.BotCommandC2S;
import io.github.zoyluo.minecraftai.network.payload.BotItemMoveC2S;
import io.github.zoyluo.minecraftai.network.payload.BotTeleportC2S;
import io.github.zoyluo.minecraftai.network.payload.BotSnapshotS2C;
import io.github.zoyluo.minecraftai.network.payload.SetOptionC2S;
import io.github.zoyluo.minecraftai.network.payload.SubscribeBotC2S;
import io.github.zoyluo.minecraftai.runtime.IntentController;
import io.github.zoyluo.minecraftai.runtime.RuntimeLifecycleCoordinator;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import io.github.zoyluo.minecraftai.task.CraftTask;
import io.github.zoyluo.minecraftai.task.EatTask;
import io.github.zoyluo.minecraftai.task.MineTask;
import io.github.zoyluo.minecraftai.task.MoveTask;
import io.github.zoyluo.minecraftai.task.SmeltTask;
import io.github.zoyluo.minecraftai.task.SleepTask;
import io.github.zoyluo.minecraftai.task.Task;
import io.github.zoyluo.minecraftai.task.TaskManager;
import io.github.zoyluo.minecraftai.task.TaskStatus;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MinecraftAiServerNetworking {
    public static final MinecraftAiServerNetworking INSTANCE = new MinecraftAiServerNetworking();

    private static final int SNAPSHOT_INTERVAL_TICKS = 10;
    private final Map<UUID, UUID> subscriptions = new ConcurrentHashMap<>();
    private int snapshotTick;

    private MinecraftAiServerNetworking() {
    }

    public void register() {
        ServerPlayNetworking.registerGlobalReceiver(SubscribeBotC2S.ID, (payload, context) ->
                context.server().execute(() -> handleSubscribe(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(BotCommandC2S.ID, (payload, context) ->
                context.server().execute(() -> handleCommand(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(SetOptionC2S.ID, (payload, context) ->
                context.server().execute(() -> handleSetOption(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(BotItemMoveC2S.ID, (payload, context) ->
                context.server().execute(() -> handleItemMove(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(BotTeleportC2S.ID, (payload, context) ->
                context.server().execute(() -> handleTeleport(context.player(), payload)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                subscriptions.remove(handler.player.getUuid()));
    }

    public void tick(MinecraftServer server) {
        snapshotTick++;
        if (snapshotTick % SNAPSHOT_INTERVAL_TICKS != 0 || subscriptions.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, UUID> entry : subscriptions.entrySet()) {
            ServerPlayerEntity viewer = server.getPlayerManager().getPlayer(entry.getKey());
            if (viewer == null) {
                subscriptions.remove(entry.getKey());
                continue;
            }
            Optional<AIPlayerEntity> bot = AIPlayerManager.INSTANCE.getByUuid(entry.getValue());
            if (bot.isEmpty() || !BotAuthorizationGate.INSTANCE.authorize(
                    viewer, bot.get(), BotAuthorizationPolicy.Operation.VIEW, "network:snapshot_push")) {
                subscriptions.remove(entry.getKey(), entry.getValue());
                continue;
            }
            if (ServerPlayNetworking.canSend(viewer, BotSnapshotS2C.ID)) {
                ServerPlayNetworking.send(viewer, snapshot(bot.get()));
            }
        }
    }

    public void clear() {
        subscriptions.clear();
        snapshotTick = 0;
    }

    public void clearBot(UUID botId) {
        subscriptions.entrySet().removeIf(entry -> botId.equals(entry.getValue()));
    }

    public void sendBotChat(AIPlayerEntity bot, String role, String text) {
        if (subscriptions.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, UUID> entry : subscriptions.entrySet()) {
            if (!bot.getUuid().equals(entry.getValue())) {
                continue;
            }
            ServerPlayerEntity viewer = bot.getEntityWorld().getServer().getPlayerManager().getPlayer(entry.getKey());
            if (viewer == null) {
                subscriptions.remove(entry.getKey(), entry.getValue());
                continue;
            }
            if (!BotAuthorizationGate.INSTANCE.authorize(
                    viewer, bot, BotAuthorizationPolicy.Operation.VIEW, "network:chat_push")) {
                subscriptions.remove(entry.getKey(), entry.getValue());
                continue;
            }
            if (ServerPlayNetworking.canSend(viewer, BotChatS2C.ID)) {
                ServerPlayNetworking.send(viewer, new BotChatS2C(bot.getGameProfile().name(), role, text));
            }
        }
    }

    private void handleSubscribe(ServerPlayerEntity player, SubscribeBotC2S payload) {
        if (!payload.subscribe()) {
            subscriptions.remove(player.getUuid());
            return;
        }
        Optional<AIPlayerEntity> bot = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                player, payload.botName(), BotAuthorizationPolicy.Operation.VIEW, "network:subscribe");
        if (bot.isEmpty()) {
            subscriptions.remove(player.getUuid());
            sendSystem(player, "", "Bot not found or insufficient permissions.");
            return;
        }
        AIPlayerEntity target = bot.get();
        subscriptions.put(player.getUuid(), target.getUuid());
        if (ServerPlayNetworking.canSend(player, BotSnapshotS2C.ID)) {
            ServerPlayNetworking.send(player, snapshot(target));
        }
        sendSystem(player, target.getGameProfile().name(), "Subscribed to " + target.getGameProfile().name());
    }

    private void handleCommand(ServerPlayerEntity player, BotCommandC2S payload) {
        Optional<AIPlayerEntity> bot = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                player, payload.botName(), BotAuthorizationPolicy.Operation.COMMAND, "network:command");
        if (bot.isEmpty()) {
            sendSystem(player, "", "Bot not found or insufficient permissions.");
            return;
        }
        try {
            dispatch(player, bot.get(), payload);
        } catch (RuntimeException exception) {
            BotLog.error(bot.get(), "panel_command_exception", exception, "action", payload.action());
            String reason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            sendSystem(player, payload.botName(), "Command execution failed: " + reason);
        }
    }

    private void handleSetOption(ServerPlayerEntity player, SetOptionC2S payload) {
        Optional<AIPlayerEntity> bot = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                player, payload.botName(), BotAuthorizationPolicy.Operation.ADMIN, "network:set_option");
        if (bot.isEmpty()) {
            sendSystem(player, "", "Bot not found or insufficient permissions.");
            return;
        }
        AIPlayerEntity target = bot.get();
        switch (payload.key()) {
            case "manual" -> BrainCoordinator.INSTANCE.setManualMode(target, payload.value());
            case "memory" -> BotRuntimeOptions.INSTANCE.setMemoryToolsEnabled(target, payload.value());
            case "reports" -> BotRuntimeOptions.INSTANCE.setVerboseReportsEnabled(target, payload.value());
            default -> throw new IllegalArgumentException("unknown_option: " + payload.key());
        }
        sendSystem(player, target.getGameProfile().name(), "Setting updated: " + payload.key() + "=" + payload.value());
    }

    // Panel teleport: runs on the server thread; authorization completes after the target is resolved, before any coordinate change.
    private void handleTeleport(ServerPlayerEntity player, BotTeleportC2S payload) {
        Optional<AIPlayerEntity> bot = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                player, payload.botName(), BotAuthorizationPolicy.Operation.TELEPORT, "network:teleport");
        if (bot.isEmpty()) {
            sendSystem(player, "", "Bot not found or insufficient permissions.");
            return;
        }
        AIPlayerEntity target = bot.get();
        if (!io.github.zoyluo.minecraftai.mode.CapabilityRuntime.decide(
                target, io.github.zoyluo.minecraftai.mode.PrivilegedCapability.MANUAL_TELEPORT,
                "network_manual_teleport").allowed()) {
            sendSystem(player, target.getGameProfile().name(),
                    "Panel teleport is disabled in the current runtime mode; explicitly enable operator/manualTeleport.");
            return;
        }
        if (payload.direction() == BotTeleportC2S.TO_AI) {
            // Player -> a standable block within 10 blocks of the AI.
            net.minecraft.server.world.ServerWorld world = target.getEntityWorld();
            io.github.zoyluo.minecraftai.pathfinding.Standability.findNearestStandable(world, target.getBlockPos(), 10, 8, 8)
                    .ifPresent(p -> player.teleport(world, p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D,
                            java.util.Set.of(), player.getYaw(), player.getPitch(), true));
        } else if (payload.direction() == BotTeleportC2S.RECALL_AI) {
            // AI -> a standable block within 10 blocks of the player (stop its current action first, then teleport).
            net.minecraft.server.world.ServerWorld world = player.getEntityWorld();
            io.github.zoyluo.minecraftai.pathfinding.Standability.findNearestStandable(world, player.getBlockPos(), 10, 8, 8)
                    .ifPresent(p -> {
                        target.getActionPack().stopAll();
                        target.teleport(world, p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D,
                                java.util.Set.of(), target.getYaw(), target.getPitch(), true);
                    });
        } else {
            sendSystem(player, target.getGameProfile().name(), "Invalid teleport direction.");
        }
    }

    private void handleItemMove(ServerPlayerEntity player, BotItemMoveC2S payload) {
        Optional<AIPlayerEntity> bot = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                player, payload.botName(), BotAuthorizationPolicy.Operation.INVENTORY, "network:item_move");
        if (bot.isEmpty()) {
            sendSystem(player, "", "Bot not found or insufficient permissions.");
            return;
        }
        AIPlayerEntity target = bot.get();
        var botInv = target.getInventory();
        var playerInv = player.getInventory();
        if (payload.direction() == BotItemMoveC2S.TAKE) {
            // Take from the AI's main[slot] into the player's inventory
            int slot = payload.slot();
            if (slot < 0 || slot >= botInv.getMainStacks().size()) {
                return;
            }
            ItemStack src = botInv.getMainStacks().get(slot);
            if (src.isEmpty()) {
                return;
            }
            int move = payload.amount() <= 0 ? src.getCount() : Math.min(payload.amount(), src.getCount());
            ItemStack moving = src.copy();
            moving.setCount(move);
            boolean inserted = playerInv.insertStack(moving); // moving is mutated in place to the "remainder not inserted"
            int placed = move - moving.getCount();
            if (placed > 0) {
                src.decrement(placed);
                botInv.markDirty();
            }
        } else if (payload.direction() == BotItemMoveC2S.PUT) {
            // Put the player's inventory.main[slot] into the AI's inventory
            int slot = payload.slot();
            if (slot < 0 || slot >= playerInv.getMainStacks().size()) {
                return;
            }
            ItemStack src = playerInv.getMainStacks().get(slot);
            if (src.isEmpty()) {
                return;
            }
            int move = payload.amount() <= 0 ? src.getCount() : Math.min(payload.amount(), src.getCount());
            ItemStack moving = src.copy();
            moving.setCount(move);
            int placed = insertIntoBot(botInv, moving);
            if (placed > 0) {
                src.decrement(placed);
                playerInv.markDirty();
            }
        } else {
            sendSystem(player, target.getGameProfile().name(), "Invalid item move direction.");
            return;
        }
        // Immediately push a snapshot frame (including both inventories) so the UI doesn't have to wait for the 10-tick refresh cycle.
        if (ServerPlayNetworking.canSend(player, BotSnapshotS2C.ID)) {
            ServerPlayNetworking.send(player, snapshot(target));
        }
    }

    // Insert stack into the AI's inventory main area as much as possible (stack onto matching items first, then fill empty slots); returns the amount actually placed.
    private static int insertIntoBot(net.minecraft.entity.player.PlayerInventory botInv, ItemStack moving) {
        int want = moving.getCount();
        // 1) Stack onto existing not-yet-full slots of the same item
        for (int i = 0; i < botInv.getMainStacks().size() && !moving.isEmpty(); i++) {
            ItemStack dst = botInv.getMainStacks().get(i);
            if (!dst.isEmpty() && ItemStack.areItemsAndComponentsEqual(dst, moving) && dst.getCount() < dst.getMaxCount()) {
                int room = dst.getMaxCount() - dst.getCount();
                int add = Math.min(room, moving.getCount());
                dst.increment(add);
                moving.decrement(add);
            }
        }
        // 2) Fill empty slots
        for (int i = 0; i < botInv.getMainStacks().size() && !moving.isEmpty(); i++) {
            if (botInv.getMainStacks().get(i).isEmpty()) {
                botInv.getMainStacks().set(i, moving.copy());
                moving.setCount(0);
            }
        }
        if (want != moving.getCount()) {
            botInv.markDirty();
        }
        return want - moving.getCount();
    }

    private void dispatch(ServerPlayerEntity player, AIPlayerEntity bot, BotCommandC2S payload) {
        String action = payload.action().toLowerCase(Locale.ROOT);
        switch (action) {
            case "move" -> assign(bot, new MoveTask(bot, parseBlockPos(payload.arg1())));
            case "mine" -> assign(bot, new MineTask(requiredBlock(payload.arg1()), count(payload)));
            case "craft" -> assign(bot, new CraftTask(requiredItem(payload.arg1()), count(payload)));
            case "smelt" -> assign(bot, new SmeltTask(requiredItem(payload.arg1()), requiredItem(payload.arg2()), count(payload)));
            case "eat" -> assign(bot, new EatTask());
            case "sleep" -> assign(bot, new SleepTask());
            case "abort" -> {
                IntentController.INSTANCE.cancelAll(bot, IntentController.ControlOrigin.PLAYER_PANEL, "panel_abort");
            }
            case "chat" -> {
                sendBotChat(bot, "user", payload.arg1());
                if (!IntentController.INSTANCE.routePlayerControlPhrase(
                        bot, IntentController.ControlOrigin.PLAYER_PANEL, payload.arg1())) {
                    BrainCoordinator.INSTANCE.handleMessage(bot, player, payload.arg1());
                }
            }
            case "pause" -> IntentController.INSTANCE.pause(
                    bot, IntentController.ControlOrigin.PLAYER_PANEL, "panel_pause");
            case "resume" -> IntentController.INSTANCE.resume(
                    bot, IntentController.ControlOrigin.PLAYER_PANEL, "panel_resume");
            case "reset" -> {
                RuntimeLifecycleCoordinator.INSTANCE.resetBot(
                        bot, IntentController.ControlOrigin.PLAYER_PANEL, "panel_brain_reset");
                sendSystem(player, bot.getGameProfile().name(), "Brain has been reset.");
            }
            default -> throw new IllegalArgumentException("unknown_action: " + payload.action());
        }
    }

    private static void assign(AIPlayerEntity bot, Task task) {
        IntentController.INSTANCE.replace(
                bot,
                IntentController.ControlOrigin.PLAYER_PANEL,
                "panel_assign:" + task.name(),
                () -> {
                    TaskManager.INSTANCE.assign(bot, task,
                            TaskOrigin.of(TaskOrigin.Kind.PLAYER_PANEL, "panel_assign"));
                    return true;
                });
    }

    private BotSnapshotS2C snapshot(AIPlayerEntity bot) {
        TaskStatus task = TaskManager.INSTANCE.status(bot);
        BrainCoordinator.BrainStatus brain = BrainCoordinator.INSTANCE.status(bot);
        BotMemory memory = BotMemoryStore.INSTANCE.of(bot.getUuid());
        ArrayList<BotSnapshotS2C.ItemEntry> inventory = new ArrayList<>();
        for (int slot = 0; slot < bot.getInventory().getMainStacks().size(); slot++) {
            ItemStack stack = bot.getInventory().getMainStacks().get(slot);
            if (!stack.isEmpty()) {
                inventory.add(new BotSnapshotS2C.ItemEntry(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), slot));
            }
        }
        // UI: full-body equipment (head/chest/legs/feet/main hand/off hand), slot index 0..5, for the inventory panel's equipment display.
        ArrayList<BotSnapshotS2C.ItemEntry> equipment = new ArrayList<>();
        net.minecraft.entity.EquipmentSlot[] equipSlots = {
                net.minecraft.entity.EquipmentSlot.HEAD, net.minecraft.entity.EquipmentSlot.CHEST,
                net.minecraft.entity.EquipmentSlot.LEGS, net.minecraft.entity.EquipmentSlot.FEET,
                net.minecraft.entity.EquipmentSlot.MAINHAND, net.minecraft.entity.EquipmentSlot.OFFHAND};
        for (int slotIndex = 0; slotIndex < equipSlots.length; slotIndex++) {
            ItemStack equipped = bot.getEquippedStack(equipSlots[slotIndex]);
            if (!equipped.isEmpty()) {
                equipment.add(new BotSnapshotS2C.ItemEntry(
                        Registries.ITEM.getId(equipped.getItem()).toString(), equipped.getCount(), slotIndex));
            }
        }
        // Task chain: prefer showing GoalExecutor's actual deterministic plan (provision_food -> [chop tree/craft pickaxe/mine stone/build furnace/hunt/cook]...),
        // and only fall back to the goal recorded by the brain's set_goal (memory) when there's no active plan. This keeps the panel's chain consistent with what the bot is actually executing.
        boolean hasPlan = GoalExecutor.INSTANCE.hasActivePlan(bot);
        String goalTitle = hasPlan ? GoalExecutor.INSTANCE.activeGoalTitle(bot) : memory.goalTitle();
        List<String> goalSteps = hasPlan ? GoalExecutor.INSTANCE.activeGoalSteps(bot) : memory.goalSteps();
        int goalIndex = hasPlan ? GoalExecutor.INSTANCE.activeGoalCurrentIndex(bot) : memory.goalCurrentStepIndex();
        int goalTotal = hasPlan ? GoalExecutor.INSTANCE.activeGoalTotalSteps(bot) : memory.goalTotalSteps();
        String goalCurrentStep = goalIndex >= 0 && goalIndex < goalSteps.size()
                ? goalSteps.get(goalIndex) : memory.currentGoalStep().orElse("");
        var goalResult = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
        var runtimeConfig = io.github.zoyluo.minecraftai.MinecraftAiConfig.get();
        List<String> effectiveCapabilities = java.util.Arrays.stream(
                        io.github.zoyluo.minecraftai.mode.PrivilegedCapability.values())
                .filter(capability -> io.github.zoyluo.minecraftai.mode.CapabilityPolicy.decide(
                        runtimeConfig.profile(), runtimeConfig.operatorCapabilities(), capability).allowed())
                .map(Enum::name)
                .toList();
        return new BotSnapshotS2C(
                bot.getGameProfile().name(),
                bot.getHealth(),
                bot.getMaxHealth(),
                bot.getHungerManager().getFoodLevel(),
                bot.getBlockX(),
                bot.getBlockY(),
                bot.getBlockZ(),
                task.name(),
                task.state().name(),
                (float) task.progress(),
                brain.busy(),
                brain.promptTokens(),
                brain.completionTokens(),
                goalTitle,
                goalCurrentStep,
                goalIndex,
                goalTotal,
                goalSteps,
                goalResult == null ? 0L : goalResult.sequence(),
                goalResult == null ? "" : goalResult.status().name(),
                goalResult == null ? "" : GoalExecutor.INSTANCE.resultSummary(goalResult),
                goalResult == null ? 0 : goalResult.evaluation().matched(),
                goalResult == null ? 0 : goalResult.evaluation().required(),
                TaskManager.INSTANCE.isUserPaused(bot),
                TaskManager.INSTANCE.pausedDepth(bot),
                runtimeConfig.profile().configValue(),
                effectiveCapabilities,
                BrainCoordinator.INSTANCE.manualMode(bot),
                BotRuntimeOptions.INSTANCE.memoryToolsEnabled(bot),
                BotRuntimeOptions.INSTANCE.verboseReportsEnabled(bot),
                inventory,
                equipment);
    }

    private void sendSystem(ServerPlayerEntity player, String botName, String text) {
        if (ServerPlayNetworking.canSend(player, BotChatS2C.ID)) {
            ServerPlayNetworking.send(player, new BotChatS2C(botName, "system", text));
        }
    }

    private static int count(BotCommandC2S payload) {
        return Math.max(1, payload.count());
    }

    private static BlockPos parseBlockPos(String value) {
        String[] parts = value.trim().split("\\s+");
        if (parts.length != 3) {
            throw new IllegalArgumentException("move expects arg1='x y z'");
        }
        return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    private static Block requiredBlock(String idText) {
        Identifier id = Identifier.of(idText);
        return Registries.BLOCK.getOptionalValue(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_block: " + id));
    }

    private static Item requiredItem(String idText) {
        Identifier id = Identifier.of(idText);
        return Registries.ITEM.getOptionalValue(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_item: " + id));
    }

}
