package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.ShieldBlockability;
import io.github.zoyluo.minecraftai.action.ShieldRules;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.gametest.GameTestChunkForcing;
import io.github.zoyluo.minecraftai.gametest.GameTestCleanup;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.MiningBudget;
import io.github.zoyluo.minecraftai.mining.MiningCursor;
import io.github.zoyluo.minecraftai.perception.CreatureSenses;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Live proofs of the companions' shield use (see {@link ShieldGuard} and docs/SHIELD_USE.md): what a noticed, blockable threat makes
 * them do, and what they leave alone. Every fixture is real (a survival bot with a real shield, real arrows, real mobs) and runs with
 * realistic perception ON (the view cone, the reaction time, hearing): a shooter is shot from only once the bot has noticed it, unless
 * the test is about a shooter it has not. Every scenario has its own environment (its own batch: the perception switch is global).
 *
 * <p>Two scenes. The {@link Arena}: the bot stands at the arena centre looking south (+Z), the threat comes from the south unless the
 * test says otherwise. The {@link Standby}: a follower standing by the player it follows (follow mode keeps an ordinary hostile from
 * pulling it into a fight, R4), with the shooter a dozen blocks away on the side it faces.
 */
public final class ShieldBlockingGameTests {
    private static final String ENV = "minecraftai-gametest:shield_blocking_game_tests_";
    /** One layer for all of them: each test clears the volume it uses, and the tests never run together. */
    private static final int LAYER_Y = 290;
    private static final int HALF_X = 8;
    private static final int BACK = 24;
    private static final int AHEAD = 24;
    /** A skeleton arrow's launch speed, blocks per tick. */
    private static final float ARROW_SPEED = 1.6F;

    /** A real registry-backed stack proves shield identity comes from vanilla's component, not Items.SHIELD. */
    @GameTest(environment = ENV + "component_backed_stack_is_recognized_as_shield", maxTicks = 20)
    public void componentBackedStackIsRecognizedAsShield(GameTestHelper context) {
        Arena arena = new Arena(context);
        ItemStack componentShield = new ItemStack(Items.STICK);
        BlocksAttacks vanillaShieldComponent = new ItemStack(Items.SHIELD).get(DataComponents.BLOCKS_ATTACKS);

        arena.require(!componentShield.is(Items.SHIELD), "fixture accidentally used Items.SHIELD");
        arena.require(vanillaShieldComponent != null, "vanilla shield did not expose BLOCKS_ATTACKS");
        componentShield.set(DataComponents.BLOCKS_ATTACKS, vanillaShieldComponent);
        arena.require(ShieldBlockability.isShield(componentShield),
                "a non-shield item with BLOCKS_ATTACKS was not recognized as a shield");
        arena.require(vanillaShieldComponent.equals(ShieldBlockability.component(componentShield)),
                "component-backed shield did not retain vanilla block parameters");
        arena.finish();
    }

    /** World-backed codec proof: factual break completion survives schema 5, while schema 4 cannot invent it. */
    @GameTest(environment = ENV + "confirmed_gone_checkpoint_round_trips_across_schema5_and_schema4", maxTicks = 20)
    public void confirmedGoneCheckpointRoundTripsAcrossSchema5AndSchema4(GameTestHelper context) {
        Arena arena = new Arena(context);
        Set<Block> ores = Set.of(Blocks.IRON_ORE);
        BlockPos breakPos = arena.feet.offset(3, 1, -4);
        OreDigCheckpoint checkpoint = new OreDigCheckpoint(
                OreDigCheckpoint.CHECKPOINT_SCHEMA, 1, true, 0, 0, false,
                MiningBudget.RARE_BATCH_TORCH_LIMIT, 0, 0,
                MiningCursor.initial(arena.feet, OreDigTask.STRIP_SEGMENT),
                OreDigTask.oreFingerprint(ores), 0, 0, null, null, null, null, -1, -1, -1,
                breakPos, 0, true, Map.of());
        arena.require("true".equals(checkpoint.encode().get("active_break_confirmed_gone")),
                "schema-5 checkpoint did not encode the factual completed-break flag");
        OreDigCheckpoint restored = OreDigCheckpoint.decode(checkpoint.encode(), ores).orElse(null);
        arena.require(restored != null && restored.activeBreakConfirmedGone(),
                "schema-5 checkpoint did not restore the factual completed-break flag");

        OreDigCheckpoint schemaFour = new OreDigCheckpoint(
                4, 1, true, 0, 0, false,
                MiningBudget.RARE_BATCH_TORCH_LIMIT, 0, 0,
                MiningCursor.initial(arena.feet, OreDigTask.STRIP_SEGMENT),
                OreDigTask.oreFingerprint(ores), 0, 0, null, null, null, null, -1, -1, -1,
                breakPos, 0, false, Map.of());
        arena.require(!schemaFour.encode().containsKey("active_break_confirmed_gone"),
                "schema-4 encoder emitted a schema-5 key");
        OreDigCheckpoint restoredSchemaFour = OreDigCheckpoint.decode(schemaFour.encode(), ores).orElse(null);
        arena.require(restoredSchemaFour != null && !restoredSchemaFour.activeBreakConfirmedGone(),
                "schema-4 checkpoint invented a factual completed-break flag");
        arena.finish();
    }

    // ------------------------------------------------------------------ a: a noticed skeleton, pre-emptive raise, blocked arrow

    @GameTest(environment = ENV + "noticed_skeleton_arrow_is_blocked_with_no_damage_and_shield_wear", maxTicks = 300)
    public void noticedSkeletonArrowIsBlockedWithNoDamageAndShieldWear(GameTestHelper context) {
        Arena arena = new Arena(context);
        AIPlayerEntity bot = arena.bot("ShieldArrowGT", 0, 0);
        arena.armed(bot);
        // Twelve blocks away, the fight's anchor midway (both inside its leash): the arrow has time to fly, and a bot that keeps
        // closing in does not walk into the arrow's path between two of its ticks (vanilla's hit test is a swept segment per tick).
        Skeleton skeleton = arena.skeleton(0, 12, 180.0F);
        CombatTask fight = CombatTask.defensive(skeleton, 6.0F, bot.blockPosition().immutable().south(6));
        TaskManager.INSTANCE.assign(bot, fight, TaskOrigin.safety("gametest_shield_arrow"));
        int[] tick = {0};
        int[] firedAt = {-1};
        Arrow[] shot = {null};
        context.onEachTick(() -> {
            int now = ++tick[0];
            if (firedAt[0] < 0 && !skeleton.isUsingItem()) {
                skeleton.startUsingItem(InteractionHand.MAIN_HAND); // the fixture skeleton has no AI: it keeps its bow drawn
            }
            arena.require(bot.isAlive(), "the bot died: " + fight.describe());
            ItemStack shield = bot.getOffhandItem();
            if (firedAt[0] < 0) {
                arena.require(bot.getHealth() >= 20.0F, "the bot was hurt before the shot: " + bot.getHealth());
                if (bot.isBlocking()) {
                    arena.require(CreatureSenses.INSTANCE.noticed(bot, skeleton), "the shield came up for a skeleton the bot had not noticed");
                    ShieldGuard.RaiseCause cause = ShieldGuard.lastRaise(bot);
                    arena.require(ShieldGuard.holdsShield(bot) && cause != null && cause.reason().equals("shooter_draw")
                                    && cause.sourceId() == skeleton.getId(),
                            "the raise was not the guard's pre-emptive answer to the drawing skeleton: " + cause);
                    firedAt[0] = now;
                    shot[0] = arena.shoot(skeleton, skeleton.getEyePosition(), bot.getEyePosition(), ARROW_SPEED);
                } else if (now > 160) {
                    arena.fail("the shield never came up for a noticed, drawing skeleton: " + fight.describe()
                            + " noticed=" + CreatureSenses.INSTANCE.noticed(bot, skeleton)
                            + " draw=" + skeleton.getTicksUsingItem());
                }
                return;
            }
            if (shield.getDamageValue() > 0) {
                arena.require(bot.getHealth() >= 20.0F, "the blocked arrow still hurt the bot: " + bot.getHealth());
                arena.finish();
            } else if (now > firedAt[0] + 25) {
                arena.fail("the arrow never wore the shield: hp=" + bot.getHealth() + " blocking=" + bot.isBlocking()
                        + " arrow=" + shot[0].position() + " removed=" + shot[0].isRemoved() + " bot=" + bot.position()
                        + " skeleton=" + skeleton.position());
            }
        });
    }

    // ------------------------------------------------------------------ b: a Piercing bolt is never blocked

