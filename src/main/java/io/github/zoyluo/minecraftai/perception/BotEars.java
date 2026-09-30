package io.github.zoyluo.minecraftai.perception;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraft.world.level.gameevent.EntityPositionSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import net.minecraft.world.phys.Vec3;

/**
 * The hearing of ONE companion: vanilla's own vibration system, CALLED (not copied), used exactly like the Warden and the sculk sensor
 * use it. A {@link VibrationSystem} (vanilla {@code VibrationSystem.Data}, our {@code VibrationSystem.User}), a vanilla
 * {@code VibrationSystem.Listener} wrapped in a vanilla {@link DynamicGameEventListener} (registered in the level's game event registry:
 * {@code add} when the bot is first seen or arrives in another level, {@code move} every tick (it only acts when the bot changes chunk
 * section), {@code remove} on every exit path: death, despawn, level change, the switch turned off, the server stopping) and the vanilla
 * {@code VibrationSystem.Ticker.tick} once per tick.
 *
 * <p>What is heard, how far ({@code behaviour.perception.hearing.listenerRadius}, 16 = the Warden's), through what (wool blocks
 * vibrations, other blocks do not), what stays silent (a sneaking player's steps, spectators, wool-dampened steps) and how long a
 * vibration takes to arrive are all vanilla rules: {@code isValidVibration}, {@code getListenableEvents = GameEventTags.VIBRATIONS} and
 * {@code calculateTravelTimeInTicks} keep their vanilla defaults. The only additions: a bot does not hear its OWN events, and a dead or
 * removed bot hears nothing.
 *
 * <p>NO MAGIC: a received vibration is recorded as a sound at its position only ({@code onReceiveVibration}'s block position) and its
 * event kind (a shot); the source entity and the projectile owner are ignored.
 */
final class BotEars implements VibrationSystem {

    /** A vibration that reached the bot: where it was made and whether it was a projectile being shot. */
    record Sound(Vec3 pos, boolean shot) {
    }

    private final AIPlayerEntity bot;
    private final VibrationSystem.Data data = new VibrationSystem.Data();
    private final VibrationSystem.User user = new Hearing();
    private final DynamicGameEventListener<VibrationSystem.Listener> dynamic =
            new DynamicGameEventListener<>(new VibrationSystem.Listener(this));
    private final List<Sound> heard = new ArrayList<>();
    private final PositionSource source;
    private int radius = 16;
    /** The level the listener is registered in, or null. */
    private ServerLevel registeredIn;

    BotEars(AIPlayerEntity bot) {
        this.bot = bot;
        this.source = new EntityPositionSource(bot, bot.getEyeHeight());
    }

    AIPlayerEntity bot() {
        return bot;
    }

    @Override
    public VibrationSystem.Data getVibrationData() {
        return data;
    }

    @Override
    public VibrationSystem.User getVibrationUser() {
        return user;
    }

    /** True while the listener is registered in a level. */
    boolean registered() {
        return registeredIn != null;
    }

    /** The level the listener is registered in, or null. */
    ServerLevel registeredLevel() {
        return registeredIn;
    }

    /** Registers in {@code level} (leaving the previous one), or just moves along when already there; then delivers due vibrations. */
    void tick(ServerLevel level, int listenerRadius) {
        radius = listenerRadius;
        if (registeredIn != level) {
            detach();
            dynamic.add(level);
            registeredIn = level;
        } else {
            dynamic.move(level);
        }
        VibrationSystem.Ticker.tick(level, data, user);
    }

    /** Unregisters from the level (idempotent). */
    void detach() {
        ServerLevel level = registeredIn;
        registeredIn = null;
        if (level != null) {
            try {
                dynamic.remove(level);
            } catch (RuntimeException ignored) {
                // The level may already be gone (server stopping); nothing is left to unregister from.
            }
        }
    }

    /** The sounds heard since the last call, oldest first. */
    List<Sound> drain() {
        if (heard.isEmpty()) {
            return List.of();
        }
        List<Sound> out = new ArrayList<>(heard);
        heard.clear();
        return out;
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
            // Dead or gone bots hear nothing; a bot does not hear its own steps and blows, nor wonder about an item it dropped itself.
            return bot.isAlive() && !bot.isRemoved() && context.sourceEntity() != bot
                    && !(context.sourceEntity() instanceof net.minecraft.world.entity.item.ItemEntity item && item.getOwner() == bot);
        }

        @Override
        public void onReceiveVibration(ServerLevel level, BlockPos pos, Holder<GameEvent> event, Entity sourceEntity,
                                       Entity projectileOwner, float distance) {
            // NO MAGIC: the position of the sound (and that it was a shot) is all that is used; who made it is not looked at.
            heard.add(new Sound(new Vec3(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D),
                    event.is(GameEvent.PROJECTILE_SHOOT.key())));
            if (heard.size() > 32) {
                heard.remove(0);
            }
        }
    }
}
