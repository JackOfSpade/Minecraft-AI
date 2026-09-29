package io.github.zoyluo.minecraftai.util;

import java.util.Optional;
import net.minecraft.core.BlockPos;

/**
 * Shared "x,y,z" text codec helpers for {@link BlockPos}, plus compact log formatting.
 *
 * <p>Before this class existed, nearly every task/checkpoint class carried its own private copy of
 * these helpers, and the copies had drifted: some tolerate a null {@code BlockPos} and some don't,
 * some catch a malformed numeric part and some let it propagate, and one splits with a trailing-empty
 * -preserving limit while the rest don't. Each variant below is named for its exact accept/reject
 * behaviour and kept byte-identical to whichever copy it replaces, so every checkpoint wire format and
 * every log line stays unchanged. Do not merge two variants that behave differently on malformed or
 * null input -- add a new variant instead.</p>
 */
public final class BlockPosText {
    private BlockPosText() {
    }

    // ---- Compact log formatting ----

    /** "x,y,z"; throws NullPointerException if {@code pos} is null. */
    public static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** Null-tolerant compact formatting: returns {@code nullText} when {@code pos} is null. */
    public static String compactOrElse(BlockPos pos, String nullText) {
        return pos == null ? nullText : compact(pos);
    }

    // ---- Checkpoint text codec ----

    /** "x,y,z"; throws NullPointerException if {@code pos} is null. Same format as {@link #compact}. */
    public static String encodePos(BlockPos pos) {
        return compact(pos);
    }

    /** "x,y,z", or "" when {@code pos} is null. */
    public static String encodePosOrEmpty(BlockPos pos) {
        return pos == null ? "" : compact(pos);
    }

    /**
     * Decodes "x,y,z" text produced by {@link #encodePos}/{@link #encodePosOrEmpty}. Returns
     * {@code Optional.empty()} for null/blank input, a value that doesn't split into exactly 3
     * comma-separated parts, or a non-numeric part; never throws.
     */
    public static Optional<BlockPos> decodePos(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            String[] parts = value.split(",");
            if (parts.length != 3) {
                return Optional.empty();
            }
            return Optional.of(new BlockPos(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Same as {@link #decodePos} but splits with a trailing-empty-preserving limit of -1, so a value
     * with a trailing comma (e.g. "1,2,3,") is rejected instead of silently accepted.
     */
    public static Optional<BlockPos> decodePosStrictSplit(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            String[] parts = value.split(",", -1);
            if (parts.length != 3) {
                return Optional.empty();
            }
            return Optional.of(new BlockPos(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    /**
     * Same as {@link #decodePos} but does not catch a malformed numeric part: a non-numeric
     * component throws {@link NumberFormatException} instead of yielding {@code Optional.empty()}.
     */
    public static Optional<BlockPos> decodePosOrThrow(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String[] parts = value.split(",");
        if (parts.length != 3) {
            return Optional.empty();
        }
        return Optional.of(new BlockPos(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2])));
    }

    // ---- Nullable, "none"-sentinel checkpoint codec ----

    /** "x,y,z", or "none" when {@code pos} is null. */
    public static String encodeOptionalPos(BlockPos pos) {
        return pos == null ? "none" : compact(pos);
    }

    /**
     * Decodes {@link #encodeOptionalPos} output: "none" decodes to null. Any other value must split
     * (with a trailing-empty-preserving limit of -1) into exactly 3 comma-separated parts, or this
     * throws {@link IllegalArgumentException}; a non-numeric part throws {@link NumberFormatException}.
     */
    public static BlockPos decodeOptionalPos(String value) {
        if ("none".equals(value)) {
            return null;
        }
        String[] parts = value.split(",", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("invalid_pos");
        }
        return new BlockPos(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]));
    }
}