    @GameTest(environment = ENV + "piercing_bolt_is_never_blocked_and_hits", maxTicks = 300)
    public void piercingBoltIsNeverBlockedAndHits(GameTestHelper context) {
        Standby s = new Standby(context, "ShieldPierceGT");
        s.armed();
        // A pillager with an EMPTY crossbow, watched by the bot: no pre-emptive raise (nothing is loaded), so the shield can only answer
        // what is shot. It releases two bolts at the same speed on the same course: an ordinary one (the control, which must be
        // blocked), then a Piercing one (never raised against, it hits).
        Pillager pillager = s.shooter(EntityType.PILLAGER, new ItemStack(Items.CROSSBOW));
        ItemStack plain = new ItemStack(Items.CROSSBOW);
        ItemStack piercing = new ItemStack(Items.CROSSBOW);
        piercing.enchant(context.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .get(Enchantments.PIERCING.identifier()).orElseThrow(), 2);
        int[] tick = {0};
        int[] phase = {0};
        int[] launchedAt = {0};
        boolean[] raisedForControl = {false};
        float[] hpBefore = {0.0F};
        int[] wearBefore = {0};
        Arrow[] bolt = {null};
        context.onEachTick(() -> {
            int now = ++tick[0];
            s.require(s.bot.isAlive(), "the bot died");
            s.requireFollowing();
            switch (phase[0]) {
                case 0 -> {
                    if (s.noticedAndStill(pillager)) {
                        s.shoot(pillager, plain);
                        launchedAt[0] = now;
                        phase[0] = 1;
                    } else if (now > 160) {
                        s.fail("the follower never noticed the pillager it faces: " + s.describeNotice(pillager));
                    }
                }
                case 1 -> {
                    raisedForControl[0] |= s.bot.isBlocking();
                    if (s.bot.getOffhandItem().getDamageValue() > 0) {
                        s.require(raisedForControl[0], "the control bolt wore the shield without it ever blocking");
                        s.require(s.bot.getHealth() >= 20.0F, "the control bolt hurt the bot through the shield");
                        phase[0] = 2;
                        launchedAt[0] = now;
                    } else if (now > launchedAt[0] + 40) {
                        s.fail("the control bolt from the watched pillager was not blocked: hp=" + s.bot.getHealth()
                                + " raised=" + raisedForControl[0]);
                    }
                }
                case 2 -> {
                    // The shield comes down once nothing is in flight; then the Piercing bolt.
                    if (now >= launchedAt[0] + 10 && !s.bot.isUsingItem()) {
                        bolt[0] = s.shoot(pillager, piercing);
                        s.require(bolt[0].getPierceLevel() > 0, "fixture: the bolt is not piercing");
                        hpBefore[0] = s.bot.getHealth();
                        wearBefore[0] = s.bot.getOffhandItem().getDamageValue();
                        launchedAt[0] = now;
                        phase[0] = 3;
                    } else if (now > launchedAt[0] + 60) {
                        s.fail("the shield never came down after the control bolt");
                    }
                }
                default -> {
                    s.require(!ShieldGuard.usingShield(s.bot),
                            "the shield went up for a Piercing bolt, which it cannot stop (tick " + (now - launchedAt[0]) + ")");
                    s.require(!ShieldBlockability.projectileBlockable(context.getLevel(), s.bot.getOffhandItem(), bolt[0]),
                            "the blockability table says a Piercing bolt is blockable");
                    if (s.bot.getHealth() < hpBefore[0]) {
                        s.require(s.bot.getOffhandItem().getDamageValue() == wearBefore[0], "the shield wore for a bolt it cannot stop");
                        s.finish();
                    } else if (now > launchedAt[0] + 40) {
                        s.fail("the bolt never hit the bot: hp=" + s.bot.getHealth());
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------ c: a splash potion of harming is never blocked

    @GameTest(environment = ENV + "splash_potion_of_harming_does_not_raise_the_shield", maxTicks = 300)
    public void splashPotionOfHarmingDoesNotRaiseTheShield(GameTestHelper context) {
        Standby s = new Standby(context, "ShieldPotionGT");
        s.armed();
        // A witch the bot is watching throws a splash potion of harming at it: seen in flight from a watched thrower, so nothing but
        // the blockability rule (indirect_magic bypasses the shield) keeps the shield down.
        Witch witch = s.shooter(EntityType.WITCH, ItemStack.EMPTY);
        int[] tick = {0};
        ThrownSplashPotion[] potion = {null};
        boolean[] onCourse = {false};
        boolean[] sensed = {false};
        float[] hpBefore = {20.0F};
        context.onEachTick(() -> {
            int now = ++tick[0];
            s.require(s.bot.isAlive(), "the bot died");
            s.requireFollowing();
            if (potion[0] == null) {
                if (s.noticedAndStill(witch)) {
                    ItemStack stack = PotionContents.createItemStack(Items.SPLASH_POTION, Potions.HARMING);
                    ThrownSplashPotion thrown = new ThrownSplashPotion(s.f.level, witch, stack);
                    // A lob: the arc (the potion falls like an arrow: gravity 0.05, drag 0.99) comes down on the bot's eyes.
                    Vec3 aim = aimFor(thrown.position(), s.bot.getEyePosition(), 1.2F);
                    thrown.shoot(aim.x, aim.y, aim.z, 1.2F, 0.0F);
                    s.f.level.addFreshEntity(thrown);
                    potion[0] = thrown;
                    hpBefore[0] = s.bot.getHealth();
                    s.require(!ShieldBlockability.projectileBlockable(s.f.level, s.bot.getOffhandItem(), thrown),
                            "the blockability table says a thrown splash potion is blockable");
                } else if (now > 160) {
                    s.fail("the follower never noticed the witch it faces: " + s.describeNotice(witch));
                }
                return;
            }
            if (!potion[0].isRemoved()) {
                sensed[0] |= CreatureSenses.INSTANCE.noticedProjectile(s.bot, potion[0]);
                if (ProjectileThreat.ticksToClosestApproach(potion[0].position().subtract(s.bot.getEyePosition()),
                        ProjectileThreat.velocityOf(potion[0]), 0.05D, 0.99D) != null) {
                    onCourse[0] = true;
                }
            }
            s.require(!ShieldGuard.usingShield(s.bot), "the shield went up for a thrown splash potion (tick " + now + ")");
            if (potion[0].isRemoved()) {
                s.require(onCourse[0], "fixture: the potion was never on a hit course, so the test proves nothing");
                s.require(sensed[0], "fixture: the bot never saw the potion in flight, so the test proves nothing");
                s.require(s.bot.getHealth() < hpBefore[0], "fixture: the potion of harming did not hurt the bot");
                s.require(s.bot.getOffhandItem().getDamageValue() == 0, "the shield wore for a potion it cannot stop");
                s.finish();
            } else if (now > 260) {
                s.fail("the potion never landed");
            }
        });
    }

    // ------------------------------------------------------------------ d: a shot from behind by an unnoticed shooter just hits

    @GameTest(environment = ENV + "arrow_from_an_unnoticed_shooter_behind_hits_without_a_block", maxTicks = 220)
    public void arrowFromAnUnnoticedShooterBehindHitsWithoutABlock(GameTestHelper context) {
        Arena arena = new Arena(context);
        AIPlayerEntity bot = arena.bot("ShieldBehindGT", 0, 0);
        arena.armed(bot);
        arena.hold(bot);
        int[] tick = {0};
        Arrow[] arrow = {null};
        float[] hpBefore = {0.0F};
        context.onEachTick(() -> {
            int now = ++tick[0];
            arena.require(bot.isAlive(), "the bot died");
            if (now == 8) {
                hpBefore[0] = bot.getHealth();
                // Twenty blocks behind the bot (north), beyond hearing and out of its view cone: nobody heard or saw the shot.
                arrow[0] = arena.shoot(null, new Vec3(arena.x(0), arena.feet.getY() + 1.5D, arena.z(-20)), bot.getEyePosition(),
                        ARROW_SPEED);
                return;
            }
            if (arrow[0] == null) {
                return;
            }
            arena.require(!ShieldGuard.usingShield(bot) && !bot.isBlocking(),
                    "the shield went up before the impact for an arrow nobody had noticed (tick " + (now - 8) + ")");
            if (bot.getHealth() < hpBefore[0]) {
                arena.require(bot.getOffhandItem().getDamageValue() == 0, "the shield wore for an arrow it was not raised against");
                arena.finish();
            } else if (!arrow[0].isRemoved()) {
                arena.require(!CreatureSenses.INSTANCE.noticedProjectile(bot, arrow[0]),
                        "fixture: the bot noticed the arrow from behind at tick " + (now - 8));
            } else if (now > 8 + 60) {
                arena.fail("the arrow never hit the bot: hp=" + bot.getHealth());
            }
        });
    }

    // ------------------------------------------------------------------ j: a slow, high behind arrow is heard through BotEars before it reaches the shield

    /**
     * A real Arrow launched from behind on a long physical arc gives vanilla's vibration system time to deliver its
     * {@code PROJECTILE_SHOOT} event. {@link CreatureSenses#heardProjectileShot} is only true after BotEars records the delivered
     * current-vibration travel time and the Arrow's back-projected launch matches that delivered source block; it is therefore the
     * direct live proof of delivery and association, not a source or trajectory-math shortcut.
     */
    @GameTest(environment = ENV + "behind_arrow_projectile_shoot_vibration_is_associated_and_raises_shield", maxTicks = 220)
    public void behindArrowProjectileShootVibrationIsAssociatedAndRaisesShield(GameTestHelper context) {
        Arena arena = new Arena(context);
        AIPlayerEntity bot = arena.bot("ShieldHeardArrowGT", 0, 0);
        bot.getInventory().clearContent();
        bot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0D);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(17);
        bot.getFoodData().setSaturation(0.0F);
        arena.hold(bot);
        // The 30-tick high arc rises above the standard arena headroom before descending on the bot, so clear only its visible corridor.
        arena.clearAir(-1, 1, -4, 1, 12);
        int[] tick = {0};
        int[] launchedAt = {-1};
        int[] heardAt = {-1};
        Arrow[] arrow = {null};
        boolean[] sawIncomingRaise = {false};
        double[] closestApproach = {Double.MAX_VALUE};
        context.onEachTick(() -> {
            int now = ++tick[0];
            arena.require(bot.isAlive(), "the bot died before the heard-arrow proof completed");
            if (arrow[0] == null) {
                if (now < 8) {
                    return;
                }
                arena.require(CreatureSenses.INSTANCE.listenerLevel(bot).orElse(null) == arena.level,
                        "fixture: BotEars was not registered before the behind shot");
                Vec3 from = new Vec3(arena.x(0), arena.feet.getY() + 1.5D, arena.z(-3));
                arrow[0] = arena.shoot(from, arcingVelocity(from, bot.getEyePosition(), 30));
                launchedAt[0] = now;
                arena.require(!CreatureSenses.INSTANCE.legacyProjectileAnswers(bot),
                        "fixture: the behind-arrow proof fell back to legacy projectile answers");
                arena.require(!CreatureSenses.INSTANCE.noticedProjectile(bot, arrow[0]),
                        "the behind arrow was treated as seen before its vibration could arrive");
                return;
            }
            closestApproach[0] = Math.min(closestApproach[0], arrow[0].position().distanceTo(bot.getEyePosition()));
            boolean heard = CreatureSenses.INSTANCE.heardProjectileShot(bot, arrow[0]);
            if (heard && heardAt[0] < 0) {
                arena.require(now - launchedAt[0] >= 2,
                        "the behind shot was associated without physical vibration travel: launched=" + launchedAt[0] + " heard=" + now);
                arena.require(arrow[0].getZ() < bot.getZ(),
                        "the arrow had already crossed in front before BotEars associated its PROJECTILE_SHOOT event");
                heardAt[0] = now;
            }
            if (heardAt[0] < 0) {
                arena.require(!CreatureSenses.INSTANCE.noticedProjectile(bot, arrow[0]),
                        "the behind arrow became visible instead of waiting for its PROJECTILE_SHOOT vibration");
            }
            if (heardAt[0] >= 0 && ShieldGuard.holdsShield(bot) && bot.isBlocking()) {
                ShieldGuard.RaiseCause cause = ShieldGuard.lastRaise(bot);
                arena.require(cause != null && cause.reason().equals("incoming_projectile") && cause.sourceId() == arrow[0].getId(),
                        "the shield raise after the heard shot was not for this incoming arrow: " + cause);
                sawIncomingRaise[0] = true;
            }
            if (now >= launchedAt[0] + 45) {
                arena.require(heardAt[0] >= 0, "BotEars never associated the behind arrow's PROJECTILE_SHOOT vibration");
                arena.require(sawIncomingRaise[0], "the heard behind arrow never received an active incoming_projectile shield raise");
                arena.require(closestApproach[0] <= 1.5D,
                        "fixture: the physical arrow never reached the bot's interception corridor: closest=" + closestApproach[0]);
                arena.require(bot.getHealth() >= bot.getMaxHealth(),
                        "the heard behind arrow hurt the bot through its shield: " + bot.getHealth());
                arena.finish();
            }
        });
    }

    // ------------------------------------------------------------------ k: an arrow seen in flight from a shooter nobody noticed yet

    @GameTest(environment = ENV + "arrow_from_a_shooter_not_yet_noticed_is_seen_too_late_and_hits", maxTicks = 220)
    public void arrowFromAShooterNotYetNoticedIsSeenTooLateAndHits(GameTestHelper context) {
        Arena arena = new Arena(context);
        AIPlayerEntity bot = arena.bot("ShieldLateGT", 0, 0);
        arena.armed(bot);
        arena.hold(bot);
        int[] tick = {0};
        Skeleton[] skeleton = {null};
        Arrow[] arrow = {null};
        boolean[] seen = {false};
        float[] hpBefore = {0.0F};
        context.onEachTick(() -> {
            int now = ++tick[0];
            arena.require(bot.isAlive(), "the bot died");
            if (now == 8) {
                // A skeleton steps out fourteen blocks in front of the bot and shoots at once: the arrow is in plain view the whole way,
                // but noticing takes the human reaction time (0.5 + 1.5 * 14 / 64 = 0.83 s, 17 ticks), longer than its flight.
                skeleton[0] = arena.skeleton(0, 14, 180.0F);
                skeleton[0].stopUsingItem();
                hpBefore[0] = bot.getHealth();
                arrow[0] = arena.shoot(skeleton[0], skeleton[0].getEyePosition(), bot.getEyePosition(), ARROW_SPEED);
                return;
            }
            if (arrow[0] == null) {
                return;
            }
            arena.require(!ShieldGuard.usingShield(bot) && !bot.isBlocking(),
                    "the shield went up for an arrow seen for less than the reaction time (tick " + (now - 8) + ")");
            if (!arrow[0].isRemoved()) {
                seen[0] |= CreatureSenses.INSTANCE.noticedProjectile(bot, arrow[0]);
            }
            if (bot.getHealth() < hpBefore[0]) {
                arena.require(seen[0], "fixture: the arrow was never in the bot's view, so the test proves nothing");
                arena.require(bot.getOffhandItem().getDamageValue() == 0, "the shield wore for an arrow it was not raised against");
                arena.finish();
            } else {
                arena.require(!CreatureSenses.INSTANCE.noticed(bot, skeleton[0]),
                        "fixture: the bot noticed the skeleton before its arrow landed (tick " + (now - 8) + ")");
                if (now > 8 + 60) {
                    arena.fail("the arrow never hit the bot: hp=" + bot.getHealth());
                }
            }
        });
    }

    // ------------------------------------------------------------------ e: melee hits are blocked between the bot's own swings

    @GameTest(environment = ENV + "zombie_hits_are_blocked_between_swings_and_the_companion_still_kills_it", maxTicks = 480)
    public void zombieHitsAreBlockedBetweenSwingsAndTheCompanionStillKillsIt(GameTestHelper context) {
        Arena arena = new Arena(context);
        AIPlayerEntity bot = arena.bot("ShieldMeleeGT", 0, 0);
        arena.armed(bot);
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0D);
        bot.setHealth(bot.getMaxHealth());
        Husk husk = arena.husk(0, 5);
        husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40.0D);
        husk.setHealth(40.0F);
        // It stays in reach (the bot's own knockback would otherwise keep it out of arm's length) and hits hard enough to wear a shield
        // whatever the difficulty scaling does to a zombie's three points.
        husk.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0D);
        husk.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(8.0D);
        CombatTask fight = CombatTask.defensive(husk, 6.0F, bot.blockPosition().immutable());
        TaskManager.INSTANCE.assign(bot, fight, TaskOrigin.safety("gametest_shield_melee"));
        int[] tick = {0};
        boolean[] actualBlockWasUp = {false};
        float[] healthAtActualBlockStart = {bot.getHealth()};
        int[] lastShieldDamage = {bot.getOffhandItem().getDamageValue()};
        boolean[] blockPhaseWithShieldUp = {false};
        boolean[] blockedHuskHitDuringActualBlock = {false};
        context.onEachTick(() -> {
            int now = ++tick[0];
            arena.require(bot.isAlive(), "the bot died: hp=" + bot.getHealth() + " " + fight.describe());
            boolean actualBlock = fight.describe().contains("phase=BLOCK")
                    && ShieldGuard.usingShield(bot)
                    && bot.isBlocking()
                    && bot.getTicksUsingItem() >= ShieldGuard.blockDelayTicks(bot);
            if (actualBlock && !actualBlockWasUp[0]) {
                healthAtActualBlockStart[0] = bot.getHealth();
            }
            if (actualBlock) {
                blockPhaseWithShieldUp[0] = true;
                arena.require(bot.getHealth() >= healthAtActualBlockStart[0],
                        "the husk hurt the bot during CombatTask's active block interval: "
                                + healthAtActualBlockStart[0] + " -> " + bot.getHealth());
                if (bot.getOffhandItem().getDamageValue() > lastShieldDamage[0]) {
                    blockedHuskHitDuringActualBlock[0] = true;
                }
            }
            actualBlockWasUp[0] = actualBlock;
            lastShieldDamage[0] = bot.getOffhandItem().getDamageValue();
            if (!husk.isAlive()) {
                arena.require(blockedHuskHitDuringActualBlock[0],
                        "no real husk hit was blocked during an active block interval: wear=" + bot.getOffhandItem().getDamageValue());
                arena.require(blockPhaseWithShieldUp[0], "the shield never reached an active block between the bot's own swings");
                arena.finish();
            } else if (now > 440) {
                arena.fail("the husk was not killed: hp=" + husk.getHealth() + " " + fight.describe());
            }
        });
    }

    // ------------------------------------------------------------------ f: GuardTask owns the same melee shield rhythm without being replaced

    /**
     * A real persistent guard (rather than a one-shot CombatTask) acquires the husk, takes its own legal swing, holds the task-owned
     * shield while its cooldown refills, blocks a real husk attack, and lowers only long enough for its second legal swing. The
     * watcher must leave that active GuardTask in place throughout the exchange.
     */
    @GameTest(environment = ENV + "guard_task_blocks_husk_between_its_own_swings_without_being_replaced", maxTicks = 520)
    public void guardTaskBlocksHuskBetweenItsOwnSwingsWithoutBeingReplaced(GameTestHelper context) {
        Arena arena = new Arena(context);
        AIPlayerEntity bot = arena.bot("ShieldGuardTaskGT", 0, 0);
        arena.armed(bot);
        bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0D);
        bot.setHealth(bot.getMaxHealth());
        Husk husk = arena.husk(0, 5);
        husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(80.0D);
        husk.setHealth(80.0F);
        husk.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0D);
        husk.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(8.0D);
        husk.setTarget(bot);
        GuardTask guard = GuardTask.point(bot.blockPosition().immutable());
        TaskManager.INSTANCE.assign(bot, guard, TaskOrigin.safety("gametest_guard_shield_rhythm"));
        int[] tick = {0};
        int[] strikes = {0};
        float[] lastHuskHealth = {husk.getHealth()};
        int[] lastShieldDamage = {bot.getOffhandItem().getDamageValue()};
        boolean[] taskShieldWasActivelyBlocking = {false};
        float[] healthAtTaskBlockStart = {bot.getHealth()};
        boolean[] taskShieldAfterFirstStrike = {false};
        boolean[] blockedHuskHit = {false};
        context.onEachTick(() -> {
            int now = ++tick[0];
            arena.require(bot.isAlive(), "the guard bot died");
            arena.require(TaskManager.INSTANCE.getActive(bot).orElse(null) == guard,
                    "DangerWatcher replaced the active GuardTask mid-defense: active="
                            + TaskManager.INSTANCE.getActive(bot).map(Task::name));
            if (husk.getHealth() < lastHuskHealth[0]) {
                strikes[0]++;
            }
            lastHuskHealth[0] = husk.getHealth();
            boolean taskShieldNow = guard.holdsItsShield(bot)
                    && ShieldGuard.usingShield(bot)
                    && !ShieldGuard.holdsShield(bot)
                    && bot.isBlocking()
                    && bot.getTicksUsingItem() >= ShieldGuard.blockDelayTicks(bot);
            if (taskShieldNow && !taskShieldWasActivelyBlocking[0]) {
                healthAtTaskBlockStart[0] = bot.getHealth();
            }
            if (strikes[0] >= 1 && taskShieldNow) {
                arena.require(bot.getHealth() >= healthAtTaskBlockStart[0],
                        "the husk hurt the bot during GuardTask's raised shield interval: "
                                + healthAtTaskBlockStart[0] + " -> " + bot.getHealth());
                taskShieldAfterFirstStrike[0] = true;
            }
            if (taskShieldNow && bot.getOffhandItem().getDamageValue() > lastShieldDamage[0]) {
                blockedHuskHit[0] = true;
            }
            taskShieldWasActivelyBlocking[0] = taskShieldNow;
            lastShieldDamage[0] = bot.getOffhandItem().getDamageValue();
            if (strikes[0] >= 2) {
                arena.require(taskShieldAfterFirstStrike[0],
                        "the GuardTask never held its own shield after its first real strike: " + guard.describe());
                arena.require(blockedHuskHit[0],
                        "no real husk hit wore the task-owned shield: wear=" + bot.getOffhandItem().getDamageValue());
                arena.finish();
            } else if (now > 480) {
                arena.fail("the guard exchange did not reach a second strike: strikes=" + strikes[0]
                        + " shield_after_first=" + taskShieldAfterFirstStrike[0]
                        + " shield_wear=" + bot.getOffhandItem().getDamageValue()
                        + " bot_hp=" + bot.getHealth()
                        + " guard=" + guard.describe() + " husk_hp=" + husk.getHealth());
            }
        });
    }

