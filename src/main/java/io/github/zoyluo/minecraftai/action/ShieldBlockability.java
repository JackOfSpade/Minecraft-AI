package io.github.zoyluo.minecraftai.action;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;

/**
 * Whether the damage an incoming projectile or melee attacker would deal is BLOCKABLE by the item in the bot's offhand: the question a
 * player answers by knowing the game. It is answered from vanilla's own data and code, never from a list of "unblockable" names:
 *
 * <ul>
 *   <li>Any item with the {@code BLOCKS_ATTACKS} component is a shield ({@link #isShield}), whatever its name.</li>
 *   <li>What a thing DEALS is the vanilla damage type and amount of its direct hit on a player ({@link Table}, the damage source the
 *       vanilla projectile class itself builds); the shield's own {@code bypassed_by} tag ({@code #minecraft:bypasses_shield}) then
 *       decides, through {@code DamageSource.is} on the real registry. A Piercing arrow bypasses the block, as in
 *       {@code LivingEntity.applyItemBlocking}; a hit of zero damage is nothing to block ({@code Player.hurtServer} ignores it
 *       altogether and {@code applyItemBlocking} returns zero for it).</li>
 *   <li>The front arc ({@code horizontal_blocking_angle} of the damage reductions, {@link #halfArcDeg}) and the block delay
 *       ({@code block_delay_seconds}) are the component's ({@link BlocksAttacks#resolveBlockedDamage},
 *       {@link BlocksAttacks#blockDelayTicks}); see {@link ShieldRules}.</li>
 * </ul>
 *
 * <p>Never blockable, each confirmed against the vanilla classes and the tag files of the 1.21.11 jar: thrown splash and lingering
 * potions ({@code indirect_magic} of the Harming effect), experience bottles and area effect clouds (no hit), snowballs, eggs and
 * ender pearls (a {@code thrown} hit of ZERO damage to a player: nothing reaches a shield; the pearl's {@code ender_pearl} damage is its
 * thrower's own landing), a warden's sonic boom ({@code sonic_boom}), Piercing arrows and bolts, evoker fangs ({@code indirect_magic}),
 * dragon fireballs (no hit; their breath cloud is magic) and the dragon breath cloud, lightning including a Channeling trident's bolt
 * ({@code lightning_bolt}), and fire, lava, cactus, berry bushes, falling anvils and stalactites and every other environment damage of
 * the tag. A guardian's beam ({@code Guardian.GuardianAttackGoal}) deals {@code indirect_magic} (unblockable: 1, 3 on Hard, +2 for an
 * elder) and then, through {@code doHurtTarget}, its {@code mob_attack} (6, an elder 8) which a shield DOES stop from the front: the
 * larger part of the beam is blockable, so it is blocked like a melee hit (see {@link #GUARDIAN_BEAM_NOTE}).
 */
public final class ShieldBlockability {
    /** The documented decision for guardian and elder guardian beams (kept here so the table, the guard and the report agree). */
    public static final String GUARDIAN_BEAM_NOTE =
            "a guardian beam deals indirect_magic (bypasses the shield) and then its mob_attack, which the shield blocks from the front:"
                    + " the larger part is blockable, so a beam locked on the bot is blocked like a melee hit";

    private ShieldBlockability() {
    }

    /**
     * The direct hit of a projectile on a player, from the vanilla projectile class: its damage type and the damage it deals (0 when it
     * deals none to a player: a shield has nothing to stop). The amount is the class's own constant, or a typical value where it
     * depends on the shot (an arrow's is {@code ceil(speed * baseDamage)}, see {@link #estimatedDamage}); it is used for the lethality
     * of a hit, never to decide whether it is blockable beyond "zero or not".
     */
    public record Hit(String damageType, float damage) {
    }

