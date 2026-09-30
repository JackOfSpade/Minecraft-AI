package io.github.zoyluo.minecraftai.entity;

import com.mojang.authlib.GameProfile;
import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.EquipAction;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationGate;
import io.github.zoyluo.minecraftai.baritone.BaritoneDriver;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationPolicy;
import io.github.zoyluo.minecraftai.inventory.BotInventoryScreenFactory;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.entity.player.Player;

public class AIPlayerEntity extends ServerPlayer {
    private final ActionPack actionPack = new ActionPack(this);
    private final DamageLogCoalescer damageLog = new DamageLogCoalescer();
    private final java.util.Map<String, Integer> damageSinceSample = new java.util.LinkedHashMap<>();
    // Where this tick began, and whether the fall check of this tick has run (see checkFallDamageOnce).
    private double tickFromX;
    private double tickFromY;
    private double tickFromZ;
    private boolean fallChecked;
    // A teleport moved the bot during this tick: its displacement is not walking (movement exhaustion skips the tick).
    private boolean teleportedThisTick;
    // Nesting of the overridden teleport calls (see auditTeleport).
    private int teleportAuditDepth;

    public AIPlayerEntity(MinecraftServer server,
                          ServerLevel world,
                          GameProfile profile,
                          ClientInformation clientOptions) {
        super(server, world, profile, clientOptions);
    }

    @Override
    public void tick() {
        // A real client resyncs this ~20x/sec via its own movement packets; this bot has no client to
        // send those, so do it every tick here instead of the old 10-tick throttle (0.5s), which was a
        // plausible source of visible movement choppiness with no real cost to justify it (cheap,
        // O(tracked entities) bookkeeping unrelated to pathfinding/mining).
        if (this.connection != null) {
            this.connection.resetPosition();
            this.level().getChunkSource().move(this);
        }

        this.fallChecked = false;
        this.teleportedThisTick = false;
        this.tickFromX = this.getX();
        this.tickFromY = this.getY();
        this.tickFromZ = this.getZ();

        try {
            // While a Baritone process drives this bot it writes the inputs (before the physics tick below, like a client's
            // input handling) and aims; the legacy executor is idle and writes nothing. See BaritoneDriver.
            // (Nothing Baritone-shaped is even loaded until an instance exists: the hooks below start with NavEngineSelector.baritoneActive().)
            boolean baritoneDrives = baritoneBeforePhysics();
            super.tick();
            this.doTick();
            if (baritoneDrives) {
                baritoneAfterPhysics();
                checkFallDamageOnce(); // a no-op when the driver checked
            } else {
                checkFallDamageOnce();
                this.actionPack.onUpdate();
            }
            chargeMovementExhaustion();
            logDamageSummary(damageLog.flushIfIdle(this.tickCount));
        } catch (RuntimeException exception) {
            // Was NullPointerException-only; widened so any unexpected exception here (not just an
            // NPE) is absorbed for this tick instead of crashing the whole server as a "Ticking
            // player" failure. A stuck bot self-recovers via StuckWatcher's own timeout, so this
            // catch intentionally does not reset actionPack state itself.
            BotLog.error(this, "tick_npe_swallowed", exception);
        }
    }

    /** The most a bot legitimately moves horizontally in one tick (sprint-jumping is ~0.35); a longer displacement was not walking. */
    private static final double MAX_WALKED_TICK_DISTANCE = 4.0D;

    /**
     * Movement costs food: a client's move packet ends in { ServerPlayer.checkMovementStatistics}, which awards the movement
     * statistics and charges the exhaustion of sprinting (0.1 per metre), swimming and so on; a bot has no client and so paid nothing
     * for running. This is that packet's tail, with the displacement of the tick. Jumping exhaustion is charged by vanilla itself
     * ({ jumpFromGround}), so it is not repeated. Not on a tick a teleport moved the bot, not while it is a passenger, not while
     * it is dead. Always on: no configuration switches it off.
     */
    private void chargeMovementExhaustion() {
        if (this.teleportedThisTick || this.isPassenger() || !this.isAlive() || this.isRemoved() || this.isSpectator()) {
            return;
        }
        double dx = this.getX() - this.tickFromX;
        double dy = this.getY() - this.tickFromY;
        double dz = this.getZ() - this.tickFromZ;
        if (Math.sqrt(dx * dx + dz * dz) > MAX_WALKED_TICK_DISTANCE) {
            return;
        }
        this.checkMovementStatistics(dx, dy, dz);
    }

    /**
     * The driver hook before the physics tick. Reached only while Baritone is active (initialised and not given up on). A failure
     * that escapes the driver (a class of Baritone that cannot even be loaded on this first driven tick) must not end the bot's tick:
     * it is classified by NavEngineSelector.handleFailure, which retires Baritone for the session when it is a linkage-type
     * failure, and the tick carries on on the legacy path.
     */
    private boolean baritoneBeforePhysics() {
        if (!NavEngineSelector.baritoneActive()) {
            return false;
        }
        try {
            return BaritoneDriver.beforePhysics(this);
        } catch (Throwable failure) {
            NavEngineSelector.handleFailure("baritone_before_physics", failure);
            return false;
        }
    }

