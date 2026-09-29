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
import net.minecraft.world.entity.player.Player;

public class AIPlayerEntity extends ServerPlayer {
    private final ActionPack actionPack = new ActionPack(this);
    private final DamageLogCoalescer damageLog = new DamageLogCoalescer();
    private final java.util.Map<String, Integer> damageSinceSample = new java.util.LinkedHashMap<>();

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

        try {
            // While a Baritone process drives this bot it writes the inputs (before the physics tick below, like a client's
            // input handling) and aims; the legacy executor is idle and writes nothing. See BaritoneDriver.
            // (Nothing Baritone-shaped is even loaded until an instance exists: NavEngineSelector.baritoneLive().)
            boolean baritoneDrives = NavEngineSelector.baritoneLive() && BaritoneDriver.beforePhysics(this);
            super.tick();
            this.doTick();
            if (baritoneDrives) {
                BaritoneDriver.afterPhysics(this);
            } else {
                this.actionPack.onUpdate();
            }
            logDamageSummary(damageLog.flushIfIdle(this.tickCount));
        } catch (RuntimeException exception) {
            // Was NullPointerException-only; widened so any unexpected exception here (not just an
            // NPE) is absorbed for this tick instead of crashing the whole server as a "Ticking
            // player" failure. A stuck bot self-recovers via StuckWatcher's own timeout, so this
            // catch intentionally does not reset actionPack state itself.
            BotLog.error(this, "tick_npe_swallowed", exception);
        }
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
