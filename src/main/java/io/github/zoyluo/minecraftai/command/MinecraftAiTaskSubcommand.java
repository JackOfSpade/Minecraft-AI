package io.github.zoyluo.minecraftai.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationGate;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationPolicy;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.runtime.IntentController;
import io.github.zoyluo.minecraftai.task.BlueprintLoader;
import io.github.zoyluo.minecraftai.action.FarmAction;
import io.github.zoyluo.minecraftai.task.BreedTask;
import io.github.zoyluo.minecraftai.task.BuildTask;
import io.github.zoyluo.minecraftai.task.CombatTask;
import io.github.zoyluo.minecraftai.task.WardenRefusal;
import io.github.zoyluo.minecraftai.task.ContainerTask;
import io.github.zoyluo.minecraftai.task.CraftTask;
import io.github.zoyluo.minecraftai.task.EatTask;
import io.github.zoyluo.minecraftai.task.FarmTask;
import io.github.zoyluo.minecraftai.task.GatherQuotaTask;
import io.github.zoyluo.minecraftai.task.LightAreaTask;
import io.github.zoyluo.minecraftai.task.MineTask;
import io.github.zoyluo.minecraftai.task.MoveTask;
import io.github.zoyluo.minecraftai.task.SmeltTask;
import io.github.zoyluo.minecraftai.task.StockpileTask;
import io.github.zoyluo.minecraftai.task.OreDigTask;
import io.github.zoyluo.minecraftai.task.Task;
import io.github.zoyluo.minecraftai.task.TaskManager;
import io.github.zoyluo.minecraftai.task.TaskStatus;
import java.io.IOException;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class MinecraftAiTaskSubcommand {
    private MinecraftAiTaskSubcommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return literal("task")
                .then(literal("assign")
                        .then(botName()
                                .then(literal("move")
                                        .then(blockPosArgs(MinecraftAiTaskSubcommand::assignMove)))
                                .then(literal("forage")
                                        .executes(context -> assignForage(context, 4))
                                        .then(argument("count", IntegerArgumentType.integer(1))
                                                .executes(context -> assignForage(context, IntegerArgumentType.getInteger(context, "count")))))
                                .then(literal("attack")
                                        .then(argument("entity_type", IdentifierArgument.id())
                                                .executes(context -> assignAttack(context, 1))
                                                .then(argument("count", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignAttack(context, IntegerArgumentType.getInteger(context, "count"))))))
                                .then(literal("mine")
                                        .then(argument("block", IdentifierArgument.id())
                                                .executes(context -> assignMine(context, 1))
                                                .then(argument("count", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignMine(context, IntegerArgumentType.getInteger(context, "count"))))))
                                .then(literal("gather")
                                        .then(argument("item", IdentifierArgument.id())
                                                .executes(context -> assignGather(context, 1))
                                                .then(argument("count", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignGather(context, IntegerArgumentType.getInteger(context, "count"))))))
                                .then(literal("craft")
                                        .then(argument("item", IdentifierArgument.id())
                                                .executes(context -> assignCraft(context, 1))
                                                .then(argument("count", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignCraft(context, IntegerArgumentType.getInteger(context, "count"))))))
                                .then(literal("eat")
                                        .executes(MinecraftAiTaskSubcommand::assignEat))
                                .then(literal("light_area")
                                        .executes(context -> assignLightArea(context, 8, 8))
                                        .then(argument("radius", IntegerArgumentType.integer(2))
                                                .executes(context -> assignLightArea(context, IntegerArgumentType.getInteger(context, "radius"), 8))
                                                .then(argument("max_torches", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignLightArea(context,
                                                                IntegerArgumentType.getInteger(context, "radius"),
                                                                IntegerArgumentType.getInteger(context, "max_torches"))))))
                                .then(literal("farm")
                                        .then(argument("x", IntegerArgumentType.integer())
                                                .then(argument("y", IntegerArgumentType.integer())
                                                        .then(argument("z", IntegerArgumentType.integer())
                                                                .then(argument("radius", IntegerArgumentType.integer(1))
                                                                        .then(argument("crop", IdentifierArgument.id())
                                                                                .executes(context -> assignFarm(context, IntegerArgumentType.getInteger(context, "radius"), false))
                                                                                .then(literal("keep_tending")
                                                                                        .executes(context -> assignFarm(context, IntegerArgumentType.getInteger(context, "radius"), true)))))))))
                                .then(literal("harvest")
                                        .then(argument("x", IntegerArgumentType.integer())
                                                .then(argument("y", IntegerArgumentType.integer())
                                                        .then(argument("z", IntegerArgumentType.integer())
                                                                .then(argument("radius", IntegerArgumentType.integer(1))
                                                                        .then(argument("crop", IdentifierArgument.id())
                                                                                .executes(context -> assignHarvest(context, IntegerArgumentType.getInteger(context, "radius")))))))))
                                .then(literal("breed")
                                        .then(argument("entity_type", IdentifierArgument.id())
                                                .executes(context -> assignBreed(context, 1))
                                                .then(argument("pairs", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignBreed(context, IntegerArgumentType.getInteger(context, "pairs"))))))
                                .then(literal("smelt")
                                        .then(argument("input_item", IdentifierArgument.id())
                                                .then(argument("output_item", IdentifierArgument.id())
                                                        .executes(context -> assignSmelt(context, 1))
                                                        .then(argument("count", IntegerArgumentType.integer(1))
                                                                .executes(context -> assignSmelt(context, IntegerArgumentType.getInteger(context, "count")))))))
                                .then(literal("deposit")
                                        .executes(context -> assignDeposit(context, null, 0, false, null))
                                        .then(literal("all_except_tools")
                                                .executes(context -> assignDeposit(context, null, 0, true, null)))
                                        .then(literal("item")
                                                .then(argument("item", IdentifierArgument.id())
                                                        .executes(context -> assignDeposit(context, requiredItem(context, "item"), 0, false, null))
                                                        .then(argument("count", IntegerArgumentType.integer(1))
                                                                .executes(context -> assignDeposit(context, requiredItem(context, "item"), IntegerArgumentType.getInteger(context, "count"), false, null)))))
                                        .then(literal("at")
                                                .then(blockPosArgs(context -> assignDepositAt(context, null, 0, false)))
                                                .then(argument("x", IntegerArgumentType.integer())
                                                        .then(argument("y", IntegerArgumentType.integer())
                                                                .then(argument("z", IntegerArgumentType.integer())
                                                                        .then(literal("all_except_tools")
                                                                                .executes(context -> assignDeposit(context, null, 0, true, getBlockPos(context))))
                                                                        .then(literal("item")
                                                                                .then(argument("item", IdentifierArgument.id())
                                                                                        .executes(context -> assignDeposit(context, requiredItem(context, "item"), 0, false, getBlockPos(context)))
                                                                                        .then(argument("count", IntegerArgumentType.integer(1))
                                                                                                .executes(context -> assignDeposit(context, requiredItem(context, "item"), IntegerArgumentType.getInteger(context, "count"), false, getBlockPos(context)))))))))))
                                .then(literal("stockpile")
                                        .executes(context -> assignStockpile(context, true))
                                        .then(literal("include_tools")
                                                .executes(context -> assignStockpile(context, false))))
                                .then(literal("withdraw")
                                        .then(argument("item", IdentifierArgument.id())
                                                .executes(context -> assignWithdraw(context, null, 1))
                                                .then(argument("count", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignWithdraw(context, null, IntegerArgumentType.getInteger(context, "count")))))
                                        .then(literal("at")
                                                .then(argument("x", IntegerArgumentType.integer())
                                                        .then(argument("y", IntegerArgumentType.integer())
                                                                .then(argument("z", IntegerArgumentType.integer())
                                                                        .then(argument("item", IdentifierArgument.id())
                                                                                .executes(context -> assignWithdraw(context, getBlockPos(context), 1))
                                                                                .then(argument("count", IntegerArgumentType.integer(1))
                                                                                        .executes(context -> assignWithdraw(context, getBlockPos(context), IntegerArgumentType.getInteger(context, "count"))))))))))
                                .then(literal("build")
                                        .then(argument("blueprint", StringArgumentType.word())
                                                .then(argument("x", IntegerArgumentType.integer())
                                                        .then(argument("y", IntegerArgumentType.integer())
                                                                .then(argument("z", IntegerArgumentType.integer())
                                                                        .executes(context -> assignBuild(context, false, false))
                                                                        .then(literal("flatten")
                                                                                .executes(context -> assignBuild(context, false, true))))))
                                                .then(literal("auto_site")
                                                        .executes(context -> assignBuild(context, true, false))
                                                        .then(literal("flatten")
                                                                .executes(context -> assignBuild(context, true, true))))))))
                .then(literal("status")
                        .then(botName()
                                .executes(MinecraftAiTaskSubcommand::status)))
                .then(literal("pause")
                        .then(botName()
                                .executes(MinecraftAiTaskSubcommand::pause)))
                .then(literal("resume")
                        .then(botName()
                                .executes(MinecraftAiTaskSubcommand::resume)))
                .then(literal("abort")
                        .then(botName()
                                .executes(MinecraftAiTaskSubcommand::abort)));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> botName() {
        return argument("name", StringArgumentType.word());
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> blockPosArgs(Command<CommandSourceStack> command) {
        return argument("x", IntegerArgumentType.integer())
                .then(argument("y", IntegerArgumentType.integer())
                        .then(argument("z", IntegerArgumentType.integer())
                                .executes(command)));
    }

    private static int assignMove(CommandContext<CommandSourceStack> context) {
        return assign(context, bot -> new MoveTask(bot, getBlockPos(context)));
    }

    private static int assignForage(CommandContext<CommandSourceStack> context, int count) {
        return assign(context, bot -> GatherQuotaTask.collectAdditional(Items.SWEET_BERRIES, count));
    }

    private static int assignAttack(CommandContext<CommandSourceStack> context, int count) {
        return assign(context, bot -> {
            var type = BuiltInRegistries.ENTITY_TYPE.getValue(IdentifierArgument.getId(context, "entity_type"));
            if (WardenRefusal.refuses(type)) {
                WardenRefusal.logRefused(bot, "command");
                throw new IllegalArgumentException(WardenRefusal.MESSAGE);
            }
            return new CombatTask(type, count, io.github.zoyluo.minecraftai.MinecraftAiConfig.get().combat().retreatHp());
        });
    }

    private static int assignMine(CommandContext<CommandSourceStack> context, int count) {
        return assign(context, bot -> {
            Block block = BuiltInRegistries.BLOCK.getValue(IdentifierArgument.getId(context, "block"));
            return OreScan.isOreBlock(block) ? new OreDigTask(OreScan.oreFamily(block), count) : new MineTask(block, count);
        });
    }

    private static int assignGather(CommandContext<CommandSourceStack> context, int count) {
        return assign(context, bot -> GatherQuotaTask.collectAdditional(
                BuiltInRegistries.ITEM.getValue(IdentifierArgument.getId(context, "item")),
                count));
    }

    private static int assignCraft(CommandContext<CommandSourceStack> context, int count) {
        return assign(context, bot -> new CraftTask(requiredItem(context, "item"), count));
    }

    private static int assignEat(CommandContext<CommandSourceStack> context) {
        return assign(context, bot -> new EatTask());
    }

    private static int assignLightArea(CommandContext<CommandSourceStack> context, int radius, int maxTorches) {
        return assign(context, bot -> new LightAreaTask(radius, maxTorches));
    }

    private static int assignFarm(CommandContext<CommandSourceStack> context, int radius, boolean keepTending) {
        return assign(context, bot -> {
            FarmAction.CropSpec spec = cropSpec(context);
            return new FarmTask(getBlockPos(context), radius, spec.seed(), spec.crop(), keepTending, false);
        });
    }

    private static int assignHarvest(CommandContext<CommandSourceStack> context, int radius) {
        return assign(context, bot -> {
            FarmAction.CropSpec spec = cropSpec(context);
            return new FarmTask(getBlockPos(context), radius, spec.seed(), spec.crop(), false, true);
        });
    }

    private static int assignBreed(CommandContext<CommandSourceStack> context, int pairs) {
        return assign(context, bot -> new BreedTask(
                BuiltInRegistries.ENTITY_TYPE.getValue(IdentifierArgument.getId(context, "entity_type")),
                pairs));
    }

    private static int assignSmelt(CommandContext<CommandSourceStack> context, int count) {
        return assign(context, bot -> new SmeltTask(
                requiredItem(context, "input_item"),
                requiredItem(context, "output_item"),
                count));
    }

    private static int assignDepositAt(CommandContext<CommandSourceStack> context, Item item, int count, boolean allExceptTools) {
        return assignDeposit(context, item, count, allExceptTools, getBlockPos(context));
    }

    private static int assignDeposit(CommandContext<CommandSourceStack> context,
                                     Item item,
                                     int count,
                                     boolean allExceptTools,
                                     BlockPos pos) {
        return assign(context, bot -> ContainerTask.deposit(pos, item, count, allExceptTools));
    }

    private static int assignWithdraw(CommandContext<CommandSourceStack> context, BlockPos pos, int count) {
        return assign(context, bot -> ContainerTask.withdraw(pos, requiredItem(context, "item"), count));
    }

    private static int assignStockpile(CommandContext<CommandSourceStack> context, boolean allExceptTools) {
        return assign(context, bot -> new StockpileTask(allExceptTools));
    }

    private static int assignBuild(CommandContext<CommandSourceStack> context, boolean autoSite, boolean flatten) {
        return assign(context, bot -> {
            try {
                return new BuildTask(
                        BlueprintLoader.load(StringArgumentType.getString(context, "blueprint")),
                        autoSite ? null : getBlockPos(context),
                        autoSite,
                        flatten);
            } catch (IOException exception) {
                throw new IllegalArgumentException(exception.getMessage(), exception);
            }
        });
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        Optional<AIPlayerEntity> bot = getBot(context, BotAuthorizationPolicy.Operation.VIEW, "status");
        if (bot.isEmpty()) {
            return 0;
        }
        TaskStatus status = TaskManager.INSTANCE.status(bot.get());
        context.getSource().sendSuccess(() -> Component.literal("[Minecraft-AI] task "
                + status.name()
                + " state=" + status.state()
                + " progress=" + String.format(java.util.Locale.ROOT, "%.2f", status.progress())
                + " elapsed=" + status.elapsedTicks()
                + " desc=" + status.description()
                + (status.failureReason().isBlank() ? "" : " reason=" + status.failureReason())), false);
        return 1;
    }

    private static int abort(CommandContext<CommandSourceStack> context) {
        Optional<AIPlayerEntity> bot = getBot(context, BotAuthorizationPolicy.Operation.COMMAND, "abort");
        if (bot.isEmpty()) {
            return 0;
        }
        IntentController.INSTANCE.cancelAll(
                bot.get(), IntentController.ControlOrigin.PLAYER_COMMAND, "command_task_abort");
        context.getSource().sendSuccess(() -> Component.literal("[Minecraft-AI] task aborted"), false);
        return 1;
    }

    private static int pause(CommandContext<CommandSourceStack> context) {
        Optional<AIPlayerEntity> bot = getBot(context, BotAuthorizationPolicy.Operation.COMMAND, "pause");
        if (bot.isEmpty()) {
            return 0;
        }
        IntentController.INSTANCE.pause(
                bot.get(), IntentController.ControlOrigin.PLAYER_COMMAND, "command_task_pause");
        return 1;
    }

    private static int resume(CommandContext<CommandSourceStack> context) {
        Optional<AIPlayerEntity> bot = getBot(context, BotAuthorizationPolicy.Operation.COMMAND, "resume");
        if (bot.isEmpty()) {
            return 0;
        }
        IntentController.INSTANCE.resume(
                bot.get(), IntentController.ControlOrigin.PLAYER_COMMAND, "command_task_resume");
        return 1;
    }

    private static int assign(CommandContext<CommandSourceStack> context, TaskFactory factory) {
        Optional<AIPlayerEntity> bot = getBot(context, BotAuthorizationPolicy.Operation.COMMAND, "assign");
        if (bot.isEmpty()) {
            return 0;
        }
        try {
            Task task = factory.create(bot.get());
            IntentController.INSTANCE.replace(
                    bot.get(),
                    IntentController.ControlOrigin.PLAYER_COMMAND,
                    "command_task_assign:" + task.name(),
                    () -> {
                        TaskManager.INSTANCE.assign(bot.get(), task,
                                TaskOrigin.of(TaskOrigin.Kind.PLAYER_COMMAND, "command_task_assign"));
                        return true;
                    });
            context.getSource().sendSuccess(() -> Component.literal("[Minecraft-AI] task assigned: " + task.name()), false);
            return 1;
        } catch (RuntimeException exception) {
            context.getSource().sendFailure(Component.literal("[Minecraft-AI] task assign failed: " + exception.getMessage()));
            return 0;
        }
    }

    private static Optional<AIPlayerEntity> getBot(CommandContext<CommandSourceStack> context,
                                                   BotAuthorizationPolicy.Operation operation,
                                                   String action) {
        String name = StringArgumentType.getString(context, "name");
        return BotAuthorizationGate.INSTANCE.resolveAuthorized(
                context.getSource(), name, operation, "command:task_" + action);
    }

    private static BlockPos getBlockPos(CommandContext<CommandSourceStack> context) {
        return new BlockPos(
                IntegerArgumentType.getInteger(context, "x"),
                IntegerArgumentType.getInteger(context, "y"),
                IntegerArgumentType.getInteger(context, "z"));
    }

    private static Item requiredItem(CommandContext<CommandSourceStack> context, String name) {
        Identifier id = IdentifierArgument.getId(context, name);
        return BuiltInRegistries.ITEM.getOptional(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_item: " + id));
    }

    private static FarmAction.CropSpec cropSpec(CommandContext<CommandSourceStack> context) {
        Identifier id = IdentifierArgument.getId(context, "crop");
        return FarmAction.cropSpec(id.toString());
    }

    @FunctionalInterface
    private interface TaskFactory {
        Task create(AIPlayerEntity bot);
    }
}