    /**
     * The driver hook after the physics tick, for a bot the hook before it reported as driven (same containment). If the hook did not
     * complete (it failed, or the bot was no longer driven) or Baritone was retired by the failure, the legacy executor takes this
     * tick over in the same tick: nobody else would advance its mining, walk or route bookkeeping until the next one.
     */
    private void baritoneAfterPhysics() {
        boolean completed = false;
        if (NavEngineSelector.baritoneActive()) {
            try {
                completed = BaritoneDriver.afterPhysics(this);
            } catch (Throwable failure) {
                NavEngineSelector.handleFailure("baritone_after_physics", failure);
            }
        }
        if (!completed || !NavEngineSelector.baritoneActive()) {
            checkFallDamageOnce();
            this.actionPack.onUpdate();
        }
    }

    /**
     * Fall damage for a bot. In 1.21.11 {@code Entity.move} skips {@code checkFallDamage} for a server-side player (it is not the
     * "local instance authoritative" one and a player is client authoritative): vanilla expects the client's move packet to drive
     * {@code doCheckFallDamage} on the server. A bot has no client, so without this it never accumulated fall distance and never took
     * fall damage, however far it dropped. This is what the move packet handler does: it passes the tick's movement and the ground flag
     * to {@code doCheckFallDamage}, which accumulates the fall distance while airborne and, on landing, applies the vanilla damage of
     * the block landed on (armour, feather falling, honey, hay, water and so on are all vanilla's).
     *
     * <p>Runs at most once per tick: the Baritone driver checks in its own step (before the PlayerUpdate POST event, as the client's
     * packet precedes it) and marks the tick, so a driven bot is never charged twice. Not for a passenger (the vehicle's own physics
     * decide) and not for a dead bot. A teleport (FakePlayerMotion, safety moves, recall, respawn) is not a fall: see {@link #teleportTo}, which clears the fall distance
     * and marks the tick checked.</p>
     */
    private void checkFallDamageOnce() {
        if (this.fallChecked) {
            return;
        }
        this.fallChecked = true;
        if (!this.isAlive() || this.isRemoved() || this.isPassenger()) {
            return;
        }
        this.doCheckFallDamage(this.getX() - this.tickFromX, this.getY() - this.tickFromY, this.getZ() - this.tickFromZ, this.onGround());
    }

    // A teleport is not a fall. Nothing in vanilla's teleport path clears the fall distance (a real player's client resets it with its
    // own movement packet), and a bot now takes vanilla fall damage, so a bot moved while it had fall distance (a safety-net climb,
    // a respawn of a bot killed in mid-air, a panel recall, a dark-trap surfacing) would be charged for the fall it never finished on
    // its next grounded tick, and the jump itself would be measured as a fall of that many blocks. Overridden here, once, for every
    // caller: the fall is over, and this tick's fall check is not to measure the jump.
    // Every override also records the move in TeleportAudit (kind, caller, distance) before it happens: logging and counting only.

    @Override
    public boolean teleportTo(ServerLevel level, double x, double y, double z, java.util.Set<Relative> relatives,
                              float yaw, float pitch, boolean resetCamera) {
        auditTeleport(new net.minecraft.world.phys.Vec3(x, y, z));
        try {
            boolean moved = super.teleportTo(level, x, y, z, relatives, yaw, pitch, resetCamera);
            if (moved) {
                fallEndedByTeleport();
            }
            return moved;
        } finally {
            endAuditedTeleport();
        }
    }

    @Override
    public void teleportTo(double x, double y, double z) {
        auditTeleport(new net.minecraft.world.phys.Vec3(x, y, z));
        try {
            super.teleportTo(x, y, z);
            fallEndedByTeleport();
        } finally {
            endAuditedTeleport();
        }
    }

    @Override
    public ServerPlayer teleport(TeleportTransition transition) {
        auditTeleport(transition == null ? null : transition.position());
        try {
            ServerPlayer moved = super.teleport(transition);
            if (moved != null) {
                fallEndedByTeleport();
            }
            return moved;
        } finally {
            endAuditedTeleport();
        }
    }

    /**
     * Records the outermost teleport call only: vanilla routes {@code teleportTo(level, ...)} through {@code teleport(transition)}, and
     * both are overridden here, so the inner call of one move must not count as a second teleport.
     */
    private void auditTeleport(net.minecraft.world.phys.Vec3 to) {
        if (this.teleportAuditDepth++ > 0) {
            return;
        }
        try {
            TeleportAudit.record(this, this.position(), to);
        } catch (RuntimeException ignored) {
            // An audit line must never stop a teleport.
        }
    }

    private void endAuditedTeleport() {
        this.teleportAuditDepth--;
    }

    private void fallEndedByTeleport() {
        this.resetFallDistance();
        this.fallChecked = true;
        this.teleportedThisTick = true;
    }

    /** The Baritone driver has run this tick's fall check (with its own measured movement); the bot's tick must not run it again. */
    public void markFallChecked() {
        this.fallChecked = true;
    }

