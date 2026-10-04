package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.EquipAction;
import io.github.zoyluo.minecraftai.action.HumanAim;
import io.github.zoyluo.minecraftai.action.InteractAction;
import io.github.zoyluo.minecraftai.action.OffhandPolicy;
import io.github.zoyluo.minecraftai.action.RangedWeapon;
import io.github.zoyluo.minecraftai.action.ShieldBlockability;
import io.github.zoyluo.minecraftai.action.ShieldRules;
import io.github.zoyluo.minecraftai.action.StrikeLegality;
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
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.PrimedTnt;
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
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.gamerules.GameRules;
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
 *   <li>A noticed creeper whose fuse is lit and late, or a visibly primed TNT entity within its real blast envelope: the explosion is
 *       blockable like any hit. TNT's synced fuse is timed against the human turn, hotbar and block-delay budget; an obscured TNT or
 *       one that is already too late is not magically answered.</li>
 * </ul>
 * <p>Following, escorting, escaping, combat regrouping or a combat-task retreat, only what is already in flight at the bot is blocked
 * (a hold of a few ticks): the bot normally sprints while hostiles are aggroed (RULES), so there is no pre-emptive hold. The brief
 * vanilla-slowed block ends as soon as the projectile threat ends and sprinting resumes.
 *
 * Never reacted to: thrown splash and lingering potions, experience bottles, snowballs, eggs, ender pearls,
 * Piercing arrows, evoker fangs, lightning, fire, lava and every other damage of
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
 * shooter is the return shot. The emergency tasks that build (shelter, barricade, creeper defence, lava, fire, powder snow) own the
 * hands too; escape only yields for an already-in-flight projectile.
 */
public final class ShieldGuard {
    public static final ShieldGuard INSTANCE = new ShieldGuard();

    /** A skeleton is "visibly drawing" once its bow has been up this long (a full draw is 20 ticks); the shield needs five ticks to start blocking. */
    static final int SHOOTER_DRAW_TICKS = 12;
    /** Head-aim tolerance: the shooter must face the bot within roughly twenty degrees. */
    static final double SHOOTER_AIM_DOT = 0.94D;
    /**
     * How far a drawing shooter or a guardian beam is looked for: exactly as far as the bot can notice a creature at all, its profile
     * observation radius ({@code perception.radius}, {@code CreatureSenses}). No distance of its own: a shorter one would be an invented
     * limit (a shooter drawing at the bot from farther is as real a threat), a longer one only costs a wider query.
     */
    private static double scanRange() {
        return CreatureSenses.observationRadius();
    }
    /** Raise the shield a little ahead of CreeperDefenseTask's own late-fuse wall threshold. */
    static final float CREEPER_FUSE_THRESHOLD = 0.35F;
    /** Vanilla Creeper starts at explosion radius 3; a powered Creeper doubles it, and explosion damage reaches twice that radius. */
    private static final double VANILLA_CREEPER_EXPLOSION_RADIUS = 3.0D;
    private static final double MAX_CREEPER_BLAST_REACH = VANILLA_CREEPER_EXPLOSION_RADIUS * 2.0D * 2.0D;
    /** Vanilla {@link PrimedTnt}'s private default explosion power is 4; an explosion can damage out to twice its radius. */
    private static final double VANILLA_TNT_EXPLOSION_RADIUS = 4.0D;
    private static final double VANILLA_TNT_BLAST_REACH = VANILLA_TNT_EXPLOSION_RADIUS * 2.0D;
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
        TNT("primed_tnt"),
        GUARDIAN_BEAM("guardian_beam"),
        SHOOTER("shooter_draw");

        final String reason;