    /** The pure half: the direct hit of each vanilla projectile, keyed by entity type path. Free of any Minecraft class. */
    public static final class Table {
        private static final Map<String, Hit> HIT = Map.ofEntries(
                // Blockable: an ordinary attack damage type, and damage to stop.
                Map.entry("arrow", new Hit("arrow", 6.0F)),                  // AbstractArrow: ceil(speed * 2.0), about 6 at a full draw
                Map.entry("spectral_arrow", new Hit("arrow", 6.0F)),
                Map.entry("trident", new Hit("trident", 8.0F)),              // ThrownTrident: 8
                Map.entry("small_fireball", new Hit("fireball", 5.0F)),      // SmallFireball: 5
                Map.entry("fireball", new Hit("fireball", 6.0F)),            // LargeFireball: 6 (and the explosion)
                Map.entry("wither_skull", new Hit("wither_skull", 8.0F)),    // WitherSkull: 8
                Map.entry("shulker_bullet", new Hit("mob_projectile", 4.0F)), // ShulkerBullet: 4, levitation only when it hurts
                Map.entry("llama_spit", new Hit("spit", 1.0F)),              // LlamaSpit: 1
                Map.entry("wind_charge", new Hit("wind_charge", 1.0F)),      // AbstractWindCharge: 1 (the burst still pushes)
                Map.entry("breeze_wind_charge", new Hit("wind_charge", 1.0F)),
                Map.entry("firework_rocket", new Hit("fireworks", 7.0F)),    // FireworkRocketEntity: 5 + 2 per explosion, near its blast
                // A hit of zero damage to a player: Player.hurtServer returns at once, nothing reaches a shield.
                Map.entry("snowball", new Hit("thrown", 0.0F)),              // Snowball: 3 to a blaze, 0 to anything else
                Map.entry("egg", new Hit("thrown", 0.0F)),                   // ThrownEgg: 0
                Map.entry("ender_pearl", new Hit("thrown", 0.0F)),           // ThrownEnderpearl: 0 (ender_pearl hurts its own thrower)
                // Unblockable by the tag: they deal a damage type that bypasses the shield.
                Map.entry("splash_potion", new Hit("indirect_magic", 6.0F)), // the Harming effect of AbstractThrownPotion
                Map.entry("lingering_potion", new Hit("indirect_magic", 6.0F)),
                Map.entry("evoker_fangs", new Hit("indirect_magic", 6.0F)),  // EvokerFangs: 6
                Map.entry("dragon_fireball", new Hit("dragon_breath", 6.0F)), // no hit of its own: its breath cloud
                Map.entry("lightning_bolt", new Hit("lightning_bolt", 5.0F)));

        private Table() {
        }

        /**
         * The direct hit of an entity of this type, or empty when it deals no hit at all (an experience bottle, an area effect cloud,
         * an eye of ender, a fishing bobber, ...) or is unknown (never reacted to). {@code ownerKnown} matters for the ghast and blaze
         * fireballs only: vanilla's {@code DamageSources.fireball} is {@code unattributed_fireball} without an owner.
         */
        public static Optional<Hit> hit(String entityTypePath, boolean ownerKnown) {
            Hit hit = HIT.get(entityTypePath);
            if (hit == null) {
                return Optional.empty();
            }
            if ("fireball".equals(hit.damageType()) && !ownerKnown) {
                return Optional.of(new Hit("unattributed_fireball", hit.damage()));
            }
            return Optional.of(hit);
        }

        /** The damage type path of the direct hit (see {@link #hit}). */
        public static Optional<String> hitDamageType(String entityTypePath, boolean ownerKnown) {
            return hit(entityTypePath, ownerKnown).map(Hit::damageType);
        }

        /** Every entity type path the table knows. */
        public static Set<String> knownTypes() {
            return HIT.keySet();
        }
    }

    /**
     * The decision over data alone: a hit is blockable when it deals damage to a player, its type does not bypass the shield, and it
     * does not pierce.
     */
    public static boolean blockable(Optional<Hit> hit, Predicate<String> bypassesShield, int pierceLevel) {
        return hit.isPresent() && hit.get().damage() > 0.0F && !bypassesShield.test(hit.get().damageType()) && pierceLevel <= 0;
    }

    /** {@link #blockable(Optional, Predicate, int)} for a damage type that deals damage (a melee blow, an explosion). */
    public static boolean blockableDamageType(String damageType, Predicate<String> bypassesShield) {
        return blockable(Optional.of(new Hit(damageType, 1.0F)), bypassesShield, 0);
    }

    // ------------------------------------------------------------------ the vanilla adapter

    /** True for any item carrying the vanilla {@code BLOCKS_ATTACKS} component. */
    public static boolean isShield(ItemStack stack) {
        return !stack.isEmpty() && stack.get(DataComponents.BLOCKS_ATTACKS) != null;
    }

    /** The shield's blocking component, or null when {@code stack} is not a shield. */
    public static BlocksAttacks component(ItemStack stack) {
        return stack.isEmpty() ? null : stack.get(DataComponents.BLOCKS_ATTACKS);
    }

