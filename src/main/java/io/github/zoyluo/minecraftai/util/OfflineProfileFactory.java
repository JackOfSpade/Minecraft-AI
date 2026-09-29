package io.github.zoyluo.minecraftai.util;

import com.mojang.authlib.GameProfile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class OfflineProfileFactory {
    /**
     * Vanilla's client-side {@code DefaultPlayerSkin} renders any profile with no signed skin
     * texture using one of 18 built-in default skins (9 named characters x slim/wide arm model),
     * chosen deterministically via {@code Math.floorMod(profile.id().hashCode(), 18)}. That is the
     * only public hook into that fixed skin set -- there is no "textures" property or other API to
     * request one directly -- so to give a bot an intentionally CHOSEN one of the 18 (instead of
     * whatever its name's plain "OfflinePlayer:&lt;name&gt;" UUID happens to hash to, which is how
     * every bot ended up with the same skin whenever they shared a name), the name-derived UUID is
     * salted until it lands in the desired bucket.
     */
    public static final int DEFAULT_SKIN_COUNT = 18;
    private static final int MAX_SALT_SEARCH = 500;

    private OfflineProfileFactory() {
    }

    /** A uniformly random default-skin index in [0, DEFAULT_SKIN_COUNT); pick once at spawn and persist it. */
    public static int randomSkinIndex() {
        return ThreadLocalRandom.current().nextInt(DEFAULT_SKIN_COUNT);
    }

    /**
     * Builds the profile for a bot named {@code name}, with a UUID chosen so vanilla's own
     * default-skin fallback renders {@code skinIndex} (see {@link #randomSkinIndex()}). The same
     * (name, skinIndex) pair always yields the same UUID.
     */
    public static GameProfile create(String name, int skinIndex) {
        return new GameProfile(uuidForSkin(name, Math.floorMod(skinIndex, DEFAULT_SKIN_COUNT)), name);
    }

    private static UUID uuidForSkin(String name, int target) {
        UUID base = seed(name, null);
        if (Math.floorMod(base.hashCode(), DEFAULT_SKIN_COUNT) == target) {
            return base;
        }
        for (int salt = 0; salt < MAX_SALT_SEARCH; salt++) {
            UUID candidate = seed(name, salt);
            if (Math.floorMod(candidate.hashCode(), DEFAULT_SKIN_COUNT) == target) {
                return candidate;
            }
        }
        // Astronomically unlikely with 18 buckets and a good hash (expected ~18 tries), but never
        // loop forever or silently render the wrong skin -- fail back to the plain name-derived UUID.
        return base;
    }

    private static UUID seed(String name, Integer salt) {
        String key = salt == null ? "OfflinePlayer:" + name : "OfflinePlayer:" + name + ":" + salt;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
