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
import net.minecraft.world.level.gameevent.GameEvent;
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
        removeStaleBots();
    }

    /**
     * Removes bots that an earlier test left online. The engine removes a finished test's bot a few ticks later, and the next
     * test starts at the same place at once: the leftover then fights the new inhabitant (it did: a chase of "the player"
     * that was a leftover bot, a kill the test never asked for).
     */
    private void removeStaleBots() {
        try {
            CommandServices services = InhabitantsMod.servicesOf(server);
            if (services == null || services.adapter() == null) {
                return;
            }
            int mocks = 0;
            for (ServerPlayer p : new java.util.ArrayList<>(server.getPlayerList().getPlayers())) {
                if (services.adapter().isBotEntity(p)) {
                    // PvP BOT no longer lists a bot that is being removed, yet it stays in the world for a while (it "falls out of
                    // the world"): to the next inhabitant it is an ordinary player in plain view. Take it out at once.
                    services.adapter().removeBot(server, p.getName().getString());
                    server.getPlayerList().remove(p);
                    p.discard();
                    mocks++;
                } else if (p.getName().getString().equals("test-mock-player")) {
                    // a mock player an earlier test left standing in the same arena: to a bot it is a player in plain view
                    server.getPlayerList().remove(p);
                    p.discard();
                    mocks++;
                }
            }
            if (mocks > 0) {
                LOG.info("removed {} stale mock player(s) of earlier tests", mocks);
            }
        } catch (RuntimeException e) {
            LOG.warn("removing stale bots failed: {}", e.toString());
        }
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
            // The engine removes the bot a few ticks later; the next test starts at the same place at once.
            if (services != null && services.adapter() != null && botName != null) {
                services.adapter().removeBot(server, botName);
                if (bot != null) {
                    server.getPlayerList().remove(bot);
                    bot.discard();
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("cleanup of the inhabitant failed: {}", e.toString());
        }
        try {
            if (target != null) {
                server.getPlayerList().remove(target);
                target.discard();
            }
        } catch (RuntimeException e) {
            LOG.warn("cleanup of the mock player failed: {}", e.toString());
        }
    }

    /** A 25x25 stone floor with a high air ceiling around the bot's cell. */
    void buildPlatform() {
        buildPlatform(12);
    }

    /** Removes hostile mobs that already stand around the scene (natural spawns would fight the inhabitant), and the arrows and
     * dropped items an earlier test left there. */
    void clearHostiles() {
        net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(botFeet).inflate(60.0, 20.0, 60.0);
        for (net.minecraft.world.entity.Entity e : level.getEntities((net.minecraft.world.entity.Entity) null, area,
                x -> x instanceof net.minecraft.world.entity.monster.Enemy)) {
            e.discard();
        }
        // Arrows and dropped items of an earlier test lie in the same arena: a bot picks them up (a quiver that grew by itself).
        for (net.minecraft.world.entity.Entity e : level.getEntities((net.minecraft.world.entity.Entity) null, area,
                x -> x instanceof net.minecraft.world.entity.projectile.Projectile
                        || x instanceof net.minecraft.world.entity.item.ItemEntity)) {
            e.discard();
        }
    }

    /** A stone floor {@code radius} blocks to every side of the bot's cell with a high air ceiling. */
    void buildPlatform(int radius) {
        clearHostiles();
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

    // ------------------------------------------------------------------ aggro scenes

    /** The absolute cell {@code dx, dy, dz} from the bot's feet cell. */
    BlockPos rel(int dx, int dy, int dz) {
        return botFeet.offset(dx, dy, dz);
    }

    /** Fills a box (inclusive, relative to the bot's feet cell) with a block; no neighbour updates, so it is cheap. */
    void fill(int x0, int y0, int z0, int x1, int y1, int z1, Block block) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    level.setBlock(rel(x, y, z), block.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /** A floor strip 7 wide, from 3 blocks west of the bot to {@code length + 3} blocks east, with a high air ceiling. */
    void buildStrip(int length) {
        clearHostiles();
        fill(-3, -1, -3, length + 3, -1, 3, Blocks.STONE);
        fill(-3, 0, -3, length + 3, 6, 3, Blocks.AIR);
    }

    /** Puts the target at an absolute position, standing still there (its old position is the new one). */
    void placeTargetAt(double x, double y, double z) {
        target.teleportTo(level, x, y, z, Set.of(), target.getYRot(), 0.0F, true);
        target.setOldPosAndRot();
    }

    /** Moves the target to an absolute position as a step of a walk: the old position is where it was, so it counts as moving. */
    void walkTargetTo(double x, double y, double z) {
        target.setOldPosAndRot();
        target.setPos(x, y, z);
    }

    /** The target crouches (or stands up), the way a client's shift key does. */
    void setSneaking(boolean sneaking) {
        target.setShiftKeyDown(sneaking);
        target.setLastClientInput(new net.minecraft.world.entity.player.Input(false, false, false, false, false, sneaking, false));
    }

    /**
     * The player takes a step: vanilla's STEP game event at its position with itself as the source entity, exactly what a
     * walking player emits every few ticks. Everything after this is vanilla (the dispatcher, the sneaking and wool rules,
     * the listener radius, the travel delay) and the wrapper's listener; a mock player does not walk by itself.
     */
    void emitStep() {
        level.gameEvent(target, GameEvent.STEP, target.position());
    }

    /** The horizontal component of the inhabitant's look direction along x (1 = east, -1 = west). */
    double lookX() {
        return bot.getViewVector(1.0F).x;
    }

    /** Turns the inhabitant to face a horizontal direction (dx, dz), level with the eyes. */
    void faceDirection(double dx, double dz) {
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        bot.setYRot(yaw);
        bot.yRotO = yaw;
        bot.setYHeadRot(yaw);
        bot.yHeadRotO = yaw;
        bot.setXRot(0.0F);
        bot.xRotO = 0.0F;
    }

    /** Where the inhabitant is in the aggro hunt (IDLE, CHASE, PURSUE, SEARCH, RETURN). */
    String phase() {
        return InhabitantsMod.aggroPhaseOf(botName);
    }

    /** True when PvP BOT currently has a target for the inhabitant. */
    boolean hasTarget() {
        return !Upstream.target(botName).equals("none");
    }

    /** Horizontal distance from the inhabitant to a point. */
    double horizontalTo(double x, double z) {
        return Math.hypot(bot.getX() - x, bot.getZ() - z);
    }

    /** The centre of the bot's feet cell (its start point), x. */
    double homeX() {
        return botFeet.getX() + 0.5;
    }

    /** The centre of the bot's feet cell (its start point), z. */
    double homeZ() {
        return botFeet.getZ() + 0.5;
    }

    /** Keeps the target player alive (a test that needs many shots must not end because the player died). */
    void keepTargetAlive() {
        if (target != null && target.getHealth() < target.getMaxHealth()) {
            target.setHealth(target.getMaxHealth());
        }
    }

    void placeTarget(double dx) {
        Vec3 spot = Vec3.atBottomCenterOf(botFeet).add(dx, 0, 0);
        target.teleportTo(level, spot.x, spot.y, spot.z, Set.of(), 90.0F, 0.0F, true);
    }

    /** Puts the player {@code dx} blocks east of where the bot stands NOW (an archer backs away from a close player, so the cell moves). */
    void placeTargetBesideBot(double dx) {
        Vec3 spot = bot.position().add(dx, 0, 0);
        target.teleportTo(level, spot.x, spot.y, spot.z, Set.of(), 90.0F, 0.0F, true);
    }

    StructureKey key() {
        return new StructureKey(level.dimension().identifier().toString(), structureId,
                botFeet.getX() >> 4, botFeet.getZ() >> 4);
    }

    /** The unique id of this scene's structure (what the population store remembers it by). */
    String structureId() {
        return structureId;
    }

    /** The structure as the engine sees it: the same snapshot the scene's inhabitant is requested with. */
    StructureSnapshot arena() {
        IntBox cell = new IntBox(botFeet.getX(), botFeet.getY(), botFeet.getZ(), botFeet.getX(), botFeet.getY(), botFeet.getZ());
        return new StructureSnapshot(key(), Set.of(), cell, List.of(), true);
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
        return inhabitantReady(true);
    }

    /** As above; with {@code forceSurvival} false the bot is left exactly as the wrapper made it, so a test can judge its game mode. */
    boolean inhabitantReady(boolean forceSurvival) {
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
        if (forceSurvival && bot.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) {
            bot.setGameMode(GameType.SURVIVAL);
        }
        botName = planned.name;
        return true;
    }

    /**
     * A bow and a full quiver, an iron chestplate and boots that are already worn down, and nothing to eat: what an archer
     * looks like that has been in fights. Hurt on purpose (health 13, food 14: below the level at which vanilla heals), so
     * nothing but the wrapper's own restore can put the numbers back.
     */
    void dressWornArcher() {
        Inventory inv = bot.getInventory();
        inv.clearContent();
        inv.setItem(0, new ItemStack(Items.BOW));
        inv.setItem(1, new ItemStack(Items.ARROW, 64));
        ItemStack chest = new ItemStack(Items.IRON_CHESTPLATE);
        chest.setDamageValue(37);
        inv.setItem(38, chest);
        ItemStack boots = new ItemStack(Items.IRON_BOOTS);
        boots.setDamageValue(12);
        inv.setItem(36, boots);
        inv.setSelectedSlot(0);
        bot.setHealth(13.0F);
        bot.getFoodData().setFoodLevel(14);
    }

    // ------------------------------------------------------------------ loadouts

    private Holder<Enchantment> enchantment(ResourceKey<Enchantment> key) {
        return level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
    }

    /** The Skirmisher-like loadout of the user's log: sword 0, shield 1, crossbow (quick charge 3, piercing 1) 2, arrows, totem. */
    void dressSkirmisher() {
        dressSkirmisher(true);
    }

    /**
     * The Skirmisher without its sword: a crossbow bot with nothing to melee with, which (unlike the sword carrier) keeps
     * its crossbow in hand however close the player stands.
     */
    void dressCrossbowShield() {
        dressSkirmisher(false);
    }

    private void dressSkirmisher(boolean sword) {
        Inventory inv = bot.getInventory();
        inv.clearContent();
        if (sword) {
            inv.setItem(0, new ItemStack(Items.NETHERITE_SWORD));
        }
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

    /** What {@link #dressHybrid} gives the bot (set before {@link #awaitDressed} with {@link Loadout#HYBRID}). */
    int hybridArrows;
    boolean hybridCrossbow = true;
    boolean hybridLoaded;

    /**
     * A sword in slot 0, a crossbow (or a bow) in slot 1 and {@link #hybridArrows} arrows in slot 2; the crossbow starts
     * with a bolt in it when {@link #hybridLoaded}. The bot that ran out of ammunition (0 arrows) is the subject of the
     * out-of-ammo tests.
     */
    void dressHybrid() {
        Inventory inv = bot.getInventory();
        inv.clearContent();
        inv.setItem(0, new ItemStack(Items.IRON_SWORD));
        ItemStack ranged = new ItemStack(hybridCrossbow ? Items.CROSSBOW : Items.BOW);
        if (hybridCrossbow && hybridLoaded) {
            ranged.set(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES,
                    net.minecraft.world.item.component.ChargedProjectiles.of(new ItemStack(Items.ARROW)));
        }
        inv.setItem(1, ranged);
        if (hybridArrows > 0) {
            inv.setItem(2, new ItemStack(Items.ARROW, hybridArrows));
        }
        inv.setSelectedSlot(0);
        finishDressing();
    }

    /** Arrows of any kind in slots 0-35 plus a charged crossbow in the hotbar or hands: what the bot can still shoot. */
    int ammunitionLeft() {
        Inventory inv = bot.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof net.minecraft.world.item.ArrowItem) {
                n += s.getCount();
            }
            if (net.minecraft.world.item.CrossbowItem.isCharged(s)) {
                n++;
            }
        }
        return n + (net.minecraft.world.item.CrossbowItem.isCharged(bot.getOffhandItem()) ? 1 : 0);
    }

    private void finishDressing() {
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
    }

    /**
     * Turns the inhabitant (body and head) toward the test target, once, when it is dressed. A spawned inhabitant looks
     * wherever the spawn happened to leave it, and the aggro controller only notices a still player inside the bot's view
     * cone (the Perception model), so a test that waits for the bot to notice a player standing in the open would fail
     * whenever the spawn looked away. Tests that place their player elsewhere later pin the facing themselves.
     */
    void faceTarget() {
        if (bot == null || target == null) {
            return;
        }
        double dx = target.getX() - bot.getX();
        double dz = target.getZ() - bot.getZ();
        if (dx * dx + dz * dz < 1.0e-6) {
            return;
        }
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        bot.setYRot(yaw);
        bot.setYHeadRot(yaw);
        bot.setXRot(0.0F);
        bot.yRotO = yaw;
        bot.xRotO = 0.0F;
    }

    /** Which loadout {@link #awaitDressed} gives the inhabitant. */
    enum Loadout {
        SKIRMISHER,
        CROSSBOW_SHIELD,
        BOW_ONLY,
        MELEE_ONLY,
        BOW_AND_SWORD,
        HYBRID
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
            case CROSSBOW_SHIELD -> dressCrossbowShield();
            case BOW_ONLY -> dressBowOnly();
            case MELEE_ONLY -> dressMeleeOnly();
            case BOW_AND_SWORD -> dressBowAndSword();
            case HYBRID -> dressHybrid();
        }
        faceTarget();
        dressed[0] = true;
        dressedAt[0] = ctx.getTick();
        LOG.info("[{}] dressed {} at test tick {}; PvP BOT settings: autoEquipWeapon={} autoTarget={} maxTarget={} ranged={}/{}/{} melee={} retreatOnClose={}",
                tag, botName, dressedAt[0], Upstream.setting("isAutoEquipWeapon"), Upstream.setting("isAutoTargetEnabled"),
                Upstream.setting("getMaxTargetDistance"), Upstream.setting("getRangedMinRange"),
                Upstream.setting("getRangedOptimalRange"), Upstream.setting("getRangedMaxRange"), Upstream.setting("getMeleeRange"),
                Upstream.setting("isRangedRetreatOnClose"));
        return false;
    }

    /**
     * Forces the target player on the inhabitant through the same PvP BOT call the aggro controller uses. For tests whose
     * subject needs a bot that HAS a target it cannot see (PvP BOT's own auto-target is managed OFF, and the aggro
     * controller only acquires what the bot can see).
     */
    void forceTarget() {
        CommandServices services = InhabitantsMod.servicesOf(server);
        if (services != null && services.adapter() != null && target != null) {
            services.adapter().targetControl().setTarget(botName, target.getName().getString());
        }
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
    /** What the aggro perception reads off the test player: stance, movement and noise, for failure messages. */
    String aggroSubjectTrace() {
        return "player[shift=" + target.isShiftKeyDown() + " discrete=" + target.isDiscrete() + " crouching=" + target.isCrouching()
                + " sprint=" + target.isSprinting() + " swinging=" + target.swinging + " hurtTime=" + target.hurtTime
                + " using=" + target.isUsingItem() + " moved=" + String.format(Locale.ROOT, "%.3f",
                Math.hypot(target.getX() - target.xo, target.getZ() - target.zo)) + " gameMode="
                + target.gameMode.getGameModeForPlayer() + "] status=" + InhabitantsMod.aggroDescribe(botName);
    }

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
