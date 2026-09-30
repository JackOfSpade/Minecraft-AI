package dev.spawnbotswrapper.inhabitants.mc;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reach rule of {@link MeleeLegality} against vanilla's real {@link AttackRange}: sword, axe and spear items, a
 * survival, creative and spectator attacker. The attacker is a player entity made without a server ({@link McObjects})
 * with just what {@code AttackRange.isInRange} reads: its position, eye height, game mode and attributes. The weapon's
 * range is picked as vanilla's {@code LivingEntity.entityAttackRange()} does (the item's {@code attack_range}
 * component, else {@code AttackRange.defaultFor}); that method itself needs the entity's synched data, so the
 * real-server GameTests cover it.
 */
public final class MeleeLegalityMcCases {
    private static final double EYE_Y = 1.62;

    private MeleeLegalityMcCases() {
    }

    private static ServerPlayer attacker(GameType mode) {
        ServerPlayer player = McObjects.opaque(ServerPlayer.class);
        McObjects.setField(player, Entity.class, "position", new Vec3(0.0, 0.0, 0.0));
        McObjects.setFloat(player, Entity.class, "eyeHeight", (float) EYE_Y);
        ServerPlayerGameMode gameMode = McObjects.opaque(ServerPlayerGameMode.class);
        McObjects.setField(gameMode, ServerPlayerGameMode.class, "gameModeForPlayer", mode);
        McObjects.setField(player, ServerPlayer.class, "gameMode", gameMode);
        McObjects.setField(player, LivingEntity.class, "attributes", new AttributeMap(Player.createAttributes().build()));
        return player;
    }

    private static AttackRange rangeOf(ItemStack weapon, LivingEntity holder) {
        AttackRange own = weapon.get(DataComponents.ATTACK_RANGE);
        return own != null ? own : AttackRange.defaultFor(holder);
    }

    /** A player-sized box whose nearest face is {@code distance} blocks east of the eye (level with it, so the distance is exact). */
    private static AABB boxAt(double distance) {
        return new AABB(distance, 0.0, -0.3, distance + 0.6, 1.8, 0.3);
    }

    private static boolean legal(ServerPlayer holder, Item weapon, double distanceToBox) {
        return MeleeLegality.inAttackRange(rangeOf(new ItemStack(weapon), holder), holder, boxAt(distanceToBox));
    }

    public static void aSwordAndAnAxeUseTheDefaultRangeOfThreeBlocks() {
        McBootstrap.ensure();
        ServerPlayer holder = attacker(GameType.SURVIVAL);
        for (Item weapon : new Item[] {Items.IRON_SWORD, Items.NETHERITE_SWORD, Items.IRON_AXE, Items.MACE}) {
            assertNull(new ItemStack(weapon).get(DataComponents.ATTACK_RANGE), weapon + " has its own range");
            AttackRange range = rangeOf(new ItemStack(weapon), holder);
            assertEquals(0.0F, range.minRange());
            assertEquals(3.0F, range.maxRange());
            assertTrue(legal(holder, weapon, 0.5), weapon + " at 0.5");
            assertTrue(legal(holder, weapon, 3.0), weapon + " at 3.0");
            assertTrue(legal(holder, weapon, 3.15), weapon + " inside the lag tolerance");
            assertFalse(legal(holder, weapon, 3.3), weapon + " beyond reach");
            assertFalse(legal(holder, weapon, 4.0), weapon + " at spear distance");
        }
    }

    public static void theDefaultRangeFollowsTheEntityInteractionRangeAttribute() {
        McBootstrap.ensure();
        ServerPlayer holder = attacker(GameType.SURVIVAL);
        AttackRange range = AttackRange.defaultFor(holder);
        assertEquals(3.0F, range.maxRange());
        assertEquals(0.0F, range.hitboxMargin());
        assertEquals(1.0F, range.mobFactor());
    }

    public static void aSpearReachesFartherThanASwordButNotTooClose() {
        McBootstrap.ensure();
        ServerPlayer holder = attacker(GameType.SURVIVAL);
        for (Item spear : new Item[] {Items.WOODEN_SPEAR, Items.IRON_SPEAR, Items.NETHERITE_SPEAR}) {
            AttackRange range = new ItemStack(spear).get(DataComponents.ATTACK_RANGE);
            assertNotNull(range, spear + " has no attack range");
            assertEquals(2.0F, range.minRange(), spear.toString());
            assertEquals(4.5F, range.maxRange(), spear.toString());
            assertEquals(0.125F, range.hitboxMargin(), spear.toString());
        }
        // Beyond a sword's 3.2, inside the spear's 4.5 + 0.125 margin + 0.2 tolerance.
        assertTrue(legal(holder, Items.IRON_SPEAR, 3.5));
        assertTrue(legal(holder, Items.IRON_SPEAR, 4.5));
        assertTrue(legal(holder, Items.IRON_SPEAR, 4.8));
        assertFalse(legal(holder, Items.IRON_SPEAR, 4.9), "beyond the spear's maximum");
        // Closer than the minimum range (2.0 - 0.125 margin - 0.2 tolerance = 1.675): vanilla refuses the jab.
        assertTrue(legal(holder, Items.IRON_SPEAR, 2.0));
        assertTrue(legal(holder, Items.IRON_SPEAR, 1.7));
        assertFalse(legal(holder, Items.IRON_SPEAR, 1.6), "closer than the spear's minimum range");
        assertFalse(legal(holder, Items.IRON_SPEAR, 0.5));
    }

    public static void aCreativeAttackerHasTheCreativeRange() {
        McBootstrap.ensure();
        ServerPlayer holder = attacker(GameType.CREATIVE);
        assertTrue(legal(holder, Items.IRON_SPEAR, 6.5), "spear creative maximum 6.5 + margin");
        assertFalse(legal(holder, Items.IRON_SPEAR, 7.0));
    }

    public static void aSpectatorHasNoMinimumRange() {
        McBootstrap.ensure();
        ServerPlayer holder = attacker(GameType.SPECTATOR);
        assertTrue(legal(holder, Items.IRON_SPEAR, 0.5));
    }
}