    // ------------------------------------------------------------------ g: an axe disables the shield; the bot fights on without re-raise spam

    @GameTest(environment = ENV + "axe_vindicator_disables_the_shield_and_the_companion_fights_on_without_re_raise_spam", maxTicks = 700)
    public void axeVindicatorDisablesTheShieldAndTheCompanionFightsOnWithoutReRaiseSpam(GameTestHelper context) {
        Arena arena = new Arena(context);
        AIPlayerEntity bot = arena.bot("ShieldAxeGT", 0, 0);
        arena.armed(bot);
        Vindicator vindicator = EntityType.VINDICATOR.create(arena.level, EntitySpawnReason.COMMAND);
        arena.require(vindicator != null, "failed to create the vindicator");
        vindicator.setPersistenceRequired();
        vindicator.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        vindicator.getAttribute(Attributes.MAX_HEALTH).setBaseValue(120.0D);
        vindicator.setHealth(120.0F);
        vindicator.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0D); // it stays in reach of the bot's blows
        arena.add(vindicator, 0, 5, 180.0F);
        CombatTask fight = CombatTask.defensive(vindicator, 6.0F, bot.blockPosition().immutable());
        TaskManager.INSTANCE.assign(bot, fight, TaskOrigin.safety("gametest_shield_axe"));
        int[] tick = {0};
        int[] disabledAt = {-1};
        long[] attemptsAtDisable = {0};
        float[] vindicatorHpAtDisable = {0.0F};
        boolean[] raisedBeforeDisable = {false};
        context.onEachTick(() -> {
            int now = ++tick[0];
            bot.setHealth(bot.getMaxHealth()); // the fight is about the shield's state, not about surviving an iron axe
            ItemStack shield = bot.getOffhandItem();
            boolean onCooldown = bot.getCooldowns().isOnCooldown(shield);
            if (disabledAt[0] < 0) {
                raisedBeforeDisable[0] |= ShieldGuard.usingShield(bot);
                if (onCooldown) {
                    disabledAt[0] = now;
                    attemptsAtDisable[0] = ShieldGuard.vanillaUseAttempts();
                    vindicatorHpAtDisable[0] = vindicator.getHealth();
                    arena.require(raisedBeforeDisable[0], "the axe disabled a shield that was never raised");
                } else if (now > 330) {
                    arena.fail("the vindicator never hit the raised shield: " + fight.describe() + " wear=" + shield.getDamageValue());
                }
                return;
            }
            if (onCooldown) {
                arena.require(!ShieldGuard.usingShield(bot), "the disabled shield was raised during its cooldown (tick " + (now - disabledAt[0]) + ")");
                arena.require(ShieldGuard.vanillaUseAttempts() == attemptsAtDisable[0],
                        "the bot kept trying to raise the disabled shield (re-raise spam): "
                                + (ShieldGuard.vanillaUseAttempts() - attemptsAtDisable[0]) + " attempts");
                arena.require(fight.state() == TaskState.RUNNING, "the fight ended while the shield was disabled: " + fight.state());
                return;
            }
            // The vanilla cooldown (five seconds for an axe) is over: the bot fought on through it.
            arena.require(now - disabledAt[0] >= 80, "the shield came back early: " + (now - disabledAt[0]) + " ticks");
            arena.require(vindicator.getHealth() < vindicatorHpAtDisable[0] - 5.0F || !vindicator.isAlive(),
                    "the bot did not fight on while the shield was disabled: vindicator hp " + vindicatorHpAtDisable[0] + " -> "
                            + vindicator.getHealth());
            arena.finish();
        });
    }

    // ------------------------------------------------------------------ g: a primed creeper that cannot be escaped

    @GameTest(environment = ENV + "primed_creeper_that_cannot_be_escaped_is_faced_and_blocked_for_less_damage", maxTicks = 260)
    public void primedCreeperThatCannotBeEscapedIsFacedAndBlockedForLessDamage(GameTestHelper context) {
        Arena arena = new Arena(context);
        // Two identical sealed rooms twelve blocks apart: the same blast at the same distance, one bot with a shield and one without.
        // Each bot looks south and has its creeper three blocks to the east, at the edge of its view: it notices the creeper first
        // (peripheral reaction time), then the fuse is lit, and the shielded bot must face it (human turn speed) before the blast.
        AIPlayerEntity shielded = arena.bot("ShieldBlastGT", 0, 0);
        AIPlayerEntity bare = arena.bot("BareBlastGT", 12, 0);
        for (int room : new int[]{0, 12}) {
            arena.sealedRoom(room);
        }
        for (AIPlayerEntity bot : List.of(shielded, bare)) {
            bot.getInventory().clearContent();
            bot.setHealth(20.0F);
        }
        InventoryAction.giveItem(shielded, new ItemStack(Items.SHIELD));
        Creeper shieldedCreeper = arena.creeper(3, 0);
        Creeper bareCreeper = arena.creeper(15, 0);
        arena.hold(shielded);
        arena.hold(bare);
        int[] tick = {0};
        int[] primedAt = {-1};
        CreeperDefenseTask[] shieldedTask = {null};
        boolean[] sawShieldPhase = {false};
        boolean[] sawBlocking = {false};
        double[] offsetAtBlast = {-1.0D};
        int[] gone = {0};
        float[] lastHp = {20.0F, 20.0F};
        context.onEachTick(() -> {
            int now = ++tick[0];
            if (primedAt[0] < 0) {
                if (CreatureSenses.INSTANCE.noticed(shielded, shieldedCreeper) && CreatureSenses.INSTANCE.noticed(bare, bareCreeper)) {
                    primedAt[0] = now;
                    arena.prime(shieldedCreeper);
                    arena.prime(bareCreeper);
                    shieldedTask[0] = new CreeperDefenseTask(shieldedCreeper, shieldedCreeper.blockPosition());
                    TaskManager.INSTANCE.assign(shielded, shieldedTask[0], TaskOrigin.safety("gametest_blast_shielded"));
                    TaskManager.INSTANCE.assign(bare, new CreeperDefenseTask(bareCreeper, bareCreeper.blockPosition()),
                            TaskOrigin.safety("gametest_blast_bare"));
                } else if (now > 100) {
                    arena.fail("fixture: the bots never noticed their creepers: shielded=" + CreatureSenses.INSTANCE.noticed(shielded, shieldedCreeper)
                            + " bare=" + CreatureSenses.INSTANCE.noticed(bare, bareCreeper));
                }
                return;
            }
            if (shieldedTask[0].describe().contains("phase=SHIELD")) {
                sawShieldPhase[0] = true;
            }
            if (shielded.isBlocking()) {
                sawBlocking[0] = true;
            }
            if (shieldedCreeper.isAlive()) {
                offsetAtBlast[0] = ShieldRules.offsetDeg(shielded.getYHeadRot(),
                        shieldedCreeper.getX() - shielded.getX(), shieldedCreeper.getZ() - shielded.getZ());
            }
            if (shielded.isAlive()) {
                lastHp[0] = shielded.getHealth();
            }
            if (bare.isAlive()) {
                lastHp[1] = bare.getHealth();
            }
            if (!shieldedCreeper.isAlive() && !bareCreeper.isAlive()) {
                if (++gone[0] >= 3) {
                    float shieldedDamage = 20.0F - lastHp[0];
                    float bareDamage = 20.0F - lastHp[1];
                    arena.require(sawShieldPhase[0] && sawBlocking[0],
                            "the shielded bot never blocked: phase_seen=" + sawShieldPhase[0] + " blocking_seen=" + sawBlocking[0]);
                    arena.require(offsetAtBlast[0] >= 0.0D && offsetAtBlast[0] <= 90.0D,
                            "the shielded bot did not face the creeper: " + offsetAtBlast[0] + " degrees off its head direction");
                    arena.require(bareDamage > 0.0F, "fixture: the unshielded bot took no damage from the blast");
                    arena.require(shieldedDamage < bareDamage,
                            "the shield did not lower the damage: shielded=" + shieldedDamage + " unshielded=" + bareDamage);
                    arena.finish();
                }
            } else if (now > primedAt[0] + 120) {
                arena.fail("the creepers never exploded");
            }
        });
    }

    // ------------------------------------------------------------------ h: a follower blocks an arrow in flight and keeps following

    /**
     * RULES: a follower sprints after its player while hostiles are aggroed, so in follow mode the shield is raised only for what is
     * already in flight at it (a hold of a few ticks), never pre-emptively against a shooter drawing at it. The follower passes a
     * skeleton that keeps its bow drawn at it: no raise for the draw; then the skeleton's arrow is blocked with a short raise, during
     * which the follower keeps pushing forward at the vanilla item-use pace (no sprint), and after which it follows at full pace again.
     */
    @GameTest(environment = ENV + "follower_blocks_an_arrow_at_the_slowed_pace_and_keeps_following", maxTicks = 360)
    public void followerBlocksAnArrowAtTheSlowedPaceAndKeepsFollowing(GameTestHelper context) {
        FollowFieldFixture f = new FollowFieldFixture(context, 40, 10);
        // The shooter must tick (an entity in a chunk that is merely loaded does not, and the arena straddles chunk borders at
        // random): every chunk of the course is forced, as the fixture of a wide scene does.
        GameTestChunkForcing.forceForTest(context,
                ((int) Math.floor(f.x(-24.0D))) >> 4, ((int) Math.floor(f.x(40.0D))) >> 4,
                ((int) Math.floor(f.z(-12.0D))) >> 4, ((int) Math.floor(f.z(12.0D))) >> 4);
        CreatureSenses.forceEnabledForTests(true);
        f.onFinish(() -> {
            CreatureSenses.forceEnabledForTests(false);
            CreatureSenses.INSTANCE.clearAll();
        });
        AIPlayerEntity bot = f.bot("ShieldFollowGT", -6, 0, false);
        bot.getInventory().clearContent();
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD));
        bot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        ServerPlayer target = f.target(2, 0);
        Skeleton skeleton = EntityType.SKELETON.create(f.level, EntitySpawnReason.COMMAND);
        skeleton.setPersistenceRequired();
        skeleton.setNoAi(true);
        skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        skeleton.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        f.add(skeleton, 10.0D, 2.0D);
        skeleton.setYRot(90.0F);
        skeleton.setYHeadRot(90.0F);
        skeleton.setYBodyRot(90.0F);
        skeleton.startUsingItem(InteractionHand.MAIN_HAND);
        FollowTask follow = f.follow(bot, target.getGameProfile().name(), "gametest_shield_follow");
        int[] tick = {0};
        int[] noticedAt = {-1};
        int[] firedAt = {-1};
        int[] wornAt = {-1};
        int[] loweredAt = {-1};
        int[] heldTicks = {0};
        Arrow[] arrow = {null};
        double[] maxSpeedAfter = {0.0D};
        Vec3[] last = {bot.position()};
        context.onEachTick(() -> {
            int now = ++tick[0];
            f.place(target, 2.0D + Math.min(now, 200) * 0.2D, 0.0D); // the followed player walks off along +x
            f.require(bot.isAlive(), "the bot died");
            f.require(follow.state() == TaskState.RUNNING && TaskManager.INSTANCE.getActive(bot).orElse(null) == follow,
                    "the follower stopped following: " + follow.state());
            Vec3 here = bot.position();
            double speed = Math.hypot(here.x - last[0].x, here.z - last[0].z);
            last[0] = here;
            if (!skeleton.isUsingItem()) {
                skeleton.startUsingItem(InteractionHand.MAIN_HAND); // the fixture skeleton has no AI: it keeps its bow drawn
            }
            if (firedAt[0] < 0) {
                f.require(!ShieldGuard.usingShield(bot),
                        "the follower held its shield up against a drawing shooter (follow mode sprints: in-flight blocks only)");
                if (noticedAt[0] < 0 && CreatureSenses.INSTANCE.noticed(bot, skeleton)) {
                    noticedAt[0] = now;
                }
                if (noticedAt[0] >= 0 && now >= noticedAt[0] + 5) {
                    f.require(CombatTask.nearbyDrawingShooter(bot) == skeleton, "fixture: the skeleton is not drawing at the follower");
                    firedAt[0] = now;
                    arrow[0] = new Arrow(f.level, skeleton, new ItemStack(Items.ARROW), null);
                    Vec3 aim = aimFor(arrow[0].position(), bot.getEyePosition(), ARROW_SPEED);
                    arrow[0].shoot(aim.x, aim.y, aim.z, ARROW_SPEED, 0.0F);
                    f.level.addFreshEntity(arrow[0]);
                } else if (now > 200) {
                    f.require(false, "fixture: the follower never noticed the skeleton: draw=" + skeleton.getTicksUsingItem());
                }
                return;
            }
            if (ShieldGuard.usingShield(bot)) {
                heldTicks[0]++;
                ShieldGuard.RaiseCause cause = ShieldGuard.lastRaise(bot);
                f.require(ShieldGuard.holdsShield(bot) && cause != null && cause.reason().equals("incoming_projectile")
                                && cause.sourceId() == arrow[0].getId(),
                        "the shield was up, but not for the arrow in flight: " + cause);
                f.require(!bot.isSprinting(), "the follower sprinted with the shield up");
                f.require(bot.zza > 0.05F && bot.zza <= 0.21F,
                        "with the shield up the follower's forward key is not the vanilla item-use pace: zza=" + bot.zza);
            }
            if (wornAt[0] < 0) {
                if (bot.getOffhandItem().getDamageValue() > 0) {
                    f.require(bot.getHealth() >= 20.0F, "the arrow hurt the follower through the shield: " + bot.getHealth());
                    f.require(heldTicks[0] > 0, "the arrow wore the shield without the follower ever holding it up");
                    wornAt[0] = now;
                } else if (now > firedAt[0] + 30) {
                    f.require(false, "the arrow was never blocked: hp=" + bot.getHealth() + " held=" + heldTicks[0]);
                }
                return;
            }
            if (loweredAt[0] < 0) {
                if (!ShieldGuard.usingShield(bot)) {
                    loweredAt[0] = now;
                } else {
                    f.require(now <= wornAt[0] + 10, "the follower kept its shield up after the arrow was blocked (a short hold only)");
                }
                return;
            }
            maxSpeedAfter[0] = Math.max(maxSpeedAfter[0], speed);
            if (now >= loweredAt[0] + 30) {
                f.require(maxSpeedAfter[0] > 0.2D, "the follower did not get back to its full pace after the block: " + maxSpeedAfter[0]);
                f.finish();
            }
        });
    }

    // ------------------------------------------------------------------ m: a task shield never outlives its task

    /**
     * A combat task that ends while its melee rhythm holds the shield up (here: a combat timeout during BLOCK) leaves nothing up: the
     * shield comes down, the follow it paused resumes, and the follower moves at its full pace again.
     */
    @GameTest(environment = ENV + "combat_ending_in_its_block_phase_leaves_no_shield_up_and_the_follow_resumes", maxTicks = 400)
    public void combatEndingInItsBlockPhaseLeavesNoShieldUpAndTheFollowResumes(GameTestHelper context) {
        Standby s = new Standby(context, "ShieldStaleGT");
        s.armed();
        Husk husk = EntityType.HUSK.create(s.f.level, EntitySpawnReason.COMMAND);
        s.require(husk != null, "failed to create the husk");
        husk.setPersistenceRequired();
        husk.setNoAi(true);
        husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200.0D);
        husk.setHealth(200.0F);
        husk.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1.0D);
        s.f.add(husk, -6.0D, 2.0D);
        int[] tick = {0};
        CombatTask[] fight = {null};
        int[] endedAt = {-1};
        int[] resumedAt = {-1};
        context.onEachTick(() -> {
            int now = ++tick[0];
            s.require(s.bot.isAlive(), "the bot died");
            if (fight[0] == null) {
                if (now == 10) {
                    TaskManager.INSTANCE.pauseFor(s.bot, "gametest_fight");
                    fight[0] = CombatTask.defensive(husk, 6.0F, s.bot.blockPosition().immutable());
                    TaskManager.INSTANCE.assign(s.bot, fight[0], TaskOrigin.safety("gametest_shield_stale"));
                }
                return;
            }
            if (endedAt[0] < 0) {
                if (fight[0].holdsItsShield() && ShieldGuard.usingShield(s.bot) && !ShieldGuard.holdsShield(s.bot)) {
                    // The fight ends with its own shield up (what a combat_timeout in the BLOCK phase does), the husk gone.
                    fight[0].fail("combat_timeout");
                    husk.discard();
                    endedAt[0] = now;
                } else if (now > 250) {
                    s.fail("fixture: the fight never raised its shield between swings: " + fight[0].describe());
                }
                return;
            }
            if (now >= endedAt[0] + 3) {
                s.require(!s.bot.isUsingItem(), "the shield the ended fight raised is still up " + (now - endedAt[0]) + " ticks later");
            }
            if (resumedAt[0] < 0) {
                if (TaskManager.INSTANCE.getActive(s.bot).orElse(null) == s.follow) {
                    resumedAt[0] = now;
                } else if (now > endedAt[0] + 100) {
                    s.fail("the paused follow never resumed: active=" + TaskManager.INSTANCE.getActive(s.bot).map(Task::name));
                }
                return;
            }
            if (now >= resumedAt[0] + 20) {
                s.require(s.follow.state() == TaskState.RUNNING, "the resumed follow is not running: " + s.follow.state());
                s.require(!s.bot.isUsingItem(), "the resumed follower carries a raised shield");
                s.finish();
            }
        });
    }

    // ------------------------------------------------------------------ i: a bow in the main hand is switched away first

    @GameTest(environment = ENV + "bow_in_the_main_hand_is_switched_to_the_sword_before_the_shield_goes_up", maxTicks = 300)
    public void bowInTheMainHandIsSwitchedToTheSwordBeforeTheShieldGoesUp(GameTestHelper context) {
        Standby s = new Standby(context, "ShieldBowGT");
        AIPlayerEntity bot = s.bot;
        bot.getInventory().clearContent();
        bot.getInventory().setItem(0, new ItemStack(Items.BOW));
        bot.getInventory().setItem(1, new ItemStack(Items.STONE_SWORD));
        bot.getInventory().setItem(9, new ItemStack(Items.ARROW, 16));
        bot.getInventory().setSelectedSlot(0);
        bot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        s.require(bot.getMainHandItem().is(Items.BOW), "fixture: the bow is not in the main hand");
        // A skeleton the follower watches shoots at it (follow mode blocks what is in flight, RULES): the raise needs the use key,
        // which the bow would take first (vanilla tries MAIN_HAND before OFF_HAND), so the hotbar goes to the sword (one tick), then the
        // shield comes up, both before the arrow arrives.
        Skeleton skeleton = s.shooter(EntityType.SKELETON, new ItemStack(Items.BOW));
        skeleton.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        int[] tick = {0};
        boolean[] switched = {false};
        boolean[] shieldUp = {false};
        int[] firedAt = {-1};
        context.onEachTick(() -> {
            int now = ++tick[0];
            s.require(bot.isAlive(), "the bot died");
            s.requireFollowing();
            s.require(!(bot.isUsingItem() && bot.getUseItem().is(Items.BOW)),
                    "the bow was drawn instead of the shield going up (vanilla's use order takes the main hand first)");
            if (!bot.getMainHandItem().is(Items.BOW)) {
                switched[0] = true;
            }
            if (ShieldGuard.usingShield(bot)) {
                shieldUp[0] = true;
                s.require(switched[0] && bot.getMainHandItem().is(Items.STONE_SWORD),
                        "the shield went up with " + bot.getMainHandItem().getItem() + " in the main hand, before the hotbar change");
            }
            if (firedAt[0] < 0) {
                s.require(!ShieldGuard.usingShield(bot), "the shield went up before anything was shot");
                if (s.noticedAndStill(skeleton)) {
                    firedAt[0] = now;
                    s.shoot(skeleton, null);
                } else if (now > 200) {
                    s.fail("fixture: the follower never noticed the skeleton: " + s.describeNotice(skeleton));
                }
                return;
            }
            if (bot.getOffhandItem().getDamageValue() > 0) {
                s.require(shieldUp[0] && switched[0], "the arrow wore the shield without the switch and the raise being seen");
                s.require(bot.getHealth() >= 20.0F, "the arrow hurt the bot: " + bot.getHealth());
                s.finish();
            } else if (now > firedAt[0] + 30) {
                s.fail("the arrow was never blocked: switched=" + switched[0] + " shield_up=" + shieldUp[0] + " hp=" + bot.getHealth()
                        + " main=" + bot.getMainHandItem().getItem());
            }
        });
    }

    // ------------------------------------------------------------------ n: a guardian beam keeps its magic component but blocks its mob attack

    /**
     * A real, AI-driven guardian charging its beam on a stationary survivor in an open one-block-deep pool. The beam's direct
     * {@code mob_attack} component must wear the shield, while its unavoidable magic component still takes a small amount of health.
     * No manual damage is applied: the guardian's ordinary attack goal delivers both parts.
     */
    @GameTest(environment = ENV + "guardian_beam_blocks_mob_attack_while_magic_still_hurts", maxTicks = 420)
    public void guardianBeamBlocksMobAttackWhileMagicStillHurts(GameTestHelper context) {
        Arena arena = new Arena(context);
        AIPlayerEntity bot = arena.bot("ShieldGuardianBeamGT", 0, 0);
        arena.armed(bot);
        arena.hold(bot);
        // Seventeen food stays below vanilla's natural-regeneration threshold without triggering the critical-resupply scheduler.
        bot.getFoodData().setFoodLevel(17);
        bot.getFoodData().setSaturation(0.0F);
        Guardian guardian = arena.guardianInOpenPool(0, 12);
        guardian.setTarget(bot);
        int[] tick = {0};
        int[] beamAt = {-1};
        int[] wearBefore = {0};
        float[] healthBefore = {0.0F};
        boolean[] sawGuardianRaise = {false};
        boolean[] shieldWorn = {false};
        context.onEachTick(() -> {
            int now = ++tick[0];
            arena.require(bot.isAlive(), "the guardian killed the bot before the beam proof completed");
            if (beamAt[0] < 0) {
                if (guardian.hasActiveAttackTarget() && guardian.getActiveAttackTarget() == bot) {
                    beamAt[0] = now;
                    wearBefore[0] = bot.getOffhandItem().getDamageValue();
                    healthBefore[0] = bot.getHealth();
                } else if (now > 220) {
                    arena.fail("fixture: the guardian never locked its real beam on the bot: target=" + guardian.getTarget()
                            + " guardian=" + guardian.position() + " bot=" + bot.position());
                }
                return;
            }
            if (guardian.hasActiveAttackTarget() && guardian.getActiveAttackTarget() == bot
                    && ShieldGuard.holdsShield(bot) && bot.isBlocking()) {
                ShieldGuard.RaiseCause cause = ShieldGuard.lastRaise(bot);
                arena.require(cause != null && cause.reason().equals("guardian_beam") && cause.sourceId() == guardian.getId(),
                        "the shield came up during the guardian beam for the wrong cause: " + cause);
                sawGuardianRaise[0] = true;
            }
            shieldWorn[0] |= bot.getOffhandItem().getDamageValue() > wearBefore[0];
            float lost = healthBefore[0] - bot.getHealth();
            if (shieldWorn[0] && lost > 0.0F) {
                arena.require(sawGuardianRaise[0], "the guardian beam fired before its guardian_beam shield raise was observed");
                // Vanilla GuardianAttackGoal first applies indirect magic, then doHurtTarget. The former is not blockable; the latter
                // is. Normal loses at most one health point here and hard at most three, so the cross-difficulty proof uses the hard cap.
                arena.require(lost <= 3.0F,
                        "the guardian's blockable mob-attack part got through the shield: health lost=" + lost);
                arena.finish();
            } else if (now > beamAt[0] + 140) {
                arena.fail("the active guardian beam never produced both its magic damage and shield wear: hp=" + healthBefore[0]
                        + " -> " + bot.getHealth() + " wear=" + wearBefore[0] + " -> " + bot.getOffhandItem().getDamageValue()
                        + " raised=" + sawGuardianRaise[0] + " active_beam=" + guardian.hasActiveAttackTarget());
            }
        });
    }

    // ------------------------------------------------------------------ o: a visible PrimedTnt gets the real reactive shield response

    /**
     * Two identical, enclosed blast cells make the real explosion comparison stable: a visibly primed TNT entity has its ordinary
     * vanilla fuse, while only one stationary survivor has a shield. The shielded survivor must react with the TNT-specific reason,
     * actually wear the shield, and take less blast damage than the otherwise identical unshielded control.
     */
    @GameTest(environment = ENV + "visible_primed_tnt_raises_and_reduces_blast_damage_against_an_unshielded_control", maxTicks = 220)
    public void visiblePrimedTntRaisesAndReducesBlastDamageAgainstAnUnshieldedControl(GameTestHelper context) {
        Arena arena = new Arena(context);
        AIPlayerEntity shielded = arena.bot("ShieldPrimedTntGT", 0, 0);
        AIPlayerEntity bare = arena.bot("BarePrimedTntGT", 12, 0);
        for (int room : new int[]{0, 12}) {
            arena.sealedRoom(room);
        }
        for (AIPlayerEntity bot : List.of(shielded, bare)) {
            bot.getInventory().clearContent();
            bot.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0D);
            bot.setHealth(bot.getMaxHealth());
            // Below the vanilla regeneration threshold, above the emergency hunger threshold.
            bot.getFoodData().setFoodLevel(17);
            bot.getFoodData().setSaturation(0.0F);
            arena.hold(bot);
        }
        shielded.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        // Both TNTs are inside their cells and within the default vanilla blast reach. Eighty ticks leaves room for normal sighting,
        // reaction, facing, and the shield's vanilla block delay; no test code deals damage or forces a shield raise.
        PrimedTnt shieldedTnt = arena.primedTnt(3, 1, 80);
        PrimedTnt bareTnt = arena.primedTnt(15, 1, 80);
        int[] tick = {0};
        int[] explosionsSettled = {0};
        int[] shieldWearBefore = {shielded.getOffhandItem().getDamageValue()};
        boolean[] sawPrimedTntRaise = {false};
        context.onEachTick(() -> {
            int now = ++tick[0];
            arena.require(shielded.isAlive() && bare.isAlive(), "a TNT fixture bot died before the damage comparison");
            if (shieldedTnt.isAlive() && ShieldGuard.holdsShield(shielded) && shielded.isBlocking()) {
                ShieldGuard.RaiseCause cause = ShieldGuard.lastRaise(shielded);
                arena.require(cause != null && cause.reason().equals("primed_tnt") && cause.sourceId() == shieldedTnt.getId(),
                        "the shield came up for the visible TNT with the wrong reactive cause: " + cause);
                sawPrimedTntRaise[0] = true;
            }
            if (!shieldedTnt.isAlive() && !bareTnt.isAlive()) {
                if (++explosionsSettled[0] >= 3) {
                    float shieldedDamage = shielded.getMaxHealth() - shielded.getHealth();
                    float bareDamage = bare.getMaxHealth() - bare.getHealth();
                    arena.require(sawPrimedTntRaise[0], "the shield never actively raised for visible primed TNT");
                    arena.require(shielded.getOffhandItem().getDamageValue() > shieldWearBefore[0],
                            "the real TNT blast did not wear the raised shield");
                    arena.require(bareDamage > 0.0F, "fixture: the unshielded control took no TNT blast damage");
                    arena.require(shieldedDamage < bareDamage,
                            "the primed-TNT shield response did not reduce damage: shielded=" + shieldedDamage + " unshielded=" + bareDamage);
                    arena.finish();
                }
            } else if (now > 150) {
                arena.fail("the PrimedTnt fixtures did not both explode: shielded_fuse=" + shieldedTnt.getFuse()
                        + " bare_fuse=" + bareTnt.getFuse() + " raised=" + sawPrimedTntRaise[0]);
            }
        });
    }

    /**
     * The exact initial velocity for an ordinary vanilla Arrow to travel from {@code from} to {@code to} in the requested number of
     * physical ticks. This deliberately uses the same drag/gravity recurrence as {@link ProjectileBallistics}, so its high arc is a
     * real in-flight projectile rather than a delayed manual hit.
     */
    private static Vec3 arcingVelocity(Vec3 from, Vec3 to, int flightTicks) {
        int ticks = Math.max(1, flightTicks);
        double dragSum = (1.0D - Math.pow(ProjectileBallistics.DRAG, ticks)) / (1.0D - ProjectileBallistics.DRAG);
        double gravityShift = ProjectileBallistics.GRAVITY / (1.0D - ProjectileBallistics.DRAG);
        Vec3 delta = to.subtract(from);
        return new Vec3(delta.x / dragSum,
                (delta.y + gravityShift * ticks) / dragSum - gravityShift,
                delta.z / dragSum);
    }

    /** The direction to launch an arrow at {@code speed} so that it reaches {@code to} from {@code from} despite gravity and drag. */
    private static Vec3 aimFor(Vec3 from, Vec3 to, float speed) {
        Vec3 delta = to.subtract(from);
        double horizontal = Math.hypot(delta.x, delta.z);
        double pitch = Math.toRadians(ProjectileBallistics.pitchForShot(horizontal, delta.y, speed));
        double yaw = Math.atan2(-delta.x, delta.z);
        return new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
    }

    // ------------------------------------------------------------------ the follower standing by its player

    /**
     * A follower standing by the player it follows (follow mode: an ordinary hostile does not pull it into a fight), looking at that
     * player, and a shooter a dozen blocks away on the same side, so the bot faces it too and notices it with the ordinary reaction
     * time. The player stands off the line of fire.
     */
    private static final class Standby {
        final FollowFieldFixture f;
        final AIPlayerEntity bot;
        final ServerPlayer player;
        final FollowTask follow;
        private final GameTestHelper context;

        Standby(GameTestHelper context, String name) {
            this.context = context;
            this.f = new FollowFieldFixture(context, 24, 10);
            GameTestChunkForcing.forceForTest(context,
                    ((int) Math.floor(f.x(-24.0D))) >> 4, ((int) Math.floor(f.x(24.0D))) >> 4,
                    ((int) Math.floor(f.z(-12.0D))) >> 4, ((int) Math.floor(f.z(12.0D))) >> 4);
            CreatureSenses.forceEnabledForTests(true);
            f.onFinish(() -> {
                CreatureSenses.forceEnabledForTests(false);
                CreatureSenses.INSTANCE.clearAll();
            });
            this.bot = f.bot(name, -6, 0, false);
            this.player = f.target(-4, -2);
            this.follow = f.follow(bot, player.getGameProfile().name(), "gametest_shield_standby");
        }

        /** A stone sword in the hotbar and a shield in the offhand, nothing else. */
        void armed() {
            bot.getInventory().clearContent();
            InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD));
            bot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        }

        /** A mob with no AI at (8, 3), fourteen blocks from the bot, facing it, holding {@code weapon}. */
        <T extends Mob> T shooter(EntityType<T> type, ItemStack weapon) {
            T mob = type.create(f.level, EntitySpawnReason.COMMAND);
            require(mob != null, "failed to create the shooter");
            mob.setPersistenceRequired();
            mob.setNoAi(true);
            if (!weapon.isEmpty()) {
                mob.setItemSlot(EquipmentSlot.MAINHAND, weapon);
            }
            f.add(mob, 8.0D, 3.0D);
            float yaw = (float) Math.toDegrees(Math.atan2(-(bot.getX() - mob.getX()), bot.getZ() - mob.getZ()));
            mob.setYRot(yaw);
            mob.setYHeadRot(yaw);
            mob.setYBodyRot(yaw);
            return mob;
        }

        /** The bot has noticed {@code creature} and stands by its player (not walking): the shot can come. */
        boolean noticedAndStill(LivingEntity creature) {
            return CreatureSenses.INSTANCE.noticed(bot, creature)
                    && bot.getActionPack().isPathExecutorIdle() && bot.getActionPack().isWalkToIdle();
        }

        String describeNotice(LivingEntity creature) {
            return "noticed=" + CreatureSenses.INSTANCE.noticed(bot, creature) + " bot=" + bot.position() + " yaw=" + bot.getYHeadRot()
                    + " creature=" + creature.position() + " path_idle=" + bot.getActionPack().isPathExecutorIdle();
        }

        /** An arrow (or bolt) released by {@code owner}, from its eyes at the bot's eyes, as if fired from {@code weapon}. */
        Arrow shoot(LivingEntity owner, ItemStack weapon) {
            Arrow arrow = new Arrow(f.level, owner, new ItemStack(Items.ARROW), weapon);
            Vec3 aim = aimFor(arrow.position(), bot.getEyePosition(), ARROW_SPEED);
            arrow.shoot(aim.x, aim.y, aim.z, ARROW_SPEED, 0.0F);
            f.level.addFreshEntity(arrow);
            return arrow;
        }

        void requireFollowing() {
            require(follow.state() == TaskState.RUNNING && TaskManager.INSTANCE.getActive(bot).orElse(null) == follow,
                    "the follower stopped following: " + follow.state() + " active=" + TaskManager.INSTANCE.getActive(bot).map(Task::name));
        }

        void require(boolean condition, String message) {
            f.require(condition, message);
        }

        void fail(String message) {
            f.require(false, message);
        }

        void finish() {
            f.finish();
        }
    }

    // ------------------------------------------------------------------ the arena

    private static final class Arena {
        final GameTestHelper context;
        final ServerLevel level;
        final BlockPos feet;
        final List<Entity> entities = new ArrayList<>();
        private final List<String> botNames = new ArrayList<>();
        private boolean finished;

        /** The arena, with realistic perception ON for the test's batch. */
        Arena(GameTestHelper context) {
            this.context = context;
            this.level = context.getLevel();
            this.feet = context.absolutePos(new BlockPos(8, LAYER_Y, 8));
            level.setDayTime(1000L);
            GameTestChunkForcing.forceForTest(context,
                    (feet.getX() - HALF_X - 16) >> 4, (feet.getX() + HALF_X + 16) >> 4,
                    (feet.getZ() - BACK) >> 4, (feet.getZ() + AHEAD) >> 4);
            for (int dx = -HALF_X; dx <= HALF_X + 14; dx++) {
                for (int dz = -BACK; dz <= AHEAD; dz++) {
                    level.setBlock(feet.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                    for (int dy = 0; dy <= 6; dy++) {
                        level.setBlock(feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
            CreatureSenses.forceEnabledForTests(true);
            GameTestCleanup.whenFinished(context, this::cleanUp);
        }

        double x(double dx) {
            return feet.getX() + 0.5D + dx;
        }

        double z(double dz) {
            return feet.getZ() + 0.5D + dz;
        }

        void require(boolean condition, String message) {
            if (!condition) {
                fail(message);
            }
        }

        void fail(String message) {
            context.fail(Component.nullToEmpty(message));
            throw new IllegalStateException(message);
        }

        void finish() {
            if (!finished) {
                finished = true;
                context.succeed();
            }
        }

        AIPlayerEntity bot(String name, int dx, int dz) {
            AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                            level.getServer(), name, level, new Vec3(x(dx), feet.getY(), z(dz)), 0.0F, 0.0F, GameType.SURVIVAL)
                    .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
            botNames.add(name);
            if (!bot.connection.hasClientLoaded()) {
                bot.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            }
            bot.setHealth(bot.getMaxHealth());
            bot.getFoodData().setFoodLevel(20);
            bot.getFoodData().setSaturation(20.0F);
            return bot;
        }

        /** A stone sword in the hotbar and a shield in the offhand, nothing else. */
        void armed(AIPlayerEntity bot) {
            bot.getInventory().clearContent();
            InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD));
            bot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        }

        /** The bot keeps still and keeps its head where it is (a task that does nothing). */
        void hold(AIPlayerEntity bot) {
            TaskManager.INSTANCE.assign(bot, new HoldingTask(), TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shield"));
        }

        <T extends Entity> T add(T entity, double dx, double dz, float yaw) {
            entity.snapTo(x(dx), feet.getY(), z(dz), yaw, 0.0F);
            level.addFreshEntity(entity);
            entities.add(entity);
            return entity;
        }

        Skeleton skeleton(int dx, int dz, float yaw) {
            Skeleton skeleton = EntityType.SKELETON.create(level, EntitySpawnReason.COMMAND);
            require(skeleton != null, "failed to create the skeleton");
            skeleton.setPersistenceRequired();
            skeleton.setNoAi(true);
            skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            skeleton.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET)); // no sun burn
            add(skeleton, dx, dz, yaw);
            skeleton.setYHeadRot(yaw);
            skeleton.setYBodyRot(yaw);
            skeleton.startUsingItem(InteractionHand.MAIN_HAND);
            return skeleton;
        }

        Husk husk(int dx, int dz) {
            Husk husk = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
            require(husk != null, "failed to create the husk");
            husk.setPersistenceRequired();
            return add(husk, dx, dz, 180.0F);
        }

        /** A real guardian in an open 5x5, one-block-deep water pool centred at the requested arena offset. */
        Guardian guardianInOpenPool(int centerDx, int centerDz) {
            for (int dx = centerDx - 2; dx <= centerDx + 2; dx++) {
                for (int dz = centerDz - 2; dz <= centerDz + 2; dz++) {
                    level.setBlock(feet.offset(dx, 0, dz), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
            Guardian guardian = EntityType.GUARDIAN.create(level, EntitySpawnReason.COMMAND);
            require(guardian != null, "failed to create the guardian");
            guardian.setPersistenceRequired();
            return add(guardian, centerDx, centerDz, 180.0F);
        }

        /** Clears a small test-only flight corridor above the normal arena headroom. */
        void clearAir(int minDx, int maxDx, int minDz, int maxDz, int maxDy) {
            for (int dx = minDx; dx <= maxDx; dx++) {
                for (int dz = minDz; dz <= maxDz; dz++) {
                    for (int dy = 0; dy <= maxDy; dy++) {
                        level.setBlock(feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }

        /** A real TNT entity with its normal explosion behavior and a deliberately long enough exposed fuse. */
        PrimedTnt primedTnt(int dx, int dz, int fuseTicks) {
            PrimedTnt tnt = EntityType.TNT.create(level, EntitySpawnReason.COMMAND);
            require(tnt != null, "failed to create PrimedTnt");
            tnt.setFuse(fuseTicks);
            return add(tnt, dx, dz, 0.0F);
        }

        /** An arrow from {@code from} at {@code to} at {@code speed} blocks per tick, owned by {@code owner} (or by nobody). */
        Arrow shoot(LivingEntity owner, Vec3 from, Vec3 to, float speed) {
            Arrow arrow = owner == null
                    ? new Arrow(level, from.x, from.y, from.z, new ItemStack(Items.ARROW), null)
                    : new Arrow(level, owner, new ItemStack(Items.ARROW), null);
            if (owner != null) {
                arrow.setPos(from.x, from.y, from.z);
            }
            Vec3 aim = aimFor(from, to, speed);
            arrow.shoot(aim.x, aim.y, aim.z, speed, 0.0F);
            level.addFreshEntity(arrow);
            entities.add(arrow);
            return arrow;
        }

        /** A real Arrow with an explicitly calculated ordinary vanilla launch velocity. */
        Arrow shoot(Vec3 from, Vec3 velocity) {
            Arrow arrow = new Arrow(level, from.x, from.y, from.z, new ItemStack(Items.ARROW), null);
            arrow.shoot(velocity.x, velocity.y, velocity.z, (float) velocity.length(), 0.0F);
            level.addFreshEntity(arrow);
            entities.add(arrow);
            return arrow;
        }

        /** A sealed room: stone walls and ceiling around an interior {@code dx-1..dx+4} by {@code dz-1..dz+1}, two blocks high. */
        void sealedRoom(int originDx) {
            for (int dx = -2; dx <= 5; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    boolean interior = dx >= -1 && dx <= 4 && dz >= -1 && dz <= 1;
                    for (int dy = 0; dy <= 1; dy++) {
                        level.setBlock(feet.offset(originDx + dx, dy, dz),
                                interior ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                    }
                    level.setBlock(feet.offset(originDx + dx, 2, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }

        /** A creeper with no AI, not lit yet. */
        Creeper creeper(int dx, int dz) {
            Creeper creeper = EntityType.CREEPER.create(level, EntitySpawnReason.COMMAND);
            require(creeper != null, "failed to create the creeper");
            creeper.setPersistenceRequired();
            creeper.setNoAi(true);
            add(creeper, dx, dz, 90.0F);
            return creeper;
        }

        /** Lights {@code creeper} and swells it to a late fuse (fifteen ticks from the blast). */
        void prime(Creeper creeper) {
            creeper.ignite();
            creeper.setSwellDir(1);
            for (int tick = 0; tick < 15; tick++) {
                creeper.tick();
            }
            require(creeper.isAlive() && creeper.getSwelling(1.0F) >= 0.45F, "fixture: the creeper did not reach a late fuse");
        }

        private void cleanUp() {
            CreatureSenses.forceEnabledForTests(false);
            for (Entity entity : entities) {
                entity.discard();
            }
            for (String name : botNames) {
                AIPlayerManager.INSTANCE.getByName(name).ifPresent(bot -> {
                    TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
                    bot.getActionPack().stopAll();
                });
                AIPlayerManager.INSTANCE.despawn(level.getServer(), name);
            }
            AggroSense.clearAll();
            CreatureSenses.INSTANCE.clearAll();
        }
    }

    private static final class HoldingTask extends AbstractTask {
        @Override
        public String name() {
            return "holding_work";
        }

        @Override
        public String describe() {
            return "Holding still, facing its work";
        }

        @Override
        public double progress() {
            return 0.5D;
        }

        @Override
        protected void onStart(AIPlayerEntity bot) {
        }

        @Override
        protected void onTick(AIPlayerEntity bot) {
        }
    }
}
