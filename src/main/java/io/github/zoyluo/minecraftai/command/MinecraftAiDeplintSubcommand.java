package io.github.zoyluo.minecraftai.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationGate;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationPolicy;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.goal.Goal;
import io.github.zoyluo.minecraftai.goal.GoalPlanner;
import io.github.zoyluo.minecraftai.goal.GoalStep;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.mining.ToolTier;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * S6: dependency-chain auditor `/minecraftai deplint <bot> <spec>` -- runs {@link GoalPlanner#plan}
 * offline against the given target, printing the step tree / unresolved items / step count, serving
 * as a regression yardstick for "skill dependency-chain completeness" (use it as a health check after each dev step).
 *
 * spec looks like: mine_ore:diamond:3 / item:cooked_beef:8 / item:bread:3 / pickaxe:iron / armor / workstation / stockpile:iron_ingot:32
 */
public final class MinecraftAiDeplintSubcommand {
    private MinecraftAiDeplintSubcommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return literal("deplint")
                .then(argument("name", StringArgumentType.word())
                        .then(argument("spec", StringArgumentType.greedyString())
                                .executes(MinecraftAiDeplintSubcommand::run)));
    }

    private static int run(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        Optional<AIPlayerEntity> botOpt = BotAuthorizationGate.INSTANCE.resolveAuthorized(
                context.getSource(), name, BotAuthorizationPolicy.Operation.ADMIN, "command:deplint");
        if (botOpt.isEmpty()) {
            return 0;
        }
        String spec = StringArgumentType.getString(context, "spec").trim();
        Goal goal;
        try {
            goal = parseGoal(spec);
        } catch (RuntimeException e) {
            context.getSource().sendFailure(Component.literal("[deplint] bad spec '" + spec + "': " + e.getMessage()));
            return 0;
        }
        GoalPlanner.GoalPlan plan = GoalPlanner.plan(botOpt.get(), goal);
        List<GoalStep> steps = plan.steps();
        List<String> unresolved = plan.unresolved();
        context.getSource().sendSuccess(() -> Component.literal(
                "[deplint] goal=" + goal + "  steps=" + steps.size() + "  unresolved=" + unresolved.size()), false);
        context.getSource().sendSuccess(() -> Component.literal("[deplint] " + plan.describeSteps()), false);
        if (unresolved.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("[deplint] OK · chain complete, no unresolved items"), false);
        } else {
            context.getSource().sendFailure(Component.literal("[deplint] UNRESOLVED · " + String.join(", ", unresolved)));
        }
        return 1;
    }

    private static Goal parseGoal(String spec) {
        String[] p = spec.split(":");
        String kind = p[0].toLowerCase(Locale.ROOT);
        switch (kind) {
            case "armor":
                return new Goal.Armor();
            case "workstation":
                return new Goal.Workstation();
            case "pickaxe":
                return new Goal.HavePickaxeTier(parseTier(arg(p, 1)));
            case "mine_ore": {
                Block ore = block(arg(p, 1));
                return new Goal.MineOre(OreScan.oreFamily(ore), count(p, 2));
            }
            case "item":
                return new Goal.HaveItem(item(arg(p, 1)), count(p, 2));
            case "stockpile":
                return new Goal.Stockpile(item(arg(p, 1)), count(p, 2));
            default:
                throw new IllegalArgumentException("unknown kind: " + kind
                        + " (mine_ore/item/pickaxe/armor/workstation/stockpile)");
        }
    }

    private static String arg(String[] p, int i) {
        if (i >= p.length) {
            throw new IllegalArgumentException("missing arg #" + i);
        }
        return p[i];
    }

    private static int count(String[] p, int i) {
        return i < p.length ? Integer.parseInt(p[i]) : 1;
    }

    private static int parseTier(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "wood", "wooden" -> ToolTier.WOOD;
            case "stone" -> ToolTier.STONE;
            case "iron" -> ToolTier.IRON;
            case "diamond" -> ToolTier.DIAMOND;
            default -> Integer.parseInt(s);
        };
    }

    // Accepts "diamond" / "diamond_ore" / "minecraft:diamond_ore"
    private static Block block(String name) {
        Block b = BuiltInRegistries.BLOCK.getValue(id(name));
        if (b == Blocks.AIR && !name.contains(":") && !name.endsWith("_ore")) {
            b = BuiltInRegistries.BLOCK.getValue(id(name + "_ore"));
        }
        if (b == Blocks.AIR) {
            throw new IllegalArgumentException("unknown ore block: " + name);
        }
        return b;
    }

    private static Item item(String name) {
        return BuiltInRegistries.ITEM.getOptional(id(name))
                .orElseThrow(() -> new IllegalArgumentException("unknown item: " + name));
    }

    private static Identifier id(String name) {
        if (name.contains(":")) {
            String[] x = name.split(":", 2);
            return Identifier.fromNamespaceAndPath(x[0], x[1]);
        }
        return Identifier.fromNamespaceAndPath("minecraft", name);
    }
}
