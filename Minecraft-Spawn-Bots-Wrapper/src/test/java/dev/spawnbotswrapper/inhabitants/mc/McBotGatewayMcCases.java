package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.adapter.PvpBotOperations;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.engine.BotGateway;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;
import dev.spawnbotswrapper.inhabitants.store.BotSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import static org.junit.jupiter.api.Assertions.*;

/** {@link McBotGateway} logic against a scripted adapter, applier and server, run inside {@link McSandbox}. */
public final class McBotGatewayMcCases {
    private McBotGatewayMcCases() {
    }

    private static final class Rig {
        final GatewayFakes.Adapter adapter = new GatewayFakes.Adapter();
        final GatewayFakes.Applier applier = new GatewayFakes.Applier();
        final GatewayFakes.Access access = new GatewayFakes.Access();
        final InhabitantsConfig config = new InhabitantsConfig();
        final McBotGateway gateway = new McBotGateway(access, adapter, applier, () -> config);
        final ServerLevel overworld = McObjects.opaque(ServerLevel.class);
        final ServerPlayer bot = McObjects.opaque(ServerPlayer.class);

        Rig() {
            access.worlds.put("minecraft:overworld", overworld);
        }

        void online(String name) {
            access.online.add(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    private static BotProfile standing() {
        return new BotProfile(1, 1L, "idle", null, null, BotProfile.Behavior.standing());
    }

    private static BotProfile patrolling() {
        BotProfile.Behavior walk = new BotProfile.Behavior(BotProfile.Stance.PATROL_CYCLE, true, BotProfile.WalkType.WALK, 8.0, 3,
                List.of(new BotProfile.Waypoint(1, 64, 1), new BotProfile.Waypoint(5, 64, 1), new BotProfile.Waypoint(5, 64, 5)));
        return new BotProfile(1, 2L, "guard", null, null, walk);
    }

    private static BotGateway.SpawnRequest request() {
        return new BotGateway.SpawnRequest("minecraft:overworld", "Inh_Bot", 10.5, 64.0, -3.5, 90.0f);
    }

    // ------------------------------------------------------------------------------ availability

    public static void availabilityAndReasonFollowTheAdapterStatus() {
        Rig rig = new Rig();
        assertTrue(rig.gateway.available());
        assertEquals("", rig.gateway.unavailableReason());
        rig.adapter.status = new PvpBotOperations.Status(PvpBotOperations.Availability.DEGRADED, "0.0.15", "n/a", "t", "COMMAND",
                "running on the command fallback", List.of(), List.of());
        assertTrue(rig.gateway.available(), "degraded is still usable");
        rig.adapter.status = new PvpBotOperations.Status(PvpBotOperations.Availability.UNAVAILABLE, "not installed", "not installed", "t",
                "NONE", "PvP BOT is not installed", List.of(), List.of());
        assertFalse(rig.gateway.available());
        assertEquals("PvP BOT is not installed", rig.gateway.unavailableReason());
    }

    public static void capabilitiesPassThroughAndFallBackToUpstreamDefaults() {
        Rig rig = new Rig();
        GlobalCapabilities custom = GlobalCapabilities.allEnabled();
        rig.adapter.capabilities = custom;
        assertSame(custom, rig.gateway.capabilities());
        rig.adapter.capabilitiesNull = true;
        assertEquals(GlobalCapabilities.upstreamDefaults(), rig.gateway.capabilities());
        rig.adapter.capabilitiesNull = false;
        rig.adapter.capabilitiesFailure = new IllegalStateException("reflection broke");
        assertEquals(GlobalCapabilities.upstreamDefaults(), rig.gateway.capabilities());
    }

    public static void nameAvailabilityIsDelegatedAndAFailureCountsAsTaken() {
        Rig rig = new Rig();
        assertTrue(rig.gateway.nameAvailable("Inh_Bot"));
        rig.adapter.nameFree = false;
        assertFalse(rig.gateway.nameAvailable("Inh_Bot"));
        rig.adapter.nameFree = true;
        rig.adapter.nameFailure = new IllegalStateException("boom");
        assertFalse(rig.gateway.nameAvailable("Inh_Bot"), "when in doubt, do not hand out the name");
    }

    // ------------------------------------------------------------------------------ spawning

    public static void aSpawnRequestReachesTheAdapterWithTheWorldAndExactPosition() {
        Rig rig = new Rig();
        BotGateway.SpawnHandle handle = rig.gateway.requestSpawn(request());
        assertEquals(new BotGateway.SpawnHandle(7, "Inh_Bot"), handle);
        assertSame(rig.overworld, rig.adapter.lastWorld);
        assertEquals("Inh_Bot", rig.adapter.lastName);
        assertArrayEquals(new double[]{10.5, 64.0, -3.5}, rig.adapter.lastPosition);
        assertEquals(90.0f, rig.adapter.lastYaw);
    }

    public static void pollingMapsEveryAdapterStateAndDropsFinishedTickets() {
        Rig rig = new Rig();
        BotGateway.SpawnHandle handle = rig.gateway.requestSpawn(request());

        assertInstanceOf(BotGateway.SpawnPoll.Pending.class, rig.gateway.poll(handle));
        assertEquals(new PvpBotOperations.SpawnTicket(7, "Inh_Bot", 100), rig.adapter.polled.get(0), "the ticket from the request is reused");

        UUID id = UUID.randomUUID();
        rig.adapter.state = new PvpBotOperations.SpawnState.Ready(id);
        assertEquals(new BotGateway.SpawnPoll.Ready(id), rig.gateway.poll(handle));

        rig.adapter.state = new PvpBotOperations.SpawnState.Failed("PvP BOT said no");
        assertEquals(new BotGateway.SpawnPoll.Failed("PvP BOT said no"), rig.gateway.poll(handle));
    }

    public static void aReadyBotWithoutAUuidIsStillReady() {
        Rig rig = new Rig();
        BotGateway.SpawnHandle handle = rig.gateway.requestSpawn(request());
        rig.adapter.state = new PvpBotOperations.SpawnState.Ready(null);
        assertEquals(new BotGateway.SpawnPoll.Ready(null), rig.gateway.poll(handle));
    }

    public static void anUnknownDimensionFailsThroughTheNormalPollPath() {
        Rig rig = new Rig();
        BotGateway.SpawnHandle handle = rig.gateway.requestSpawn(
                new BotGateway.SpawnRequest("somemod:pocket", "Inh_Bot", 0, 64, 0, 0));
        assertEquals("Inh_Bot", handle.name());
        assertTrue(handle.id() < 0, "local failures use ids that cannot clash with the adapter's");
        assertNull(rig.adapter.lastWorld, "the adapter was never asked");
        BotGateway.SpawnPoll poll = rig.gateway.poll(handle);
        BotGateway.SpawnPoll.Failed failed = assertInstanceOf(BotGateway.SpawnPoll.Failed.class, poll);
        assertTrue(failed.reason().contains("somemod:pocket"), failed.reason());
    }

    public static void everyLocalFailureHasItsOwnHandle() {
        Rig rig = new Rig();
        BotGateway.SpawnRequest bad = new BotGateway.SpawnRequest("nowhere:void", "A_Bot", 0, 0, 0, 0);
        BotGateway.SpawnHandle first = rig.gateway.requestSpawn(bad);
        BotGateway.SpawnHandle second = rig.gateway.requestSpawn(bad);
        assertNotEquals(first.id(), second.id());
        assertInstanceOf(BotGateway.SpawnPoll.Failed.class, rig.gateway.poll(second));
        assertInstanceOf(BotGateway.SpawnPoll.Failed.class, rig.gateway.poll(first));
    }

    public static void anAdapterThatThrowsOrReturnsNothingBecomesAFailedSpawnNotACrash() {
        Rig rig = new Rig();
        rig.adapter.nullTicket = true;
        assertInstanceOf(BotGateway.SpawnPoll.Failed.class, rig.gateway.poll(rig.gateway.requestSpawn(request())));

        rig.adapter.nullTicket = false;
        rig.adapter.spawnFailure = new IllegalStateException("upstream exploded");
        BotGateway.SpawnPoll poll = rig.gateway.poll(rig.gateway.requestSpawn(request()));
        BotGateway.SpawnPoll.Failed failed = assertInstanceOf(BotGateway.SpawnPoll.Failed.class, poll);
        assertTrue(failed.reason().contains("upstream exploded"), failed.reason());
    }

    public static void aPollThatThrowsOrReturnsNothingIsAFailure() {
        Rig rig = new Rig();
        BotGateway.SpawnHandle handle = rig.gateway.requestSpawn(request());
        rig.adapter.pollFailure = new IllegalStateException("poll exploded");
        assertInstanceOf(BotGateway.SpawnPoll.Failed.class, rig.gateway.poll(handle));

        Rig other = new Rig();
        BotGateway.SpawnHandle h2 = other.gateway.requestSpawn(request());
        other.adapter.nullState = true;
        assertInstanceOf(BotGateway.SpawnPoll.Failed.class, other.gateway.poll(h2));
    }

    public static void aHandleFromBeforeARestartGetsARebuiltTicket() {
        Rig rig = new Rig();
        rig.access.ticks = 4321;
        rig.gateway.poll(new BotGateway.SpawnHandle(99, "Old_Bot"));
        assertEquals(new PvpBotOperations.SpawnTicket(99, "Old_Bot", 4321), rig.adapter.polled.get(0));
    }

    public static void staleTicketsAreForgottenWhenNewSpawnsAreRequested() {
        Rig rig = new Rig();
        rig.gateway.requestSpawn(request());
        rig.access.ticks = 7000;
        rig.adapter.ticket = new PvpBotOperations.SpawnTicket(8, "Inh_Two", 7000);
        rig.gateway.requestSpawn(new BotGateway.SpawnRequest("minecraft:overworld", "Inh_Two", 0, 64, 0, 0));

        rig.gateway.poll(new BotGateway.SpawnHandle(7, "Inh_Bot"));
        assertEquals(7000, rig.adapter.polled.get(0).requestedAtTick(), "the old ticket was dropped and rebuilt with the current tick");
        rig.gateway.poll(new BotGateway.SpawnHandle(8, "Inh_Two"));
        assertEquals(7000, rig.adapter.polled.get(1).requestedAtTick());
    }

    // ------------------------------------------------------------------------------ presence

    public static void onlineAndManagedAreDistinct() {
        Rig rig = new Rig();
        rig.adapter.managed = true;
        assertFalse(rig.gateway.isOnline("Inh_Bot"));
        assertFalse(rig.gateway.isManaged("Inh_Bot"), "listed upstream but not online is not managed here");
        rig.online("Inh_Bot");
        assertTrue(rig.gateway.isOnline("INH_BOT"), "names compare case-insensitively");
        assertTrue(rig.gateway.isManaged("Inh_Bot"));
        rig.adapter.managed = false;
        assertTrue(rig.gateway.isOnline("Inh_Bot"));
        assertFalse(rig.gateway.isManaged("Inh_Bot"), "online but not listed by PvP BOT");
    }

    public static void managedStatusOfAnAdapterThatThrowsIsNotManaged() {
        Rig rig = new Rig();
        rig.online("Inh_Bot");
        rig.adapter.managed = true;
        assertTrue(rig.gateway.isManaged("Inh_Bot"));
        GatewayFakes.Adapter throwing = new GatewayFakes.Adapter() {
            @Override
            public boolean isManaged(String name) {
                throw new IllegalStateException("upstream state unreadable");
            }
        };
        McBotGateway gateway = new McBotGateway(rig.access, throwing, rig.applier, () -> rig.config);
        assertFalse(gateway.isManaged("Inh_Bot"));
    }

    public static void removeAndForgetDelegateAndSwallowFailures() {
        Rig rig = new Rig();
        assertTrue(rig.gateway.remove("Inh_Bot"));
        rig.adapter.removeResult = false;
        assertFalse(rig.gateway.remove("Inh_Bot"));
        rig.adapter.removeFailure = new IllegalStateException("boom");
        assertFalse(rig.gateway.remove("Inh_Bot"));

        rig.gateway.forget("Inh_Bot");
        assertEquals(List.of("Inh_Bot"), rig.adapter.cleared);
        rig.adapter.clearFailure = new IllegalStateException("boom");
        assertDoesNotThrow(() -> rig.gateway.forget("Inh_Other"));
        assertEquals(List.of("Inh_Bot", "Inh_Other"), rig.adapter.cleared);
    }

    // ------------------------------------------------------------------------------ applying a profile

    public static void aBotThatIsNotOnlineCannotBeDressed() {
        Rig rig = new Rig();
        BotGateway.ApplyResult result = rig.gateway.applyProfile("Inh_Bot", patrolling());
        assertFalse(result.loadoutApplied());
        assertFalse(result.vitalsApplied());
        assertFalse(result.behaviorApplied());
        assertFalse(result.warnings().isEmpty());
        assertTrue(rig.applier.clearFlags.isEmpty(), "nothing was applied");
        assertTrue(rig.adapter.assigned.isEmpty());
    }

    public static void aFreshBotIsWipedDressedMarkedAndGivenItsPath() {
        Rig rig = new Rig();
        rig.adapter.entity = Optional.of(rig.bot);
        BotProfile profile = patrolling();
        BotGateway.ApplyResult result = rig.gateway.applyProfile("Inh_Bot", profile);

        assertTrue(result.allApplied(), result.warnings().toString());
        assertEquals(List.of(true), rig.applier.clearFlags, "an unmarked bot is a fresh addon bot: wipe before filling");
        assertSame(profile, rig.applier.profiles.get(0));
        assertTrue(rig.applier.isMarked(rig.bot));
        assertEquals(List.of(profile.behavior()), rig.adapter.assigned);
        assertEquals(List.of("Inh_Bot"), rig.adapter.assignedTo);
    }

    public static void aBotThatIsAlreadyMarkedIsNotWipedAgain() {
        Rig rig = new Rig();
        rig.adapter.entity = Optional.of(rig.bot);
        rig.applier.mark(rig.bot);
        rig.gateway.applyProfile("Inh_Bot", standing());
        assertEquals(List.of(false), rig.applier.clearFlags, "a second application overwrites in place, it never wipes");
    }

    public static void aStandingBotHasNoPathToAssign() {
        Rig rig = new Rig();
        rig.adapter.entity = Optional.of(rig.bot);
        BotGateway.ApplyResult result = rig.gateway.applyProfile("Inh_Bot", standing());
        assertTrue(result.allApplied());
        assertTrue(rig.adapter.assigned.isEmpty(), "STAND needs no path");
    }

    public static void aFailedApplicationIsNotMarkedSoItCanBeRetried() {
        Rig rig = new Rig();
        rig.adapter.entity = Optional.of(rig.bot);
        rig.applier.vitalsApplied = false;
        rig.applier.warnings = List.of("vitals could not be applied: boom");
        BotGateway.ApplyResult result = rig.gateway.applyProfile("Inh_Bot", standing());
        assertTrue(result.loadoutApplied());
        assertFalse(result.vitalsApplied());
        assertFalse(result.allApplied());
        assertFalse(rig.applier.isMarked(rig.bot));
        assertEquals(List.of("vitals could not be applied: boom"), result.warnings());
    }

    public static void aPathThatCannotBeAssignedIsReportedNotThrown() {
        Rig rig = new Rig();
        rig.adapter.entity = Optional.of(rig.bot);
        rig.adapter.assignResult = false;
        BotGateway.ApplyResult refused = rig.gateway.applyProfile("Inh_Bot", patrolling());
        assertTrue(refused.loadoutApplied() && refused.vitalsApplied());
        assertFalse(refused.behaviorApplied());
        assertTrue(refused.warnings().stream().anyMatch(w -> w.contains("PATROL_CYCLE")), refused.warnings().toString());

        rig.adapter.assignFailure = new IllegalStateException("path store broke");
        BotGateway.ApplyResult thrown = rig.gateway.applyProfile("Inh_Bot", patrolling());
        assertFalse(thrown.behaviorApplied());
        assertTrue(thrown.warnings().stream().anyMatch(w -> w.contains("path store broke")), thrown.warnings().toString());
    }

    public static void anAdapterThatCannotFindTheEntityMeansNotOnline() {
        Rig rig = new Rig();
        rig.adapter.findFailure = new IllegalStateException("boom");
        assertFalse(rig.gateway.applyProfile("Inh_Bot", standing()).loadoutApplied());
    }

    // ------------------------------------------------------------------------------ ender pearl sanitize

    public static void stripEnderPearlsGoesThroughTheApplierForTheBotEntityAndReturnsItsCount() {
        Rig rig = new Rig();
        rig.adapter.entity = Optional.of(rig.bot);
        rig.applier.strippedCount = 7;
        assertEquals(7, rig.gateway.stripEnderPearls("Inh_Bot"));
        assertEquals(1, rig.applier.stripped.size());
        assertSame(rig.bot, rig.applier.stripped.get(0));
    }

    public static void stripEnderPearlsDoesNothingForAnOfflineNameAndContainsAdapterFailures() {
        Rig rig = new Rig();
        rig.applier.strippedCount = 7;
        assertEquals(0, rig.gateway.stripEnderPearls("Nobody"), "no bot entity: nothing to strip");
        assertTrue(rig.applier.stripped.isEmpty());
        rig.adapter.entity = Optional.of(rig.bot);
        rig.adapter.findFailure = new IllegalStateException("boom");
        assertEquals(0, rig.gateway.stripEnderPearls("Inh_Bot"));
        assertTrue(rig.applier.stripped.isEmpty());
    }

    // ------------------------------------------------------------------------------ disabled-enchantment sanitize

    public static void stripDisabledEnchantmentsGoesThroughTheApplierAndReportsWhatWasRemoved() {
        Rig rig = new Rig();
        rig.adapter.entity = Optional.of(rig.bot);
        rig.applier.strippedEnchantments = List.of("minecraft:piercing (crossbow)");
        assertEquals(List.of("minecraft:piercing (crossbow)"), rig.gateway.stripDisabledEnchantments("Inh_Bot"));
        assertEquals(1, rig.applier.enchantmentStripped.size());
        assertSame(rig.bot, rig.applier.enchantmentStripped.get(0));

        rig.adapter.entity = Optional.empty();
        assertEquals(List.of(), rig.gateway.stripDisabledEnchantments("Nobody"), "no bot entity: nothing to strip");
        rig.adapter.entity = Optional.of(rig.bot);
        rig.adapter.findFailure = new IllegalStateException("boom");
        assertEquals(List.of(), rig.gateway.stripDisabledEnchantments("Inh_Bot"));
        assertEquals(1, rig.applier.enchantmentStripped.size());
    }

    /**
     * A dressing (fresh spawn or restore) removes pearls and disabled enchantments before the roster's first sweep of
     * that bot, so the gateway holds on to what the dressing removed and the next sweep of that bot reports it once:
     * otherwise the first removal would never be logged.
     */
    public static void whatADressingRemovedIsReportedByTheNextSweepExactlyOnce() {
        Rig rig = new Rig();
        rig.adapter.entity = Optional.of(rig.bot);
        rig.applier.pearlsRemoved = 4;
        rig.applier.enchantmentsRemoved = List.of("minecraft:piercing (crossbow)");
        rig.applier.strippedCount = 1;
        rig.applier.strippedEnchantments = List.of();

        assertTrue(rig.gateway.applyProfile("Inh_Bot", standing()).loadoutApplied());
        assertEquals(5, rig.gateway.stripEnderPearls("INH_BOT"), "the dressing's 4 plus the sweep's own 1 (names are case-insensitive)");
        assertEquals(1, rig.gateway.stripEnderPearls("Inh_Bot"), "reported once, then only what the sweep finds");
        assertEquals(List.of("minecraft:piercing (crossbow)"), rig.gateway.stripDisabledEnchantments("Inh_Bot"));
        assertEquals(List.of(), rig.gateway.stripDisabledEnchantments("Inh_Bot"));
    }

    public static void aRestoreRedressingIsReportedTooAndForgettingDropsWhatWasPending() {
        Rig rig = new Rig();
        rig.adapter.entity = Optional.of(rig.bot);
        rig.adapter.managed = true;
        rig.online("Inh_Bot");
        rig.applier.enchantmentsRemoved = List.of("minecraft:piercing (crossbow)");
        assertTrue(rig.gateway.restore("Inh_Bot", standing()));
        assertEquals(List.of("minecraft:piercing (crossbow)"), rig.gateway.stripDisabledEnchantments("Inh_Bot"));

        assertTrue(rig.gateway.applyProfile("Inh_Bot", standing()).loadoutApplied());
        rig.gateway.forget("Inh_Bot");
        assertEquals(List.of(), rig.gateway.stripDisabledEnchantments("Inh_Bot"));
    }

    // ------------------------------------------------------------------------------ saved state

    private static BotSnapshot savedState() {
        BotSnapshot s = new BotSnapshot();
        s.stacks.add(new BotSnapshot.Entry(9, "{\"id\":\"minecraft:arrow\",\"count\":37}"));
        s.health = 7.0f;
        s.foodLevel = 11;
        return s;
    }

    public static void aMarkedBotThatCameBackGetsOnlyItsVitalsPutBackNotADressing() {
        Rig rig = new Rig();
        rig.online("Inh_Bot");
        rig.adapter.managed = true;
        rig.adapter.entity = Optional.of(rig.bot);
        rig.applier.mark(rig.bot);
        BotSnapshot saved = savedState();
        assertTrue(rig.gateway.restore("Inh_Bot", standing(), saved), "something was put back");
        assertEquals(List.of(saved), rig.applier.restoredSnapshots);
        assertEquals(List.of(false), rig.applier.restoredInventoryFlags, "its inventory came back with the player data");
        assertTrue(rig.applier.clearFlags.isEmpty(), "and nothing was dressed from the profile");
    }

    public static void anUnmarkedBotIsRestoredFromItsSavedStateNotItsProfile() {
        Rig rig = new Rig();
        rig.online("Inh_Bot");
        rig.adapter.managed = true;
        rig.adapter.entity = Optional.of(rig.bot);
        BotSnapshot saved = savedState();
        assertTrue(rig.gateway.restore("Inh_Bot", standing(), saved));
        assertEquals(List.of(saved), rig.applier.restoredSnapshots);
        assertEquals(List.of(true), rig.applier.restoredInventoryFlags, "the whole state, inventory included");
        assertTrue(rig.applier.clearFlags.isEmpty(), "the profile is not applied: that would refill everything");
        assertTrue(rig.applier.isMarked(rig.bot), "the marker and the saved state stay in step");
    }

    public static void anUnmarkedBotThatWasNeverSavedFallsBackToItsProfile() {
        Rig rig = new Rig();
        rig.online("Inh_Bot");
        rig.adapter.managed = true;
        rig.adapter.entity = Optional.of(rig.bot);
        BotProfile profile = standing();
        assertTrue(rig.gateway.restore("Inh_Bot", profile, null));
        assertTrue(rig.applier.restoredSnapshots.isEmpty());
        assertEquals(List.of(true), rig.applier.clearFlags);
        assertSame(profile, rig.applier.profiles.get(0));
    }

    public static void aFailedSnapshotRestoreDoesNotMarkTheBot() {
        Rig rig = new Rig();
        rig.online("Inh_Bot");
        rig.adapter.managed = true;
        rig.adapter.entity = Optional.of(rig.bot);
        rig.applier.loadoutApplied = false;
        rig.gateway.restore("Inh_Bot", standing(), savedState());
        assertFalse(rig.applier.isMarked(rig.bot), "so the next restart tries again");
    }

    public static void reapplyOnRestoreOffStillLeavesTheVitalsOfAMarkedBotRestored() {
        Rig rig = new Rig();
        rig.online("Inh_Bot");
        rig.adapter.managed = true;
        rig.adapter.entity = Optional.of(rig.bot);
        rig.config.profiles.reapplyOnRestore = false;
        rig.applier.mark(rig.bot);
        assertTrue(rig.gateway.restore("Inh_Bot", standing(), savedState()),
                "the switch is about re-dressing; a wound is not a dressing");
        assertEquals(List.of(false), rig.applier.restoredInventoryFlags);
    }

    public static void wakingAppliesTheSavedStateAssignsThePathAndMarksTheBotWithoutDressingIt() {
        Rig rig = new Rig();
        rig.online("Inh_Bot");
        rig.adapter.entity = Optional.of(rig.bot);
        BotSnapshot saved = savedState();
        BotProfile profile = patrolling();
        BotGateway.ApplyResult result = rig.gateway.wake("Inh_Bot", profile, saved);
        assertTrue(result.allApplied(), result.warnings().toString());
        assertEquals(List.of(saved), rig.applier.restoredSnapshots);
        assertEquals(List.of(true), rig.applier.restoredInventoryFlags);
        assertTrue(rig.applier.clearFlags.isEmpty(), "never dressed from the profile");
        assertEquals(1, rig.adapter.assigned.size(), "the path is assigned again");
        assertTrue(rig.applier.isMarked(rig.bot));
    }

    public static void wakingABotThatIsNotThereReportsItInsteadOfThrowing() {
        Rig rig = new Rig();
        BotGateway.ApplyResult result = rig.gateway.wake("Inh_Bot", standing(), savedState());
        assertFalse(result.loadoutApplied());
        assertFalse(result.warnings().isEmpty());
        assertTrue(rig.applier.restoredSnapshots.isEmpty());
    }

    public static void aSnapshotIsTheAppliersCaptureOfTheOnlineBotAndNullWhenItIsNotThere() {
        Rig rig = new Rig();
        rig.applier.captured = savedState();
        assertNull(rig.gateway.snapshot("Inh_Bot"), "no entity, no snapshot");
        rig.adapter.entity = Optional.of(rig.bot);
        assertSame(rig.applier.captured, rig.gateway.snapshot("Inh_Bot"));
        rig.adapter.findFailure = new IllegalStateException("lookup broke");
        assertNull(rig.gateway.snapshot("Inh_Bot"), "a lookup that throws is a bot that cannot be read");
    }

    // ------------------------------------------------------------------------------ survival mode and vanilla stats

    public static void aFreshSpawnIsPutRightTheMomentItAppearsAndTheSweepReportsWhatWasWrong() {
        Rig rig = new Rig();
        rig.adapter.entity = Optional.of(rig.bot);
        rig.applier.fixes = new BotGateway.StateFixes("creative", List.of("instabuild"), List.of());
        BotGateway.SpawnHandle handle = rig.gateway.requestSpawn(request());
        rig.adapter.state = new PvpBotOperations.SpawnState.Ready(UUID.randomUUID());
        assertInstanceOf(BotGateway.SpawnPoll.Ready.class, rig.gateway.poll(handle));
        assertEquals(1, rig.applier.enforced.size(), "checked when the poll saw it ready, before anything else looks at it");

        rig.applier.fixes = BotGateway.StateFixes.NONE; // now an ordinary survival player: only what was found before is left to report
        BotGateway.StateFixes reported = rig.gateway.enforceVanilla("Inh_Bot");
        assertEquals("creative", reported.previousGameMode());
        assertEquals(List.of("instabuild"), reported.abilities());
        assertTrue(rig.gateway.enforceVanilla("Inh_Bot").isEmpty(), "reported once");
    }

    public static void aRestoreAlsoPutsTheEntityRightAndForgettingDropsWhatWasPending() {
        Rig rig = new Rig();
        rig.online("Inh_Bot");
        rig.adapter.managed = true;
        rig.adapter.entity = Optional.of(rig.bot);
        rig.applier.mark(rig.bot);
        rig.applier.fixes = new BotGateway.StateFixes(null, List.of(), List.of("minecraft:max_health +12.00 (add_value)"));
        rig.gateway.restore("Inh_Bot", standing(), null);
        assertEquals(1, rig.applier.enforced.size());
        rig.applier.fixes = BotGateway.StateFixes.NONE;
        assertEquals(List.of("minecraft:max_health +12.00 (add_value)"), rig.gateway.enforceVanilla("Inh_Bot").modifiers());

        rig.applier.fixes = new BotGateway.StateFixes("adventure", List.of(), List.of());
        rig.gateway.restore("Inh_Bot", standing(), null);
        rig.gateway.forget("Inh_Bot");
        rig.applier.fixes = BotGateway.StateFixes.NONE;
        assertTrue(rig.gateway.enforceVanilla("Inh_Bot").isEmpty(), "forgotten with the bot");
    }

    public static void aBotThatIsNotThereHasNothingToPutRight() {
        Rig rig = new Rig();
        assertTrue(rig.gateway.enforceVanilla("Inh_Bot").isEmpty());
        assertTrue(rig.applier.enforced.isEmpty());
    }

    // ------------------------------------------------------------------------------ restore

    public static void restoreDoesNothingForABotThatIsNotOnlineAndListed() {
        Rig rig = new Rig();
        rig.adapter.entity = Optional.of(rig.bot);
        rig.adapter.managed = true;
        assertFalse(rig.gateway.restore("Inh_Bot", patrolling()), "not online");
        rig.online("Inh_Bot");
        rig.adapter.managed = false;
        assertFalse(rig.gateway.restore("Inh_Bot", patrolling()), "not listed by PvP BOT");
        assertTrue(rig.adapter.assigned.isEmpty());
        assertTrue(rig.applier.clearFlags.isEmpty());
    }

    public static void restoreReassignsAPathThatLostItsFollower() {
        Rig rig = new Rig();
        rig.online("Inh_Bot");
        rig.adapter.managed = true;
        rig.adapter.entity = Optional.of(rig.bot);
        rig.applier.mark(rig.bot);
        assertTrue(rig.gateway.restore("Inh_Bot", patrolling()));
        assertEquals(1, rig.adapter.assigned.size());
        assertTrue(rig.applier.clearFlags.isEmpty(), "already dressed: only the path is restored");

        rig.adapter.patrolling = true;
        assertFalse(rig.gateway.restore("Inh_Bot", patrolling()), "still following its path: nothing to do");
        assertEquals(1, rig.adapter.assigned.size());
    }

    public static void restoreContainsAnAdapterThatThrows() {
        Rig rig = new Rig();
        rig.online("Inh_Bot");
        rig.adapter.managed = true;
        rig.adapter.entity = Optional.of(rig.bot);
        rig.applier.mark(rig.bot);
        rig.adapter.assignFailure = new IllegalStateException("path store broke");
        assertFalse(rig.gateway.restore("Inh_Bot", patrolling()), "nothing could be re-applied, and nothing was thrown");
    }

    public static void restoreIgnoresPathsForStandingBots() {
        Rig rig = new Rig();
        rig.online("Inh_Bot");
        rig.adapter.managed = true;
        rig.adapter.entity = Optional.of(rig.bot);
        rig.applier.mark(rig.bot);
        assertFalse(rig.gateway.restore("Inh_Bot", standing()));
        assertTrue(rig.adapter.assigned.isEmpty());
    }

    public static void restoreDressesABotThatCameBackWithoutTheMarker() {
        Rig rig = new Rig();
        rig.online("Inh_Bot");
        rig.adapter.managed = true;
        rig.adapter.entity = Optional.of(rig.bot);
        BotProfile profile = standing();
        assertTrue(rig.gateway.restore("Inh_Bot", profile));
        assertEquals(List.of(true), rig.applier.clearFlags);
        assertSame(profile, rig.applier.profiles.get(0));
        assertTrue(rig.applier.isMarked(rig.bot));
        assertFalse(rig.gateway.restore("Inh_Bot", profile), "the second restore finds the marker and does nothing");
        assertEquals(1, rig.applier.clearFlags.size());
    }

    public static void restoreHonoursTheReapplyOnRestoreSwitch() {
        Rig rig = new Rig();
        rig.online("Inh_Bot");
        rig.adapter.managed = true;
        rig.adapter.entity = Optional.of(rig.bot);
        rig.config.profiles.reapplyOnRestore = false;
        assertFalse(rig.gateway.restore("Inh_Bot", standing()));
        assertTrue(rig.applier.clearFlags.isEmpty());
        assertFalse(rig.applier.isMarked(rig.bot));

        rig.config.profiles.reapplyOnRestore = true;
        assertTrue(rig.gateway.restore("Inh_Bot", standing()), "the switch is read on every call, so a config reload applies");
    }

    public static void aFailedReapplyOnRestoreDoesNotMarkTheBot() {
        Rig rig = new Rig();
        rig.online("Inh_Bot");
        rig.adapter.managed = true;
        rig.adapter.entity = Optional.of(rig.bot);
        rig.applier.loadoutApplied = false;
        assertTrue(rig.gateway.restore("Inh_Bot", standing()));
        assertFalse(rig.applier.isMarked(rig.bot), "so the next restart tries again");
    }

    // ------------------------------------------------------------------------------ world gateway

    public static void theWorldGatewayServesTheSeedAndLoadedDimensionsOnly() {
        GatewayFakes.Access access = new GatewayFakes.Access();
        access.seed = -8_675_309L;
        ServerLevel nether = McObjects.opaque(ServerLevel.class);
        access.worlds.put("minecraft:the_nether", nether);
        ServerLevel[] seen = new ServerLevel[1];
        dev.spawnbotswrapper.inhabitants.spawn.BlockProbe stub = new dev.spawnbotswrapper.inhabitants.spawn.BlockProbe() {
            @Override
            public dev.spawnbotswrapper.inhabitants.spawn.Cell cell(int x, int y, int z) {
                return dev.spawnbotswrapper.inhabitants.spawn.Cell.EMPTY;
            }

            @Override
            public int minY() {
                return 0;
            }

            @Override
            public int maxY() {
                return 127;
            }
        };
        McWorldGateway gateway = new McWorldGateway(access, world -> {
            seen[0] = world;
            return stub;
        });
        assertEquals(-8_675_309L, gateway.worldSeed());
        assertSame(stub, gateway.probe("minecraft:the_nether"));
        assertSame(nether, seen[0]);
        assertNull(gateway.probe("minecraft:overworld"), "not loaded");
        assertNull(gateway.probe("somemod:unknown"));
        assertNull(gateway.probe(null));
        assertNull(gateway.probe(""));
    }
}
