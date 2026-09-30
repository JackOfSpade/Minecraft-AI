package dev.spawnbotswrapper.inhabitants.gametest;

import dev.spawnbotswrapper.inhabitants.InhabitantsMod;
import dev.spawnbotswrapper.inhabitants.command.CommandServices;
import dev.spawnbotswrapper.inhabitants.engine.EngineControl;
import dev.spawnbotswrapper.inhabitants.engine.ForceMode;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The scene every ranged-combat GameTest uses: a stone platform, one REAL inhabitant (asked from the population
 * engine like a structure population would, so PvP BOT spawns it through the same path as in game) dressed with a
 * chosen loadout, and one survival player that is not a PvP BOT bot.
 */
final class Rig {
    static final Logger LOG = LoggerFactory.getLogger("harness");
    /** Relative height of the fixture platform. */
    static final int LAYER_Y = 100;

    final GameTestHelper ctx;
    final MinecraftServer server;
    final ServerLevel level;
    /** Feet cell of the bot (absolute). */
    final BlockPos botFeet;
    ServerPlayer bot;
    ServerPlayer target;
    String botName;
    private boolean requested;
    private boolean cleaned;
    private final java.util.List<Runnable> cleanups = new java.util.ArrayList<>();
    /** Unique per scene: the population store remembers a structure key for good, so a reused key would be refused. */
    private final String structureId = "gametest:arena_" + Long.toHexString(System.nanoTime());

    Rig(GameTestHelper ctx) {
        this.ctx = ctx;
        this.level = ctx.getLevel();
        this.server = level.getServer();
        this.botFeet = ctx.absolutePos(new BlockPos(4, LAYER_Y, 4));
    }

    void fail(String message) {
        cleanup();
        ctx.fail(Component.nullToEmpty(message));
        throw new IllegalStateException(message);
    }

    /** Something to undo when the scene ends (settings a test changed for the whole server). */
    void onCleanup(Runnable undo) {
        cleanups.add(undo);
    }

    void succeed() {
        cleanup();
        ctx.succeed();
    }

