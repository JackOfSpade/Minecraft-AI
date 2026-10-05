package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.combat.AggroWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraft.world.level.gameevent.EntityPositionSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;

/**
 * The hearing of the inhabitants: vanilla's own vibration system, used like the Warden and the sculk sensor use it. Per inhabitant
 * there is one {@link VibrationSystem} (vanilla {@code VibrationSystem.Data}, our
 * {@code VibrationSystem.User}), a vanilla {@code VibrationSystem.Listener} wrapped in a vanilla
 * {@link DynamicGameEventListener} (registered in the level's game event registry: {@code add} when the inhabitant is
 * first seen, {@code move} every tick (it only acts when the inhabitant changes chunk section), {@code remove} on every exit
 * path: death, removal, level change, the switch turned off, the server stopping) and {@link SilentVibrationTicker} once per tick.
 * It preserves vanilla scheduling and delivery while leaving out the otherwise visible vibration-particle packet.
 * <p>
 * What is heard, how far ({@code aggro.hearing.listenerRadius}, 16 = the Warden's), through what (wool blocks vibrations, other
 * blocks do not), what stays silent (a sneaking player's steps, spectators, wool-dampened steps) and how long a vibration takes
 * to arrive are all vanilla rules: this class overrides none of them ({@code isValidVibration},
 * {@code getListenableEvents = GameEventTags.VIBRATIONS}, {@code calculateTravelTimeInTicks} keep their vanilla defaults). The only
 * additions: a bot does not hear its OWN events, and a dead or removed bot hears nothing.
 * <p>
 * NO MAGIC: a received vibration is recorded as a sound at its position only ({@code onReceiveVibration}'s block position); the
 * source entity and projectile owner are ignored.
 */
final class InhabitantEars {

    /** One inhabitant's ear: the vibration system and its registration. */
    private static final class Ear implements VibrationSystem {
        private final ServerPlayer bot;
        private final VibrationSystem.Data data = new VibrationSystem.Data();
        private final VibrationSystem.User user = new Hearing();
        private final DynamicGameEventListener<VibrationSystem.Listener> dynamic =
                new DynamicGameEventListener<>(new VibrationSystem.Listener(this));
        private final List<AggroWorld.Sound> heard = new ArrayList<>();
        private final PositionSource source;
        private int radius = 16;
        /** The level the listener is registered in, or null. */
        private ServerLevel registeredIn;

        Ear(ServerPlayer bot) {
            this.bot = bot;
            this.source = new EntityPositionSource(bot, bot.getEyeHeight());
        }

        @Override
        public VibrationSystem.Data getVibrationData() {
            return data;
        }

        @Override
        public VibrationSystem.User getVibrationUser() {
            return user;
        }

        void attach(ServerLevel level) {
            if (registeredIn != level) {
                detach();
                dynamic.add(level);
                registeredIn = level;
            } else {
                dynamic.move(level);
            }
        }

        void detach() {
            ServerLevel level = registeredIn;
            registeredIn = null;
            if (level != null) {
                dynamic.remove(level);
            }
        }

        private final class Hearing implements VibrationSystem.User {
            @Override
            public int getListenerRadius() {
                return radius;
            }

            @Override
            public PositionSource getPositionSource() {
                return source;
            }

            @Override
            public boolean canReceiveVibration(ServerLevel level, BlockPos pos, Holder<GameEvent> event,
                                               GameEvent.Context context) {
                // Dead or gone bots hear nothing; a bot does not hear its own steps and blows.
                return bot.isAlive() && !bot.isRemoved() && context.sourceEntity() != bot;
            }

            @Override
            public void onReceiveVibration(ServerLevel level, BlockPos pos, Holder<GameEvent> event, Entity sourceEntity,
                                           Entity projectileOwner, float distance) {
                // NO MAGIC: the position of the sound is all that is used; who made it is not looked at.
                heard.add(new AggroWorld.Sound(new AggroWorld.Pos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5)));
                if (heard.size() > 32) {
                    heard.remove(0);
                }
            }
        }
    }

    private final Map<UUID, Ear> ears = new HashMap<>();
    /** Bots whose ear failed, with the game tick before which no new ear is built (back-off); also "already reported". */
    private final Map<UUID, Long> failed = new HashMap<>();
    /** Ticks a bot whose ear failed is left without one before the next attempt. */
    static final long FAILURE_BACKOFF_TICKS = 100;
    private final java.util.function.Consumer<String> onFailure;

    InhabitantEars(java.util.function.Consumer<String> onFailure) {
        this.onFailure = onFailure;
    }

    /**
     * Once per tick: registers the ears of the inhabitants that are alive, moves them, ticks the vibration systems (which
     * delivers due vibrations), and removes the ears of everyone who is gone, dead or in no server level. {@code enabled}
     * false removes them all.
     */
    void sync(Collection<ServerPlayer> inhabitants, boolean enabled, int radius) {
        if (!enabled) {
            closeAll();
            return;
        }
        Set<UUID> keep = new HashSet<>();
        for (ServerPlayer p : inhabitants) {
            if (!p.isAlive() || p.isRemoved() || !(p.level() instanceof ServerLevel level)) {
                continue;
            }
            keep.add(p.getUUID());
            Long retryAt = failed.get(p.getUUID());
            if (retryAt != null && level.getGameTime() < retryAt) {
                continue; // backing off after a failure: no ear is rebuilt every tick
            }
            Ear ear = ears.get(p.getUUID());
            if (ear == null || ear.bot != p) {
                if (ear != null) {
                    safeDetach(ear);
                }
                ear = new Ear(p);
                ears.put(p.getUUID(), ear);
            }
            ear.radius = radius;
            try {
                ear.attach(level);
                SilentVibrationTicker.tick(level, ear.data, ear.user);
            } catch (RuntimeException e) {
                // One bot's ear failing must not stop the others': drop it (it is registered again next tick, and a persistent
                // failure is reported once).
                safeDetach(ear);
                ears.remove(p.getUUID());
                if (failed.put(p.getUUID(), level.getGameTime() + FAILURE_BACKOFF_TICKS) == null) {
                    onFailure.accept("hearing of " + p.getName().getString() + " failed: " + e);
                }
            }
        }
        // A bot that is gone or dead is forgotten (the set would grow for the life of the server); a bot whose ear keeps
        // failing stays in it, so it is reported once and retried only after the back-off.
        failed.keySet().retainAll(keep);
        for (Iterator<Map.Entry<UUID, Ear>> it = ears.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Ear> e = it.next();
            if (!keep.contains(e.getKey())) {
                safeDetach(e.getValue());
                it.remove();
            }
        }
    }

    /** The sounds {@code bot} heard since the last call, oldest first. */
    List<AggroWorld.Sound> drain(ServerPlayer bot) {
        Ear ear = ears.get(bot.getUUID());
        if (ear == null || ear.heard.isEmpty()) {
            return List.of();
        }
        List<AggroWorld.Sound> out = new ArrayList<>(ear.heard);
        ear.heard.clear();
        return out;
    }

    /** How many vibration listeners are registered right now (a seam for the leak tests). */
    int listenerCount() {
        int n = 0;
        for (Ear e : ears.values()) {
            if (e.registeredIn != null) {
                n++;
            }
        }
        return n;
    }

    /** Removes every listener (the switch is off, or the server is stopping). */
    void closeAll() {
        for (Ear e : ears.values()) {
            safeDetach(e);
        }
        ears.clear();
    }

    private static void safeDetach(Ear ear) {
        try {
            ear.detach();
        } catch (RuntimeException ignored) {
            // The level may already be gone (server stopping); nothing is left to unregister from.
        }
    }
}
