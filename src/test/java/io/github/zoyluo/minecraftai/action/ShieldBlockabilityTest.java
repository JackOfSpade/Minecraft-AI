package io.github.zoyluo.minecraftai.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The blockability table over every projectile and damage kind the shield logic knows, checked against VANILLA'S OWN data: the
 * {@code #minecraft:bypasses_shield} tag (with its nested {@code #minecraft:bypasses_armor}) is read from the 1.21.11 jar, so a
 * kind is blockable only when the game's tag says its damage type does not bypass the shield, and the Piercing rule holds.
 */
class ShieldBlockabilityTest {
    private static final Set<String> BYPASSES_SHIELD = tag("bypasses_shield");
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/action/ShieldBlockability.java");

    /** The set of damage type paths a tag holds, nested tags expanded, read from the vanilla jar on the classpath. */
    private static Set<String> tag(String name) {
        Set<String> result = new HashSet<>();
        String resource = "data/minecraft/tags/damage_type/" + name + ".json";
        try (InputStream in = ShieldBlockabilityTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertTrue(in != null, "the vanilla tag file " + resource + " is on the classpath");
            JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray values = json.getAsJsonArray("values");
            for (JsonElement value : values) {
                String entry = value.getAsString();
                if (entry.startsWith("#minecraft:")) {
                    result.addAll(tag(entry.substring("#minecraft:".length())));
                } else {
                    result.add(entry.substring("minecraft:".length()));
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        return result;
    }

    private static boolean blockableProjectile(String entityType, boolean ownerKnown, int pierce) {
        return ShieldBlockability.blockable(ShieldBlockability.Table.hit(entityType, ownerKnown), BYPASSES_SHIELD::contains, pierce);
    }

    private static boolean blockableDamage(String damageType) {
        return ShieldBlockability.blockableDamageType(damageType, BYPASSES_SHIELD::contains);
    }

    @Test
    void aNonShieldItemWithBlocksAttacksIsRecognizedAsAShield() throws IOException {
        // Ordinary JUnit intentionally does not bootstrap Minecraft's mapped registries. The
        // registry-backed ItemStack assertion lives in ShieldBlockingGameTests; this unit
        // contract pins the production predicate that it exercises.
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("stack.get(DataComponents.BLOCKS_ATTACKS) != null"),
                "the vanilla BLOCKS_ATTACKS component defines a shield");
        assertFalse(source.contains("stack.is(Items.SHIELD)"),
                "shield identity must never be restricted to Items.SHIELD");
    }

    @Test
    void theVanillaTagHoldsTheDamageTypesTheTaskNamed() {
        // #bypasses_shield = #bypasses_armor + these (the task's list, re-derived from the jar).
        for (String path : List.of("cactus", "campfire", "dry_out", "falling_anvil", "falling_stalactite", "hot_floor", "in_fire",
                "lava", "lightning_bolt", "sweet_berry_bush")) {
            assertTrue(BYPASSES_SHIELD.contains(path), path + " bypasses the shield");
        }
        for (String path : List.of("magic", "indirect_magic", "sonic_boom", "dragon_breath", "wither", "freeze", "starve", "fall",
                "drown", "on_fire", "in_wall", "cramming", "fly_into_wall", "generic", "generic_kill", "ender_pearl", "stalagmite",
                "out_of_world", "outside_border")) {
            assertTrue(BYPASSES_SHIELD.contains(path), path + " is in #bypasses_armor and so bypasses the shield");
        }
    }

    @Test
    void theBlockableProjectilesAreBlocked() {
        // Arrows and bolts without Piercing (tipped and spectral too), tridents, ghast and blaze fireballs, wither skulls, shulker
        // bullets, llama spit, wind charges (a player's and a breeze's), firework rockets (from crossbows).
        for (String type : List.of("arrow", "spectral_arrow", "trident", "small_fireball", "fireball", "wither_skull",
                "shulker_bullet", "llama_spit", "wind_charge", "breeze_wind_charge", "firework_rocket")) {
            assertTrue(blockableProjectile(type, true, 0), type + " is blockable");
        }
        // A wither skull without a living owner hits with plain magic, 5 (WitherSkull.onHitEntity): unblockable.
        assertEquals(Optional.of(new ShieldBlockability.Hit("magic", 5.0F)), ShieldBlockability.Table.hit("wither_skull", false));
        assertFalse(blockableProjectile("wither_skull", false, 0));
        // A firework rocket without explosions (or not shot at an angle) deals nothing: a zero hit is never blockable.
        assertFalse(ShieldBlockability.blockable(Optional.of(new ShieldBlockability.Hit("fireworks", 0.0F)), BYPASSES_SHIELD::contains, 0));
        // An ownerless ghast fireball is vanilla's unattributed_fireball: still blockable.
        assertEquals(Optional.of("unattributed_fireball"), ShieldBlockability.Table.hitDamageType("fireball", false));
        assertTrue(blockableProjectile("fireball", false, 0));
    }

    @Test
    void snowballsEggsAndPearlsDealNothingToAPlayerSoThereIsNothingToBlock() {
        // Snowball: thrown, 3 to a blaze and 0 to anything else; ThrownEgg and ThrownEnderpearl: thrown, 0. Player.hurtServer returns at
        // once for zero damage (no knockback either) and applyItemBlocking blocks nothing of it: never reacted to.
        for (String type : List.of("snowball", "egg", "ender_pearl")) {
            ShieldBlockability.Hit hit = ShieldBlockability.Table.hit(type, true).orElseThrow();
            assertEquals("thrown", hit.damageType(), type);
            assertEquals(0.0F, hit.damage(), type);
            assertFalse(blockableProjectile(type, true, 0), type + " deals nothing a shield could stop");
        }
        // The thrown damage type itself is an ordinary blockable type: it is the zero amount that makes these three harmless.
        assertTrue(blockableDamage("thrown"));
    }

    @Test
    void piercingArrowsAndBoltsBypassTheBlock() {
        assertFalse(blockableProjectile("arrow", true, 1));
        assertFalse(blockableProjectile("arrow", true, 4));
        assertFalse(blockableProjectile("spectral_arrow", true, 1));
        assertFalse(blockableProjectile("trident", true, 1));
        assertTrue(blockableProjectile("arrow", true, 0));
    }

    @Test
    void theUnblockableProjectilesAreNeverBlocked() {
        // Splash and lingering potions (indirect_magic), evoker fangs (indirect_magic), dragon fireballs (dragon_breath), lightning
        // (lightning_bolt, a Channeling trident's bolt too): all data-driven by the tag.
        for (String type : List.of("splash_potion", "lingering_potion", "evoker_fangs", "dragon_fireball", "lightning_bolt")) {
            assertTrue(ShieldBlockability.Table.hit(type, true).isPresent(), type + " has a hit in the table");
            assertTrue(BYPASSES_SHIELD.contains(ShieldBlockability.Table.hitDamageType(type, true).orElseThrow()),
                    type + " deals a damage type of #bypasses_shield");
            assertFalse(blockableProjectile(type, true, 0), type + " bypasses the shield");
        }
        // Things that deal no hit damage at all are never reacted to: no hit, no block.
        for (String type : List.of("experience_bottle", "area_effect_cloud", "eye_of_ender", "fishing_bobber", "some_modded_thing")) {
            assertEquals(Optional.empty(), ShieldBlockability.Table.hit(type, true), type);
            assertFalse(blockableProjectile(type, true, 0), type + " deals no blockable hit");
        }
    }

    @Test
    void meleeAndExplosionsAreBlockableFromTheFront() {
        for (String damage : List.of("mob_attack", "mob_attack_no_aggro", "player_attack", "sting", "mace_smash", "spear",
                "explosion", "player_explosion", "arrow", "trident", "fireball", "unattributed_fireball", "wither_skull",
                "mob_projectile", "spit", "thrown", "wind_charge", "fireworks", "thorns")) {
            assertTrue(blockableDamage(damage), damage + " is blockable");
        }
    }

    @Test
    void theWardenSonicBoomTheEnvironmentAndMagicAreNeverBlockable() {
        for (String damage : List.of("sonic_boom", "magic", "indirect_magic", "dragon_breath", "wither", "freeze", "starve",
                "fall", "drown", "on_fire", "in_wall", "cramming", "fly_into_wall", "generic", "generic_kill", "ender_pearl",
                "stalagmite", "out_of_world", "outside_border", "cactus", "campfire", "dry_out", "falling_anvil",
                "falling_stalactite", "hot_floor", "in_fire", "lava", "lightning_bolt", "sweet_berry_bush")) {
            assertFalse(blockableDamage(damage), damage + " bypasses the shield");
        }
    }

    @Test
    void aGuardianBeamIsBlockedForItsMobAttackPart() {
        // Guardian.GuardianAttackGoal: an indirect_magic hit (unblockable, 1; 3 on Hard; +2 for an elder), then doHurtTarget, a
        // mob_attack (6; an elder 8) that the shield stops from the front: the larger part of the beam is blockable.
        assertFalse(blockableDamage("indirect_magic"));
        assertTrue(blockableDamage("mob_attack"));
        assertTrue(ShieldBlockability.GUARDIAN_BEAM_NOTE.contains("blocked like a melee hit"));
    }

    @Test
    void everyDamageTypeOfTheTableIsEitherAttackOrBypassAsTheTaskListed() {
        // A guard against a typo in the table: every mapped damage type is a known vanilla type path.
        Set<String> known = new HashSet<>(BYPASSES_SHIELD);
        known.addAll(List.of("arrow", "trident", "fireball", "unattributed_fireball", "wither_skull", "mob_projectile", "spit",
                "thrown", "wind_charge", "fireworks"));
        for (String type : ShieldBlockability.Table.knownTypes()) {
            String damage = ShieldBlockability.Table.hitDamageType(type, true).orElseThrow();
            assertTrue(known.contains(damage), type + " maps to an unknown damage type " + damage);
        }
        // Every entry is either a real attack (damage above zero, not in the tag), a zero hit, or a type of the tag.
        for (String type : ShieldBlockability.Table.knownTypes()) {
            ShieldBlockability.Hit hit = ShieldBlockability.Table.hit(type, true).orElseThrow();
            assertTrue(hit.damage() >= 0.0F, type);
        }
    }
}
