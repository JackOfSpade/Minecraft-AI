package io.github.zoyluo.minecraftai.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationGate;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Real terrain capture tool: /minecraftai snapshot [radius] -- dumps the "notable blocks" in the
 * radius-sized cube around the bot into reproducible setRel code + a compact tally, writing to
 * reports/snapshot_<coords>.txt.
 * Purpose: one-click capture of the scene at the moment of a real real_diamond/real_iron failure ->
 * paste it into a deterministic L1 scenario -> actually fix flaky real-world failures.
 */
public final class MinecraftAiSnapshotSubcommand {
    private static final int DEFAULT_RADIUS = 8;
    private static final int MAX_RADIUS = 24;

    private static final Set<Block> SKIP = Set.of(
            Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR,
            Blocks.STONE, Blocks.DEEPSLATE, Blocks.TUFF, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE,
            Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.PODZOL, Blocks.MUD,
            Blocks.SAND, Blocks.GRAVEL, Blocks.SANDSTONE, Blocks.NETHERRACK);

    private MinecraftAiSnapshotSubcommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return literal("snapshot")
                .executes(context -> run(context, DEFAULT_RADIUS))
                .then(argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                        .executes(context -> run(context, IntegerArgumentType.getInteger(context, "radius"))));
    }

    private static int run(CommandContext<CommandSourceStack> context, int radius) {
        CommandSourceStack source = context.getSource();
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(source, "command:snapshot")) {
            return 0;
        }
        Optional<AIPlayerEntity> botOpt = selectBot(source);
        if (botOpt.isEmpty()) {
            source.sendFailure(Component.literal("[MinecraftAi Snapshot] no bot — /minecraftai spawn <name> first"));
            return 0;
        }
        AIPlayerEntity bot = botOpt.get();
        if (!CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "admin_snapshot").allowed()) {
            source.sendFailure(Component.literal("[MinecraftAi Snapshot] unavailable in strict_survival; enable operator hiddenBlockScan explicitly"));
            return 0;
        }
        ServerLevel world = bot.level();
        BlockPos center = bot.blockPosition();

        StringBuilder code = new StringBuilder();
        Map<String, Integer> tally = new TreeMap<>();
        Map<String, Integer> notable = new LinkedHashMap<>();
        int written = 0;

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius))) {
            BlockState state = world.getBlockState(pos);
            Block block = state.getBlock();
            boolean fluid = !state.getFluidState().isEmpty();
            if (SKIP.contains(block) && !fluid) {
                continue;
            }
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            String key = id.toString();
            tally.merge(key, 1, Integer::sum);
            if (fluid || isOreLike(block, id)) {
                notable.merge(key, 1, Integer::sum);
            }
            int dx = pos.getX() - center.getX();
            int dy = pos.getY() - center.getY();
            int dz = pos.getZ() - center.getZ();
            code.append(String.format(
                    "        setRel(world, origin, %d, %d, %d, \"%s\");%n", dx, dy, dz, key));
            written++;
        }

        String header = buildHeader(world, bot, center, radius, written, notable);
        Path file;
        try {
            file = writeReport(center, header, code.toString(), tally);
        } catch (IOException e) {
            source.sendFailure(Component.literal("[MinecraftAi Snapshot] write failed: " + e.getMessage()));
            return 0;
        }
        final int total = written;
        source.sendSuccess(() -> Component.literal("[MinecraftAi Snapshot] " + total + " notable blocks @ "
                + center.toShortString() + " r=" + radius + " notable=" + notable + " -> " + file), false);
        return 1;
    }

    private static boolean isOreLike(Block block, Identifier id) {
        return io.github.zoyluo.minecraftai.mining.OreScan.isOreBlock(block)
                || id.getPath().endsWith("_ore") || id.getPath().contains("ancient_debris");
    }

    private static String buildHeader(ServerLevel world, AIPlayerEntity bot, BlockPos center,
                                      int radius, int written, Map<String, Integer> notable) {
        int surfaceY = world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                center.getX(), center.getZ());
        long seed = world.getSeed();
        return "// === MinecraftAi terrain snapshot ===\n"
                + "// bot=" + bot.getGameProfile().name()
                + "  center=" + center.getX() + "," + center.getY() + "," + center.getZ()
                + "  yaw=" + Math.round(bot.getYRot()) + "  pitch=" + Math.round(bot.getXRot()) + "\n"
                + "// dimension=" + world.dimension().identifier()
                + "  seed=" + seed + "  surface_y=" + surfaceY + "\n"
                + "// radius=" + radius + "  notable_blocks=" + written + "  notable_kinds=" + notable + "\n"
                + "// PASTE the block below into the testmod verification fixture's assignCapturedX.\n";
    }

    private static Path writeReport(BlockPos center, String header, String code,
                                    Map<String, Integer> tally) throws IOException {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path reports = (gameDir.getParent() != null ? gameDir.getParent() : gameDir).resolve("reports");
        Files.createDirectories(reports);
        Path file = reports.resolve(String.format("snapshot_%d_%d_%d.txt",
                center.getX(), center.getY(), center.getZ()));
        StringBuilder out = new StringBuilder();
        out.append(header).append('\n');
        out.append("// ---- TALLY ----\n");
        tally.forEach((k, v) -> out.append("//   ").append(k).append(" x").append(v).append('\n'));
        out.append("\n    // ---- SETBLOCK CODE ----\n").append(code);
        Files.writeString(file, out.toString());
        return file;
    }

    private static Optional<AIPlayerEntity> selectBot(CommandSourceStack source) {
        return Optional.ofNullable(source.getPlayer())
                .flatMap(p -> AIPlayerManager.INSTANCE.botOf(p.getUUID()))
                .or(() -> AIPlayerManager.INSTANCE.all().stream().findFirst());
    }
}
