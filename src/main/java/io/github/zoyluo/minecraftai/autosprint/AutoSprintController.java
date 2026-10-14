package io.github.zoyluo.minecraftai.autosprint;

import baritone.api.IBaritone;
import baritone.api.event.events.PlayerUpdateEvent;
import baritone.api.event.events.SprintStateEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.input.Input;
import io.github.zoyluo.minecraftai.baritone.BaritoneHost;
import io.github.zoyluo.minecraftai.network.payload.AutoSprintControlS2C;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Owns the finite set of real players currently using {@code /minecraft-ai autosprint}.
 *
 * <p>Baritone plans on the server over a fresh, never-loading loaded-chunk snapshot. Rather than moving a
 * {@link ServerPlayer} directly (which would fight normal client prediction), it sends Baritone's chosen held keys and
 * body rotation to that player's modded client once per server tick. The client then sends ordinary vanilla movement
 * packets back. This leaves terrain/hazard planning authoritative while preserving smooth movement, swimming, and
 * normal server-side movement validation.</p>
 */
public final class AutoSprintController {
    public static final AutoSprintController INSTANCE = new AutoSprintController();

    private static final int GOAL_DISTANCE = 96;
    private static final int GOAL_RENEW_DISTANCE = 12;

    private final Map<UUID, Entry> entries = new HashMap<>();

    private AutoSprintController() {
    }

    /** Starts (or retargets) auto sprint for this player. Must be called on the server thread. */
    public boolean start(ServerPlayer player, AutoSprintDirection direction) {
        if (!ServerPlayNetworking.canSend(player, AutoSprintControlS2C.ID)) {
            return false;
        }
        Entry entry = entries.get(player.getUUID());
        try {
            if (entry == null) {
                IBaritone baritone = BaritoneHost.createAutoSprint(player);
                entry = new Entry(player, baritone, (AutoSprintPlayerContext) baritone.getPlayerContext(), direction);
                entries.put(player.getUUID(), entry);
            }
            entry.direction = direction;
            entry.level = player.level() instanceof ServerLevel level ? level : null;
            startNextGoal(entry);
            // Activate third-person free-look immediately, even if the first path calculation needs another tick.
            send(entry, new Controls(false, false, false, false, false, false, false,
                    player.getYRot(), player.getXRot()));
            return true;
        } catch (Throwable exception) {
            rethrowFatal(exception);
            if (entry != null) {
                entries.remove(player.getUUID());
                destroy(entry, true);
            }
            return false;
        }
    }

    /** Stops this player's route, clears Baritone's forced keys, and tells the client to restore first person. */
    public boolean stop(ServerPlayer player) {
        Entry entry = entries.remove(player.getUUID());
        if (entry != null) {
            destroy(entry, true);
            return true;
        }
        sendStop(player);
        return false;
    }

    /** Called at the beginning of each world tick, before the world's players are ticked. */
    public void beforeWorldTick(ServerLevel level) {
        for (Entry entry : entries.values().toArray(Entry[]::new)) {
            if (discardIfInactive(entry)) {
                continue;
            }
            if (entry.level != level) {
                continue;
            }
            try {
                entry.context.refreshTerrain();
                renewGoalIfNeeded(entry);
                entry.baritone.getGameEventHandler().onTick(nextTick(EventState.PRE));
            } catch (Throwable exception) {
                rethrowFatal(exception);
                if (entries.remove(entry.player.getUUID(), entry)) {
                    destroy(entry, true);
                }
            }
        }
    }

    /** Called at the end of each world tick, after the client-supplied player movement has been processed. */
    public void afterWorldTick(ServerLevel level) {
        for (Entry entry : entries.values().toArray(Entry[]::new)) {
            if (discardIfInactive(entry)) {
                continue;
            }
            if (entry.level != level) {
                continue;
            }
            try {
                sendSteeringFrame(entry);
            } catch (Throwable exception) {
                rethrowFatal(exception);
                if (entries.remove(entry.player.getUUID(), entry)) {
                    destroy(entry, true);
                }
            }
        }
    }

    /** Removes a disconnected player without attempting to write a final packet. */
    public void onDisconnect(ServerPlayer player) {
        Entry entry = entries.remove(player.getUUID());
        if (entry != null) {
            destroy(entry, false);
        }
    }

    /** Stops steering immediately after a portal, command, or respawn world transfer. */
    public void onWorldChange(ServerPlayer player) {
        Entry entry = entries.remove(player.getUUID());
        if (entry != null) {
            // A Fabric respawn transfer may supply a fresh destination player. The old entry
            // cannot necessarily send a packet anymore, so always restore input through the
            // player passed by the transfer event.
            destroy(entry, false);
            sendStop(player);
        }
    }

    /**
     * Stops input forwarding as soon as its player is no longer the player/world the Baritone
     * instance was created for. This is checked before filtering a world-tick callback: a portal
     * transfer can happen between a world's start and end callbacks, and sending even one more
     * frame at that point can leave the client holding stale keys or free-look in its new world.
     */
    private boolean discardIfInactive(Entry entry) {
        ServerPlayer player = entry.player;
        // The iteration is a snapshot. An event handler can remove or replace this entry before
        // its turn, in which case the snapshot must never emit a frame for the retired route.
        if (entries.get(player.getUUID()) != entry) {
            return true;
        }
        if (player.isAlive() && !player.isRemoved() && player.level() == entry.level
                && ServerPlayNetworking.canSend(player, AutoSprintControlS2C.ID)) {
            return false;
        }
        if (entries.remove(player.getUUID(), entry)) {
            destroy(entry, true);
        }
        return true;
    }

