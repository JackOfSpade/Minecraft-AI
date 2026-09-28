package io.github.zoyluo.aibot.entity;

import com.mojang.authlib.GameProfile;
import io.github.zoyluo.aibot.action.ActionPack;
import io.github.zoyluo.aibot.auth.BotAuthorizationGate;
import io.github.zoyluo.aibot.auth.BotAuthorizationPolicy;
import io.github.zoyluo.aibot.inventory.BotInventoryScreenFactory;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;

public class AIPlayerEntity extends ServerPlayerEntity {
    private final ActionPack actionPack = new ActionPack(this);

    public AIPlayerEntity(MinecraftServer server,
                          ServerWorld world,
                          GameProfile profile,
                          SyncedClientOptions clientOptions) {
        super(server, world, profile, clientOptions);
    }

    @Override
    public void tick() {
        // A real client resyncs this ~20x/sec via its own movement packets; this bot has no client to
        // send those, so do it every tick here instead of the old 10-tick throttle (0.5s), which was a
        // plausible source of visible movement choppiness with no real cost to justify it (cheap,
        // O(tracked entities) bookkeeping unrelated to pathfinding/mining).
        if (this.networkHandler != null) {
            this.networkHandler.syncWithPlayerPosition();
            this.getEntityWorld().getChunkManager().updatePosition(this);
        }

        try {
            super.tick();
            this.playerTick();
            this.actionPack.onUpdate();
        } catch (NullPointerException exception) {
            BotLog.error(this, "tick_npe_swallowed", exception);
        }
    }

    @Override
    public String getIp() {
        return "127.0.0.1";
    }

    public void reviveForAIBotSpawn() {
        this.unsetRemoved();
    }

    public ActionPack getActionPack() {
        return actionPack;
    }

    /** {@code Entity#getServer()} is gone in 1.21.11 and the base class keeps its server private. */
    public MinecraftServer getServer() {
        return this.getEntityWorld().getServer();
    }

    /**
     * Opens the normal server-authoritative inventory UI for the bot's owner (or an operator).
     * This is deliberately an entity interaction instead of an AI tool so routine hand-offs do
     * not consume an LLM turn.
     */
    @Override
    public ActionResult interact(PlayerEntity player, Hand hand) {
        if (hand != Hand.MAIN_HAND) {
            return ActionResult.PASS;
        }
        if (!(player instanceof ServerPlayerEntity viewer)) {
            return ActionResult.SUCCESS;
        }
        if (!BotAuthorizationGate.INSTANCE.authorize(viewer, this,
                BotAuthorizationPolicy.Operation.INVENTORY, "entity_inventory_screen")) {
            return ActionResult.FAIL;
        }
        viewer.openHandledScreen(new BotInventoryScreenFactory(this, viewer));
        return ActionResult.SUCCESS;
    }
}
