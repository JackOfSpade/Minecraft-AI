package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.HumanAim;
import io.github.zoyluo.minecraftai.action.InteractAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.OffhandPolicy;
import io.github.zoyluo.minecraftai.action.RangedWeapon;
import io.github.zoyluo.minecraftai.action.ShieldBlockability;
import io.github.zoyluo.minecraftai.action.ShieldRules;
import io.github.zoyluo.minecraftai.action.ShieldRules.MainHandKind;
import io.github.zoyluo.minecraftai.action.ShieldRules.UseKind;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.perception.CreaturePerception;
import io.github.zoyluo.minecraftai.perception.CreatureSenses;
import io.github.zoyluo.minecraftai.perception.ExposureTracker;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;

/**
 * The ONE owner of a companion's reactive shield use, for every task (following, escorting, mining, gathering, idle, combat): a
 * bot blocks what it has NOTICED and the shield can stop, the way a competent player does, and never raises it against damage it
 * cannot stop. One instance per server, one {@link #tickBot} per bot per server tick ({@code BotTickCoordinator}, after the tasks
 * of the tick); the tasks ask it ({@link #holding}) instead of re-deciding, so they never fight over the use-item hand. See
 * docs/SHIELD_USE.md.
 *
 * <h2>What it reacts to (all NOTICED through the perception model, see docs/PERCEPTION.md)</h2>
 * <ul>
 *   <li>An incoming projectile on a hit course that {@link ShieldBlockability#projectileBlockable} says the shield stops (arrows and
 *       bolts without Piercing, tridents, ghast and blaze fireballs, wither skulls, shulker bullets, llama spit, wind charges, firework
 *       rockets), seen in flight or heard being shot. One from a shooter the bot is tracking was anticipated (the bot watched the
 *       release) and is reacted to at once; any other is a first sighting and waits the human reaction time of the shared formula
 *       ({@link ShieldRules#reacted}), so an arrow from a shooter nobody noticed normally just hits. A raise that cannot be active in
 *       time (turn into the front arc, hotbar change, the shield's own block delay) is not started: the hit is taken, like a player
 *       caught off guard.</li>
 *   <li>A noticed hostile with a bow drawn or a crossbow charging or loaded, aimed at the bot (weapon-neutral): the pre-emptive raise
 *       of a player in PvP, held until the shot lands or the draw stops. When that shooter is the combat target within striking reach,
 *       the combat task's melee rhythm owns the shield instead (it comes down for each swing and goes back up between them).</li>
 *   <li>A noticed guardian or elder guardian whose beam is locked on the bot: the beam's {@code mob_attack} part is blocked from the
 *       front ({@link ShieldBlockability#GUARDIAN_BEAM_NOTE}); the shield is held until the beam lets go.</li>
 *   <li>A noticed creeper whose fuse is lit and late: the explosion is blockable like any hit.</li>
 * </ul>
 * Never reacted to: thrown splash and lingering potions, experience bottles, snowballs, eggs, ender pearls, a warden's sonic boom,
 * Piercing arrows, evoker fangs, dragon fireballs and breath clouds, lightning, fire, lava and every other damage of
 * {@code #bypasses_shield}; a projectile from behind that nobody heard or saw.
 *
 * <h2>How (vanilla's own paths)</h2>
 * <ul>
 *   <li>The use goes through {@code gameMode.useItem}, MAIN_HAND first and then OFF_HAND, exactly as the client tries them. A main-hand
 *       item that would take the use (a bow with ammunition, a loaded crossbow, food, a usable trident, a spear, armour to swap, ...)
 *       makes the bot change its hotbar first to its melee weapon, another plain item or an empty slot, which costs one tick and the
 *       vanilla attack-strength reset of a changed main-hand item.</li>
 *   <li>The head turns at the human aim speed ({@link HumanAim}), only as far as the shield's own front arc
 *       ({@code horizontal_blocking_angle}, less a margin) needs. Nothing turns instantly.</li>
 *   <li>A shield on its item cooldown (an axe hit disables it for {@code Weapon.disableBlockingForSeconds}) is not raised and no
 *       attempt is made until the cooldown ends (vanilla's use would fail): no re-raise spam.</li>
 *   <li>While it is up a player cannot mine or attack: {@code ActionPack.tickBreak} pauses and {@code InteractAction.attackEntity}
 *       refuses ({@link #holdsShield}); the movement keys keep their task's direction at the vanilla 0.2 use-item slowdown
 *       ({@code PaceRules}), so a follower keeps following, only slower.</li>
 *   <li>The offhand content is the equipment rule's ({@link OffhandPolicy}): an empty offhand, or one that holds a totem only because
 *       no shield was carried, takes the best carried shield; anything else the offhand holds (arrows of a ranged loadout, a torch)
 *       stays, and then there is no shield to raise.</li>
 * </ul>
 *
 * <h2>The hand policy (what a block may interrupt)</h2>
 * Eating in progress, a bow being drawn and any other use are finished, not cancelled, for a hit that only hurts; they are cancelled
 * only for a hit that would be lethal ({@link ShieldRules#mayInterrupt}). A ranged exchange of the combat task (its bow or crossbow
 * drawn or loaded, arrow in the offhand) keeps shooting: the shield is not at hand (its arrow took the offhand) and the answer to a
 * shooter is the return shot. The emergency tasks that build or escape (shelter, barricade, creeper defence, lava, fire, powder snow)
 * own the hands too.
 */