    /** Server lifecycle cleanup. */
    public void clear() {
        for (Entry entry : entries.values()) {
            destroy(entry, false);
        }
        entries.clear();
    }

    private static void renewGoalIfNeeded(Entry entry) {
        IBaritone baritone = entry.baritone;
        double dx = entry.goalX + 0.5D - entry.player.getX();
        double dz = entry.goalZ + 0.5D - entry.player.getZ();
        if (!baritone.getCustomGoalProcess().isActive()
                || dx * dx + dz * dz <= GOAL_RENEW_DISTANCE * GOAL_RENEW_DISTANCE) {
            startNextGoal(entry);
        }
    }

    private static void startNextGoal(Entry entry) {
        int x = entry.player.getBlockX() + entry.direction.stepX() * GOAL_DISTANCE;
        int z = entry.player.getBlockZ() + entry.direction.stepZ() * GOAL_DISTANCE;
        entry.goalX = x;
        entry.goalZ = z;
        entry.baritone.getCustomGoalProcess().setGoalAndPath(new GoalXZ(x, z));
    }

    /** Advances Baritone's look processor, without leaving a server-side rotation that fights the real client. */
    private static void sendSteeringFrame(Entry entry) {
        ServerPlayer player = entry.player;
        float originalYaw = player.getYRot();
        float originalPitch = player.getXRot();
        float pathYaw = originalYaw;
        float pathPitch = originalPitch;
        try {
            entry.baritone.getGameEventHandler().onPlayerUpdate(new PlayerUpdateEvent(EventState.PRE));
            pathYaw = player.getYRot();
            pathPitch = player.getXRot();
            player.setYRot(originalYaw);
            player.setXRot(originalPitch);
            entry.baritone.getGameEventHandler().onPlayerUpdate(new PlayerUpdateEvent(EventState.POST));
        } finally {
            // Baritone's look behavior is useful for planning, but body rotation remains client-owned.
            player.setYRot(originalYaw);
            player.setXRot(originalPitch);
        }
        entry.baritone.getGameEventHandler().onPostTick(nextTick(EventState.POST));
        IInputOverrideHandler keys = entry.baritone.getInputOverrideHandler();
        // PathExecutor consumes the temporary SPRINT key while it resolves the safe gait for the
        // next movement tick. Reading that key here therefore made a real-player route walk even
        // when Baritone had selected sprint. The event exposes Baritone's final decision instead.
        SprintStateEvent sprint = new SprintStateEvent();
        entry.baritone.getGameEventHandler().onPlayerSprintState(sprint);
        send(entry, new Controls(
                keys.isInputForcedDown(Input.MOVE_FORWARD),
                keys.isInputForcedDown(Input.MOVE_BACK),
                keys.isInputForcedDown(Input.MOVE_LEFT),
                keys.isInputForcedDown(Input.MOVE_RIGHT),
                keys.isInputForcedDown(Input.JUMP),
                keys.isInputForcedDown(Input.SNEAK),
                Boolean.TRUE.equals(sprint.getState()),
                pathYaw, pathPitch));
    }

    private static void destroy(Entry entry, boolean notifyClient) {
        try {
            entry.baritone.getPathingBehavior().forceCancel();
            entry.baritone.getInputOverrideHandler().clearAllKeys();
            entry.baritone.getPlayerContext().playerController().resetBlockRemoving();
        } finally {
            BaritoneHost.destroy(entry.baritone);
            if (notifyClient) {
                sendStop(entry.player);
            }
        }
    }

    private static void send(Entry entry, Controls controls) {
        if (ServerPlayNetworking.canSend(entry.player, AutoSprintControlS2C.ID)) {
            ServerPlayNetworking.send(entry.player, new AutoSprintControlS2C(true,
                    controls.forward, controls.back, controls.left, controls.right,
                    controls.jump, controls.sneak, controls.sprint, controls.yaw, controls.pitch));
        }
    }

    private static void sendStop(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, AutoSprintControlS2C.ID)) {
            ServerPlayNetworking.send(player, AutoSprintControlS2C.stop());
        }
    }

    private static TickEvent nextTick(EventState state) {
        BiFunction<EventState, TickEvent.Type, TickEvent> provider = TickEvent.createNextProvider();
        return provider.apply(state, TickEvent.Type.IN);
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
    }

    private static final class Entry {
        private final ServerPlayer player;
        private final IBaritone baritone;
        private final AutoSprintPlayerContext context;
        private AutoSprintDirection direction;
        private ServerLevel level;
        private int goalX;
        private int goalZ;

        private Entry(ServerPlayer player, IBaritone baritone, AutoSprintPlayerContext context,
                      AutoSprintDirection direction) {
            this.player = player;
            this.baritone = baritone;
            this.context = context;
            this.direction = direction;
        }
    }

    private record Controls(boolean forward, boolean back, boolean left, boolean right, boolean jump, boolean sneak,
                            boolean sprint, float yaw, float pitch) {
    }
}
