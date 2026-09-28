package io.github.zoyluo.aibot.mining.assist;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins mining-assist design 4.11 and the contract's B.1/M22: server-wide, per-dimension soft claims on
 * valuables a detour is about to break. Only the pure {@code String dimensionKey}/{@code UUID}/{@code long
 * posKey} overloads are exercised here; the {@code AIPlayerEntity} adapters need a live bot/world and are
 * left to the GameTest layer (contract A.4/M11).
 */
class OreClaimsTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final long seed = BlockPos.asLong(10, 12, -4);

    @BeforeEach
    @AfterEach
    void clean() {
        OreClaims.clearAll();
    }

    @Test
    void freeCellIsClaimedAndThenHeldAgainstOthers() {
        assertTrue(OreClaims.tryClaim(OVERWORLD, alice, seed, 0));
        assertEquals(1, OreClaims.size());
        assertFalse(OreClaims.heldByOther(OVERWORLD, alice, seed, 0));
        assertTrue(OreClaims.heldByOther(OVERWORLD, bob, seed, 0));
    }

    @Test
    void anotherOwnersUnexpiredClaimBlocksTryClaimAndChangesNothing() {
        assertTrue(OreClaims.tryClaim(OVERWORLD, alice, seed, 0));
        assertFalse(OreClaims.tryClaim(OVERWORLD, bob, seed, 50));
        assertFalse(OreClaims.heldByOther(OVERWORLD, alice, seed, 50));
        assertTrue(OreClaims.heldByOther(OVERWORLD, bob, seed, 99));
    }

    @Test
    void ownerCanRefreshTheirOwnClaimBeforeItExpires() {
        assertTrue(OreClaims.tryClaim(OVERWORLD, alice, seed, 0));
        assertTrue(OreClaims.tryClaim(OVERWORLD, alice, seed, 90));
        assertTrue(OreClaims.heldByOther(OVERWORLD, bob, seed, 189));
        assertFalse(OreClaims.heldByOther(OVERWORLD, bob, seed, 190));
    }

    @Test
    void expiredClaimIsNotHeldAndMayBeTakenByAnotherBot() {
        assertTrue(OreClaims.tryClaim(OVERWORLD, alice, seed, 0));
        assertTrue(OreClaims.heldByOther(OVERWORLD, bob, seed, 99)); // still live at 99
        assertFalse(OreClaims.heldByOther(OVERWORLD, bob, seed, 100)); // expired at 100
        assertTrue(OreClaims.tryClaim(OVERWORLD, bob, seed, 100));
        assertTrue(OreClaims.heldByOther(OVERWORLD, alice, seed, 100));
    }

    @Test
    void heldByOtherIsFalseWhenNoClaimExistsAnywhere() {
        assertEquals(0, OreClaims.size());
        assertFalse(OreClaims.heldByOther(OVERWORLD, alice, seed, 0));
    }

    @Test
    void heldByOtherIsFalseInAnUnrelatedDimension() {
        assertTrue(OreClaims.tryClaim(OVERWORLD, alice, seed, 0));
        assertFalse(OreClaims.heldByOther(NETHER, bob, seed, 0));
    }

    @Test
    void renewAllRefreshesLiveClaimsOfOneOwnerAndCountsThem() {
        long other = BlockPos.asLong(1, 1, 1);
        assertTrue(OreClaims.tryClaim(OVERWORLD, alice, seed, 0));
        assertTrue(OreClaims.tryClaim(OVERWORLD, alice, other, 0));
        int renewed = OreClaims.renewAll(alice, 90);
        assertEquals(2, renewed);
        assertTrue(OreClaims.heldByOther(OVERWORLD, bob, seed, 189));
        assertFalse(OreClaims.heldByOther(OVERWORLD, bob, seed, 190));
    }

    @Test
    void renewAllDropsExpiredClaimsWithoutRefreshingOrCountingThem() {
        assertTrue(OreClaims.tryClaim(OVERWORLD, alice, seed, 0));
        assertTrue(OreClaims.tryClaim(NETHER, bob, seed, 0));
        int renewed = OreClaims.renewAll(alice, 150);
        assertEquals(0, renewed);
        assertEquals(1, OreClaims.size());
        assertFalse(OreClaims.heldByOther(OVERWORLD, bob, seed, 150));
    }

    @Test
    void releaseRemovesOnlyTheOwnersClaim() {
        assertTrue(OreClaims.tryClaim(OVERWORLD, alice, seed, 0));
        assertFalse(OreClaims.release(OVERWORLD, bob, seed));
        assertTrue(OreClaims.release(OVERWORLD, alice, seed));
        assertEquals(0, OreClaims.size());
        assertFalse(OreClaims.release(OVERWORLD, alice, seed));
    }

    @Test
    void releaseAllDropsEveryDimensionOfOneOwnerOnly() {
        long other = BlockPos.asLong(2, 2, 2);
        assertTrue(OreClaims.tryClaim(OVERWORLD, alice, seed, 0));
        assertTrue(OreClaims.tryClaim(NETHER, alice, other, 0));
        assertTrue(OreClaims.tryClaim(OVERWORLD, bob, other, 0));
        assertEquals(2, OreClaims.releaseAll(alice));
        assertEquals(1, OreClaims.size());
        assertTrue(OreClaims.heldByOther(OVERWORLD, alice, other, 0));
    }

    @Test
    void clearAllDropsEveryDimension() {
        assertTrue(OreClaims.tryClaim(OVERWORLD, alice, seed, 0));
        assertTrue(OreClaims.tryClaim(NETHER, bob, seed, 0));
        OreClaims.clearAll();
        assertEquals(0, OreClaims.size());
    }
}