    /**
     * Combat logging: without this a fight left no trace beyond the bare {@code diag_health_drop} (no source, no
     * attacker), so a bot that lost or won a fight could not be explained from its log. Logged at the moment the
     * hit is resolved, with the vanilla result (a blocked/shielded/cooldown hit reports {@code applied=false}).
     * Repeats of the same source/attacker within two seconds (fire, lava and drowning damage every few ticks)
     * are coalesced into one summary line with a count, see {@link DamageLogCoalescer}.
     */
    @Override
    public boolean hurtServer(ServerLevel world, DamageSource source, float amount) {
        float before = this.getHealth();
        boolean applied = super.hurtServer(world, source, amount);
        try {
            Entity attacker = source == null ? null : source.getEntity();
            String sourceId = source == null ? "unknown" : source.getMsgId();
            String attackerType = attacker == null ? "-" : attacker.getType().toString();
            int attackerId = attacker == null ? -1 : attacker.getId();
            if (applied) {
                damageSinceSample.merge(attacker == null ? sourceId : sourceId + "/" + attackerType, 1, Integer::sum);
            }
            DamageLogCoalescer.Result result = damageLog.record(
                    sourceId + " attacker=" + attackerType + "#" + attackerId,
                    this.tickCount, amount, applied, before, this.getHealth());
            logDamageSummary(result.flushed());
            if (result.logNow()) {
                BotLog.danger(this, "damage_taken",
                        "source", sourceId,
                        "attacker", attackerType,
                        "attacker_id", attackerId,
                        "amount", amount,
                        "applied", applied,
                        "hp", before + "->" + this.getHealth(),
                        "blocking", this.isBlocking());
            }
            if (this.getHealth() <= 0.0F || !this.isAlive()) {
                // die() has already run inside super.hurtServer. Closes any run left open by hits
                // recorded after die() ran, so it is reported with the death (the fatal hit itself is
                // already logged individually above) instead of by the removal flush long after bot_death.
                logDamageSummary(damageLog.flush());
            }
        } catch (RuntimeException ignored) {
            // Logging must never affect combat resolution.
        }
        return applied;
    }

    private void logDamageSummary(DamageLogCoalescer.Summary summary) {
        if (summary == null) {
            return;
        }
        BotLog.danger(this, "damage_taken_repeated",
                "kind", summary.key(),
                "repeats", summary.repeats(),
                "amount", summary.totalAmount(),
                "applied", summary.applied(),
                "hp", summary.hpFrom() + "->" + summary.hpTo(),
                "span_ticks", summary.lastTick() - summary.firstTick());
    }

    /**
     * What damaged this bot since the previous call, as {@code "source/attacker xN, ..."}, or {@code "none"}.
     * Drained by the diagnostic sampler so a {@code diag_health_drop} names its cause (Minecraft's own
     * last-damage-source expires after 40 ticks, the sampler runs every 40).
     */
    public String drainDamageSinceSample() {
        if (damageSinceSample.isEmpty()) {
            return "none";
        }
        StringBuilder text = new StringBuilder();
        damageSinceSample.forEach((kind, count) -> {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(kind).append(" x").append(count);
        });
        damageSinceSample.clear();
        return text.toString();
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        try {
            logDamageSummary(damageLog.flush());
        } catch (RuntimeException ignored) {
            // Logging must never affect removal.
        }
        EquipAction.forgetArmorLog(this.getUUID());
        super.remove(reason);
    }

    @Override
    public void die(DamageSource source) {
        try {
            logDamageSummary(damageLog.flush());
            Entity attacker = source == null ? null : source.getEntity();
            BotLog.danger(this, "bot_death",
                    "source", source == null ? "unknown" : source.getMsgId(),
                    "attacker", attacker == null ? "-" : attacker.getType().toString(),
                    "attacker_id", attacker == null ? -1 : attacker.getId(),
                    "pos", this.blockPosition().toShortString());
        } catch (RuntimeException ignored) {
            // Logging must never affect death handling.
        }
        super.die(source);
    }

    @Override
    public String getIpAddress() {
        return "127.0.0.1";
    }

    public void reviveForMinecraftAiSpawn() {
        this.unsetRemoved();
    }

    public ActionPack getActionPack() {
        return actionPack;
    }

    /** {@code Entity#getServer()} is gone in 1.21.11 and the base class keeps its server private. */
    public MinecraftServer getServer() {
        return this.level().getServer();
    }

    /**
     * Opens the normal server-authoritative inventory UI for the bot's owner (or an operator).
     * This is deliberately an entity interaction instead of an AI tool so routine hand-offs do
     * not consume an LLM turn.
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (!(player instanceof ServerPlayer viewer)) {
            return InteractionResult.SUCCESS;
        }
        if (!BotAuthorizationGate.INSTANCE.authorize(viewer, this,
                BotAuthorizationPolicy.Operation.INVENTORY, "entity_inventory_screen")) {
            return InteractionResult.FAIL;
        }
        viewer.openMenu(new BotInventoryScreenFactory(this, viewer));
        return InteractionResult.SUCCESS;
    }
}
