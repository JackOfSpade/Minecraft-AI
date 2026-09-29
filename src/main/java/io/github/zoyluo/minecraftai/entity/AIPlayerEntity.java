package io.github.zoyluo.minecraftai.entity;

import com.mojang.authlib.GameProfile;
import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationGate;
import io.github.zoyluo.minecraftai.auth.BotAuthorizationPolicy;
import io.github.zoyluo.minecraftai.inventory.BotInventoryScreenFactory;
import io.github.zoyluo.minecraftai.log.BotLog;
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
            super.tick();
            this.doTick();
            this.actionPack.onUpdate();
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
     */
    @Override
    public boolean hurtServer(ServerLevel world, DamageSource source, float amount) {
        float before = this.getHealth();
        boolean applied = super.hurtServer(world, source, amount);
        try {
            Entity attacker = source == null ? null : source.getEntity();
            BotLog.danger(this, "damage_taken",
                    "source", source == null ? "unknown" : source.getMsgId(),
                    "attacker", attacker == null ? "-" : attacker.getType().toString(),
                    "attacker_id", attacker == null ? -1 : attacker.getId(),
                    "amount", amount,
                    "applied", applied,
                    "hp", before + "->" + this.getHealth(),
                    "blocking", this.isBlocking());
        } catch (RuntimeException ignored) {
            // Logging must never affect combat resolution.
        }
        return applied;
    }

    @Override
    public void die(DamageSource source) {
        try {
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
