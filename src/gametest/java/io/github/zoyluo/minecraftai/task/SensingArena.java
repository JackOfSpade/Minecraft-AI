package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.log.BotLogWriter;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Fixture pieces shared by the sensing-arena GameTests ({@code MiningAssistSenseGameTests} and the OreDig /
 * LegChooser suites of this package): the sealed stone {@link Room} and the per-bot structured-log readers.
 * Each of those files used to carry its own byte-identical copy; a caller now imports {@code SensingArena.Room}
 * and statically imports {@link #botLog}/{@link #hasSpawnLine}, so it keeps referring to them by the bare names.
 */
public final class SensingArena {
    private static final int SHELL = 3;
    private static final BlockState STONE = Blocks.STONE.getDefaultState();
    private static final BlockState AIR = Blocks.AIR.getDefaultState();

    private SensingArena() {
    }

    /** The bot's own structured-log lines, or null when the writer or the file is unavailable. */
    public static List<String> botLog(String botName) {
        try {
            Path base = BotLogWriter.INSTANCE.baseDir();
            if (base == null) {
                return null;
            }
            Path file = base.resolve("by-bot").resolve(botName.replaceAll("[^a-zA-Z0-9_.-]", "_") + ".log");
            if (!Files.isRegularFile(file)) {
                return null;
            }
            String needle = " bot=" + botName + " ";
            try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
                return lines.filter(line -> line.contains(needle)).toList();
            }
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    /** The log covers this bot's whole life only if its spawn line is in it (the file can rotate under a long run). */
    public static boolean hasSpawnLine(List<String> lines) {
        return lines.stream().anyMatch(line -> line.contains("event=bot_spawned"));
    }

    /** A sealed stone box with an air interior. {@code feet} is the interior origin (the bot's feet cell). */
    public static final class Room {
        public final ServerWorld world;
        public final BlockPos feet;
        private final int minDx;
        private final int maxDx;
        private final int minDz;
        private final int maxDz;
        private final int height;
        private final int shellH;

        public Room(TestContext context, int relY, int minDx, int maxDx, int minDz, int maxDz, int height) {
            this(context, relY, minDx, maxDx, minDz, maxDz, height, SHELL);
        }

        /** {@code shellH} is the horizontal thickness of the stone around the interior (the vertical one is {@code SHELL}). */
        public Room(TestContext context, int relY, int minDx, int maxDx, int minDz, int maxDz, int height, int shellH) {
            this.world = context.getWorld();
            this.feet = context.getAbsolutePos(new BlockPos(3, relY, 3)).toImmutable();
            this.minDx = minDx;
            this.maxDx = maxDx;
            this.minDz = minDz;
            this.maxDz = maxDz;
            this.height = height;
            this.shellH = shellH;
            fill(minDx - shellH, -SHELL, minDz - shellH, maxDx + shellH, height - 1 + SHELL, maxDz + shellH, STONE);
            fill(minDx, 0, minDz, maxDx, height - 1, maxDz, AIR);
            discardEntities();
        }

        /**
         * Hands the space back as air. Every GameTest of these suites shares one set of absolute coordinates with the
         * others, so a stone shell left standing would enclose whatever a later test builds at the same place.
         */
        public void clear() {
            fill(minDx - shellH, -SHELL, minDz - shellH, maxDx + shellH, height - 1 + SHELL, maxDz + shellH, AIR);
            discardEntities();
        }

        private void discardEntities() {
            BlockPos low = at(minDx - shellH, -SHELL, minDz - shellH);
            BlockPos high = at(maxDx + shellH + 1, height + SHELL, maxDz + shellH + 1);
            Box box = new Box(low.getX(), low.getY(), low.getZ(), high.getX(), high.getY(), high.getZ());
            for (Entity entity : world.getEntitiesByClass(Entity.class, box, e -> !(e instanceof PlayerEntity))) {
                entity.discard();
            }
        }

        public BlockPos at(int dx, int dy, int dz) {
            return feet.add(dx, dy, dz);
        }

        public void set(int dx, int dy, int dz, Block block) {
            world.setBlockState(at(dx, dy, dz), block.getDefaultState(), Block.NOTIFY_ALL);
        }

        /** Seals the whole cross-section (interior and shell) at this X plane: a walk-only route cannot pass it. */
        public void wall(int dx) {
            for (int y = -1; y <= height; y++) {
                for (int z = minDz - shellH; z <= maxDz + shellH; z++) {
                    world.setBlockState(at(dx, y, z), STONE, Block.NOTIFY_ALL);
                }
            }
        }

        /** True when {@code pos} is inside this room's own carved-out interior (not its shell). */
        public boolean contains(BlockPos pos) {
            int dx = pos.getX() - feet.getX();
            int dy = pos.getY() - feet.getY();
            int dz = pos.getZ() - feet.getZ();
            return dx >= minDx && dx <= maxDx && dy >= 0 && dy <= height - 1 && dz >= minDz && dz <= maxDz;
        }

        private void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        world.setBlockState(at(x, y, z), state, Block.NOTIFY_ALL);
                    }
                }
            }
        }
    }
}
