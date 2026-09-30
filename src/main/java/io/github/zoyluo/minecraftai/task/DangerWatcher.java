package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.EquipAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.PaceRules;
import io.github.zoyluo.minecraftai.action.QuietZone;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DangerWatcher {
    public static final DangerWatcher INSTANCE = new DangerWatcher();
    private final Map<UUID, Integer> nextThreatAttemptTick = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> nextEatAttemptTick = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> nextFireAttemptTick = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> nextPowderSnowAttemptTick = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> nextResupplyAttemptTick = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> nextNightAttemptTick = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> nextSurfaceSkipLogTick = new ConcurrentHashMap<>();
    private final Map<UUID, TrapRecord> trapRecords = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> nextHuntAttemptTick = new ConcurrentHashMap<>();
    private final Map<UUID, PosRecord> darkStuckRecords = new ConcurrentHashMap<>(); // Mitigation: trapped-in-the-dark detection
    private final Map<UUID, Integer> nextEscapeHelpTick = new ConcurrentHashMap<>();  // Escape help-request throttling
    private final Map<UUID, Integer> nextShelterAttemptTick = new ConcurrentHashMap<>();
    private final Map<UUID, ShelterEpisode> shelterEpisodes = new ConcurrentHashMap<>();
    /** botId -> (target entity uuid -> server tick until which defensive combat vs that target is held off after it left the leash). */
    private final Map<UUID, Map<UUID, Integer>> leashCooldowns = new ConcurrentHashMap<>();
    /** Counts how often a bot was judged trapped in the dark (before any surface escape is attempted); read by tests. */
    private final Map<UUID, Integer> darkTrapDetections = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> nextFollowKeepLogTick = new ConcurrentHashMap<>();

    // Layer 1 trapped backoff: an evasion-class task (evade/shelter) repeatedly firing on the same
    // cell without the bot escaping counts as "trapped". Back off for a while and stop dispatching,
    // throttling help requests by interval. This ends the "shelter/evade infinite-loop chat spam
    // every 2 seconds while stuck at the bottom of a pit at night" failure mode.
    private static final int TRAP_REPEAT_LIMIT = 4;      // 4 repeated evasive attempts on the same cell -> judged trapped
    private static final int TRAP_BACKOFF_TICKS = 600;   // After being trapped, back off for 30s and stop dispatching threat tasks
    private static final int TRAP_HELP_INTERVAL = 1200;  // Minimum interval between help messages: 60s (prevents chat spam)
    private static final int HUNT_FOOD_TARGET = 3;       // Layer 2 hunger chain: amount of raw meat to actively hunt for when there is no food
    private static final int DARK_STUCK_TICKS = 160;     // Mitigation: standing still in a dark underground spot for 8s is judged a "trapped in the dark" hazard; retreat to the surface
    /** Shelter is a final response, not a generic low-health/night-time behaviour. */
    private static final float LAST_RESORT_HEALTH_CAP = 10.0F;
    /** Vanilla stops a player's sprint at 6 food points or fewer: the bot eats at 7 so its food never gets there (R6). */
    static final int SPRINT_LIMIT_FOOD = PaceRules.SPRINT_FOOD_FLOOR + 1;
    /** Eating is put off while a calm warden is observed this close (its bites are vibrations) ... */
    static final double CALM_WARDEN_EAT_RANGE = 20.0D;
    /** ... unless the bot's health is at or below this (then the meal is worth the risk). */
    static final float CALM_WARDEN_EAT_HEALTH = 6.0F;
    /** The follow keeps an ordinary hostile threat instead of pausing for it, log at most this often (ticks). */
    private static final int FOLLOW_KEEP_LOG_TICKS = 100;
    /** A warden this close (even a calm one) is escaped, not followed past. */
    private static final double FOLLOW_WARDEN_EVADE_RANGE = 8.0D;
    private static final double DROP_RECOVERY_MAX_DISTANCE = 80.0D;
    private static final int DROP_RECOVERY_MAX_VERTICAL_DELTA = 24;
    private static final int SHELTER_RETRY_COOLDOWN = 100;
    private static final int SURFACE_RECHECK_TICKS = 200;   // a bot that is on the surface is looked at again (it may have walked into a cave) after 10s
    private static final int SURFACE_SKIP_LOG_TICKS = 600;  // at most one auto_light_skipped log per bot every 30s
    private static final double SHELTER_EPISODE_RADIUS = 4.0D;
    private static final double CLOSE_DEFENSIVE_HOSTILE_RADIUS = CombatCore.ATTACK_RANGE + 2.0D;
    /** After a defensive fight ended because its target left the leash, hold off re-engaging that same target this long (5s). */
    static final int TARGET_LEASH_COOLDOWN_TICKS = 100;

    private DangerWatcher() {
    }

    public void clear(AIPlayerEntity bot) {
        UUID id = bot.getUUID();
        nextThreatAttemptTick.remove(id);
        leashCooldowns.remove(id);
        darkTrapDetections.remove(id);
        nextEatAttemptTick.remove(id);
        nextFireAttemptTick.remove(id);
        nextPowderSnowAttemptTick.remove(id);
        nextResupplyAttemptTick.remove(id);
        nextNightAttemptTick.remove(id);
        nextSurfaceSkipLogTick.remove(id);
        trapRecords.remove(id);
        nextHuntAttemptTick.remove(id);
        darkStuckRecords.remove(id);
        nextEscapeHelpTick.remove(id);
        nextShelterAttemptTick.remove(id);
        shelterEpisodes.remove(id);
        nextFollowKeepLogTick.remove(id);
        AggroSense.clear(bot);
    }

    public void clearAll() {
        nextThreatAttemptTick.clear();
        leashCooldowns.clear();
        darkTrapDetections.clear();
        nextEatAttemptTick.clear();
        nextFireAttemptTick.clear();
        nextPowderSnowAttemptTick.clear();
        nextResupplyAttemptTick.clear();
        nextNightAttemptTick.clear();
        nextSurfaceSkipLogTick.clear();
        trapRecords.clear();
        nextHuntAttemptTick.clear();
        darkStuckRecords.clear();
        nextEscapeHelpTick.clear();
        nextShelterAttemptTick.clear();
        shelterEpisodes.clear();
        nextFollowKeepLogTick.clear();
    }

    private record TrapRecord(BlockPos pos, int repeatCount, int lastHelpTick) {
    }

    private record PosRecord(BlockPos pos, int sinceTick) {
    }

    private record ShelterEpisode(BlockPos anchor,
                                  int terminalTick,
                                  TaskState outcome,
                                  String reason) {
    }

    record DropRecoveryDecision(boolean allowed, String reason) {
    }

    static DropRecoveryDecision dropRecoveryDecision(BlockPos respawn,
                                                       BlockPos death,
                                                       int visibleHostiles,
                                                       boolean dangerous) {
        if (respawn == null || death == null) {
            return new DropRecoveryDecision(false, "missing_position");
        }
        if (dangerous) {
            return new DropRecoveryDecision(false, "known_danger_zone");
        }
        if (visibleHostiles > 0) {
            return new DropRecoveryDecision(false, "hostile_death_site");
        }
        if (Math.abs(respawn.getY() - death.getY()) > DROP_RECOVERY_MAX_VERTICAL_DELTA) {
            return new DropRecoveryDecision(false, "deep_route_without_trail");
        }
        if (!respawn.closerThan(death, DROP_RECOVERY_MAX_DISTANCE)) {
            return new DropRecoveryDecision(false, "route_too_far");
        }
        return new DropRecoveryDecision(true, "short_clear_route");
    }

    public boolean scanBot(MinecraftServer server, AIPlayerEntity bot) {
        // SAFE-DEAD: a dead bot no longer endlessly dispatches evade (zombie loop). Respawn at full health on the surface, clear tasks/plans, and notify in chat.
        // isAlive() alone is not a death signal: it also goes false when the entity is removed for
        // a non-death reason (e.g. chunk unload), same pitfall documented in HuntTask's
        // resolveUnavailableTarget. Only zero health or Minecraft's explicit KILLED reason count.
        if (bot.getHealth() <= 0.0F || bot.getRemovalReason() == Entity.RemovalReason.KILLED) {
            BlockPos deathPos = bot.blockPosition();
            long deathTick = server.getTickCount();
            int visibleHostilesAtDeath = bot.level()
                    .getEntitiesOfClass(LivingEntity.class, bot.getBoundingBox().inflate(8.0D),
                            entity -> isActiveHostileThreat(bot, entity))
                    .stream()
                    .filter(entity -> ObservableWorldQuery.canNoticeCreature(bot, entity))
                    .toList().size();
            AIPlayerManager.INSTANCE.respawnDeadBot(bot);
            // Death-recovery reflex: dropped gear sits at the death point (despawns in 5 minutes); a
            // real player's first instinct is to run back for it ("corpse run").
            // Only a short, shallow route with an already-clear death site auto-runs the corpse.
            // Strict survival has no teleport; digging straight back down to a deep mine naked from
            // the world spawn point has no provable entry route, and risks dying again before the
            // drops despawn. Deep-mine recovery must wait for a future persisted, reversible
            // entry/trail contract; for now, fail closed and immediately restart the original
            // Mission, rebuilding supplies from the surface.
            boolean dangerous = io.github.zoyluo.minecraftai.memory.KnowledgeBase.INSTANCE
                    .isDanger(bot.getUUID(), deathPos);
            DropRecoveryDecision recovery = dropRecoveryDecision(
                    bot.blockPosition(), deathPos, visibleHostilesAtDeath, dangerous);
            if (recovery.allowed()) {
                TaskManager.INSTANCE.assign(bot, new RecoverDropsTask(deathPos, deathTick), TaskOrigin.safety("recover_drops"));
                BrainCoordinator.INSTANCE.sendPanelChat(bot, "system",
                        bot.getGameProfile().name() + " respawned and is returning to "
                                + deathPos.toShortString() + " to recover dropped equipment.");
            } else {
                BotLog.danger(bot, "drop_recovery_skipped",
                        "death", deathPos.toShortString(),
                        "respawn", bot.blockPosition().toShortString(),
                        "hostiles", visibleHostilesAtDeath,
                        "reason", recovery.reason());
                BrainCoordinator.INSTANCE.sendPanelChat(bot, "system",
                        bot.getGameProfile().name() + " respawned safely at the surface. "
                                + "(Unsafe equipment-recovery route skipped: " + recovery.reason() + ")");
            }
            return true;
        }
        // combat-dangerwatcher-repeated-hostile-scans: observableActiveHostilePressure(bot) does an
        // entity-class world query plus a per-candidate observability/LOS raycast, and used to be
        // recomputed independently by collectTopThreat, refreshShelterEpisode (unconditionally,
        // every scan), decideCombatOrEvade's canFight/shouldDefensivelyFightClosePressure and
        // maybeEat's hasNakedEatHostilePressure -- 2 to 4 redundant re-scans of the same nearby
        // entities per bot per tick. Compute it once here and thread it through every one of those
        // call sites instead. This is safe because nothing between here and the last read below can
        // change its inputs: no code on any path that falls through (rather than returning) between
        // this point and scanBot's final read of hostilePressure spawns, kills or moves an entity,
        // or places/breaks a block that could change line of sight -- every branch that does mutate
        // the world/task state (lava escape, shelter/barricade assignment, creeper defense, combat
        // regroup, threat dispatch, ...) returns immediately afterward, so a later call within the
        // same scanBot invocation always sees the same world this list was computed from.
        List<LivingEntity> hostilePressure = observableActiveHostilePressure(bot);
        Optional<Threat> threat = collectTopThreat(bot, hostilePressure);
        Optional<Task> active = TaskManager.INSTANCE.getActive(bot);
        refreshShelterEpisode(bot, hostilePressure);
        // Self-rescue on lava contact (highest priority, overrides threat): lava burns 4 damage per
        // tick, killing the bot within seconds. SurvivalGuard only interrupts the current job, with a
        // comment claiming it "defers to DangerWatcher to escape" but that was never implemented --
        // the bot burned to death while sitting in lava (real_diamond dove and dug through a lava
        // pocket, failing at 14/15 steps).
        // Patched here: if the bot is stuck in lava and the current task isn't already the
        // lava-escape task -> immediately dispatch LavaEscapeTask to save its life first.
        if (bot.isInLava() && !(active.isPresent() && active.get() instanceof LavaEscapeTask)) {
            if (active.isPresent()) {
                TaskManager.INSTANCE.pauseFor(bot, "lava_escape");
            }
            TaskManager.INSTANCE.assign(bot, new LavaEscapeTask(), TaskOrigin.safety("lava_escape"));
            BotLog.danger(bot, "lava_escape_start", "pos", bot.blockPosition().toShortString(),
                    "hp", (int) bot.getHealth());
            return true;
        }
        // Fire and powder-snow self-rescue, right behind lava: burning costs about a heart a second (the
        // lava exit alone leaves fifteen seconds of it), and a bot sunk in powder snow cannot walk or path out.
        if (maybeSelfRescue(server, bot, active, threat)) {
            return true;
        }
        // CreateObsidianTask deliberately works near a pool and independently enforces dry,
        // standable, non-adjacent work poses. Do not replace that bounded operation with an
        // EvadeTask merely because lava is visible two cells away. Contact/adjacency/fire/low HP
        // fail the task's predicate and continue through the normal emergency path above/below.
        if (threat.isPresent()
                && threat.get().type() == Threat.Type.LAVA
                && active.isPresent()
                && active.get() instanceof CreateObsidianTask obsidianTask
                && obsidianTask.controlsNearbyLava(bot, threat.get().pos())) {
            noteThreatOwned(bot);
            threat = Optional.empty();
        }
        // OreDig owns a bounded branch cursor and rejects observed hazardous directions itself.
        // In a two-block-high mine, generic Evade has no valid open escape goal and used to pause
        // the miner every cooldown without changing the lava-facing branch. Rotate the factual
        // cursor now; actual lava contact still takes the LavaEscape path above.
        if (threat.isPresent()
                && threat.get().type() == Threat.Type.LAVA
                && active.isPresent()
                && active.get() instanceof OreDigTask oreDig
                && oreDig.avoidObservedLava(bot, threat.get().pos())) {
            noteThreatOwned(bot);
            return true;
        }
        // DigDown owns a factual staircase back to its exact entry. Visible lava closes the current
        // descent, but a generic underground Evade has no proven surface destination and can leave
        // the return owner paused forever while the same source remains visible. Let the active
        // owner turn around, or resume the exact paused owner once any prior safety task has ended.
        Task digDownCandidate = active.filter(DigDownTask.class::isInstance)
                .orElseGet(() -> active.isEmpty()
                        ? TaskManager.INSTANCE.peekPaused(bot)
                        .filter(DigDownTask.class::isInstance)
                        .orElse(null)
                        : null);
        boolean pausedDigDownCanResume = active.isEmpty()
                && !TaskManager.INSTANCE.isUserPaused(bot)
                && !bot.getActionPack().hasActiveActions()
                && bot.hurtTime == 0
                && bot.getHealth() > MinecraftAiConfig.get().combat().retreatHp();
        if (threat.isPresent()
                && threat.get().type() == Threat.Type.LAVA
                && digDownCandidate instanceof DigDownTask digDown
                && (active.orElse(null) == digDown || pausedDigDownCanResume)
                && digDown.claimObservedLavaReturn(bot, threat.get().pos())) {
            noteThreatOwned(bot);
            if (active.isEmpty()) {
                TaskManager.INSTANCE.resumeFromPause(bot);
            }
            return true;
        }
        // A shelter is an atomic safety envelope. Let it either prove all nine cells sealed or
        // fail with shelter_unsealable; Combat/Evade/Eat must not preempt it on the next scan and
        // grow a nested pause stack while the same holes are still being closed.
        if (active.isPresent() && (active.get() instanceof EmergencyShelterTask
                || active.get() instanceof MiningBarricadeTask)) {
            return true;
        }
        // CreeperDefense normally remains the sole owner through LOS flicker and secondary
        // pressure. The only hostile arbitration allowed to replace it is a factually backed,
        // non-Creeper *two-hit lethal* emergency.  Broad "low HP" was enough to make a bot wall
        // itself in during recoverable fights, so keep the dedicated creeper owner otherwise.
        if (active.isPresent() && active.get() instanceof CreeperDefenseTask) {
            boolean criticalNonCreeperLethal = threat.isPresent()
                    && shouldStartLastResortShelter(bot, threat.get());
            if (criticalNonCreeperLethal
                    && EmergencyShelterTask.hasMaterialsForEmergencyRetreat(bot)
                    && canAttemptShelter(server, bot)) {
                TaskManager.INSTANCE.assign(
                        bot,
                        new EmergencyShelterTask(threat.orElseThrow()),
                        TaskOrigin.safety("creeper_defense_critical_shelter"));
                noteShelterAttempt(server, bot);
                BotLog.danger(bot, "creeper_defense_critical_shelter",
                        "hp", (int) bot.getHealth(),
                        "source", threat.get().pos(),
                        "threat", threat.get().entity().getType().toString(),
                        "paused_depth", TaskManager.INSTANCE.pausedDepth(bot));
            }
            return true;
        }
        // A controlled strip mine already owns a real rear corridor. Seal the branch before the
        // dedicated Creeper owner (or any generic combat/evade logic) can displace its checkpoint.
        // A paused OreDig is also eligible because SurvivalGuard preserves mission instances.
        Task miningCandidate = active.filter(OreDigTask.class::isInstance)
                .orElseGet(() -> TaskManager.INSTANCE.peekPaused(bot)
                        .filter(OreDigTask.class::isInstance)
                        .orElse(null));
        if (threat.isPresent()
                && isHostileBacked(threat.get())
                && miningCandidate instanceof OreDigTask oreDig
                && MiningBarricadeTask.hasMaterialsForOpenGate(bot)) {
            Optional<MiningBarricadeTask> barricade =
                    oreDig.prepareHostileBarricade(bot, threat.get().pos());
            if (barricade.isPresent()) {
                if (active.orElse(null) == oreDig) {
                    TaskManager.INSTANCE.pauseFor(bot, "mining_hostile_barricade");
                }
                TaskManager.INSTANCE.assign(bot, barricade.get(),
                        TaskOrigin.safety("mining_hostile_barricade"));
                BotLog.danger(bot, "mining_hostile_barricade_started",
                        "source", threat.get().pos(),
                        "paused", oreDig.name());
                return true;
            }
        }
        // Creepers need one continuous owner across source changes, LOS flicker, route stalls and
        // physical-wall escalation. Assign it before the atomic Eat/Combat branches below.
        // Authority comes from TaskOrigin rather than the task class: SAFETY work is replaceable
        // in place, while every non-SAFETY task is paused exactly once and resumed only after the
        // owner proves distance or a maintained barrier beyond its observation grace.
        Optional<CreeperDefenseTask.ObservedCreeper> visibleCreeper =
                CreeperDefenseTask.selectObservableCreeper(bot);
        if (visibleCreeper.isPresent()) {
            Task current = active.orElse(null);
            boolean replaceInPlace = current != null
                    && TaskManager.INSTANCE.activeOrigin(bot)
                    .map(TaskOrigin::safety)
                    .orElse(false);
            if (current != null
                    && !replaceInPlace) {
                TaskManager.INSTANCE.pauseFor(bot, "creeper_defense");
            }
            CreeperDefenseTask.ObservedCreeper observed =
                    visibleCreeper.orElseThrow();
            TaskManager.INSTANCE.assign(
                    bot,
                    new CreeperDefenseTask(observed.uuid(), observed.pos()),
                    TaskOrigin.safety("creeper_defense"));
            noteThreatOwned(bot);
            BotLog.danger(bot, "creeper_defense_started",
                    "source", observed.pos(),
                    "replaced", current == null ? "none" : current.name(),
                    "replace_in_place", replaceInPlace,
                    "paused_depth", TaskManager.INSTANCE.pausedDepth(bot));
            return true;
        }
        // A healing EatTask is a short, atomic survival transaction. LOW_HP is expected to remain
        // true throughout the bite, and an already-observed hostile can also remain in range; using
        // either signal to pause Eat again grows a safety-on-safety stack and prevents the food from
        // ever being consumed. Contact lava and drowning are deliberately excluded here and retain
        // their higher-priority handling.
        if (active.isPresent()
                && active.get() instanceof EatTask
                && isHealingEatTransaction(bot)
                && threat.filter(DangerWatcher::isHostilePressure).isPresent()) {
            return true;
        }
        // Swarmed: three or more hostiles simultaneously aggro'd on this one bot pull it back
        // toward its owning player instead of letting it try to out-fight the whole crowd alone.
        // Checked ahead of ordinary Combat so a live fight is paused/replaced the moment the
        // threshold is crossed; CombatRegroupTask itself keeps striking anything adjacent while it
        // falls back, so this is a fighting retreat rather than a flee.
        if (maybeRegroup(bot, active)) {
            return true;
        }
        // Combat owns ordinary close contact.  The single exception is the narrowly proven
        // two-hit lethal boundary below: it replaces melee with a retreat-first shelter rather
        // than trying to place walls under the attacking mob's feet.
        if (active.isPresent()
                && active.get() instanceof CombatTask
                && (threat.isEmpty()
                || threat.get().type() == Threat.Type.HOSTILE
                || threat.get().type() == Threat.Type.LOW_HP)) {
            if (threat.isPresent()
                    && shouldStartLastResortShelter(bot, threat.get())
                    && EmergencyShelterTask.hasMaterialsForEmergencyRetreat(bot)
                    && canAttemptShelter(server, bot)) {
                TaskManager.INSTANCE.assign(bot, new EmergencyShelterTask(threat.get()),
                        TaskOrigin.safety("combat_two_hit_lethal_shelter"));
                noteShelterAttempt(server, bot);
                BotLog.danger(bot, "combat_two_hit_lethal_shelter",
                        "hp", (int) bot.getHealth(),
                        "source", threat.get().pos(),
                        "threat", threat.get().entity().getType().toString());
            }
            return true;
        }
        // Last-resort auto shelter: an observed reachable non-creeper could plausibly kill this
        // already-low-health bot in two hits.  No night, cave, ordinary low-health, or unseen-mob
        // shortcut is sufficient.  EmergencyShelterTask itself first retreats (and gets dry if
        // needed) before it begins placing its owned shell.
        if (threat.isPresent()
                && shouldStartLastResortShelter(bot, threat.get())
                && EmergencyShelterTask.hasMaterialsForEmergencyRetreat(bot)
                && canAttemptShelter(server, bot)) {
            if (active.isPresent()) {
                markThreatDirectionAvoided(active.get(), bot, threat.get());
                // Preserve mission/background work, but replace an active safety task in place.
                // Pausing Evade (also SAFETY) here used to add another frame on every shelter
                // retry, eventually burying the mission under an unbounded safety stack.
                if (shouldPreserveActiveWork(bot)) {
                    TaskManager.INSTANCE.pauseFor(bot, "emergency_entomb");
                }
            }
            TaskManager.INSTANCE.assign(bot, new EmergencyShelterTask(threat.get()),
                    TaskOrigin.safety("emergency_entomb"));
            noteShelterAttempt(server, bot);
            BotLog.danger(bot, "emergency_entomb", "hp", (int) bot.getHealth(),
                    "two_hit_damage", estimatedIncomingHit(threat.get().entity()),
                    "threat", threat.get().type());
            return true;
        }
        if (threat.isPresent()) {
            Threat top = threat.get();
            boolean followKeeps = followKeepsThreat(server, bot, active, top);
            if (!followKeeps
                    && top.severity().ordinal() >= Threat.Severity.MEDIUM.ordinal()
                    && shouldAssignThreatTask(active, top)
                    && canAssignThreatTask(server, bot, top)) {
                Task task = decideCombatOrEvade(bot, top, canAttemptShelter(server, bot), hostilePressure);
                // Align with the leash: a defensive fight against a target that is already outside its
                // leash (a drowned in water 10 blocks off / below the floor) would be assigned and then
                // immediately disengage (target_left_leash), over and over. Hold off instead.
                boolean heldOff = task instanceof CombatTask leashed
                        && holdOffOutOfLeashCombat(server, bot, leashed);
                if (!heldOff) {
                    boolean trapped = trappedBackoff(server, bot, task);
                    if (trapped) {
                        boolean criticalHostile = isHostileBacked(top)
                                && (top.type() == Threat.Type.LOW_HP
                                || top.severity() == Threat.Severity.HIGH);
                        if (!criticalHostile || hasActiveHostileDefenseOwner(bot)) {
                            return true;
                        }
                        // A hard backoff may throttle ordinary churn, but it cannot claim a live
                        // critical hostile while leaving only paused mission work. Fall through and
                        // assign the already-decided shelter/evade owner; the normal high-severity
                        // cooldown below replaces the longer diagnostic backoff.
                    }
                    if (active.isPresent()
                            && shouldPauseForThreat(active.get(), top, task)
                            && shouldPreserveActiveWork(bot)) {
                        if (task instanceof EmergencyShelterTask) {
                            markThreatDirectionAvoided(active.get(), bot, top);
                        }
                        TaskManager.INSTANCE.pauseFor(bot, "threat: " + top.type());
                    }
                    TaskManager.INSTANCE.assign(bot, task, TaskOrigin.safety("threat:" + top.type()));
                    if (task instanceof EmergencyShelterTask) {
                        noteShelterAttempt(server, bot);
                    }
                    nextThreatAttemptTick.put(bot.getUUID(), server.getTickCount() + threatCooldownTicks(top, task));
                    BotLog.danger(bot, "threat_detected",
                            "type", top.type(),
                            "severity", top.severity(),
                            "source", top.pos(),
                            "decision", task.name());
                    return true;
                }
            }
        }
        // Mitigation hardening (life-saving fallback): trapped in a dark underground spot -> retreat to the surface, taking priority over resupply/eating.
        if (maybeEscapeDarkTrap(server, bot, active)) {
            return true;
        }
        if (maybeResupply(server, bot, active)) {
            return true;
        }
        if (maybeEat(server, bot, active, hostilePressure)) {
            return true;
        }
        if (maybeStartNightTask(server, bot, active)) {
            return true;
        }
        if (maybeLightDarkArea(server, bot, active)) {
            return true;
        }
        if (active.isEmpty()
                && !TaskManager.INSTANCE.isUserPaused(bot)
                && !bot.getActionPack().hasActiveActions()
                && BrainCoordinator.INSTANCE.maybeWakeForFailureOrGoal(bot)) {
            return true;
        }
        if (active.isEmpty()
                && !bot.getActionPack().hasActiveActions()
                && TaskManager.INSTANCE.hasPaused(bot)
                && canResumePausedWork(bot, threat)) {
            TaskManager.INSTANCE.resumeFromPause(bot);
            return true;
        }
        return false;
    }

    static boolean canResumePausedWork(AIPlayerEntity bot, Optional<Threat> threat) {
        return threat.isEmpty()
                && bot.hurtTime == 0
                && bot.getHealth() > MinecraftAiConfig.get().combat().retreatHp();
    }

    /**
     * Fire and powder-snow reflexes. Returns true when one of them owns this scan (already running, or just
     * assigned). Both are SAFETY tasks; ordinary work is paused and resumed afterwards, a running safety
     * owner is replaced in place. Deliberately skipped while lava escape or a sealed shelter/barricade runs.
     */
    private boolean maybeSelfRescue(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active,
                                    Optional<Threat> threat) {
        // At critical health with a factual melee hostile in range the fight decides first: neither a running
        // rescue keeps the controls (the threat handling below pauses it, see shouldPreserveActiveWork, and it
        // resumes afterwards) nor does a new one start (it would just be paused again on the next scan).
        if (threat.isPresent() && shouldFightBeforeRescue(bot, threat.get())) {
            return false;
        }
        if (active.isPresent() && (active.get() instanceof FireExtinguishTask
                || active.get() instanceof PowderSnowEscapeTask)) {
            return true; // the running rescue owns the controls; both are bounded by their own timeouts
        }
        if (active.isPresent() && (active.get() instanceof LavaEscapeTask
                || active.get() instanceof EmergencyShelterTask
                || active.get() instanceof MiningBarricadeTask)) {
            return false;
        }
        UUID id = bot.getUUID();
        int now = server.getTickCount();
        if (FireExtinguishTask.isBurningWithoutImmunity(bot) && now >= nextFireAttemptTick.getOrDefault(id, 0)) {
            if (FireExtinguishTask.hasMeans(bot)) {
                startSelfRescue(bot, active, new FireExtinguishTask(), "fire_extinguish");
                nextFireAttemptTick.put(id, now + 40);
                BotLog.danger(bot, "fire_extinguish_start", "pos", bot.blockPosition().toShortString(),
                        "hp", (int) bot.getHealth(), "fire_ticks", bot.getRemainingFireTicks());
                return true;
            }
            nextFireAttemptTick.put(id, now + 20); // no way to help right now: look again shortly
        }
        if (PowderSnowEscapeTask.isSunkInPowderSnow(bot) && now >= nextPowderSnowAttemptTick.getOrDefault(id, 0)) {
            startSelfRescue(bot, active, new PowderSnowEscapeTask(), "powder_snow_escape");
            nextPowderSnowAttemptTick.put(id, now + 40);
            BotLog.danger(bot, "powder_snow_escape_start", "pos", bot.blockPosition().toShortString(),
                    "hp", (int) bot.getHealth());
            return true;
        }
        return false;
    }

    /**
     * Whether a critical-health bot must deal with an observed hostile before a fire / powder-snow rescue: the
     * bot is at or below the retreat health, the threat is a live, observed hostile, and it is inside the
     * pressure envelope (it can actually reach the bot). A distant or unseen mob never preempts a rescue.
     */
    static boolean shouldFightBeforeRescue(AIPlayerEntity bot, Threat threat) {
        return isHostileBacked(threat)
                && bot.getHealth() <= MinecraftAiConfig.get().combat().retreatHp()
                && ObservableWorldQuery.canNoticeCreature(bot, threat.entity())
                && CombatCore.isWithinHostilePressureEnvelope(bot, threat.entity());
    }

    /** Depth beyond which a rescue replaces a running safety task instead of stacking another paused frame. */
    private static final int MAX_PAUSED_FRAMES_FOR_RESCUE = 4;

    private static void startSelfRescue(AIPlayerEntity bot, Optional<Task> active, Task rescue, String why) {
        // Ordinary work is always paused. A running SAFETY task (a fight, an evade) is paused too rather than
        // replaced, so it resumes once the rescue is over; only a deep pause stack falls back to replacing it.
        if (active.isPresent() && (shouldPreserveActiveWork(bot)
                || TaskManager.INSTANCE.pausedDepth(bot) < MAX_PAUSED_FRAMES_FOR_RESCUE)) {
            TaskManager.INSTANCE.pauseFor(bot, why);
        }
        TaskManager.INSTANCE.assign(bot, rescue, TaskOrigin.safety(why));
    }

    /** The active task is an ordinary (non-safety) follow and the follow keeps its escort-only stance (config follow.escortOnly). */
    private static boolean isEscortFollow(AIPlayerEntity bot, Optional<Task> active) {
        return active.isPresent()
                && active.get() instanceof FollowTask
                && MinecraftAiConfig.get().behaviour().followOrDefaults().escortOnlyEnabled()
                && !TaskManager.INSTANCE.activeOrigin(bot).map(TaskOrigin::safety).orElse(false);
    }

    /**
     * R4: a bot that follows a player does not stop following for an ordinary hostile: the escort (FollowEscort) knocks back whatever
     * is in reach and the follow pace sprints while anyone is under attack. What still takes the bot off the follow is what is not
     * ordinary: low health, a creeper (CreeperDefense), a hunting or close warden, a hostile that could kill the bot in two hits
     * (last-resort shelter), lava, drowning and falling (other threat types).
     */
    private boolean followKeepsThreat(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active, Threat top) {
        if (!isEscortFollow(bot, active)
                || top.type() != Threat.Type.HOSTILE
                || top.entity() == null
                || top.entity() instanceof Creeper
                || bot.getHealth() <= MinecraftAiConfig.get().combat().retreatHp()
                || shouldStartLastResortShelter(bot, top)) {
            return false;
        }
        if (top.entity() instanceof Warden warden
                && (bot.distanceTo(warden) <= FOLLOW_WARDEN_EVADE_RANGE
                || WardenState.isHunting(warden, QuietZone.victimsOf(bot), bot.level().getGameTime()))) {
            return false;
        }
        int now = server.getTickCount();
        if (now >= nextFollowKeepLogTick.getOrDefault(bot.getUUID(), 0)) {
            nextFollowKeepLogTick.put(bot.getUUID(), now + FOLLOW_KEEP_LOG_TICKS);
            BotLog.danger(bot, "follow_escort_keeps_threat", "threat", top.entity().getType(),
                    "distance", Math.round(bot.distanceTo(top.entity()) * 10.0D) / 10.0D, "hp", (int) bot.getHealth());
        }
        return true;
    }

    private boolean maybeRegroup(AIPlayerEntity bot, Optional<Task> active) {
        // A follower does not fall back to a rally point: it keeps following (the escort knocks back whatever is in reach).
        if (isEscortFollow(bot, active)) {
            return false;
        }
        Task regroupCandidate = active.filter(CombatRegroupTask.class::isInstance)
                .orElseGet(() -> active.isEmpty()
                        ? TaskManager.INSTANCE.peekPaused(bot)
                        .filter(CombatRegroupTask.class::isInstance)
                        .orElse(null)
                        : null);
        boolean currentlyRegrouping = regroupCandidate != null;
        if (!CombatRegroupGuard.shouldRegroup(bot, currentlyRegrouping)) {
            return false;
        }
        if (active.isPresent() && active.get() instanceof CombatRegroupTask) {
            return true; // already the live owner; its own onTick drives movement/strikes
        }
        if (TaskManager.INSTANCE.isUserPaused(bot)) {
            return false;
        }
        if (currentlyRegrouping) {
            // A paused CombatRegroupTask sits beneath something that already returned above (lava,
            // creeper, shelter). Resume it directly instead of stacking a second regroup owner.
            TaskManager.INSTANCE.resumeFromPause(bot);
            return true;
        }
        if (active.isPresent()) {
            TaskManager.INSTANCE.pauseFor(bot, "combat_regroup");
        }
        TaskManager.INSTANCE.assign(bot, new CombatRegroupTask(), TaskOrigin.safety("combat_regroup"));
        BotLog.danger(bot, "combat_regroup_assigned",
                "aggro_count", CombatRegroupGuard.countAggro(bot),
                "paused", active.map(Task::name).orElse("none"));
        return true;
    }

    private boolean maybeResupply(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {
        boolean criticalStarvation = bot.getFoodData().getFoodLevel()
                <= MinecraftAiConfig.get().survival().hungerCriticalThreshold();
        // A just-finished shelter has a deterministic post-combat sequence: consume carried food
        // to 20 first, then permit low-priority artifact cleanup.  A routine tool resupply must
        // not jump that recovery boundary merely because a pick happened to be selected.
        if (EmergencyShelterTask.hasPendingCleanup(bot)
                && bot.getFoodData().getFoodLevel() < 20
                && InventoryAction.hasFood(bot)) {
            return false;
        }
        if (TaskManager.INSTANCE.isUserPaused(bot) && !criticalStarvation) {
            return false;
        }
        if (bot.getActionPack().hasActiveActions()) {
            return false; // Ordinary resupply does not preempt an action-only replacement; emergency-survival branches are handled earlier in this method.
        }
        if (active.isPresent() && active.get() instanceof ResupplyTask) {
            return true;
        }
        if (active.isPresent() && (active.get() instanceof EvadeTask || active.get() instanceof CombatTask || active.get() instanceof EatTask)) {
            return false;
        }
        int now = server.getTickCount();
        if (now < nextResupplyAttemptTick.getOrDefault(bot.getUUID(), 0)) {
            return false;
        }

        ResupplyTask task = null;
        ItemStack mainHand = bot.getMainHandItem();
        Optional<Task> paused = active.isEmpty()
                ? TaskManager.INSTANCE.peekPaused(bot) : Optional.empty();
        // These mining tasks own an exact break/pickup/return transaction. Even the raw-one
        // threshold of the generic resupply is reported by ToolTier as NONE for a raw-one pick,
        // so the owner settles it itself. Resolve ownership separately
        // from the held slot: combat may leave a sword selected when the miner resumes. The owner
        // must first settle an already-legal break, then either yield at its service boundary or
        // report its own typed durability failure before opening a new one. DigDown additionally
        // owns the exact-return path for that failure. DescendToY does not yet expose the same typed
        // durability contract and therefore remains under generic resupply.
        boolean activeTaskOwnsMiningTransaction = active
                .filter(DangerWatcher::ownsMiningPickTransaction).isPresent();
        boolean pausedTaskOwnsMiningTransaction = paused
                .filter(DangerWatcher::ownsMiningPickTransaction).isPresent();
        boolean pausedDigDownOwnsReturnDebt = paused
                .filter(DigDownTask.class::isInstance).isPresent();
        // Crafting never spends or depends on the held mining tool. In particular, the final rare
        // bootstrap deliberately crafts fresh replacements while an old nearly-broken pick may
        // still be selected. Generic tool resupply here would pause that atomic craft and consume
        // the sealed stick/stone inputs before the five-pick hand-off is complete.
        boolean taskDoesNotUseHeldTool = active.filter(CraftTask.class::isInstance).isPresent();
        // Only a TOOL at its last use with no successor starts a generic resupply (see lastUseOfAToolWithNoSuccessor): a weapon, bow,
        // crossbow, shield or armor piece is used until it breaks and never triggers one, and a one-use tool with another tool of its
        // kind in the pack is simply used up (the next worst takes over when it breaks).
        boolean lastToolUse = lastUseOfAToolWithNoSuccessor(bot, mainHand, active.isPresent() || paused.isPresent());
        if (lastToolUse
                && mainHand.is(ItemTags.PICKAXES)
                && pausedDigDownOwnsReturnDebt
                && !taskDoesNotUseHeldTool) {
            // DigDown alone needs a generic tool to pay an exact physical RETURN debt after a
            // safety displacement. Service it from carried materials only; travelling to a
            // remembered base would compound that displacement. MiningService/CreateObsidian/
            // OreDig retain their own exact-budget or typed, persisted service boundaries instead
            // of spending materials through this generic last-use threshold.
            task = ResupplyTask.toolInPlace(mainHand.getItem());
        } else if (lastToolUse
                && !activeTaskOwnsMiningTransaction
                && !pausedTaskOwnsMiningTransaction
                && !taskDoesNotUseHeldTool) {
            Item item = mainHand.getItem();
            task = ResupplyTask.tool(item);
        } else {
            MinecraftAiConfig.Survival survival = MinecraftAiConfig.get().survival();
            // When there is no food: if prey is nearby, yield to maybeEat's hunting (hunting meat in
            // the wild is more reliable than digging through chests for wheat, see the layer-2 hunger
            // chain); only fall back to ResupplyTask.food() (searching storage chests) when there is
            // no prey nearby. Fixes "repeatedly resupplying for wheat and failing instead of hunting
            // when hungry".
            if (bot.getFoodData().getFoodLevel() <= survival.hungerEatThreshold()
                    && !InventoryAction.hasFood(bot)
                    && !HuntTask.hasPreyNearby(bot)) {
                task = ResupplyTask.food();
            }
        }

        if (task == null) {
            return false;
        }
        if (active.isPresent()) {
            TaskManager.INSTANCE.pauseFor(bot, "resupply");
        }
        TaskManager.INSTANCE.assign(bot, task, criticalStarvation
                ? TaskOrigin.safety("critical_resupply")
                : TaskOrigin.of(TaskOrigin.Kind.SYSTEM_BACKGROUND, "resupply"));
        nextResupplyAttemptTick.put(bot.getUUID(), now + 200);
        BotLog.danger(bot, "resupply_started", "need", task.describe());
        return true;
    }

    private static boolean ownsMiningPickTransaction(Task task) {
        return task instanceof MiningServiceTask
                || task instanceof CreateObsidianTask
                || task instanceof OreDigTask
                || task instanceof DigDownTask;
    }

    private boolean maybeEat(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active,
                             List<LivingEntity> hostilePressure) {
        int foodLevel = bot.getFoodData().getFoodLevel();
        MinecraftAiConfig.Survival survival = MinecraftAiConfig.get().survival();
        boolean healingEmergency = isHealingEatTransaction(bot);
        // Cleanup is deliberately post-combat housekeeping, but it must begin with a full hunger
        // bar so natural regeneration has already started.  This is deterministic survival work,
        // not a Gemini decision or an excuse to leave an enclosure half-cleaned while weakened.
        boolean shelterCleanupRecovery = EmergencyShelterTask.hasPendingCleanup(bot)
                && foodLevel < 20;
        // Wounded but stalled: natural regeneration needs food >= 18, so a bot at 17 food and 11.8
        // hp used to sit wounded for ~85 minutes with bread in its pack (it is above the low-hunger
        // threshold and above the retreat-hp heal threshold). Eat to full, but only when it is
        // plainly safe (no observed hostile pressure, not hurt just now, no safety task running).
        boolean regenStall = isRegenStall(bot)
                && !hasNakedEatHostilePressure(bot, hostilePressure)
                && !isSafetyTaskActive(bot, active);
        // R6: a player cannot sprint at food 6 or lower, so the bot eats at 7 (the food it still has above the sprint limit) whatever
        // the configured eat threshold, and does not wait for a walk to finish: a follower on a long walk would otherwise run out
        // of sprint before it ever ate. Combat/evade, a hostile in view and a protected transaction still defer it (below).
        boolean sprintLimitHunger = foodLevel <= SPRINT_LIMIT_FOOD;
        if (foodLevel > survival.hungerEatThreshold()
                && !sprintLimitHunger
                && !healingEmergency
                && !shelterCleanupRecovery
                && !regenStall) {
            return false;
        }
        boolean critical = foodLevel <= survival.hungerCriticalThreshold();
        boolean urgent = critical || healingEmergency || shelterCleanupRecovery;
        if (TaskManager.INSTANCE.isUserPaused(bot) && !urgent) {
            return false;
        }
        if (bot.getActionPack().hasActiveActions() && !urgent && !sprintLimitHunger) {
            return false; // Non-urgent eating waits for the current action to finish; critical starvation can still preempt to save the bot's life.
        }
        if (active.isPresent() && active.get() instanceof EatTask) {
            return true;
        }
        // ShelterCleanupTask owns its own physical bite between blocks, preventing a pause stack
        // on every saturation tick while it is already holding the exact-state cleanup lease.
        if (active.isPresent() && active.get() instanceof ShelterCleanupTask) {
            return false;
        }
        // Admission and continuation are deliberately different boundaries. Once a physical bite
        // has started, the earlier atomic-Eat branch lets it settle without growing another safety
        // frame. Before assignment, however, eating in the open beside an already-observed hostile
        // (or immediately after a hit) is never safe. A sealed shelter owns its own internal EatTask
        // and therefore does not pass through this unprotected admission gate.
        if (hasNakedEatHostilePressure(bot, hostilePressure)) {
            return false;
        }
        int now = server.getTickCount();
        if (now < nextEatAttemptTick.getOrDefault(bot.getUUID(), 0)) {
            return false;
        }
        // Chewing is a sound a calm warden hears: wait until it is out of range, unless the bot is badly hurt.
        if (bot.getHealth() > CALM_WARDEN_EAT_HEALTH && QuietZone.calmWardenObservedWithin(bot, CALM_WARDEN_EAT_RANGE)) {
            nextEatAttemptTick.put(bot.getUUID(), now + 20);
            return false;
        }
        if (!InventoryAction.hasFood(bot)) {
            // Layer 2 hunger chain: no food at all -> if huntable animals are nearby, actively hunt them for raw meat instead of just waiting to starve.
            if (huntForFood(server, bot, active)) {
                return true;
            }
            nextEatAttemptTick.put(bot.getUUID(), now + 100);
            return false;
        }

        boolean activeBlocksInterrupt = active.isPresent()
                && (active.get() instanceof EvadeTask || active.get() instanceof CombatTask);
        boolean activeIsProtectedTransaction = active.isPresent()
                && isProtectedEatTransaction(active.get());
        EatInterruptDecision decision = decideEatInterrupt(
                active.isPresent(), urgent, activeBlocksInterrupt, activeIsProtectedTransaction);
        if (!decision.startEating()) {
            return false;
        }
        if (decision.pauseActive()) {
            TaskManager.INSTANCE.pauseFor(bot, healingEmergency
                    ? "low_health_heal: " + bot.getHealth()
                    : shelterCleanupRecovery ? "shelter_cleanup_hunger: " + foodLevel
                    : "hunger: " + foodLevel);
        }
        // A top-up that exists only because regen is stalled must never fall back to harmful food.
        boolean regenStallOnly = regenStall && foodLevel > survival.hungerEatThreshold()
                && !healingEmergency && !shelterCleanupRecovery;
        TaskManager.INSTANCE.assign(bot, regenStallOnly ? EatTask.safeFoodOnly() : new EatTask(), healingEmergency || critical
                ? TaskOrigin.safety(healingEmergency ? "low_health_heal" : "critical_hunger")
                : TaskOrigin.of(TaskOrigin.Kind.SYSTEM_BACKGROUND,
                        shelterCleanupRecovery ? "shelter_cleanup_hunger" : "eat"));
        nextEatAttemptTick.put(bot.getUUID(), now + (shelterCleanupRecovery ? 1 : 100));
        BotLog.danger(bot, "hunger_eat_started", "food", foodLevel, "critical", critical,
                "healing", healingEmergency, "cleanup_recovery", shelterCleanupRecovery,
                "hp", (int) bot.getHealth(), "interrupted_active", decision.pauseActive());
        return true;
    }

    /**
     * Whether hunger eating may take over the currently active task right now, given how urgent
     * the hunger is. Pure decision helper (package-private for unit testing).
     *
     * <p>Low (non-critical) hunger is allowed to pause ordinary interruptible work -- follow,
     * hold, guard, gather, idle-ish tasks -- via the existing {@code TaskManager.pauseFor}
     * machinery, exactly like the urgent path already does, so the paused task resumes once
     * eating finishes. It must never preempt active combat/evasion, and it must not interrupt a
     * protected atomic transaction (an in-flight mining-pick transaction, or a crafting/smelting/
     * container transaction) unless the situation is actually urgent (critical starvation, a
     * low-health heal, or shelter-cleanup recovery) -- matching the pre-existing urgent path,
     * which was always allowed to preempt those.
     */
    record EatInterruptDecision(boolean startEating, boolean pauseActive) {
    }

    static EatInterruptDecision decideEatInterrupt(boolean hasActiveTask, boolean urgent,
                                                    boolean activeBlocksInterrupt,
                                                    boolean activeIsProtectedTransaction) {
        if (!hasActiveTask) {
            return new EatInterruptDecision(true, false);
        }
        if (activeBlocksInterrupt) {
            return new EatInterruptDecision(false, false);
        }
        if (!urgent && activeIsProtectedTransaction) {
            return new EatInterruptDecision(false, false);
        }
        return new EatInterruptDecision(true, true);
    }

    /**
     * Atomic transactions that a non-urgent hunger pause must never interrupt mid-flight: an
     * in-flight mining-pick transaction (reusing the existing {@link #ownsMiningPickTransaction}
     * notion -- covers a break in progress), and crafting/smelting/container transactions.
     * Shelter/emergency tasks are already excluded earlier in {@link #scanBot}, and
     * Evade/Combat are handled separately via {@code activeBlocksInterrupt}.
     */
    private static boolean isProtectedEatTransaction(Task task) {
        return ownsMiningPickTransaction(task)
                || task instanceof CraftTask
                || task instanceof SmeltTask
                || task instanceof ContainerTask;
    }

    // Layer 2 hunger chain: actively hunt for food (raw meat) when there is no food. Only dispatched
    // when not already responding to a threat (evade/combat); never dispatched when there is no prey nearby.
    private boolean huntForFood(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {
        boolean critical = bot.getFoodData().getFoodLevel()
                <= MinecraftAiConfig.get().survival().hungerCriticalThreshold();
        if (TaskManager.INSTANCE.isUserPaused(bot) && !critical) {
            return false;
        }
        if (active.isPresent()) {
            if (active.get() instanceof HuntTask) {
                return true; // Already hunting for food, keep it
            }
            if (active.get() instanceof EvadeTask || active.get() instanceof CombatTask) {
                return false; // Currently responding to a threat, don't interrupt
            }
        }
        int now = server.getTickCount();
        if (now < nextHuntAttemptTick.getOrDefault(bot.getUUID(), 0)) {
            return false;
        }
        if (!HuntTask.hasPreyNearby(bot)) {
            nextHuntAttemptTick.put(bot.getUUID(), now + 200); // No prey nearby, check again later
            return false;
        }
        if (active.isPresent()) {
            TaskManager.INSTANCE.pauseFor(bot, "hunt_for_food");
        }
        TaskManager.INSTANCE.assign(bot, new HuntTask(HUNT_FOOD_TARGET), critical
                ? TaskOrigin.safety("critical_hunt_for_food")
                : TaskOrigin.of(TaskOrigin.Kind.SYSTEM_BACKGROUND, "hunt_for_food"));
        nextHuntAttemptTick.put(bot.getUUID(), now + 400);
        BotLog.danger(bot, "hunt_for_food_started", "food", bot.getFoodData().getFoodLevel());
        return true;
    }

    private Task decideCombatOrEvade(AIPlayerEntity bot,
                                     Threat threat,
                                     boolean shelterAllowed,
                                     List<LivingEntity> hostilePressure) {
        MinecraftAiConfig.Combat combat = MinecraftAiConfig.get().combat();
        // These mobs require a dedicated tactic, never the generic defensive melee loop.
        if (isMeleeForbiddenThreat(threat)) {
            return new EvadeTask(threat);
        }
        if (shelterAllowed
                && shouldStartLastResortShelter(bot, threat)
                && EmergencyShelterTask.hasMaterialsForEmergencyRetreat(bot)) {
            return new EmergencyShelterTask(threat);
        }
        // A completed/failed shelter owns this local hostile episode until pressure clears or the
        // bot genuinely relocates. When that fixed-anchor option is locked, a single close
        // non-Creeper must not fall through to naked healing merely because canFight's ordinary
        // cost/benefit gate rejects low HP. Defensive Combat starts in RETREAT, counterattacks only
        // if boxed in, and owns the later safe-heal boundary.
        if (!shelterAllowed && shouldDefensivelyFightClosePressure(bot, threat, hostilePressure)) {
            return CombatTask.defensive(threat.entity(), combat.retreatHp(), bot.blockPosition());
        }
        // combat stuck-trap: combat repeatedly aborted as stuck (target unreachable -- e.g. a zombie below in a mineshaft/behind a wall) -> stop standing there waiting to die, switch to fleeing.
        if (canFight(bot, threat, combat, hostilePressure) && !combatStuck(bot)) {
            // Safety combat defends the interrupted work site. It binds the observed entity and
            // cannot turn into an open-ended hunt by reacquiring another mob of the same type.
            return CombatTask.defensive(threat.entity(), combat.retreatHp(), bot.blockPosition());
        }
        return new EvadeTask(threat);
    }

    private static boolean shouldDefensivelyFightClosePressure(AIPlayerEntity bot,
                                                                Threat threat,
                                                                List<LivingEntity> hostilePressure) {
        return isHostileBacked(threat)
                && !isMeleeForbiddenThreat(threat)
                && EquipAction.bestWeaponSlot(bot).isPresent()
                && hostilePressure.size() == 1
                && bot.distanceTo(threat.entity()) < CLOSE_DEFENSIVE_HOSTILE_RADIUS;
    }

    private boolean canAttemptShelter(MinecraftServer server, AIPlayerEntity bot) {
        return server.getTickCount() >= nextShelterAttemptTick.getOrDefault(bot.getUUID(), 0)
                && !shelterEpisodeActive(bot)
                && EmergencyShelterTask.hasMaterialsForEmergencyRetreat(bot);
    }

    boolean shelterEpisodeActive(AIPlayerEntity bot) {
        return shelterEpisodes.containsKey(bot.getUUID());
    }

    private void noteShelterAttempt(MinecraftServer server, AIPlayerEntity bot) {
        nextShelterAttemptTick.put(bot.getUUID(), server.getTickCount() + SHELTER_RETRY_COOLDOWN);
    }

    /** Called by the fixed-anchor owner at its exact terminal boundary. */
    void noteShelterTerminal(AIPlayerEntity bot,
                             BlockPos anchor,
                             TaskState outcome,
                             String reason) {
        if (anchor == null || bot.level().getServer() == null) {
            return;
        }
        int now = bot.level().getServer().getTickCount();
        BlockPos fixedAnchor = anchor.immutable();
        shelterEpisodes.put(bot.getUUID(), new ShelterEpisode(
                fixedAnchor, now, outcome, reason == null ? "" : reason));
        // Assignment-time cooldowns can expire while a real shelter is still building/holding.
        // Start the retry clock at terminal instead; the episode latch below is stronger while the
        // same local hostile pressure remains continuous.
        nextShelterAttemptTick.put(bot.getUUID(), now + SHELTER_RETRY_COOLDOWN);
        // A shelter terminal is a new safety boundary, not another failed scheduler attempt. Let the
        // next scan immediately choose the episode-safe fallback (normally defensive Combat) rather
        // than waiting out the assignment-time threat cooldown with no active protection.
        nextThreatAttemptTick.remove(bot.getUUID());
        BotLog.danger(bot, "shelter_episode_terminal",
                "anchor", fixedAnchor.toShortString(),
                "outcome", outcome,
                "reason", reason == null ? "" : reason);
    }

    private void refreshShelterEpisode(AIPlayerEntity bot, List<LivingEntity> hostilePressure) {
        ShelterEpisode episode = shelterEpisodes.get(bot.getUUID());
        if (episode == null) {
            return;
        }
        boolean sameSite = episode.anchor().closerThan(
                bot.blockPosition(), SHELTER_EPISODE_RADIUS);
        boolean hostileContinues = !hostilePressure.isEmpty();
        if (sameSite && hostileContinues) {
            return;
        }
        shelterEpisodes.remove(bot.getUUID(), episode);
        // Keep the terminal-time cooldown even when LOS flickers or the bot crosses the local
        // episode radius. Removing both latches made a failed shelter immediately eligible again.
        BotLog.danger(bot, "shelter_episode_reset",
                "anchor", episode.anchor().toShortString(),
                "reason", sameSite ? "hostile_cleared" : "site_relocated");
    }

    private static void markThreatDirectionAvoided(Task interrupted,
                                                   AIPlayerEntity bot,
                                                   Threat threat) {
        if (interrupted instanceof DescendToYTask descend) {
            descend.avoidCurrentDescentDirection(bot, threat.pos());
        }
    }

    // Layer 1: trapped backoff + help request. Applies only to evasion-class tasks (evade/shelter);
    // combat (canFight -> CombatTask) is not blocked.
    // If the bot repeatedly triggers evasion on the same cell without moving (surrounded/stuck at
    // the bottom of a pit) -> accumulate a count; once the threshold is hit, back off (a long
    // cooldown, silently waiting for rescue) and throttle help requests to the player, instead of
    // spamming an empty shelter/evade dispatch every 2 seconds. If the bot is genuinely escaping
    // (position changes), the count resets naturally.
    private boolean trappedBackoff(MinecraftServer server, AIPlayerEntity bot, Task next) {
        if (!(next instanceof EvadeTask) && !(next instanceof EmergencyShelterTask)) {
            trapRecords.remove(bot.getUUID());
            return false;
        }
        int now = server.getTickCount();
        BlockPos here = bot.blockPosition().immutable();
        TrapRecord rec = trapRecords.get(bot.getUUID());
        if (rec == null || !rec.pos().closerThan(here, 2.5D)) {
            trapRecords.put(bot.getUUID(), new TrapRecord(here, 1, 0));
            return false;
        }
        int repeat = rec.repeatCount() + 1;
        // Last-stand counterattack: trapped (evasion keeps landing back in the same spot) and
        // currently taking hits -- backing off = standing still waiting to die (real_iron test case:
        // 13 spiders cornered the bot in a cave, the evade destination computed to a 1-tick no-op in
        // place, and once backoff stopped issuing threat tasks the bot was beaten to death).
        // canFight's weapon/count gate is a "is this fight worth it" calculation; in a last stand
        // there's no calculating -- fight even bare-handed, trading damage for a window to survive.
        if (repeat >= 2 && bot.hurtTime > 0) {
            trapRecords.remove(bot.getUUID());
            var hostile = bot.level().getEntitiesOfClass(
                    LivingEntity.class,
                    bot.getBoundingBox().inflate(4.0D), e -> e.isAlive())
                    .stream()
                    .filter(e -> isActiveHostileThreat(bot, e))
                    .filter(e -> !CombatCore.isMeleeForbiddenThreat(e))
                    .filter(e -> io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canNoticeCreature(bot, e))
                    .findFirst().orElse(null);
            if (hostile != null) {
                BotLog.danger(bot, "trapped_fight_back", "target", hostile.getType().toString());
                if (TaskManager.INSTANCE.getActive(bot).isPresent()
                        && shouldPreserveActiveWork(bot)) {
                    TaskManager.INSTANCE.pauseFor(bot, "trapped_fight_back");
                }
                TaskManager.INSTANCE.assign(bot, new CombatTask(hostile.getType(), 1, 0.0F),
                        TaskOrigin.safety("trapped_fight_back"));
                return true;
            }
        }
        if (repeat < TRAP_REPEAT_LIMIT) {
            trapRecords.put(bot.getUUID(), new TrapRecord(rec.pos(), repeat, rec.lastHelpTick()));
            return false;
        }
        nextThreatAttemptTick.put(bot.getUUID(), now + TRAP_BACKOFF_TICKS);
        if (now - rec.lastHelpTick() >= TRAP_HELP_INTERVAL) {
            BrainCoordinator.INSTANCE.sendPanelChat(bot, "system",
                    bot.getGameProfile().name() + " is trapped at (" + here.getX() + "," + here.getY() + "," + here.getZ()
                            + ") and could not escape after repeated safety attempts. Please move me to safe open ground.");
            BotLog.danger(bot, "trapped_backoff", "pos", here.getX() + "," + here.getY() + "," + here.getZ(), "repeat", repeat);
            trapRecords.put(bot.getUUID(), new TrapRecord(here, 0, now));
        } else {
            trapRecords.put(bot.getUUID(), new TrapRecord(here, repeat, rec.lastHelpTick()));
        }
        return true;
    }

    private boolean maybeStartNightTask(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {
        if (TaskManager.INSTANCE.isUserPaused(bot)) {
            return false;
        }
        MinecraftAiConfig.Night night = MinecraftAiConfig.get().night();
        if (!night.autoLight()
                || bot.level().isBrightOutside()
                || active.isPresent()
                || bot.getActionPack().hasActiveActions()) {
            return false;
        }
        // While a goal plan is in progress (active is briefly empty between steps), don't insert
        // night lighting: it's a foreign task and would make GoalExecutor abandon the whole goal
        // (same guard as maybeLightDarkArea -- real_iron_bulk test case: while mining at night, at
        // 91/100 progress the step gap got hijacked by night-lighting -> goal_abandoned -> stuck
        // churning on light_area, never completing). Deep-mine lighting is handled by GoalPlanner's
        // torch prerequisite step.
        if (io.github.zoyluo.minecraftai.goal.GoalExecutor.INSTANCE.hasActivePlan(bot)) {
            return false;
        }
        int now = server.getTickCount();
        if (now < nextNightAttemptTick.getOrDefault(bot.getUUID(), 0)) {
            return false;
        }
        // Bots never sleep: whether the night is skipped is decided by the human players alone (vanilla
        // sleep vote). At night an idle bot only tops up lighting with torches, to prevent mob spawns.
        if (InventoryAction.countItem(bot, net.minecraft.world.item.Items.TORCH) <= 0) {
            nextNightAttemptTick.put(bot.getUUID(), now + 600);
            return false;
        }
        if (skipAutoLightOnSurface(bot, now, "night_task")) {
            return false;
        }
        Task task = LightAreaTask.automatic(8, 8);
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.SYSTEM_BACKGROUND, "night_task"));
        nextNightAttemptTick.put(bot.getUUID(), now + 600);
        BotLog.danger(bot, "night_task_started", "task", task.name());
        return true;
    }

    /**
     * Automatic lighting never happens on the surface (it would burn torches lighting the open air, or
     * a spot under a tree canopy). Returns true, after backing the shared lighting throttle off a short
     * while and logging a throttled {@code auto_light_skipped}, when the bot stands where the column
     * above it is open to the sky (canopy and mushroom growth do not count as a roof, see
     * {@link SurfaceCheck}). Explicit light_area requests never come through here.
     */
    private boolean skipAutoLightOnSurface(AIPlayerEntity bot, int now, String reflex) {
        if (!SurfaceCheck.isOnSurface(bot.level(), bot.blockPosition())) {
            return false;
        }
        nextNightAttemptTick.put(bot.getUUID(), now + SURFACE_RECHECK_TICKS);
        if (now >= nextSurfaceSkipLogTick.getOrDefault(bot.getUUID(), 0)) {
            nextSurfaceSkipLogTick.put(bot.getUUID(), now + SURFACE_SKIP_LOG_TICKS);
            BotLog.danger(bot, "auto_light_skipped", "reason", "surface", "reflex", reflex);
        }
        return true;
    }

    // Mitigation hardening: underground/dark spots (block light < 8) get lit as soon as the bot is
    // idle and has a torch -- cutting mob spawns off at the source. Not limited to nighttime
    // (underground, daytime with light=0 still spawns mobs). Only dispatched when active is empty
    // (idle/goal step gap) to avoid interrupting mining.
    private boolean maybeLightDarkArea(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {
        if (TaskManager.INSTANCE.isUserPaused(bot)) {
            return false;
        }
        if (!MinecraftAiConfig.get().night().autoLight() || active.isPresent() || bot.getActionPack().hasActiveActions()) {
            return false;
        }
        // While a goal plan is in progress (active is briefly empty between steps), don't insert
        // lighting: it's a foreign task and would make GoalExecutor abandon the whole goal (test
        // case: after mining raw_gold, the gap before smelting got hijacked by lighting ->
        // goal_abandoned, no smelting -> no gold ingot). Deep-mine lighting is handled by
        // GoalPlanner's mining-prerequisite torch step, not by this idle reflex.
        if (io.github.zoyluo.minecraftai.goal.GoalExecutor.INSTANCE.hasActivePlan(bot)) {
            return false;
        }
        var world = bot.level();
        BlockPos feet = bot.blockPosition();
        int threshold = MinecraftAiConfig.get().night().torchLightThreshold();
        if (world.canSeeSky(feet)
                || world.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, feet) >= threshold) {
            return false;
        }
        // Block light alone also fires in broad daylight under a leaf canopy: canSeeSky is
        // false there (leaves are opaque to the sky-visibility test) even though enough sunlight
        // filters through to keep the spot above the mob-spawn light level. The combined light --
        // block light OR sky light reduced by the current ambient darkness, the same value vanilla
        // uses for spawn eligibility -- stays high there during the day (ambient darkness ~0) and
        // only drops at night, so require it to actually be spawn-dark too.
        int combinedLight = world.getMaxLocalRawBrightness(feet, world.getSkyDarken());
        if (combinedLight >= threshold) {
            return false;
        }
        if (InventoryAction.countItem(bot, net.minecraft.world.item.Items.TORCH) <= 0) {
            return false; // Can't light anything without a torch -- GoalPlanner's deep-mining prerequisite covers stocking torches
        }
        int now = server.getTickCount();
        if (now < nextNightAttemptTick.getOrDefault(bot.getUUID(), 0)) {
            return false; // Reuses the night-time throttle to avoid dispatching on every scan
        }
        if (skipAutoLightOnSurface(bot, now, "dark_area_light")) {
            return false;
        }
        TaskManager.INSTANCE.assign(bot, LightAreaTask.automatic(8, 8),
                TaskOrigin.of(TaskOrigin.Kind.SYSTEM_BACKGROUND, "dark_area_light"));
        nextNightAttemptTick.put(bot.getUUID(), now + 600);
        BotLog.danger(bot, "dark_area_lit",
                "light", world.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, feet),
                "combined_light", combinedLight);
        return true;
    }

    // Mitigation hardening (life-saving fallback): a bot stuck "underground + in the dark" = a
    // trapped-in-the-dark hazard (can be one-shot by a spawned mob at any time). Only watches for
    // movement-type (move) getting stuck or idle stillness -- mining/smelting etc. have their own
    // watchdogs or are legitimately stationary, so let those fail on their own first. Once trapped is
    // detected, teleport back to the surface + clear the current goal + request help (throttled).
    // Sacrifice the current goal to save the bot's life; the brain can retry after returning to the
    // surface (by then torches should be better stocked, making it safer).
    private boolean maybeEscapeDarkTrap(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {
        // isWaiting = the task self-reports "standing still in place is a normal state": MoveTask can
        // stand still for several seconds while tunnel-digging straight through hard stone, and
        // dark + same-cell was misjudged as trapped, getting "rescued" to the surface and aborting
        // the task (observed two consecutive aborts in testing after the nav-suite canvas change).
        if (active.isPresent() && (!"move".equals(active.get().name()) || active.get().isWaiting())) {
            darkStuckRecords.remove(bot.getUUID());
            return false;
        }
        var world = bot.level();
        BlockPos feet = bot.blockPosition();
        if (!isDarkTrapCell(world, feet)) {
            darkStuckRecords.remove(bot.getUUID());
            return false;
        }
        int now = server.getTickCount();
        PosRecord rec = darkStuckRecords.get(bot.getUUID());
        if (rec == null || !rec.pos().equals(feet)) {
            darkStuckRecords.put(bot.getUUID(), new PosRecord(feet, now));
            return false;
        }
        if (now - rec.sinceTick() < DARK_STUCK_TICKS) {
            return false; // Not stuck long enough yet
        }
        darkStuckRecords.remove(bot.getUUID());
        darkTrapDetections.merge(bot.getUUID(), 1, Integer::sum);
        BotLog.danger(bot, "dark_trap_detected", "at", feet.toShortString(),
                "block_light", world.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, feet),
                "combined_light", world.getMaxLocalRawBrightness(feet, world.getSkyDarken()));
        if (!escapeToSurface(bot)) {
            return false; // No open-sky standable spot above (rare); hand off to other logic
        }
        TaskManager.INSTANCE.abort(bot);
        // Issue 4: no longer clear the goal -- after retreating to the surface, keep the
        // diamond-mining goal; GoalExecutor will replan/retry the current step and continue
        // (abort the currently-stuck task -> handleStepFailure replans; the bot is now on the
        // surface, the environment changed, no longer trapped). Test finding: the old logic forgot
        // the goal after retreating.
        BotLog.danger(bot, "dark_trap_escape",
                "from", feet.getX() + "," + feet.getY() + "," + feet.getZ());
        if (now >= nextEscapeHelpTick.getOrDefault(bot.getUUID(), 0)) {
            BrainCoordinator.INSTANCE.sendPanelChat(bot, "system",
                    bot.getGameProfile().name() + " was trapped in a dark cave too long and returned to the surface to avoid hostile spawns. The unfinished task will resume later.");
            nextEscapeHelpTick.put(bot.getUUID(), now + TRAP_HELP_INTERVAL);
        }
        return true;
    }

    private volatile int surfaceScans;

    /** Test hook: how many upward column scans {@link #escapeToSurface} has started (none while the emergency teleport is denied). */
    int surfaceScanCount() {
        return surfaceScans;
    }

    /** How many times this bot was judged trapped in the dark since it was last cleared. */
    int darkTrapDetections(AIPlayerEntity bot) {
        return darkTrapDetections.getOrDefault(bot.getUUID(), 0);
    }

    /** Light level below which a spot counts as dark enough to spawn hostile mobs (same 8 as before). */
    static final int DARK_TRAP_LIGHT = 8;

    /**
     * A genuinely dark, enclosed spot: not on the surface (a roof, not merely tree canopy or
     * mushroom growth, see {@link SurfaceCheck}) and dark by the combined light vanilla uses for
     * spawn eligibility (block light, or sky light reduced by the current ambient darkness).
     * {@code canSeeSky} alone treated every leaf-covered or roofed cell as a cave, even at noon.
     */
    static boolean isDarkTrapCell(net.minecraft.world.level.Level world, BlockPos feet) {
        // Cheap light reads first; the column scan of SurfaceCheck only runs for a genuinely dark spot.
        if (world.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, feet) >= DARK_TRAP_LIGHT
                || world.getMaxLocalRawBrightness(feet, world.getSkyDarken()) >= DARK_TRAP_LIGHT) {
            return false;
        }
        return !SurfaceCheck.isOnSurface(world, feet);
    }

    // teleport upward to the nearest open-sky standable spot directly above (life-saving fallback, resets fallDistance).
    boolean escapeToSurface(AIPlayerEntity bot) {
        // Ask first: the column scan below reads cells the bot cannot see, and a profile without the emergency teleport (strict
        // survival) must not do that work for an answer it may not use.
        if (!io.github.zoyluo.minecraftai.mode.CapabilityRuntime.decide(
                bot, io.github.zoyluo.minecraftai.mode.PrivilegedCapability.EMERGENCY_TELEPORT,
                "danger_dark_trap_surface").allowed()) {
            return false;
        }
        surfaceScans++;
        var world = bot.level();
        BlockPos feet = bot.blockPosition();
        int top = world.getMinY() + world.getHeight();
        for (int dy = 1; feet.getY() + dy < top - 1 && dy <= 120; dy++) {
            BlockPos cand = feet.above(dy);
            if (io.github.zoyluo.minecraftai.pathfinding.Standability.isStandable(world, cand)
                    && world.canSeeSky(cand)) {
                bot.getActionPack().stopAll();
                bot.teleportTo(world, cand.getX() + 0.5D, cand.getY(), cand.getZ() + 0.5D,
                        java.util.Collections.emptySet(), bot.getYRot(), bot.getXRot(), true);
                return true;
            }
        }
        return false;
    }

    // combat stuck-trap detection: combat aborted by StuckWatcher as stuck (stuck:combat) 2+ times in a row means the target is unreachable -> switch to fleeing, don't stand there and get killed.
    private boolean combatStuck(AIPlayerEntity bot) {
        Optional<TaskManager.FailureRecord> fail = TaskManager.INSTANCE.peekFailure(bot);
        return fail.isPresent()
                && "combat".equals(fail.get().name())
                && fail.get().reason().contains("stuck")
                && fail.get().count() >= 2;
    }

    private boolean canFight(AIPlayerEntity bot, Threat threat, MinecraftAiConfig.Combat combat,
                             List<LivingEntity> hostilePressure) {
        if (threat.type() != Threat.Type.HOSTILE || threat.entity() == null || !threat.entity().isAlive()) {
            return false;
        }
        if (bot.getHealth() <= combat.retreatHp()) {
            return false;
        }
        if (CombatCore.isMeleeForbiddenThreat(threat.entity())) {
            return false;
        }
        int hostiles = hostilePressure.size();
        return hostiles <= combat.maxEnemiesToFight() && EquipAction.bestWeaponSlot(bot).isPresent();
    }

    /**
     * Starting a bite is unsafe when pressure already exists, even if the ordinary threat
     * scheduler is still inside its retry cooldown. A close observed hostile does not require LOS:
     * one that just rounded a tunnel corner can reopen contact before an unprotected EatTask can
     * finish. The wider ranged envelope does require factual LOS. Continuation is handled
     * separately by the atomic-Eat branch near the top of
     * {@link #scanBot(MinecraftServer, AIPlayerEntity)}.
     */
    private static boolean hasNakedEatHostilePressure(AIPlayerEntity bot, List<LivingEntity> hostilePressure) {
        return bot.hurtTime > 0
                || !hostilePressure.isEmpty();
    }

    /** Shared cleanup gate: only ordinary, already-observable hostile pressure can preempt it. */
    static boolean hasObservableHostilePressure(AIPlayerEntity bot) {
        return !observableActiveHostilePressure(bot).isEmpty();
    }

    private static List<LivingEntity> observableActiveHostilePressure(AIPlayerEntity bot) {
        return bot.level()
                .getEntitiesOfClass(
                        LivingEntity.class,
                        bot.getBoundingBox().inflate(CombatCore.hostilePressureScanRange()),
                        entity -> isActiveHostileThreat(bot, entity)
                                && SharedVision.seenByBotOrOwner(bot, entity)
                                && CombatCore.isWithinHostilePressureEnvelope(bot, entity));
    }

    /**
     * Some mobs are implemented as {@link Monster} without being unconditionally hostile.
     * An unprovoked Enderman can stand in a cave indefinitely and must not pause a survival
     * mission or trigger a shelter that changes the local fluid boundary. Once it is angry or has
     * selected this bot as its target, it is treated exactly like every other hostile mob.
     */
    static boolean isActiveHostileThreat(AIPlayerEntity bot, LivingEntity entity) {
        // One shared policy (CombatCore.hostileTo): Enemy-interface hostility, neutral mobs (the
        // Enderman rule generalised) only once angry at this exact bot or after they hurt the bot
        // or its owner, and never the owner or another bot. isAngry() is only a broad tracked flag
        // (an Enderman targeting another mob also sets it); isAngryAt() binds persistent anger to
        // this exact bot and stays factual if teleportation temporarily clears the live target.
        return CombatCore.hostileTo(bot, entity);
    }

    private static boolean shouldAssignThreatTask(Optional<Task> active, Threat threat) {
        if (active.isEmpty()) {
            return true;
        }
        Task task = active.get();
        if (task instanceof EvadeTask) {
            return false;
        }
        return !(task instanceof CombatTask);
    }

    private static boolean shouldPauseForThreat(Task active, Threat threat, Task nextTask) {
        // Already fighting/fleeing -> don't pause a second time (let it redirect itself).
        if (active instanceof CombatTask || active instanceof EvadeTask) {
            return false;
        }
        // FREEZE fix: any other in-progress task (mining/gathering/crafting...) is always **paused
        // and preserved** when any threat appears, then resumed after the fight/flee is done, instead
        // of being destroyed outright by a subsequent assign's abort. The old logic returned false
        // (= don't pause = destroy the current task) for both "hostile -> combat" and LOW_HP, causing
        // GoalExecutor to classify it as foreign and abandon the whole goal (test finding: while mobs
        // were spawning, the mining goal was repeatedly abandoned and the bot idled doing nothing).
        return true;
    }

    /**
     * Safety work is a replaceable owner, never another resumable mission frame. Unknown legacy
     * origins remain preservable so a missing origin cannot silently destroy user work.
     */
    private static boolean shouldPreserveActiveWork(AIPlayerEntity bot) {
        // A fire / powder-snow rescue is safety work but not disposable: preempted by a critical fight it is
        // paused so it resumes (the condition it fixes is still there), never replaced and forgotten.
        if (TaskManager.INSTANCE.getActive(bot)
                .filter(task -> task instanceof FireExtinguishTask || task instanceof PowderSnowEscapeTask)
                .isPresent()) {
            return true;
        }
        return TaskManager.INSTANCE.activeOrigin(bot)
                .map(origin -> !origin.safety())
                .orElse(true);
    }

    /**
     * A hard trapped backoff may stand down only behind an owner that is physically defending
     * against hostile pressure. SAFETY is an authority class, not proof of that property:
     * critical hunt/resupply and future safety transactions must still be replaced by real
     * hostile defense.
     */
    static boolean hasActiveHostileDefenseOwner(AIPlayerEntity bot) {
        return TaskManager.INSTANCE.getActive(bot)
                .map(task -> task instanceof EvadeTask
                        || task instanceof CreeperDefenseTask
                        || task instanceof CombatTask
                        || task instanceof EmergencyShelterTask
                        || task instanceof MiningBarricadeTask)
                .orElse(false);
    }

    private boolean canAssignThreatTask(MinecraftServer server, AIPlayerEntity bot, Threat threat) {
        return server.getTickCount() >= nextThreatAttemptTick.getOrDefault(bot.getUUID(), 0);
    }

    private static int threatCooldownTicks(Threat threat, Task task) {
        if (threat.type() == Threat.Type.LOW_HP || threat.severity() == Threat.Severity.HIGH) {
            return 100;
        }
        return task instanceof EvadeTask ? 80 : 40;
    }

    /**
     * True when {@code held} is a TOOL (pickaxe, shovel, hoe, shears, or an axe while a task is working) that is down to its last use
     * AND the bot carries no other usable tool of that kind to take over when it breaks: the one case where a resupply has to start
     * before the break. Wear alone never starts one (that would swap or interrupt an item because it is worn, and the owner gets a chat
     * warning instead, see DurabilityWarnings); weapons, bows, crossbows, shields, armor, tridents and maces are never tools here, they
     * are used until they break and the next best is equipped by EquipAction. A successor is any other non-raw-one stack of the same
     * tool kind (main inventory or offhand); which tier a mission needs is the planner's business, not this reflex's.
     */
    static boolean lastUseOfAToolWithNoSuccessor(AIPlayerEntity bot, ItemStack held, boolean working) {
        if (held.isEmpty() || !io.github.zoyluo.minecraftai.util.ItemStackUtil.isNearlyBroken(held)) {
            return false;
        }
        java.util.function.Predicate<ItemStack> sameKind;
        if (held.is(ItemTags.PICKAXES)) {
            sameKind = stack -> stack.is(ItemTags.PICKAXES);
        } else if (held.is(ItemTags.SHOVELS)) {
            sameKind = stack -> stack.is(ItemTags.SHOVELS);
        } else if (held.is(ItemTags.HOES)) {
            sameKind = stack -> stack.is(ItemTags.HOES);
        } else if (held.is(net.minecraft.world.item.Items.SHEARS)) {
            sameKind = stack -> stack.is(net.minecraft.world.item.Items.SHEARS);
        } else if (working && held.is(ItemTags.AXES)) {
            sameKind = stack -> stack.is(ItemTags.AXES);
        } else {
            return false;
        }
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (stack != held && !stack.isEmpty() && sameKind.test(stack)
                    && !io.github.zoyluo.minecraftai.util.ItemStackUtil.isNearlyBroken(stack)) {
                return false;
            }
        }
        ItemStack offhand = bot.getOffhandItem();
        return offhand == held || offhand.isEmpty() || !sameKind.test(offhand)
                || io.github.zoyluo.minecraftai.util.ItemStackUtil.isNearlyBroken(offhand);
    }

    /** Natural regeneration only runs at food >= 18 (vanilla FoodData). */
    static final int REGEN_FOOD_LEVEL = 18;

    /**
     * Wounded (below max health) with a food bar too low for natural regeneration and food to eat.
     * Pure of any safety context; the caller adds the hostile-pressure and safety-task gates.
     * Only SAFE food counts (never the last-resort harmful food such as rotten flesh or
     * pufferfish), and the check is side-effect free (no offhand promotion, no logging).
     */
    static boolean isRegenStall(AIPlayerEntity bot) {
        return bot.getHealth() < bot.getMaxHealth()
                && bot.getFoodData().getFoodLevel() < REGEN_FOOD_LEVEL
                && InventoryAction.hasSafeFood(bot);
    }

    private static boolean isSafetyTaskActive(AIPlayerEntity bot, Optional<Task> active) {
        return active.isPresent()
                && TaskManager.INSTANCE.activeOrigin(bot).map(TaskOrigin::safety).orElse(false);
    }

    private static boolean isHealingEatTransaction(AIPlayerEntity bot) {
        return bot.getHealth() <= MinecraftAiConfig.get().combat().retreatHp()
                && bot.getFoodData().getFoodLevel() < 20
                && InventoryAction.hasFood(bot);
    }

    private static boolean isHostilePressure(Threat threat) {
        return threat.type() == Threat.Type.LOW_HP || threat.type() == Threat.Type.HOSTILE;
    }

    private static boolean isHostileBacked(Threat threat) {
        return isHostilePressure(threat)
                && threat.entity() != null
                && threat.entity().isAlive();
    }

    private static boolean isCreeperThreat(Threat threat) {
        return threat.entity() instanceof Creeper;
    }

    /**
     * Auto-shelter admission is intentionally stricter than "low health": it needs a factual,
     * reachable nearby hostile and a conservative two-hit damage estimate, while the bot is below
     * half health.  This keeps ordinary nighttime recovery, a distant wall-blocked mob, and a
     * merely cautious combat retreat from creating a dirt enclosure.
     */
    private static boolean shouldStartLastResortShelter(AIPlayerEntity bot, Threat threat) {
        // Building a shelter next to a warden is a stream of vibrations that wakes it: a warden is escaped (Evade), never walled off.
        if (!isHostileBacked(threat)
                || isCreeperThreat(threat)
                || threat.entity() instanceof Warden
                || !ObservableWorldQuery.canNoticeCreature(bot, threat.entity())
                || !CombatCore.hasLineOfSight(bot, threat.entity())
                || !CombatCore.isWithinHostilePressureEnvelope(bot, threat.entity())) {
            return false;
        }
        return isTwoHitLethalHealth(
                bot.getHealth(), bot.getMaxHealth(), estimatedIncomingHit(threat.entity()));
    }

    static boolean isTwoHitLethalHealth(float health, float maxHealth, double estimatedHitDamage) {
        if (health <= 0.0F || maxHealth <= 0.0F || estimatedHitDamage <= 0.0D) {
            return false;
        }
        return health <= LAST_RESORT_HEALTH_CAP
                && health <= maxHealth * 0.5F
                && health <= estimatedHitDamage * 2.0D;
    }

    /**
     * This is deliberately conservative rather than a promise of vanilla's exact damage roll.
     * Ranged arrows retain a floor because their actual damage is not represented by the mob's
     * melee attribute; armour/position can only make the final hit less lethal, never make this
     * admission discover an unseen threat.
     */
    static double estimatedIncomingHit(LivingEntity hostile) {
        if (hostile == null) {
            return 0.0D;
        }
        double melee = Math.max(1.0D,
                hostile.getAttributeValue(Attributes.ATTACK_DAMAGE));
        return hostile instanceof RangedAttackMob ? Math.max(4.0D, melee) : melee;
    }

    private static boolean isMeleeForbiddenThreat(Threat threat) {
        return threat.entity() != null
                && CombatCore.isMeleeForbiddenThreat(threat.entity());
    }

    /**
     * True when defensive combat against {@code combat}'s bound target should not be assigned right
     * now: the target is already outside the fight's leash (see
     * {@link CombatTask#isWithinDefensiveLeash}) or a recent fight against it ended with
     * {@code target_left_leash} (per-target cooldown), and it exerts no immediate pressure.
     * Assigning anyway made combat start and disengage in the same breath, repeatedly (a drowned
     * in water out of reach). Immediate pressure (ranged, or close and visible) and a bot that
     * was just hurt keep the existing behaviour.
     */
    private boolean holdOffOutOfLeashCombat(MinecraftServer server, AIPlayerEntity bot, CombatTask combat) {
        LivingEntity target = combat.defensiveTarget();
        if (target == null || bot.hurtTime > 0) {
            return false;
        }
        int now = server.getTickCount();
        Map<UUID, Integer> cooldowns = leashCooldowns.get(bot.getUUID());
        Integer until = cooldowns == null ? null : cooldowns.get(target.getUUID());
        boolean cooling = until != null && now < until;
        if (until != null && !cooling) {
            cooldowns.remove(target.getUUID());
        }
        boolean outside = combat.defensiveTargetOutsideLeash();
        if (!cooling && !outside) {
            return false;
        }
        if (CombatTask.isImmediatePressureOn(bot, target)) {
            return false;
        }
        if (CombatTask.canShootFromWhereItStands(bot, target)) {
            // Outside the melee leash but inside bow range with a clear line: shoot from here
            // instead of holding off (or assigning melee that would immediately disengage).
            return false;
        }
        if (!cooling) {
            // First sighting outside the leash: log once and start the per-target cooldown so the
            // same target is not re-evaluated (and re-logged) on every scan.
            leashCooldowns.computeIfAbsent(bot.getUUID(), id -> new ConcurrentHashMap<>())
                    .put(target.getUUID(), now + TARGET_LEASH_COOLDOWN_TICKS);
            BotLog.danger(bot, "combat_held_off", "reason", "target_outside_leash",
                    "target", target.getType().toString(),
                    "distance", (int) bot.distanceTo(target));
        }
        return true;
    }

    /** Called by defensive Combat when it ended because its target left the leash. */
    void noteTargetLeftLeash(AIPlayerEntity bot, LivingEntity target) {
        if (target == null || bot.level().getServer() == null) {
            return;
        }
        leashCooldowns.computeIfAbsent(bot.getUUID(), id -> new ConcurrentHashMap<>())
                .put(target.getUUID(), bot.level().getServer().getTickCount() + TARGET_LEASH_COOLDOWN_TICKS);
    }

    /** A completed escape is a new safety boundary; its assignment-time debounce must not linger. */
    void noteEvadeCompleted(AIPlayerEntity bot) {
        nextThreatAttemptTick.remove(bot.getUUID());
    }

    /** Task-owned safety progress starts a new boundary; stale generic backoff must not leak on. */
    private void noteThreatOwned(AIPlayerEntity bot) {
        UUID id = bot.getUUID();
        nextThreatAttemptTick.remove(id);
        trapRecords.remove(id);
    }

    private static Optional<Threat> collectTopThreat(AIPlayerEntity bot, List<LivingEntity> hostiles) {
        // Close threats use the original ten-block envelope. A ranged attacker remains pressure
        // through twenty blocks only with factual LOS, matching naked-Eat admission and secondary
        // combat settlement. Sort the shared pressure set before choosing the top threat. The caller
        // owns this list for the rest of its scanBot() call (combat-dangerwatcher-repeated-hostile-scans
        // -- see the comment at scanBot's hostilePressure computation); sorting it in place is safe
        // because every later use only reads .isEmpty()/.size(), never relies on element order.
        // "melee mode, prioritize closest enemy; ranged mode, prioritize ranged enemies first":
        // once something is already close enough to be a melee exchange, ranged-ness stops
        // mattering and plain distance decides. Otherwise (nothing close yet -- the bot would be
        // kiting/shooting rather than swinging) a mob that can hit back from range outranks one
        // that cannot, before distance breaks the remaining ties.
        boolean meleeModeActive = hostiles.stream()
                .anyMatch(mob -> bot.distanceTo(mob) <= CombatCore.MELEE_ENGAGEMENT_RANGE);
        // Explosive pressure cannot be hidden behind a closer ordinary mob. A strict obsidian run
        // resumed its water mission while a Creeper was still visible at fifteen blocks; sorting
        // Creepers first keeps every shelter/combat branch below aligned with the no-melee policy.
        // A warden, like a creeper, is a lethal never-melee source: it sorts first as well.
        hostiles.sort(Comparator
                .comparing((LivingEntity mob) -> !(mob instanceof Creeper
                        || mob instanceof net.minecraft.world.entity.monster.warden.Warden))
                .thenComparing(mob -> meleeModeActive || CombatCore.isRangedThreat(mob) ? 0 : 1)
                .thenComparingDouble(bot::distanceTo));
        for (LivingEntity mob : hostiles) {
            if (!canReachThreat(bot, mob)) {
                continue; // Blocked by a solid block, can't reach the bot -> doesn't count as a threat
            }
            // Low HP is a combat modifier, not a threat by itself. The old unconditional branch
            // emitted an entity-less LOW_HP at the bot's own position even in broad daylight with
            // no hostile nearby. Evade then chose an arbitrary +X destination and could route a
            // recovering worker from safe surface terrain into a cave. Preserve retreat priority
            // only when this observed, reachable hostile actually exists, and keep its direction.
            if (bot.getHealth() < 6.0F) {
                return Optional.of(new Threat(
                        Threat.Type.LOW_HP, Threat.Severity.HIGH, mob, mob.blockPosition()));
            }
            // A warden (sonic boom ignores armour, 30 per hit) is as lethal as a fuse: same severity.
            Threat.Severity severity = mob instanceof Creeper || mob instanceof net.minecraft.world.entity.monster.warden.Warden
                    ? Threat.Severity.HIGH : Threat.Severity.MEDIUM;
            return Optional.of(new Threat(Threat.Type.HOSTILE, severity, mob, mob.blockPosition()));
        }
        if (bot.isUnderWater() && bot.getAirSupply() < 50) {
            return Optional.of(new Threat(Threat.Type.DROWNING, Threat.Severity.MEDIUM, null, bot.blockPosition()));
        }
        Optional<BlockPos> lava = BlockPos.betweenClosedStream(bot.blockPosition().offset(-2, -1, -2), bot.blockPosition().offset(2, 1, 2))
                .filter(pos -> ObservableWorldQuery.canObserveBlock(bot, pos))
                .filter(pos -> {
                    BlockState state = bot.level().getBlockState(pos);
                    return state.getFluidState().is(FluidTags.LAVA);
                })
                .map(BlockPos::immutable)
                .findFirst();
        if (lava.isPresent()) {
            return Optional.of(new Threat(Threat.Type.LAVA, Threat.Severity.HIGH, null, lava.get()));
        }
        if (bot.fallDistance > 5.0F && !bot.onGround()) {
            return Optional.of(new Threat(Threat.Type.FALLING, Threat.Severity.LOW, null, bot.blockPosition()));
        }
        return Optional.empty();
    }

    // Whether a mob can actually threaten the bot: cast a block raycast from the bot's eyes to the
    // mob's eyes; if a solid block blocks the middle (result is not MISS), treat it as unreachable
    // (through a wall/tunnel). The raycast only checks blocks, not entities, which is exactly right
    // for judging "is there a wall in the way". A melee mob without line of sight can't hit it, a
    // ranged mob without line of sight can't shoot it, and a Creeper without line of sight can't blow
    // it up either -- none of these count as a current threat (they'll be re-detected once they come
    // around or into view).
    private static boolean canReachThreat(AIPlayerEntity bot, LivingEntity mob) {
        // A foreign bot the owner is looking at counts as reachable: the owner nominates it, the strike gate still needs the bot's own line.
        return CombatCore.hasLineOfSightOrOwnerSees(bot, mob);
    }

    /**
     * P1 (mining-assist design 4.4 item 7): the first lava cell the bot can observe in the 5x3x5 threat box around
     * it. The same probe as the LAVA branch of collectTopThreat, duplicated so that method stays untouched.
     *
     * <p>{@code freshBreakOre}, when not null, is the detour's current mining target ({@code cur}/{@code ore}); a
     * lava cell that is one of its six face neighbours is excluded from this scan while that exact block currently
     * reads air. A block only reads air here because it was just broken (by this detour's own swing, one tick
     * before the engine has processed the resulting {@code DONE} status, or by another bot taking the same member),
     * and a fluid a break exposes is design 4.7's job: re-observe, seal with material, or abort
     * {@code fluid_unsealable} cleanly. This generic ambient-hazard box is evaluated every tick, before the engine's
     * phase dispatch reaches the code that runs 4.7 (mining-assist design 4.6/4.7; {@code task/OreDigDetourEngine}),
     * so without this exclusion it would win that race and kill the whole detour with {@code LAVA_THREAT_BOX}
     * before 4.7 ever ran, even with a sacrificial block on hand to seal safely. The exclusion is narrow by
     * identity, not by area: a lava cell is only ever skipped when it is a face neighbour of THIS tick's own
     * target and that target is air; any other lava cell -- one genuinely observable from the stand pose before
     * the break, or anywhere else in the box -- still counts, so a real ambient threat still aborts (I7 unweakened).
     * The filter runs inside the stream (not as a post-hoc check on the one result {@code findFirst} already
     * picked) so a second, genuine hazard cell is still found even when the excluded one would otherwise have
     * been first in iteration order.
     */
    static Optional<BlockPos> observedLavaInThreatBox(AIPlayerEntity bot, BlockPos freshBreakOre) {
        boolean oreJustBroken = freshBreakOre != null
                && bot.level().getBlockState(freshBreakOre).isAir();
        return BlockPos.betweenClosedStream(bot.blockPosition().offset(-2, -1, -2), bot.blockPosition().offset(2, 1, 2))
                .filter(pos -> ObservableWorldQuery.canObserveBlock(bot, pos))
                .filter(pos -> bot.level().getBlockState(pos).getFluidState().is(FluidTags.LAVA))
                .filter(pos -> !(oreJustBroken && isFaceNeighbour(freshBreakOre, pos)))
                .map(BlockPos::immutable)
                .findFirst();
    }

    /** Manhattan distance 1: the six face-adjacent cells of {@code a}, never a diagonal or {@code a} itself. */
    private static boolean isFaceNeighbour(BlockPos a, BlockPos b) {
        long dx = Math.abs((long) a.getX() - b.getX());
        long dy = Math.abs((long) a.getY() - b.getY());
        long dz = Math.abs((long) a.getZ() - b.getZ());
        return dx + dy + dz == 1L;
    }

    /** P1 (design 4.4 item 5): whether the threat scheduler is still inside its assignment-time cooldown. */
    boolean threatCooldownActive(AIPlayerEntity bot, int nowTick) {
        return nowTick < nextThreatAttemptTick.getOrDefault(bot.getUUID(), 0);
    }
}
