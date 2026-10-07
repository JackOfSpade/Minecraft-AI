package io.github.zoyluo.minecraftai.persist;

import io.github.zoyluo.minecraftai.util.BlockPosText;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/**
 * The text form of the pillar a bot stands on when it is saved ({@link BotRecord#towerBase()}): the "x,y,z" of the feet cell at the
 * floor it was built from, which is where the descent that takes it down ends (see {@code TowerDescent#base}). Pure, so the
 * compatibility rules are tested without a Minecraft server.
 *
 * <p>A record written before the field existed has none ({@link Status#ABSENT}) and restores exactly as it always did. A value that
 * is present must be the canonical text of three integers and nothing else: anything that does not read back as itself
 * ({@link Status#MALFORMED}: a missing or extra part, a sign, padding, a blank) is refused, and the bot is restored without a tower
 * rather than with one guessed from damaged data.</p>
 */
public final class TowerBaseCodec {
    public enum Status {
        /** The record carries no tower: it stood on none, or was saved before the field existed. */
        ABSENT,
        VALID,
        MALFORMED
    }

    /** {@code base} is set only when {@code status} is {@link Status#VALID}. */
    public record Decoded(Status status, BlockPos base) {
    }

    private TowerBaseCodec() {
    }

    public static String encode(BlockPos base) {
        return BlockPosText.encodePos(base);
    }

    public static Decoded decode(String saved) {
        if (saved == null) {
            return new Decoded(Status.ABSENT, null);
        }
        Optional<BlockPos> parsed = BlockPosText.decodePosStrictSplit(saved);
        if (parsed.isEmpty() || !encode(parsed.get()).equals(saved)) {
            return new Decoded(Status.MALFORMED, null);
        }
        return new Decoded(Status.VALID, parsed.get().immutable());
    }
}