public final class ShieldGuard {
    public static final ShieldGuard INSTANCE = new ShieldGuard();

    /** A skeleton is "visibly drawing" once its bow has been up this long (a full draw is 20 ticks); the shield needs five ticks to start blocking. */
    static final int SHOOTER_DRAW_TICKS = 12;
    /** Head-aim tolerance: the shooter must face the bot within roughly twenty degrees. */
    static final double SHOOTER_AIM_DOT = 0.94D;
    /** How far a noticed shooter or guardian is looked for: what the bot can notice at all (the perception radius decides, see {@code CreatureSenses}). */
    private static final double SCAN_RANGE = 24.0D;
    /** Raise the shield a little ahead of CreeperDefenseTask's own late-fuse wall threshold. */
    static final float CREEPER_FUSE_THRESHOLD = 0.35F;
    static final double CREEPER_FUSE_RANGE = 10.0D;
    /** Ticks between attempts after a raise that vanilla refused for a reason other than the cooldown. */
    private static final int RETRY_TICKS = 10;

    /** Who raised the shield that is up: the reactive owner for a threat, or a task that owns its shield use (the combat melee rhythm, the creeper shield phase). */
    public enum Owner {
        NONE,
        REACTIVE,
        TASK
    }

    /** The outcome of one raise attempt. */
    public enum Raise {
        /** The shield came up this tick. */
        RAISED,
        /** It was up already. */
        ALREADY_UP,
        /** No shield in the offhand (and none to put there). */
        NO_SHIELD,
        /** The shield is on its item cooldown (an axe hit): vanilla's use would fail. */
        ON_COOLDOWN,
        /** The main-hand item would take the use: the hotbar was changed this tick, the raise follows. */
        SWITCHED_HOTBAR,
        /** The hands are busy with another use, or vanilla refused. */
        REFUSED
    }

    private enum Kind {
        PROJECTILE("incoming_projectile"),
        CREEPER("creeper_fuse"),
        GUARDIAN_BEAM("guardian_beam"),
        SHOOTER("shooter_draw");

        final String reason;

        Kind(String reason) {
            this.reason = reason;
        }
    }

    private record Threat(Kind kind, Vec3 facePoint, double halfArcDeg, double ticksToImpact, float damage, String source) {
    }

    private static final class State {
        Owner owner = Owner.NONE;
        long retryAt;
        String lastRefusal;
        /** The continuous exposure of each sensed projectile (entity id): the reaction time of a first sighting. */
        final ExposureTracker<Integer> projectiles = new ExposureTracker<>();
    }

    private final Map<AIPlayerEntity, State> states = new WeakHashMap<>();

    private ShieldGuard() {
    }

    private State stateOf(AIPlayerEntity bot) {
        return states.computeIfAbsent(bot, ignored -> new State());
    }

    // ------------------------------------------------------------------ queries for the tasks

    /** True while the reactive owner has the shield up (the tasks skip whatever needs the hands: mining, striking). */
    public boolean holding(AIPlayerEntity bot) {
        State state = states.get(bot);
        return state != null && state.owner == Owner.REACTIVE && usingShield(bot);
    }

    /** {@link #holding} for the code that has no instance at hand (the action layer). */
    public static boolean holdsShield(AIPlayerEntity bot) {
        return INSTANCE.holding(bot);
    }

    /** True when the bot has a shield up right now (vanilla blocks with one in either hand; the offhand is where the equipment rule puts it). */
    public static boolean usingShield(AIPlayerEntity bot) {
        return bot.isUsingItem() && ShieldBlockability.isShield(bot.getUseItem());
    }

    /** True when a shield could be raised now: one in the offhand (or carried and free to go there), not on its item cooldown. */
    public static boolean shieldUsable(AIPlayerEntity bot) {
        ItemStack shield = shieldStack(bot);
        return !shield.isEmpty() && !bot.getCooldowns().isOnCooldown(shield);
    }