        Kind(String reason) {
            this.reason = reason;
        }
    }

    private record Threat(Kind kind, Vec3 facePoint, double halfArcDeg, double ticksToImpact, float damage, String source, int sourceId) {
    }

    /** Why the reactive owner last raised the shield, and for which entity (a seam for the tests). */
    record RaiseCause(String reason, int sourceId) {
    }

    private static final class State {
        Owner owner = Owner.NONE;
        /** Retry clock for a refused reactive raise. It must never delay a task's melee rhythm. */
        long retryAt;
        /** Retry clock for a refused task-owned melee raise. It must never suppress an urgent reactive threat scan. */
        long taskRetryAt;
        String lastRefusal;
        RaiseCause lastRaise;
        /** The continuous exposure of each sensed projectile (entity id): the reaction time of a first sighting. */
        final ExposureTracker<Integer> projectiles = new ExposureTracker<>();
        /** The continuous exposure of each visible primed-TNT entity: an object still takes the same human first-sighting reaction. */
        final ExposureTracker<Integer> primedTnt = new ExposureTracker<>();
    }

    private final Map<AIPlayerEntity, State> states = new WeakHashMap<>();

    private ShieldGuard() {
    }

    private State stateOf(AIPlayerEntity bot) {
        return states.computeIfAbsent(bot, ignored -> new State());
    }

    // ------------------------------------------------------------------ queries for the tasks

    /** Why the reactive owner last raised the bot's shield ({@code null} before the first raise): a seam for the tests. */
    static RaiseCause lastRaise(AIPlayerEntity bot) {
        State state = INSTANCE.states.get(bot);
        return state == null ? null : state.lastRaise;
    }

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
            if (ShieldBlockability.isShield(stack)) {
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

    /**
     * Holds a task-owned shield between the bot's own melee swings. Call this only after
     * {@link CombatCore#strikeIfReady(AIPlayerEntity, LivingEntity)} declined to strike: that method is the only place that lowers a
     * raised shield for a ready, legal swing, and intentionally leaves the swing for the following tick. Guard uses the same rhythm as
     * {@link CombatTask} instead of making its unprotected cooldown windows a special case. Hunt's fixed prey are passive animals, so
     * it deliberately has no task-owned melee block.
     */
    static boolean holdMeleeBetweenSwings(AIPlayerEntity bot, LivingEntity attacker) {
        if (INSTANCE.holding(bot)) {
            return false;
        }
        if (!meleeShieldEligible(bot, attacker)) {
            lowerIfOwner(bot, Owner.TASK);
            return false;
        }
        boolean ready = bot.getAttackStrengthScale(0.5F) >= 0.95F;
        boolean onTarget = HumanAim.isUnderCrosshair(bot, attacker) && StrikeLegality.strikeRefusal(bot, attacker) == null;
        double cooldownTicksLeft = bot.getCurrentItemAttackStrengthDelay()
                * (1.0F - Math.min(1.0F, bot.getAttackStrengthScale(0.5F)));
        ShieldRules.MeleeStep step = ShieldRules.meleeStep(ready, onTarget, true, shieldUsable(bot), usingShield(bot),
                cooldownTicksLeft, blockDelayTicks(bot));
        if (step == ShieldRules.MeleeStep.IDLE) {
            lowerIfOwner(bot, Owner.TASK);
            return true;
        }
        if (step != ShieldRules.MeleeStep.RAISE) {
            return true;
        }
        State state = INSTANCE.stateOf(bot);
        long now = bot.level().getGameTime();
        if (now < state.taskRetryAt) {
            return true;
        }
        if (raise(bot, Owner.TASK) == Raise.REFUSED) {
            // A failed vanilla use is retried on the same modest cadence as reactive blocking, never once per tick. This clock is
            // separate: a task-side refusal must not make tick() skip a real incoming projectile.
            state.taskRetryAt = now + RETRY_TICKS;
        }
        return true;
    }

    /**
     * The local facts a task needs before it can claim the between-swings rhythm: the bot itself, not only its owner, has noticed a
     * live attacker in reach; the carried shield can block that attack's real vanilla damage source; and the task is not trying to
     * stand against a threat its combat rules forbid.
     */
    static boolean meleeShieldEligible(AIPlayerEntity bot, LivingEntity attacker) {
        if (!(bot.level() instanceof ServerLevel level) || attacker == null || !attacker.isAlive()
                || CombatCore.isMeleeForbiddenThreat(attacker) || !CombatCore.inMeleeRange(bot, attacker)
                || !ObservableWorldQuery.canNoticeCreature(bot, attacker) || !shieldUsable(bot)) {
            return false;
        }
        return ShieldBlockability.meleeBlockable(level, shieldStack(bot), attacker);
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
                || item instanceof net.minecraft.world.item.FireworkRocketItem
                || item instanceof net.minecraft.world.item.EnderEyeItem
                || item instanceof net.minecraft.world.item.WritableBookItem
                || item instanceof net.minecraft.world.item.WrittenBookItem
                || item instanceof net.minecraft.world.item.KnowledgeBookItem
                || item instanceof net.minecraft.world.item.BoatItem
                || item instanceof net.minecraft.world.item.PlaceOnWaterBlockItem
                || item instanceof net.minecraft.world.item.BundleItem;
    }

    /**
     * Changes the selected hotbar slot to an item that does not take the use: the best melee weapon first (its normal place in a
     * fight), then any other plain hotbar item, then an empty slot. True when the main hand no longer takes the use.
     */
    private static boolean switchMainHandAway(AIPlayerEntity bot) {
        // A hotbar change only (slots 0-8, one key press): the best melee weapon ON the hotbar, never one fetched from the backpack.
        Inventory inventory = bot.getInventory();
        int weapon = -1;
        for (int slot = 0; slot <= 8; slot++) {
            ItemStack candidate = inventory.getNonEquipmentItems().get(slot);
            if (EquipAction.isQualifiedMeleeWeapon(candidate)
                    && !ShieldRules.mainHandConsumesUse(classifyMainHand(bot, candidate))
                    && (weapon < 0 || EquipAction.attackDamage(candidate)
                    > EquipAction.attackDamage(inventory.getNonEquipmentItems().get(weapon)))) {
                weapon = slot;
            }
        }
        if (weapon >= 0) {
            inventory.setSelectedSlot(weapon);
            inventory.setChanged();
            BotLog.action(bot, "shield_hotbar_switch", "to", inventory.getNonEquipmentItems().get(weapon).getItem());
            return true;
        }
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
                inventory.setSelectedSlot(slot);
                inventory.setChanged();
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

    /**
     * What the bot is using right now, for the hand policy. Eating counts from the moment a task means to eat, not only while a bite is
     * in progress: an eating pass ({@link EatTask}) or the combat task's heal ({@link CombatTask#healing}) between two bites is
     * {@link UseKind#CONSUMING} too, so a hit that only hurts never takes the hand from the heal.
     */
    static UseKind classifyUse(AIPlayerEntity bot, Task active) {
        if (!bot.isUsingItem()) {
            return active instanceof EatTask || active instanceof CombatTask combat && combat.healing() ? UseKind.CONSUMING : UseKind.NONE;
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
            // Never leave a shield this owner raised behind: it would slow the bot and stop its mining and swings for ever.
            State state = states.get(bot);
            if (state != null && state.owner != Owner.NONE && usingShield(bot)) {
                bot.stopUsingItem();
            }
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
        releaseStaleTaskShield(bot, state, active);
        if (handsOwnedByTask(active)) {
            state.projectiles.clear();
            releaseIfOwned(bot, state, "task_owns_hands");
            return;
        }
        ItemStack shield = shieldStack(bot);
        boolean up = usingShield(bot);
        if (!up && (shield.isEmpty() || now < state.retryAt || bot.getCooldowns().isOnCooldown(shield))) {
            // Nothing to raise now (no shield, a refused raise waiting its retry, a shield an axe disabled): no assessment, no turn.
            state.projectiles.clear();
            releaseIfOwned(bot, state, "threat_over");
            return;
        }
        Threat threat = assess(bot, level, shield, state, now, active);
        if (threat == null) {
            releaseIfOwned(bot, state, "threat_over");
            return;
        }
        engage(bot, state, threat, now, active);
    }

    /**
     * A shield a task raised for its own use (the combat task's melee rhythm, the creeper defence's shield phase) is that task's only
     * while the task is in the phase that holds it. A task that ended (a combat timeout in the BLOCK phase), was paused or replaced left
     * it up: it comes down here, whatever task the bot resumed, so a follower does not crawl at the item-use pace and a miner is not
     * kept from breaking for ever.
     */
    private static void releaseStaleTaskShield(AIPlayerEntity bot, State state, Task active) {
        if (state.owner != Owner.TASK) {
            return;
        }
        boolean held = active instanceof CombatTask combat && combat.holdsItsShield()
                || active instanceof CreeperDefenseTask creeper && creeper.holdsItsShield()
                || active instanceof GuardTask guard && guard.holdsItsShield(bot);
        if (!held) {
            if (usingShield(bot)) {
                bot.releaseUsingItem();
                BotLog.action(bot, "task_shield_lowered", "reason", "owner_task_left_its_shield_phase",
                        "active", active == null ? "none" : active.name());
            }
            state.owner = Owner.NONE;
        }
    }

    /**
     * The bot's hands go to something more urgent than a block for this tick (the navigation safety net rescuing it from drowning or
     * lava): a shield this owner raised comes down (a raised shield slows every stroke by vanilla's item-use factor).
     */
    public void standDown(AIPlayerEntity bot, String reason) {
        State state = states.get(bot);
        if (state != null) {
            state.projectiles.clear();
            if (state.owner == Owner.TASK && usingShield(bot)) {
                bot.releaseUsingItem();
                state.owner = Owner.NONE;
            }
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
                        BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType()).toString(), projectile.getId());
            }
            logRefusalOnce(bot, state, "projectile_too_late");
        }
        if (active instanceof FollowTask || active instanceof EvadeTask || active instanceof CombatRegroupTask
                || active instanceof CombatTask combat && combat.retreating()) {
            // Following, escorting, escaping, regrouping or a combat-task retreat, the bot normally sprints while hostiles are aggroed.
            // It still blocks what is ALREADY in flight at it (a brief vanilla-slowed hold), never a pre-emptive hold against a drawing
            // shooter, charging beam or fuse.
            return null;
        }
        // 2. A late creeper fuse (the creeper defence task has its own shield phase).
        if (!(active instanceof CreeperDefenseTask)) {
            Creeper creeper = nearbyImminentCreeper(bot);
            if (creeper != null) {
                DamageSource blast = level.damageSources().explosion(creeper, creeper);
                double arc = ShieldBlockability.halfArcDeg(component, blast);
                if (ShieldBlockability.blocks(shield, blast) && canFace(bot, state, creeper.position(), arc)) {
                    return new Threat(Kind.CREEPER, creeper.position(), arc, Double.NaN, 12.0F, "creeper", creeper.getId());
                }
            }
        }
        // 3. A visible primed TNT block: PrimedTnt exposes its real remaining fuse, and vanilla's own source decides whether this
        // shield blocks this particular explosion. The GameRule is also vanilla's: no explosion means no reason to raise.
        if (level.getGameRules().get(GameRules.TNT_EXPLODES)) {
            List<PrimedTnt> tnts = imminentTnt(bot);
            Set<Integer> inFieldTnt = new HashSet<>();
            for (PrimedTnt tnt : tnts) {
                if (tntInViewField(bot, tnt)) {
                    inFieldTnt.add(tnt.getId());
                }
            }
            // The object query is omnidirectional. Keep a reaction run only while this individual TNT stays in the perception field;
            // in particular, an out-of-view near TNT must not mask a farther visible one that can actually be answered.
            state.primedTnt.retain(inFieldTnt);
            for (PrimedTnt tnt : tnts) {
                if (!inFieldTnt.contains(tnt.getId())) {
                    logRefusalOnce(bot, state, "tnt_outside_view");
                    continue;
                }
                long exposed = state.primedTnt.sighted(tnt.getId(), now);
                if (!reactedToVisibleTnt(bot, tnt, exposed)) {
                    logRefusalOnce(bot, state, "tnt_reaction_time");
                    continue;
                }
                DamageSource blast = Explosion.getDefaultDamageSource(level, tnt);
                double arc = ShieldBlockability.halfArcDeg(component, blast);
                if (ShieldBlockability.blocks(shield, blast)
                        && canFace(bot, state, tnt.position(), arc)
                        && canBlockPrimedTntInTime(bot, tnt, delay, arc, raised)) {
                    return new Threat(Kind.TNT, tnt.position(), arc, tnt.getFuse(),
                            primedTntWorstCaseDamage(bot.distanceTo(tnt)),
                            BuiltInRegistries.ENTITY_TYPE.getKey(tnt.getType()).toString(), tnt.getId());
                }
            }
        } else {
            state.primedTnt.clear();
        }
        // 4. A guardian's beam locked on the bot: its mob_attack part is blockable from the front.
        Guardian guardian = guardianBeamOn(bot, active);
        if (guardian != null) {
            DamageSource bite = ShieldBlockability.meleeSource(level, guardian);
            double arc = ShieldBlockability.halfArcDeg(component, bite);
            if (ShieldBlockability.blocks(shield, bite) && canFace(bot, state, guardian.position(), arc)) {
                return new Threat(Kind.GUARDIAN_BEAM, guardian.position(), arc, Double.NaN,
                            guardianBeamDamage(level, guardian),
                        BuiltInRegistries.ENTITY_TYPE.getKey(guardian.getType()).toString(), guardian.getId());
            }
        }
        // 5. A shooter drawing (or holding a loaded crossbow) at the bot: held until the shot lands or the draw stops.
        LivingEntity shooter = drawingShooterAt(bot, active);
        if (shooter != null) {
            DamageSource arrow = level.damageSources().source(DamageTypes.ARROW, shooter);
            double arc = ShieldBlockability.halfArcDeg(component, arrow);
            if (canFace(bot, state, shooter.getEyePosition(), arc)) {
                return new Threat(Kind.SHOOTER, shooter.getEyePosition(), arc, Double.NaN, 6.0F,
                        BuiltInRegistries.ENTITY_TYPE.getKey(shooter.getType()).toString(), shooter.getId());
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
     * tracking; otherwise the reaction time of the shared formula for the projectile's current distance and angle (its shot heard: the
     * hearing rule's angle factor 1; only seen: the angle factor of sight), over its continuous exposure.
     */
    private static boolean reactedTo(AIPlayerEntity bot, Projectile projectile, long exposedTicks) {
        // Perception off, the capability bypass or the scan's fail-safe: today's legacy answer, no reaction time (as noticedProjectile).
        boolean on = !CreatureSenses.INSTANCE.legacyProjectileAnswers(bot);
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
        // A heard shot turned the bot to it (the hearing rule: angle factor 1), in view or not; a projectile only seen keeps its angle.
        double angleForReaction = CreatureSenses.INSTANCE.heardProjectileShot(bot, projectile) ? 0.0D : theta;
        double required = CreaturePerception.requiredSeconds(params, angleForReaction, toward.length(),
                CreaturePerception.Subject.of(false), false);
        return ShieldRules.reacted(true, false, CreaturePerception.exposureSeconds(exposedTicks), required);
    }

    /** A visible but non-creature TNT entity follows the same first-sighting reaction formula as a visible projectile. */
    private static boolean reactedToVisibleTnt(AIPlayerEntity bot, PrimedTnt tnt, long exposedTicks) {
        if (CreatureSenses.INSTANCE.legacyObservationAnswers(bot)) {
            return true;
        }
        Vec3 toward = tnt.position().subtract(bot.getEyePosition());
        Vec3 look = bot.getViewVector(1.0F);
        double theta = CreaturePerception.angleDeg(look.x, look.y, look.z, toward.x, toward.y, toward.z);
        double required = CreaturePerception.requiredSeconds(perceptionParams(), theta, toward.length(),
                CreaturePerception.Subject.of(false), false);
        return ShieldRules.reacted(true, false, CreaturePerception.exposureSeconds(exposedTicks), required);
    }

    /** Whether a real visible TNT object is inside the same peripheral field whose continuous exposure earns a reaction. */
    private static boolean tntInViewField(AIPlayerEntity bot, PrimedTnt tnt) {
        if (CreatureSenses.INSTANCE.legacyObservationAnswers(bot)) {
            return true;
        }
        Vec3 toward = tnt.position().subtract(bot.getEyePosition());
        Vec3 look = bot.getViewVector(1.0F);
        double theta = CreaturePerception.angleDeg(look.x, look.y, look.z, toward.x, toward.y, toward.z);
        return theta <= perceptionParams().peripheralHalfAngleDeg();
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

    /**
     * The PrimedTnt-specific version of the same timing check. A shield already in use has only its remaining vanilla block delay and
     * no hotbar change left; it still has to turn into the blast's front arc before the synced fuse reaches zero. A raised shield is
     * not assumed active merely because {@link #usingShield} is true: {@link AIPlayerEntity#getTicksUsingItem()} is the vanilla count
     * used to retain the rest of the item's {@code block_delay_seconds}.
     */
    private static boolean canBlockPrimedTntInTime(AIPlayerEntity bot, PrimedTnt tnt, int blockDelay, double halfArcDeg,
                                                    boolean shieldAlreadyUp) {
        if (halfArcDeg <= 0.0D) {
            return false;
        }
        double offset = ShieldRules.offsetDeg(bot.getYHeadRot(), tnt.getX() - bot.getX(), tnt.getZ() - bot.getZ());
        double degrees = ShieldRules.degreesToTurn(offset, halfArcDeg);
        int turn = degrees <= 0.0D ? 0
                : walking(bot) ? Integer.MAX_VALUE
                : ShieldRules.turnTicks(degrees, HumanAim.maxTurnDegPerTick());
        int delayRemaining = shieldAlreadyUp ? Math.max(0, blockDelay - bot.getTicksUsingItem()) : blockDelay;
        MainHandKind main = classifyMainHand(bot, bot.getMainHandItem());
        int switching = shieldAlreadyUp || main == MainHandKind.SHIELD || !ShieldRules.mainHandConsumesUse(main)
                ? 0 : ShieldRules.HOTBAR_SWITCH_TICKS;
        return primedTntCanBeActiveInTime(tnt.getFuse(), turn, delayRemaining, switching);
    }

    /** The pure fuse/envelope seam: {@link PrimedTnt#getFuse()} is positive until its next explosion tick. */
    static boolean primedTntCanBeActiveInTime(int fuseTicks, int turnTicks, int blockDelayTicks, int switchTicks) {
        return fuseTicks > 0 && ShieldRules.canBeActiveInTime(fuseTicks, turnTicks, blockDelayTicks, switchTicks);
    }

    private void logRefusalOnce(AIPlayerEntity bot, State state, String reason) {
        if (!reason.equals(state.lastRefusal)) {
            state.lastRefusal = reason;
            BotLog.action(bot, "reactive_shield_skipped", "reason", reason);
        }
    }

    private void engage(AIPlayerEntity bot, State state, Threat threat, long now, Task active) {
        UseKind use = classifyUse(bot, active);
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
        if (use != UseKind.NONE && use != UseKind.SHIELD && bot.isUsingItem()) {
            // Cancel, never release: a drawn bow must not fire, food not be finished; only a lethal hit gets this far.
            bot.stopUsingItem();
        }
        Raise result = raise(bot, Owner.REACTIVE);
        switch (result) {
            case RAISED -> {
                state.lastRefusal = null;
                state.lastRaise = new RaiseCause(threat.kind().reason, threat.sourceId());
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

    /** The nearest noticed creeper whose fuse has reached a late stage and is inside its actual vanilla damage envelope. */
    static Creeper nearbyImminentCreeper(AIPlayerEntity bot) {
        return bot.level().getEntitiesOfClass(Creeper.class,
                        // The scan uses the maximum possible vanilla envelope; each result below is checked against its actual powered
                        // state. This is not a shield-specific cutoff: explosion damage itself stops at radius * 2.
                        bot.getBoundingBox().inflate(MAX_CREEPER_BLAST_REACH),
                        creeper -> creeper.isAlive()
                                && isVanillaCreeper(creeper)
                                && creeper.getSwelling(1.0F) >= CREEPER_FUSE_THRESHOLD
                                && bot.distanceTo(creeper) <= creeperBlastReach(creeper.isPowered())
                                && ObservableWorldQuery.canNoticeCreature(bot, creeper))
                .stream()
                .min(Comparator.comparingDouble(bot::distanceToSqr))
                .orElse(null);
    }

    /** The furthest distance vanilla explosion damage can reach for this Creeper's public powered state. */
    static double creeperBlastReach(boolean powered) {
        return VANILLA_CREEPER_EXPLOSION_RADIUS * (powered ? 2.0D : 1.0D) * 2.0D;
    }

    /** The explosion-radius model applies only to the registered vanilla creeper, never an arbitrary mod subclass. */
    private static boolean isVanillaCreeper(Creeper creeper) {
        return creeper.getType() == EntityType.CREEPER;
    }

    /**
     * All visible primed vanilla TNT within the default TNT explosion's real damage envelope, nearest first. PrimedTnt synchronizes its fuse
     * but deliberately exposes no explosion-power getter: this is the verified vanilla default (power 4, damage out to twice the
     * radius), rather than a made-up shield scan radius. A data pack or mod that changes the private saved power needs to expose that
     * value before a bot can honestly know a different envelope.
     */
    static List<PrimedTnt> imminentTnt(AIPlayerEntity bot) {
        return bot.level().getEntitiesOfClass(PrimedTnt.class,
                        bot.getBoundingBox().inflate(primedTntBlastReach()),
                        tnt -> tnt.isAlive()
                                && isVanillaPrimedTnt(tnt)
                                && tnt.getFuse() > 0
                                && bot.distanceTo(tnt) <= primedTntBlastReach()
                                // TNT is an object, not a LivingEntity tracked by CreatureSenses: require the normal visible-entity
                                // observation (profile radius + vanilla line of sight), never a query of an unseen fuse.
                                && ObservableWorldQuery.canObserveEntity(bot, tnt))
                .stream()
                .sorted(Comparator.comparingDouble(bot::distanceToSqr))
                .toList();
    }

    /** PrimedTnt has no public explosion-power getter, so use its default envelope only for the registered vanilla TNT entity. */
    private static boolean isVanillaPrimedTnt(PrimedTnt tnt) {
        return tnt.getType() == EntityType.TNT;
    }

    /** The furthest distance the default vanilla PrimedTnt explosion can damage; not a shield-specific scan cutoff. */
    static double primedTntBlastReach() {
        return VANILLA_TNT_BLAST_REACH;
    }

    /**
     * A visible default-TNT blast's conservative vanilla damage bound at the bot's observed physical distance. Vanilla multiplies
     * {@code (impact * impact + impact) / 2} by {@code 7 * radius * 2}, then truncates and adds one; using an unobstructed exposure
     * of one and no armour makes this a bound, not a claim to know an obscuring block or future damage reduction. It is used only for
     * the hand-interruption lethality decision, so a distant nonlethal TNT cannot cancel eating or drawing merely because the
     * zero-distance damage is high.
     */
    static float primedTntWorstCaseDamage(double observedDistance) {
        double normalizedDistance = Math.max(0.0D, Math.min(1.0D, observedDistance / VANILLA_TNT_BLAST_REACH));
        double impact = 1.0D - normalizedDistance;
        return (float) Math.floor((impact * impact + impact) * 0.5D * 7.0D * VANILLA_TNT_BLAST_REACH + 1.0D);
    }

    /**
     * GuardianAttackGoal's complete damage for hand-priority lethality: its unavoidable indirect-magic opening (one, plus two on
     * Hard) followed by the guardian's normal public {@code mob_attack}. The shield only blocks the latter.
     */
    static float guardianBeamDamage(double attackDamage, boolean hard, boolean elder) {
        return (float) attackDamage + 1.0F + (hard ? 2.0F : 0.0F) + (elder ? 2.0F : 0.0F);
    }

    private static float guardianBeamDamage(ServerLevel level, Guardian guardian) {
        return guardianBeamDamage(guardian.getAttributeValue(Attributes.ATTACK_DAMAGE), level.getDifficulty() == Difficulty.HARD, false);
    }

    /**
     * The nearest noticed ordinary vanilla guardian whose beam is locked on the bot: the beam is in plain view
     * from its first tick (the synced attack target a client draws it to), so a player sees it charge and raises the shield. On the
     * server the beam's target is the guardian's target while the beam is on ({@code Guardian.getActiveAttackTarget}). A modded
     * {@link Guardian} subclass is unknown rather than assumed to share vanilla beam damage.
     */
    static Guardian guardianBeamOn(AIPlayerEntity bot) {
        return guardianBeamOn(bot, null);
    }

    /** {@link #guardianBeamOn(AIPlayerEntity)} without a guardian an active melee rhythm is fighting within reach. */
    private static Guardian guardianBeamOn(AIPlayerEntity bot, Task active) {
        return bot.level().getEntitiesOfClass(Guardian.class,
                        bot.getBoundingBox().inflate(scanRange()),
                        guardian -> guardian.isAlive()
                                && isVanillaGuardian(guardian)
                                && guardian.hasActiveAttackTarget()
                                && guardian.getActiveAttackTarget() == bot
                                && ObservableWorldQuery.canNoticeCreature(bot, guardian)
                                && !activeMeleeRhythmAgainst(active, bot, guardian))
                .stream()
                .min(Comparator.comparingDouble(bot::distanceToSqr))
                .orElse(null);
    }

    /** Guardian beam formulae are vanilla-only: a compatible Java subclass is not evidence of compatible gameplay mechanics. */
    private static boolean isVanillaGuardian(Guardian guardian) {
        return guardian.getType() == EntityType.GUARDIAN;
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

    /** {@link #drawingShooterAt(AIPlayerEntity)} without the shooter an active melee rhythm is fighting within reach. */
    private static LivingEntity drawingShooterAt(AIPlayerEntity bot, Task active) {
        return bot.level().getEntitiesOfClass(LivingEntity.class,
                        bot.getBoundingBox().inflate(scanRange()),
                        shooter -> shooter != bot
                                && shooter.isAlive()
                                && isDrawingBowAt(shooter, bot)
                                && CombatCore.hostileTo(bot, shooter)
                                && ObservableWorldQuery.canNoticeCreature(bot, shooter)
                                && !activeMeleeRhythmAgainst(active, bot, shooter))
                .stream()
                .min(Comparator.comparingDouble(bot::distanceToSqr))
                .orElse(null);
    }

    /** Whether this task, rather than the reactive owner, owns the shield rhythm against {@code entity}. */
    private static boolean activeMeleeRhythmAgainst(Task active, AIPlayerEntity bot, LivingEntity entity) {
        return (active instanceof CombatTask combat && combat.meleeRhythmAgainst(bot, entity))
                || (active instanceof GuardTask guard && guard.meleeRhythmAgainst(bot, entity));
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