    /**
     * The inhabitant and the mock player live outside the test structure, so the framework does not remove them: the next
     * test at the same place would meet them. The engine forgets the structure and removes its bots through PvP BOT.
     */
    void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        for (Runnable r : cleanups) {
            try {
                r.run();
            } catch (RuntimeException e) {
                LOG.warn("cleanup step failed: {}", e.toString());
            }
        }
        try {
            CommandServices services = InhabitantsMod.servicesOf(server);
            if (services != null && requested) {
                services.engine().reset(key(), true);
            }
        } catch (RuntimeException e) {
            LOG.warn("cleanup of the inhabitant failed: {}", e.toString());
        }
        try {
            if (target != null) {
                server.getPlayerList().remove(target);
            }
        } catch (RuntimeException e) {
            LOG.warn("cleanup of the mock player failed: {}", e.toString());
        }
    }

    /** A 25x25 stone floor with a high air ceiling around the bot's cell. */
    void buildPlatform() {
        buildPlatform(12);
    }

    /** A stone floor {@code radius} blocks to every side of the bot's cell with a high air ceiling. */
    void buildPlatform(int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                level.setBlock(botFeet.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 6; dy++) {
                    level.setBlock(botFeet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    /** A survival mock player (takes damage, is not a bot) standing {@code dx} blocks east of the bot's cell. */
    void createTarget(double dx) {
        target = ctx.makeMockServerPlayerInLevel();
        target.setGameMode(GameType.SURVIVAL);
        if (!target.connection.hasClientLoaded()) {
            target.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        }
        target.setHealth(target.getMaxHealth());
        placeTarget(dx);
    }

    void placeTarget(double dx) {
        Vec3 spot = Vec3.atBottomCenterOf(botFeet).add(dx, 0, 0);
        target.teleportTo(level, spot.x, spot.y, spot.z, Set.of(), 90.0F, 0.0F, true);
    }

    private StructureKey key() {
        return new StructureKey(level.dimension().identifier().toString(), structureId,
                botFeet.getX() >> 4, botFeet.getZ() >> 4);
    }

    /** Asks the population engine for one inhabitant standing exactly in the bot's cell; false while the session is not up. */
    boolean requestInhabitant() {
        if (requested) {
            return true;
        }
        CommandServices services = InhabitantsMod.servicesOf(server);
        if (services == null) {
            return false;
        }
        IntBox cell = new IntBox(botFeet.getX(), botFeet.getY(), botFeet.getZ(), botFeet.getX(), botFeet.getY(), botFeet.getZ());
        EngineControl.ProcessOutcome outcome = services.engine().process(
                new StructureSnapshot(key(), Set.of(), cell, List.of(), true), ForceMode.OCCUPIED);
        if (outcome.kind() != EngineControl.ProcessOutcome.Kind.OCCUPIED_QUEUED) {
            fail("the engine did not queue the inhabitant: " + outcome);
        }
        requested = true;
        return true;
    }

    /** True once the requested inhabitant is online and recorded SPAWNED; sets {@link #bot}. */
    boolean inhabitantReady() {
        CommandServices services = InhabitantsMod.servicesOf(server);
        if (services == null) {
            return false;
        }
        Optional<StructureRecord> record = services.population().find(key());
        if (record.isEmpty() || record.get().bots.isEmpty()) {
            return false;
        }
        BotRecord planned = record.get().bots.get(0);
        if (planned.state == BotState.FAILED) {
            fail("the inhabitant could not be spawned: " + planned.failure);
        }
        if (planned.state != BotState.SPAWNED) {
            return false;
        }
        ServerPlayer entity = server.getPlayerList().getPlayerByName(planned.name);
        if (entity == null) {
            return false;
        }
        bot = entity;
        // PvP BOT switches a new bot to survival with a delayed command of its own, and the GameTest server defaults to
        // creative: a bot that is looked at before that command ran refuses all damage. A survival server has no such window.
        if (bot.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) {
            bot.setGameMode(GameType.SURVIVAL);
        }
        botName = planned.name;
        return true;
    }

    // ------------------------------------------------------------------ loadouts

    private Holder<Enchantment> enchantment(ResourceKey<Enchantment> key) {
        return level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
    }

    /** The Skirmisher-like loadout of the user's log: sword 0, shield 1, crossbow (quick charge 3, piercing 1) 2, arrows, totem. */
    void dressSkirmisher() {
        Inventory inv = bot.getInventory();
        inv.clearContent();
        inv.setItem(0, new ItemStack(Items.NETHERITE_SWORD));
        inv.setItem(1, new ItemStack(Items.SHIELD));
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        crossbow.enchant(enchantment(Enchantments.QUICK_CHARGE), 3);
        crossbow.enchant(enchantment(Enchantments.PIERCING), 1);
        inv.setItem(2, crossbow);
        inv.setItem(3, new ItemStack(Items.ARROW, 64));
        inv.setItem(40, new ItemStack(Items.TOTEM_OF_UNDYING));
        inv.setSelectedSlot(0);
        finishDressing();
    }

    /** An iron sword and nothing else: a pure melee bot. */
    void dressMeleeOnly() {
        Inventory inv = bot.getInventory();
        inv.clearContent();
        inv.setItem(0, new ItemStack(Items.IRON_SWORD));
        inv.setSelectedSlot(0);
        finishDressing();
    }

    /** A bow, arrows and nothing else. */
    void dressBowOnly() {
        Inventory inv = bot.getInventory();
        inv.clearContent();
        inv.setItem(0, new ItemStack(Items.BOW));
        inv.setItem(1, new ItemStack(Items.ARROW, 64));
        inv.setSelectedSlot(0);
        finishDressing();
    }

    /** A bow, arrows and a sword: the melee weapon is what PvP BOT's weapon auto-equip keeps selecting instead of the bow. */
    void dressBowAndSword() {
        Inventory inv = bot.getInventory();
        inv.clearContent();
        inv.setItem(0, new ItemStack(Items.IRON_SWORD));
        inv.setItem(1, new ItemStack(Items.BOW));
        inv.setItem(2, new ItemStack(Items.ARROW, 64));
        inv.setSelectedSlot(0);
        finishDressing();
    }

    private void finishDressing() {
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
    }

    /** Which loadout {@link #awaitDressed} gives the inhabitant. */
    enum Loadout {
        SKIRMISHER,
        BOW_ONLY,
        MELEE_ONLY,
        BOW_AND_SWORD
    }

    /**
     * Drives the setup shared by the tests: requests the inhabitant, waits until it exists, dresses it once. Returns true
     * from the tick after the dressing on (the caller then observes). {@code dressed}/{@code dressedAt} are the caller's state.
     */
    boolean awaitDressed(boolean[] dressed, long[] dressedAt, Loadout loadout, String tag) {
        if (!requestInhabitant()) {
            return false;
        }
        if (dressed[0]) {
            return true;
        }
        if (!inhabitantReady()) {
            if (ctx.getTick() > 200) {
                fail("the inhabitant never appeared");
            }
            return false;
        }
        switch (loadout) {
            case SKIRMISHER -> dressSkirmisher();
            case BOW_ONLY -> dressBowOnly();
            case MELEE_ONLY -> dressMeleeOnly();
            case BOW_AND_SWORD -> dressBowAndSword();
        }
        dressed[0] = true;
        dressedAt[0] = ctx.getTick();
        LOG.info("[{}] dressed {} at test tick {}; PvP BOT settings: autoEquipWeapon={} autoTarget={} maxTarget={} ranged={}/{}/{}",
                tag, botName, dressedAt[0], Upstream.setting("isAutoEquipWeapon"), Upstream.setting("isAutoTargetEnabled"),
                Upstream.setting("getMaxTargetDistance"), Upstream.setting("getRangedMinRange"),
                Upstream.setting("getRangedOptimalRange"), Upstream.setting("getRangedMaxRange"));
        return false;
    }

    /** Logs {@link #trace} once every {@code every} ticks of the observation. */
    void traceEvery(int every, long sinceDressing, String tag) {
        if (sinceDressing % every == 0) {
            LOG.info("[{}] {}", tag, trace());
        }
    }

    /**
     * One try at hitting the inhabitant like the target player would (a melee damage source). A fresh bot refuses damage for a
     * while (a joining client that has not finished loading is protected), so the caller repeats this every tick until it
     * answers true.
     */
    boolean tryHit(float damage) {
        if (!bot.connection.hasClientLoaded()) {
            // Left alone the protection lifts after a while, but not always within a test's window; tell the listener the
            // (imaginary) client has loaded, exactly as MockPlayers does for the player.
            bot.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        }
        net.minecraft.world.damagesource.DamageSource source = level.damageSources().playerAttack(target);
        boolean applied = bot.hurtServer(level, source, damage);
        if (!applied && ctx.getTick() % 50 == 0) {
            LOG.info("[hit-refused] t={} invulnerableTo={} invulnerableTime={} loaded={} gameMode={} health={} removed={} dead={}",
                    level.getGameTime(), bot.isInvulnerableTo(level, source), bot.invulnerableTime,
                    bot.connection.hasClientLoaded(), bot.gameMode.getGameModeForPlayer(), bot.getHealth(), bot.isRemoved(),
                    bot.isDeadOrDying());
        }
        return applied;
    }

    /** One line of the bot's state for the run log. */
    String trace() {
        Inventory inv = bot.getInventory();
        ItemStack main = bot.getMainHandItem();
        return "t=" + level.getGameTime() + " slot=" + inv.getSelectedSlot() + " main=" + main.getItem()
                + " using=" + bot.isUsingItem() + "/" + bot.getTicksUsingItem() + " dist="
                + String.format(Locale.ROOT, "%.1f", bot.distanceTo(target)) + " hp=" + bot.getHealth()
                + " shots=" + HarnessMod.shotsBy(bot.getUUID()).size() + " target=" + (Upstream.target(botName).equals("none") ? "none" : "set")
                + " " + Upstream.combatState(botName);
    }
}