    private static DamageSource source(ServerLevel level, String damageTypePath, Entity direct, Entity causing) {
        ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.withDefaultNamespace(damageTypePath));
        return level.damageSources().source(key, direct, causing);
    }

    /**
     * Would this shield stop {@code source} (from the front)? Exactly the two vanilla gates of {@code LivingEntity.applyItemBlocking}:
     * the item's {@code bypassed_by} tag and a Piercing arrow as the direct entity, then the damage reductions.
     */
    public static boolean blocks(ItemStack shield, DamageSource source) {
        BlocksAttacks blocks = component(shield);
        if (blocks == null) {
            return false;
        }
        if (blocks.bypassedBy().map(source::is).orElse(false)) {
            return false;
        }
        if (source.getDirectEntity() instanceof AbstractArrow arrow && arrow.getPierceLevel() > 0) {
            return false;
        }
        return blocks.resolveBlockedDamage(source, 1.0F, 0.0D) > 0.0F;
    }

    /**
     * The half angle (degrees, measured from the head direction) inside which {@code blocks} reduces {@code source}: the widest
     * {@code horizontal_blocking_angle} of the damage reductions that apply to its type (vanilla's shield: one reduction, 90). Zero
     * when none applies.
     */
    public static double halfArcDeg(BlocksAttacks blocks, DamageSource source) {
        if (blocks == null) {
            return 0.0D;
        }
        double widest = 0.0D;
        for (BlocksAttacks.DamageReduction reduction : blocks.damageReductions()) {
            if (reduction.type().isEmpty() || reduction.type().get().contains(source.typeHolder())) {
                widest = Math.max(widest, reduction.horizontalBlockingAngle());
            }
        }
        return widest;
    }

    /**
     * The vanilla damage source the direct hit of {@code projectile} would carry, or empty when the projectile deals no hit damage to a
     * player. The source is built the way the projectile's own class builds it ({@code DamageSources.source} with the same damage type).
     */
    public static Optional<DamageSource> hitSource(ServerLevel level, Entity projectile) {
        Optional<Hit> hit = hitOf(projectile);
        Entity owner = projectile instanceof Projectile p ? p.getOwner() : null;
        return hit.filter(h -> h.damage() > 0.0F).map(h -> source(level, h.damageType(), projectile, owner));
    }

    /** The table's hit for {@code projectile}; a modded arrow is still an arrow (vanilla AbstractArrow builds DamageSources.arrow). */
    private static Optional<Hit> hitOf(Entity projectile) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType());
        Entity owner = projectile instanceof Projectile p ? p.getOwner() : null;
        if ("minecraft".equals(id.getNamespace())) {
            return Table.hit(id.getPath(), owner != null);
        }
        return projectile instanceof AbstractArrow ? Optional.of(new Hit("arrow", 6.0F)) : Optional.empty();
    }

    /** True when {@code shield} would stop the damage {@code projectile} deals on a front hit (see the class comment). */
    public static boolean projectileBlockable(ServerLevel level, ItemStack shield, Entity projectile) {
        if (!isShield(shield)) {
            return false;
        }
        return hitSource(level, projectile).map(source -> blocks(shield, source)).orElse(false);
    }

    /** The vanilla damage source of a melee blow by {@code attacker} (a player's attack, a bee's sting, any other mob's attack). */
    public static DamageSource meleeSource(ServerLevel level, LivingEntity attacker) {
        if (attacker instanceof Player player) {
            return level.damageSources().playerAttack(player);
        }
        if (attacker instanceof Bee) {
            return level.damageSources().sting(attacker);
        }
        return level.damageSources().mobAttack(attacker);
    }

    /** True when {@code shield} would stop a melee blow by {@code attacker} from the front. */
    public static boolean meleeBlockable(ServerLevel level, ItemStack shield, LivingEntity attacker) {
        return isShield(shield) && blocks(shield, meleeSource(level, attacker));
    }

    /**
     * What a hit by {@code projectile} is expected to do, in half hearts, for the "is it lethal" decisions (never to decide whether to
     * block): the arrow formula of vanilla ({@code ceil(speed * baseDamage)}, with the default base damage 2 an observer assumes: a
     * Power bow is not visible in flight), the table's figure for the other vanilla projectiles.
     */
    public static float estimatedDamage(Entity projectile) {
        if (projectile instanceof AbstractArrow && !(projectile instanceof net.minecraft.world.entity.projectile.arrow.ThrownTrident)) {
            double speed = projectile.getDeltaMovement().length();
            return (float) Math.ceil(Math.min(2.147483647E9D, speed * 2.0D));
        }
        return hitOf(projectile).map(Hit::damage).orElse(4.0F);
    }
}