    /**
     * The shield that would block: the offhand one (any item with {@code BLOCKS_ATTACKS}), else one in the main hand (vanilla blocks
     * with it from there), else a carried shield when the offhand rule would put one there ({@link OffhandPolicy}: an empty offhand or
     * a totem), else empty.
     */
    public static ItemStack shieldStack(AIPlayerEntity bot) {
        ItemStack offhand = bot.getOffhandItem();
        if (ShieldBlockability.isShield(offhand)) {
            return offhand;
        }
        if (ShieldBlockability.isShield(bot.getMainHandItem())) {
            return bot.getMainHandItem();
        }
        if (!offhandTakesAShield(bot)) {
            return ItemStack.EMPTY;
        }
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            ItemStack stack = inventory.getNonEquipmentItems().get(slot);
            if (stack.is(Items.SHIELD)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /** The offhand rule puts a carried shield into an empty offhand or in place of a totem; any other item stays where it is. */
    private static boolean offhandTakesAShield(AIPlayerEntity bot) {
        ItemStack offhand = bot.getOffhandItem();
        return offhand.isEmpty() || offhand.is(Items.TOTEM_OF_UNDYING);
    }

    // ------------------------------------------------------------------ the vanilla raise and lower

    /**
     * Brings the offhand shield up through the vanilla use path (MAIN_HAND first, then OFF_HAND), or says why not. Never starts a use
     * the hand policy forbids: the caller has cancelled what it may cancel. A main-hand item that would take the use is swapped for a
     * plain one first ({@link Raise#SWITCHED_HOTBAR}); the next call raises.
     */
    public static Raise raise(AIPlayerEntity bot, Owner owner) {
        ItemStack offhand = bot.getOffhandItem();
        if (!ShieldBlockability.isShield(offhand) && !ShieldBlockability.isShield(bot.getMainHandItem())) {
            // The offhand rule of the equipment job: the best carried shield into an empty offhand or in place of a totem.
            if (!offhandTakesAShield(bot) || !OffhandPolicy.apply(bot)) {
                return Raise.NO_SHIELD;
            }
            offhand = bot.getOffhandItem();
            if (!ShieldBlockability.isShield(offhand)) {
                return Raise.NO_SHIELD;
            }
        }
        if (bot.getCooldowns().isOnCooldown(shieldStack(bot))) {
            return Raise.ON_COOLDOWN;
        }
        if (bot.isUsingItem()) {
            return usingShield(bot) ? Raise.ALREADY_UP : Raise.REFUSED;
        }
        MainHandKind main = classifyMainHand(bot, bot.getMainHandItem());
        if (ShieldRules.mainHandConsumesUse(main)) {
            if (main == MainHandKind.SHIELD) {
                // A shield in the main hand blocks from there: nothing to switch, the vanilla order finds it first.
                return tryVanillaUse(bot, owner);
            }
            return switchMainHandAway(bot) ? Raise.SWITCHED_HOTBAR : Raise.REFUSED;
        }
        return tryVanillaUse(bot, owner);
    }

    /** How many times the vanilla use path was actually tried to raise a shield (a seam for the tests: no attempts on a disabled shield). */
    private static volatile long useAttempts;

    static long vanillaUseAttempts() {
        return useAttempts;
    }

    private static Raise tryVanillaUse(AIPlayerEntity bot, Owner owner) {
        useAttempts++;
        for (InteractionHand hand : InteractionHand.values()) {
            if (bot.getItemInHand(hand).isEmpty()) {
                continue;
            }
            if (InteractAction.useItemInAir(bot, hand).isSuccess()) {
                break;
            }
        }
        if (bot.isUsingItem() && ShieldBlockability.isShield(bot.getUseItem())) {
            INSTANCE.stateOf(bot).owner = owner;
            return Raise.RAISED;
        }
        if (bot.isUsingItem()) {
            // The vanilla order let a main-hand item take the use after all (misjudged kind): cancel it, never release a drawn bow.
            bot.stopUsingItem();
        }
        return Raise.REFUSED;
    }

    /** Lowers the shield if the bot is using it (a player releases the use key); the owner mark is cleared. */
    public static void lower(AIPlayerEntity bot) {
        if (usingShield(bot)) {
            bot.releaseUsingItem();
        }
        State state = INSTANCE.states.get(bot);
        if (state != null) {
            state.owner = Owner.NONE;
        }
    }

    /** Lowers the shield only when {@code owner} raised it (a task letting go of its own raise, never of the reactive owner's). */
    public static void lowerIfOwner(AIPlayerEntity bot, Owner owner) {
        State state = INSTANCE.states.get(bot);
        if (state != null && state.owner == owner) {
            lower(bot);
        }
    }

    /** The shield's {@code block_delay_seconds} in ticks (vanilla's five for a shield); 5 when the bot has none. */
    public static int blockDelayTicks(AIPlayerEntity bot) {
        BlocksAttacks component = ShieldBlockability.component(shieldStack(bot));
        return component == null ? 5 : component.blockDelayTicks();
    }

    /** What the main hand holds, for the vanilla use order (each case mirrors the item's own {@code use}). */
    static MainHandKind classifyMainHand(AIPlayerEntity bot, ItemStack stack) {
        if (stack.isEmpty()) {
            return MainHandKind.EMPTY;
        }
        if (ShieldBlockability.isShield(stack)) {
            return MainHandKind.SHIELD;
        }
        if (stack.getItem() instanceof BowItem) {
            // BowItem.use: FAIL without ammunition (the use passes to the offhand), else a draw.
            return bot.hasInfiniteMaterials() || !bot.getProjectile(stack).isEmpty()
                    ? MainHandKind.RANGED_READY : MainHandKind.RANGED_UNUSABLE;
        }
        if (stack.getItem() instanceof CrossbowItem) {
            // CrossbowItem.use: a loaded crossbow shoots, an unloaded one with ammunition starts charging, else FAIL.
            return CrossbowItem.isCharged(stack) || !bot.getProjectile(stack).isEmpty()
                    ? MainHandKind.RANGED_READY : MainHandKind.RANGED_UNUSABLE;
        }
        if (stack.getItem() instanceof TridentItem) {
            // TridentItem.use: FAIL when the next damage breaks it, or with Riptide out of water and rain.
            boolean fails = stack.nextDamageWillBreak()
                    || EnchantmentHelper.getTridentSpinAttackStrength(stack, bot) > 0.0F && !bot.isInWaterOrRain();
            return fails ? MainHandKind.RANGED_UNUSABLE : MainHandKind.RANGED_READY;
        }
        Consumable consumable = stack.get(DataComponents.CONSUMABLE);
        if (consumable != null) {
            // Consumable.startConsuming: FAIL for food the bot cannot eat now (full), else eating starts.
            return consumable.canConsume(bot, stack) ? MainHandKind.CONSUMABLE_READY : MainHandKind.CONSUMABLE_REFUSED;
        }
        if (stack.get(DataComponents.EQUIPPABLE) != null) {
            return MainHandKind.EQUIPPABLE_SWAP;
        }
        if (stack.get(DataComponents.KINETIC_WEAPON) != null || stack.getUseDuration(bot) > 0 || isInstantUseItem(stack)) {
            return MainHandKind.OTHER_USE;
        }
        return MainHandKind.PLAIN;
    }

    /** Items whose use in the air acts at once (a throw, a cast, a bucket, a map): they take the use like a draw does. */
    private static boolean isInstantUseItem(ItemStack stack) {
        var item = stack.getItem();
        return item instanceof net.minecraft.world.item.SnowballItem
                || item instanceof net.minecraft.world.item.EggItem
                || item instanceof net.minecraft.world.item.EnderpearlItem
                || item instanceof net.minecraft.world.item.ThrowablePotionItem
                || item instanceof net.minecraft.world.item.ExperienceBottleItem
                || item instanceof net.minecraft.world.item.WindChargeItem
                || item instanceof net.minecraft.world.item.FishingRodItem
                || item instanceof net.minecraft.world.item.BucketItem
                || item instanceof net.minecraft.world.item.MapItem
                || item instanceof net.minecraft.world.item.EmptyMapItem
                || item instanceof net.minecraft.world.item.FireworkRocketItem;
    }

    /**
     * Changes the selected hotbar slot to an item that does not take the use: the best melee weapon first (its normal place in a
     * fight), then any other plain hotbar item, then an empty slot. True when the main hand no longer takes the use.
     */
    private static boolean switchMainHandAway(AIPlayerEntity bot) {
        CombatCore.ensureMeleeWeapon(bot);
        if (!ShieldRules.mainHandConsumesUse(classifyMainHand(bot, bot.getMainHandItem()))) {
            BotLog.action(bot, "shield_hotbar_switch", "to", bot.getMainHandItem().getItem());
            return true;
        }
        Inventory inventory = bot.getInventory();
        int empty = -1;
        for (int slot = 0; slot <= 8; slot++) {
            ItemStack candidate = inventory.getNonEquipmentItems().get(slot);
            if (candidate.isEmpty()) {
                if (empty < 0) {
                    empty = slot;
                }
                continue;
            }
            if (!ShieldRules.mainHandConsumesUse(classifyMainHand(bot, candidate))) {
                InventoryAction.equipFromSlot(bot, slot);
                BotLog.action(bot, "shield_hotbar_switch", "to", candidate.getItem());
                return true;
            }
        }
        if (empty >= 0) {
            inventory.setSelectedSlot(empty);
            inventory.setChanged();
            BotLog.action(bot, "shield_hotbar_switch", "to", "empty_slot");
            return true;
        }
        return false;
    }

    /** What the bot is using right now, for the hand policy. */
    static UseKind classifyUse(AIPlayerEntity bot) {
        if (!bot.isUsingItem()) {
            return UseKind.NONE;
        }
        ItemStack used = bot.getUseItem();
        if (ShieldBlockability.isShield(used)) {
            return UseKind.SHIELD;
        }
        if (used.get(DataComponents.CONSUMABLE) != null) {
            return UseKind.CONSUMING;
        }
        if (RangedWeapon.isRanged(used) || used.getItem() instanceof TridentItem) {
            return UseKind.RANGED_DRAW;
        }
        return UseKind.OTHER;
    }

    // ------------------------------------------------------------------ the per-tick owner

    /**
     * One tick for one bot: assess what threatens it, face it and raise or hold the shield, or lower a shield this owner raised when
     * nothing threatens any more. Never throws into the server tick.
     */
    public void tickBot(MinecraftServer server, AIPlayerEntity bot) {
        try {
            tick(bot);
        } catch (RuntimeException exception) {
            states.remove(bot);
            BotLog.error(bot, "shield_guard_failed", exception);
        }
    }

    private void tick(AIPlayerEntity bot) {
        if (!bot.isAlive() || bot.isRemoved() || !(bot.level() instanceof ServerLevel level)) {
            states.remove(bot);
            return;
        }
        State state = stateOf(bot);
        long now = level.getGameTime();
        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        if (handsOwnedByTask(active)) {
            state.projectiles.clear();
            releaseIfOwned(bot, state, "task_owns_hands");
            return;
        }
        ItemStack shield = shieldStack(bot);
        Threat threat = shield.isEmpty() ? null : assess(bot, level, shield, state, now, active);
        if (threat == null) {
            releaseIfOwned(bot, state, "threat_over");
            return;
        }
        engage(bot, state, threat, now);
    }

    /**
     * The bot's hands go to something more urgent than a block for this tick (the navigation safety net rescuing it from drowning or
     * lava): a shield this owner raised comes down (a raised shield slows every stroke by vanilla's item-use factor).
     */
    public void standDown(AIPlayerEntity bot, String reason) {
        State state = states.get(bot);
        if (state != null) {
            state.projectiles.clear();
            releaseIfOwned(bot, state, reason);
        }
    }

    /** The tasks that own the hands for something more urgent or more specific than a block (see the class comment). */
    private static boolean handsOwnedByTask(Task task) {
        if (task == null) {
            return false;
        }
        if (task instanceof CombatTask combat) {
            return combat.isRangedExchange();
        }
        return task instanceof EmergencyShelterTask
                || task instanceof MiningBarricadeTask
                || task instanceof CreeperDefenseTask
                || task instanceof LavaEscapeTask
                || task instanceof PowderSnowEscapeTask
                || task instanceof FireExtinguishTask;
    }

    private void releaseIfOwned(AIPlayerEntity bot, State state, String reason) {
        if (state.owner == Owner.REACTIVE) {
            if (usingShield(bot)) {
                bot.releaseUsingItem();
                BotLog.action(bot, "reactive_shield_lowered", "reason", reason);
            }
            state.owner = Owner.NONE;
            state.lastRefusal = null;
        } else if (state.owner != Owner.NONE && !usingShield(bot)) {
            state.owner = Owner.NONE;
        }
    }

    private Threat assess(AIPlayerEntity bot, ServerLevel level, ItemStack shield, State state, long now, Task active) {
        BlocksAttacks component = ShieldBlockability.component(shield);
        int delay = component == null ? 5 : component.blockDelayTicks();
        boolean raised = usingShield(bot);

        // 1. What is in flight: the soonest projectile the bot may act on (reaction time) and can block in time.
        List<ProjectileThreat.Incoming> incoming = ProjectileThreat.incoming(bot, shield);
        Set<Integer> sensed = new HashSet<>();
        for (ProjectileThreat.Incoming inc : incoming) {
            sensed.add(inc.projectile().getId());
        }
        state.projectiles.retain(sensed);
        Map<Integer, Long> exposure = new HashMap<>();
        for (ProjectileThreat.Incoming inc : incoming) {
            exposure.put(inc.projectile().getId(), state.projectiles.sighted(inc.projectile().getId(), now));
        }
        for (ProjectileThreat.Incoming inc : incoming) {
            Projectile projectile = inc.projectile();
            long exposed = exposure.get(projectile.getId());
            if (!reactedTo(bot, projectile, exposed)) {
                logRefusalOnce(bot, state, "projectile_reaction_time");
                continue;
            }
            Optional<DamageSource> source = ShieldBlockability.hitSource(level, projectile);
            double arc = source.map(s -> ShieldBlockability.halfArcDeg(component, s)).orElse(0.0D);
            Vec3 at = projectile.position();
            if (raised || canBeActiveInTime(bot, at, inc.ticksToClosestApproach(), delay, arc)) {
                return new Threat(Kind.PROJECTILE, at, arc, inc.ticksToClosestApproach(), ShieldBlockability.estimatedDamage(projectile),
                        BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType()).toString());
            }
            logRefusalOnce(bot, state, "projectile_too_late");
        }
        // 2. A late creeper fuse (the creeper defence task has its own shield phase).
        if (!(active instanceof CreeperDefenseTask)) {
            Creeper creeper = nearbyImminentCreeper(bot);
            if (creeper != null) {
                DamageSource blast = level.damageSources().explosion(creeper, creeper);
                double arc = ShieldBlockability.halfArcDeg(component, blast);
                if (ShieldBlockability.blocks(shield, blast) && canFace(bot, state, creeper.position(), arc)) {
                    return new Threat(Kind.CREEPER, creeper.position(), arc, Double.NaN, 12.0F, "creeper");
                }
            }
        }
        // 3. A guardian's beam locked on the bot: its mob_attack part is blockable from the front.
        Guardian guardian = guardianBeamOn(bot, active);
        if (guardian != null) {
            DamageSource bite = ShieldBlockability.meleeSource(level, guardian);
            double arc = ShieldBlockability.halfArcDeg(component, bite);
            if (ShieldBlockability.blocks(shield, bite) && canFace(bot, state, guardian.position(), arc)) {
                return new Threat(Kind.GUARDIAN_BEAM, guardian.position(), arc, Double.NaN,
                        (float) guardian.getAttributeValue(Attributes.ATTACK_DAMAGE) + 1.0F,
                        BuiltInRegistries.ENTITY_TYPE.getKey(guardian.getType()).toString());
            }
        }
        // 4. A shooter drawing (or holding a loaded crossbow) at the bot: held until the shot lands or the draw stops.
        LivingEntity shooter = drawingShooterAt(bot, active);
        if (shooter != null) {
            DamageSource arrow = level.damageSources().source(DamageTypes.ARROW, shooter);
            double arc = ShieldBlockability.halfArcDeg(component, arrow);
            if (canFace(bot, state, shooter.getEyePosition(), arc)) {
                return new Threat(Kind.SHOOTER, shooter.getEyePosition(), arc, Double.NaN, 6.0F,
                        BuiltInRegistries.ENTITY_TYPE.getKey(shooter.getType()).toString());
            }
        }
        return null;
    }

    /**
     * A source with no flight time to race (a lit fuse, a charging beam, a drawn bow) is blocked only when the bot can have it in the
     * front arc: already there, or a turn the bot is free to make (a walker steers the head where the bot goes, so a source outside the
     * arc of a walking bot cannot be faced, and a raised shield would only slow it down).
     */
    private boolean canFace(AIPlayerEntity bot, State state, Vec3 source, double halfArcDeg) {
        if (halfArcDeg <= 0.0D) {
            return false;
        }
        double offset = ShieldRules.offsetDeg(bot.getYHeadRot(), source.x - bot.getX(), source.z - bot.getZ());
        if (ShieldRules.degreesToTurn(offset, halfArcDeg) <= 0.0D || !walking(bot)) {
            return true;
        }
        logRefusalOnce(bot, state, "source_outside_arc_while_walking");
        return false;
    }

    /** A walker (a route, a walk, a step) is steering the head where the bot goes. */
    private static boolean walking(AIPlayerEntity bot) {
        return !bot.getActionPack().isPathExecutorIdle() || !bot.getActionPack().isWalkToIdle() || !bot.getActionPack().stepIdle();
    }

    /**
     * May the bot act on {@code projectile} yet ({@link ShieldRules#reacted})? Anticipated when its shooter is a creature the bot is
     * tracking; otherwise the reaction time of the shared formula for the projectile's current distance and angle (in the view field:
     * the angle factor of sight; out of it, so only its shot was heard: the hearing rule's factor 1), over its continuous exposure.
     */
    private static boolean reactedTo(AIPlayerEntity bot, Projectile projectile, long exposedTicks) {
        boolean on = CreatureSenses.enabled();
        Entity owner = projectile.getOwner();
        boolean tracked = owner instanceof LivingEntity living && living != bot && living.isAlive()
                && ObservableWorldQuery.canNoticeCreature(bot, living);
        if (!on || tracked) {
            return ShieldRules.reacted(on, tracked, 0.0D, 0.0D);
        }
        CreaturePerception.Params params = perceptionParams();
        Vec3 eye = bot.getEyePosition();
        Vec3 toward = projectile.position().subtract(eye);
        Vec3 look = bot.getViewVector(1.0F);
        double theta = CreaturePerception.angleDeg(look.x, look.y, look.z, toward.x, toward.y, toward.z);
        double angleForReaction = theta <= params.peripheralHalfAngleDeg() ? theta : 0.0D;
        double required = CreaturePerception.requiredSeconds(params, angleForReaction, toward.length(),
                CreaturePerception.Subject.of(false), false);
        return ShieldRules.reacted(true, false, CreaturePerception.exposureSeconds(exposedTicks), required);
    }

    private static CreaturePerception.Params perceptionParams() {
        MinecraftAiConfig config = MinecraftAiConfig.get();
        MinecraftAiConfig.PerceptionBehaviour behaviour = config == null || config.behaviour() == null
                ? MinecraftAiConfig.PerceptionBehaviour.defaults()
                : config.behaviour().perceptionOrDefaults();
        return behaviour.params();
    }

    private static boolean canBeActiveInTime(AIPlayerEntity bot, Vec3 source, double ticksToImpact, int delay, double halfArcDeg) {
        if (halfArcDeg <= 0.0D) {
            return false;
        }
        double offset = ShieldRules.offsetDeg(bot.getYHeadRot(), source.x - bot.getX(), source.z - bot.getZ());
        double degrees = ShieldRules.degreesToTurn(offset, halfArcDeg);
        // A walker (a route, a walk, a step) steers the head every tick: while it walks, the head looks where the bot goes, so a
        // source outside the front arc cannot be turned to (a player walking forward blocks what comes from the front half only).
        int turn = degrees <= 0.0D ? 0
                : walking(bot) ? Integer.MAX_VALUE
                : ShieldRules.turnTicks(degrees, HumanAim.maxTurnDegPerTick());
        MainHandKind main = classifyMainHand(bot, bot.getMainHandItem());
        int switching = ShieldRules.mainHandConsumesUse(main) && main != MainHandKind.SHIELD ? ShieldRules.HOTBAR_SWITCH_TICKS : 0;
        return ShieldRules.canBeActiveInTime(ticksToImpact, turn, delay, switching);
    }

    private void logRefusalOnce(AIPlayerEntity bot, State state, String reason) {
        if (!reason.equals(state.lastRefusal)) {
            state.lastRefusal = reason;
            BotLog.action(bot, "reactive_shield_skipped", "reason", reason);
        }
    }

    private void engage(AIPlayerEntity bot, State state, Threat threat, long now) {
        UseKind use = classifyUse(bot);
        float health = bot.getHealth() + bot.getAbsorptionAmount();
        boolean lethal = ShieldRules.lethal(threat.damage(), health);
        if (!ShieldRules.mayInterrupt(use, lethal)) {
            logRefusalOnce(bot, state, "hands_busy_" + use.name().toLowerCase(Locale.ROOT));
            return;
        }
        faceIntoArc(bot, threat.facePoint(), threat.halfArcDeg());
        if (usingShield(bot)) {
            state.owner = Owner.REACTIVE;
            return;
        }
        if (now < state.retryAt) {
            return;
        }
        if (use != UseKind.NONE && use != UseKind.SHIELD) {
            // Cancel, never release: a drawn bow must not fire, food not be finished; only a lethal hit gets this far.
            bot.stopUsingItem();
        }
        Raise result = raise(bot, Owner.REACTIVE);
        switch (result) {
            case RAISED -> {
                state.lastRefusal = null;
                BotLog.action(bot, "reactive_shield_raised",
                        "reason", threat.kind().reason,
                        "source", threat.source(),
                        "ticks_to_impact", Double.isNaN(threat.ticksToImpact()) ? -1 : Math.round(threat.ticksToImpact() * 10.0D) / 10.0D);
            }
            case ON_COOLDOWN -> logRefusalOnce(bot, state, "shield_on_cooldown");
            case NO_SHIELD -> logRefusalOnce(bot, state, "no_shield");
            case REFUSED -> {
                state.retryAt = now + RETRY_TICKS;
                logRefusalOnce(bot, state, "use_refused");
            }
            default -> {
            }
        }
    }

    /**
     * Turns at the human aim speed, only as far as the front arc (the shield's {@code horizontal_blocking_angle} each side of the HEAD,
     * less the margin) needs: a threat already in front is blocked without turning, so a follower keeps looking where it walks.
     */
    private static void faceIntoArc(AIPlayerEntity bot, Vec3 source, double halfArcDeg) {
        double offset = ShieldRules.offsetDeg(bot.getYHeadRot(), source.x - bot.getX(), source.z - bot.getZ());
        if (ShieldRules.degreesToTurn(offset, halfArcDeg) > 0.0D) {
            HumanAim.lookToward(bot, source);
        }
    }

    // ------------------------------------------------------------------ what threatens the bot

    /** The nearest noticed creeper whose fuse has reached a late stage and is close enough for the blast to matter. */
    static Creeper nearbyImminentCreeper(AIPlayerEntity bot) {
        return bot.level().getEntitiesOfClass(Creeper.class,
                        bot.getBoundingBox().inflate(CREEPER_FUSE_RANGE),
                        creeper -> creeper.isAlive()
                                && ObservableWorldQuery.canNoticeCreature(bot, creeper)
                                && creeper.getSwelling(1.0F) >= CREEPER_FUSE_THRESHOLD)
                .stream()
                .min(Comparator.comparingDouble(bot::distanceToSqr))
                .orElse(null);
    }

    /**
     * The nearest noticed guardian (or elder guardian) whose beam is locked on the bot: the beam is in plain view from its first tick
     * (the synced attack target a client draws it to), so a player sees it charge and raises the shield. On the server the beam's
     * target is the guardian's target while the beam is on ({@code Guardian.getActiveAttackTarget}).
     */
    static Guardian guardianBeamOn(AIPlayerEntity bot) {
        return guardianBeamOn(bot, null);
    }

    /** {@link #guardianBeamOn(AIPlayerEntity)} without the guardian the combat task's melee rhythm is fighting within reach. */
    private static Guardian guardianBeamOn(AIPlayerEntity bot, Task active) {
        CombatTask combat = active instanceof CombatTask c ? c : null;
        return bot.level().getEntitiesOfClass(Guardian.class,
                        bot.getBoundingBox().inflate(SCAN_RANGE),
                        guardian -> guardian.isAlive()
                                && guardian.hasActiveAttackTarget()
                                && guardian.getActiveAttackTarget() == bot
                                && ObservableWorldQuery.canNoticeCreature(bot, guardian)
                                && (combat == null || !combat.meleeRhythmAgainst(bot, guardian)))
                .stream()
                .min(Comparator.comparingDouble(bot::distanceToSqr))
                .orElse(null);
    }

    /**
     * A noticed hostile shooter that visibly has its ranged weapon up at the bot: its use pose is synced, its bow (or crossbow) has
     * been up long enough for the shot to be imminent, or its crossbow is loaded, and its head is aimed at the bot. The shooter side
     * is weapon-neutral ({@link CombatCore#isRangedWeaponUp}): a skeleton's bow, a pillager's or a foreign bot's crossbow count
     * the same; trident throwers stay out. Decided only on synced pose, held item and head aim, never on the mob's hidden target.
     */
    static LivingEntity drawingShooterAt(AIPlayerEntity bot) {
        return drawingShooterAt(bot, null);
    }

    /** {@link #drawingShooterAt(AIPlayerEntity)} without the shooter the combat task's melee rhythm is fighting within reach. */
    private static LivingEntity drawingShooterAt(AIPlayerEntity bot, Task active) {
        CombatTask combat = active instanceof CombatTask c ? c : null;
        return bot.level().getEntitiesOfClass(LivingEntity.class,
                        bot.getBoundingBox().inflate(SCAN_RANGE),
                        shooter -> shooter != bot
                                && shooter.isAlive()
                                && CombatCore.hostileTo(bot, shooter)
                                && ObservableWorldQuery.canNoticeCreature(bot, shooter)
                                && isDrawingBowAt(shooter, bot)
                                && (combat == null || !combat.meleeRhythmAgainst(bot, shooter)))
                .stream()
                .min(Comparator.comparingDouble(bot::distanceToSqr))
                .orElse(null);
    }

    /** True when {@code shooter} has a bow (or crossbow) up and its head is aimed at {@code bot}: see {@link #drawingShooterAt}. */
    static boolean isDrawingBowAt(LivingEntity shooter, AIPlayerEntity bot) {
        if (!CombatCore.isRangedWeaponUp(shooter, SHOOTER_DRAW_TICKS)) {
            return false;
        }
        Vec3 toBot = bot.getEyePosition().subtract(shooter.getEyePosition());
        if (toBot.lengthSqr() < 1.0E-6D) {
            return true;
        }
        return shooter.getViewVector(1.0F).dot(toBot.normalize()) >= SHOOTER_AIM_DOT;
    }
}
