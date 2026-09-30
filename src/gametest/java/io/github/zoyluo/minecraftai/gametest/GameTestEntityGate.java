package io.github.zoyluo.minecraftai.gametest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps entities nobody asked for out of the GameTest world.
 *
 * <p>A scene is only as deterministic as what is in it. The flat test world generates passive animals with every new chunk (cows,
 * pigs, sheep, chickens, horses: a fresh cow next to the one a farm test placed is the cow the bot milks) and, being a slime-chunk
 * world below y=40, lets slimes pile up around the bots; a mob a finished test left in a chunk that has since been unloaded comes
 * back with the chunk. To a bot under test each of these is a hostile or a prey the fixture never placed.</p>
 *
 * <p>Code that adds an entity (a test, a bot, a block that drops an item, the natural spawner) calls {@code ServerLevel.addEntity};
 * {@link io.github.zoyluo.minecraftai.gametest.mixin.ServerLevelFreshEntityMixin} marks the entity there. An entity that enters the
 * world without that call was loaded from a chunk, which is exactly the set above, and it is discarded at the end of the tick.
 * Natural spawning (which does call {@code addEntity}) is switched off by the harness's game rules instead.</p>
 */
public final class GameTestEntityGate {
    private static final Logger LOG = LoggerFactory.getLogger("minecraftai-gametest-entity-gate");
    private static final boolean ENABLED = System.getProperty("fabric-api.gametest") != null;

    /**
     * Entities code added, for as long as they exist: an entity added into a chunk that is not yet tracked only announces itself
     * later, and one whose chunk stops being tracked for a while announces itself again when it is tracked again (that second
     * announcement is not a load from disk). Weak, so this set never keeps an entity alive.
     */
    private static final Set<Entity> FRESH = Collections.newSetFromMap(new WeakHashMap<>());
    private static final List<Entity> LOADED = new ArrayList<>();
    private static int discardedTotal;

    private GameTestEntityGate() {
    }

    /** Called by the mixin before an entity is added by code. */
    public static void markFresh(Entity entity) {
        if (ENABLED) {
            FRESH.add(entity);
            GameTestWorldRestorer.keepLoaded(entity.level(), entity.blockPosition().getX() >> 4, entity.blockPosition().getZ() >> 4);
        }
    }

    /** {@code ServerEntityEvents.ENTITY_LOAD}: an entity that was not added by code was loaded from a chunk. */
    public static void onLoad(Entity entity) {
        if (!ENABLED || entity instanceof Player) {
            return;
        }
        if (!FRESH.contains(entity)) {
            LOADED.add(entity);
        }
    }

    /** {@code ServerTickEvents.END_SERVER_TICK}. */
    public static void endTick() {
        if (LOADED.isEmpty()) {
            return;
        }
        for (Entity entity : LOADED) {
            if (!entity.isRemoved()) {
                entity.discard();
                discardedTotal++;
            }
        }
        LOADED.clear();
        if (discardedTotal > 0 && (discardedTotal & (discardedTotal - 1)) == 0) {
            LOG.info("{} entities loaded from chunks were discarded so far (generated animals, leftovers of earlier tests)", discardedTotal);
        }
    }
}
