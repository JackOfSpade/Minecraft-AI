package dev.spawnbotswrapper.inhabitants.gametest;

import dev.spawnbotswrapper.inhabitants.InhabitantsMod;
import dev.spawnbotswrapper.inhabitants.command.CommandServices;
import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.engine.PopulationEngine;
import dev.spawnbotswrapper.inhabitants.mc.IssuedItems;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Real-server tests of the state of an inhabitant: it is an ordinary survival player with vanilla stats, and what it used
 * up (arrows, gear condition, health) stays used up when it is despawned by dormancy and comes back. See {@link Rig}.
 */
public final class InhabitantStateGameTests {
    private static final String ENV = "pvpbot-inhabitants-gametest:";

    private static final String ADDON_NAMESPACE = "pvpbot_inhabitants";

    private static CommandServices services(Rig rig) {
        return InhabitantsMod.servicesOf(rig.server);
    }

    private static boolean ordinarySurvivalPlayer(ServerPlayer bot) {
        Abilities a = bot.getAbilities();
        return bot.gameMode.getGameModeForPlayer() == GameType.SURVIVAL
                && !a.instabuild && !a.mayfly && !a.invulnerable && !a.flying;
    }

    private static String describeMode(ServerPlayer bot) {
        Abilities a = bot.getAbilities();
        return "mode=" + bot.gameMode.getGameModeForPlayer() + " instabuild=" + a.instabuild + " mayfly=" + a.mayfly
                + " invulnerable=" + a.invulnerable + " flying=" + a.flying;
    }

    /**
     * HeroBot spawns a fake player in CREATIVE unless told otherwise, and PvP BOT's own switch to survival can run before the
     * player exists. A fresh inhabitant must be an ordinary survival player without being nudged by the test (the harness
     * default {@link Rig#inhabitantReady()} forces survival; this one does not), and one that is pushed back to creative with
     * creative abilities (what a fallback spawn leaves behind) must be put right again by the addon.
     */
    @GameTest(environment = ENV + "state_survival", maxTicks = 900)
    public void freshInhabitantIsSurvivalWithNoSpecialAbilitiesAndStaysSo(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        boolean[] judged = {false};
        long[] brokenAt = {0};
        context.onEachTick(() -> {
            if (!rig.requestInhabitant()) {
                return;
            }
            if (!judged[0]) {
                if (!rig.inhabitantReady(false)) {
                    if (context.getTick() > 300) {
                        rig.fail("the inhabitant never appeared");
                    }
                    return;
                }
                if (!ordinarySurvivalPlayer(rig.bot)) {
                    rig.fail("a fresh inhabitant is not an ordinary survival player: " + describeMode(rig.bot));
                }
                Rig.LOG.info("[survival] fresh inhabitant {} is fine: {}", rig.botName, describeMode(rig.bot));
                judged[0] = true;
                // What a creative fallback spawn leaves behind: creative mode, flight, invulnerability.
                rig.bot.setGameMode(GameType.CREATIVE);
                rig.bot.getAbilities().mayfly = true;
                rig.bot.onUpdateAbilities();
                brokenAt[0] = context.getTick();
                if (ordinarySurvivalPlayer(rig.bot)) {
                    rig.fail("the test could not put the inhabitant into creative mode");
                }
                return;
            }
            if (ordinarySurvivalPlayer(rig.bot)) {
                Rig.LOG.info("[survival] put back into survival {} ticks after being made creative", context.getTick() - brokenAt[0]);
                rig.succeed();
            } else if (context.getTick() - brokenAt[0] > 300) {
                rig.fail("the addon did not put a creative inhabitant back into survival within 300 ticks: " + describeMode(rig.bot));
            }
        });
    }

    // ------------------------------------------------------------------ attribute modifiers

    private static List<String> addonModifiers(ServerPlayer bot) {
        List<String> found = new ArrayList<>();
        BuiltInRegistries.ATTRIBUTE.listElements().forEach(holder -> {
            AttributeInstance instance = bot.getAttribute(holder);
            if (instance != null) {
                for (AttributeModifier modifier : instance.getModifiers()) {
                    if (ADDON_NAMESPACE.equals(modifier.id().getNamespace())) {
                        found.add(holder.key().identifier() + " " + modifier.id() + " " + modifier.amount());
                    }
                }
            }
        });
        return found;
    }

    private static void addOldModifier(ServerPlayer bot, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                       String path, double amount) {
        bot.getAttribute(attribute).addOrReplacePermanentModifier(new AttributeModifier(
                Identifier.fromNamespaceAndPath(ADDON_NAMESPACE, path), amount, AttributeModifier.Operation.ADD_VALUE));
    }

    /**
     * Older versions gave every bot permanent modifiers (max health, reach, knockback resistance) with no item or effect behind
     * them, stats a player cannot have. A fresh inhabitant has none, and modifiers that an existing bot still carries are
     * removed (its health clamped to the new maximum) while a modifier of any other source stays.
     */
    @GameTest(environment = ENV + "state_modifiers", maxTicks = 900)
    public void inhabitantHasNoAttributeModifiersOfTheAddonAndOldOnesAreRemoved(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        boolean[] injected = {false};
        long[] injectedAt = {0};
        Identifier other = Identifier.fromNamespaceAndPath("pvpbot-inhabitants-gametest", "another_source");
        context.onEachTick(() -> {
            if (!rig.requestInhabitant()) {
                return;
            }
            if (!injected[0]) {
                if (!rig.inhabitantReady()) {
                    if (context.getTick() > 300) {
                        rig.fail("the inhabitant never appeared");
                    }
                    return;
                }
                List<String> fresh = addonModifiers(rig.bot);
                if (!fresh.isEmpty()) {
                    rig.fail("a fresh inhabitant carries attribute modifiers of the addon: " + fresh);
                }
                addOldModifier(rig.bot, Attributes.MAX_HEALTH, "profile/max_health", 12.0);
                addOldModifier(rig.bot, Attributes.KNOCKBACK_RESISTANCE, "profile/knockback_resistance", 1.0);
                addOldModifier(rig.bot, Attributes.ENTITY_INTERACTION_RANGE, "profile/entity_interaction_range", 2.0);
                rig.bot.getAttribute(Attributes.MAX_HEALTH).addPermanentModifier(
                        new AttributeModifier(other, 2.0, AttributeModifier.Operation.ADD_VALUE));
                rig.bot.setHealth(30.0F);
                if (addonModifiers(rig.bot).size() != 3 || rig.bot.getMaxHealth() < 33.9F || rig.bot.getHealth() < 29.9F) {
                    rig.fail("the test could not give the inhabitant the old modifiers: " + addonModifiers(rig.bot) + " max "
                            + rig.bot.getMaxHealth() + " health " + rig.bot.getHealth());
                }
                injected[0] = true;
                injectedAt[0] = context.getTick();
                return;
            }
            List<String> left = addonModifiers(rig.bot);
            if (left.isEmpty()) {
                if (!rig.bot.getAttribute(Attributes.MAX_HEALTH).hasModifier(other)) {
                    rig.fail("a modifier that is not the addon's was removed too");
                }
                if (rig.bot.getMaxHealth() != 22.0F || rig.bot.getHealth() > rig.bot.getMaxHealth()) {
                    rig.fail("after the removal max health is " + rig.bot.getMaxHealth() + " (expected 20 base + 2 from the other source)"
                            + " and health " + rig.bot.getHealth());
                }
                Rig.LOG.info("[modifiers] old modifiers removed {} ticks after they were added; health now {}",
                        context.getTick() - injectedAt[0], rig.bot.getHealth());
                rig.succeed();
            } else if (context.getTick() - injectedAt[0] > 300) {
                rig.fail("the addon did not remove its old attribute modifiers within 300 ticks: " + left);
            }
        });
    }

    // ------------------------------------------------------------------ eating

    /**
     * Vanilla lets a player start eating food only below a full food bar (always-edible food like a golden apple excepted).
     * PvP BOT starts eating with {@code startUsingItem}, which skips that rule, so the addon stops it: bread at food 20 is
     * stopped at once, the same bread at food 14 is eaten, and a golden apple at food 20 is eaten (always edible).
     */
    @GameTest(environment = ENV + "state_eating", maxTicks = 700)
    public void inhabitantCannotEatOrdinaryFoodAtAFullFoodBarButCanWhenHungryOrForAGoldenApple(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        int[] stage = {0};
        long[] mark = {0};
        context.onEachTick(() -> {
            if (!rig.requestInhabitant()) {
                return;
            }
            long tick = context.getTick();
            switch (stage[0]) {
                case 0 -> {
                    if (!rig.inhabitantReady()) {
                        if (tick > 300) {
                            rig.fail("the inhabitant never appeared");
                        }
                        return;
                    }
                    if (!rig.bot.getTags().contains("pvpbot_inhabitants")) {
                        rig.fail("the inhabitant is not marked as dressed, so the eating rule would not apply to it");
                    }
                    rig.bot.getInventory().clearContent();
                    rig.bot.getInventory().setItem(0, new ItemStack(Items.BREAD, 8));
                    rig.bot.getInventory().setSelectedSlot(0);
                    rig.bot.getFoodData().setFoodLevel(20);
                    rig.bot.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
                    if (!rig.bot.isUsingItem()) {
                        rig.fail("the test could not make the inhabitant start eating");
                    }
                    mark[0] = tick;
                    stage[0] = 1;
                }
                case 1 -> {
                    if (!rig.bot.isUsingItem()) {
                        Rig.LOG.info("[eating] bread at food 20 was stopped {} tick(s) after it started", tick - mark[0]);
                        if (rig.bot.getInventory().getItem(0).getCount() != 8) {
                            rig.fail("bread was consumed at a full food bar");
                        }
                        // Hungry: the same bread may be eaten.
                        rig.bot.getFoodData().setFoodLevel(14);
                        rig.bot.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
                        mark[0] = tick;
                        stage[0] = 2;
                    } else if (tick - mark[0] > 5) {
                        rig.fail("the inhabitant is still eating bread at a full food bar after " + (tick - mark[0]) + " ticks");
                    }
                }
                case 2 -> {
                    if (tick - mark[0] < 6) {
                        return;
                    }
                    if (!rig.bot.isUsingItem() && rig.bot.getInventory().getItem(0).getCount() == 8) {
                        rig.fail("a hungry inhabitant (food 14) was stopped from eating bread");
                    }
                    // Wait until the meal is over, then try an always-edible item at a full food bar.
                    if (rig.bot.isUsingItem()) {
                        if (tick - mark[0] > 80) {
                            rig.fail("the hungry inhabitant never finished its bread");
                        }
                        return;
                    }
                    rig.bot.getInventory().setItem(0, new ItemStack(Items.GOLDEN_APPLE, 2));
                    rig.bot.getFoodData().setFoodLevel(20);
                    rig.bot.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
                    mark[0] = tick;
                    stage[0] = 3;
                }
                case 3 -> {
                    if (tick - mark[0] < 6) {
                        return;
                    }
                    if (!rig.bot.isUsingItem()) {
                        rig.fail("an always-edible golden apple was stopped at a full food bar");
                    }
                    Rig.LOG.info("[eating] the golden apple at food 20 is being eaten after {} ticks", tick - mark[0]);
                    rig.succeed();
                }
                default -> rig.fail("unknown stage " + stage[0]);
            }
        });
    }

    // ------------------------------------------------------------------ issued items

    private static ItemStack mendingItem(Rig rig, net.minecraft.world.item.Item item, boolean issued) {
        ItemStack stack = new ItemStack(item);
        stack.enchant(rig.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.MENDING), 1);
        if (issued) {
            IssuedItems.mark(stack);
        }
        return stack;
    }

    private static boolean hasMending(ItemStack stack, Rig rig) {
        return stack.getOrDefault(net.minecraft.core.component.DataComponents.ENCHANTMENTS,
                net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY).getLevel(
                rig.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                        .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.MENDING)) > 0;
    }

    /** -1 when the bot carries no such item, else 1 when it has Mending and 0 when it has not. */
    private static int mendingOf(Rig rig, net.minecraft.world.item.Item item) {
        int slot = slotOf(rig.bot, item);
        return slot < 0 ? -1 : hasMending(rig.bot.getInventory().getItem(slot), rig) ? 1 : 0;
    }

    private static int slotOf(ServerPlayer bot, net.minecraft.world.item.Item item) {
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
                return slot;
            }
        }
        return -1;
    }

    private static int countOf(ServerPlayer bot, net.minecraft.world.item.Item item) {
        int n = 0;
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
                n += inventory.getItem(slot).getCount();
            }
        }
        return n;
    }

    /**
     * The disabled-enchantment list and the pearl removal shape what the wrapper ISSUES, never what a player owns. A player's
     * Mending chestplate and ender pearls, dropped at a bot's feet, are picked up and are still exactly that 200 ticks later (several
     * sweeps on); an issued Mending helmet (marked, forced into the inventory) still loses its Mending; and an issued stack the bot
     * drops reaches the world without the marker.
     */
    @GameTest(environment = ENV + "issued_items", maxTicks = 1200)
    public void issuedItemsAreSanitizedButAPlayersItemsPickedUpByAnInhabitantAreLeftAlone(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        int[] phase = {0};
        long[] mark = {0};
        boolean[] dropChecked = {false};
        context.onEachTick(() -> {
            if (!rig.requestInhabitant()) {
                return;
            }
            long tick = context.getTick();
            switch (phase[0]) {
                case 0 -> {
                    if (!rig.inhabitantReady()) {
                        if (tick > 300) {
                            rig.fail("the inhabitant never appeared");
                        }
                        return;
                    }
                    // The one-time migration of a bot from before the marker judges every stack: let it run before the scene starts.
                    if (record(rig).map(b -> !b.itemsMigrated).orElse(true)) {
                        if (tick > 500) {
                            rig.fail("the inhabitant was never migrated (the first sweep did not run)");
                        }
                        return;
                    }
                    rig.bot.getInventory().clearContent();
                    net.minecraft.world.phys.Vec3 at = rig.bot.position();
                    for (ItemStack dropped : new ItemStack[]{
                            mendingItem(rig, Items.NETHERITE_CHESTPLATE, false), new ItemStack(Items.ENDER_PEARL, 8)}) {
                        net.minecraft.world.entity.item.ItemEntity entity =
                                new net.minecraft.world.entity.item.ItemEntity(rig.level, at.x, at.y, at.z, dropped);
                        entity.setDeltaMovement(0, 0, 0);
                        entity.setNoPickUpDelay();
                        rig.level.addFreshEntity(entity);
                    }
                    mark[0] = tick;
                    phase[0] = 1;
                }
                case 1 -> {
                    int chest = slotOf(rig.bot, Items.NETHERITE_CHESTPLATE);
                    if (chest < 0 || countOf(rig.bot, Items.ENDER_PEARL) < 8) {
                        if (tick - mark[0] > 100) {
                            rig.fail("the inhabitant did not pick up what the player dropped at its feet: chestplate slot " + chest
                                    + ", pearls " + countOf(rig.bot, Items.ENDER_PEARL));
                        }
                        return;
                    }
                    if (IssuedItems.isIssued(rig.bot.getInventory().getItem(chest))) {
                        rig.fail("an item picked up in the world carries the issued marker");
                    }
                    // Force-issue a Mending helmet and Mending boots the way a dressing would.
                    rig.bot.getInventory().setItem(30, mendingItem(rig, Items.DIAMOND_HELMET, true));
                    rig.bot.getInventory().setItem(31, mendingItem(rig, Items.DIAMOND_BOOTS, false));
                    mark[0] = tick;
                    phase[0] = 2;
                }
                case 2 -> {
                    if (!dropChecked[0]) {
                        dropChecked[0] = true;
                        // An issued stack the bot drops arrives in the world unmarked.
                        ItemStack issued = new ItemStack(Items.IRON_SWORD);
                        IssuedItems.mark(issued);
                        net.minecraft.world.entity.item.ItemEntity dropped = rig.bot.drop(issued, false, true);
                        if (dropped == null) {
                            rig.fail("the inhabitant could not drop an item");
                        }
                        if (IssuedItems.isIssued(dropped.getItem())) {
                            rig.fail("a dropped issued item is still marked in the world");
                        }
                        dropped.discard();
                    }
                    long waited = tick - mark[0];
                    // The bot may wear what it carries (armor auto-equip moves a stack between slots), so items are found by kind.
                    int helmet = mendingOf(rig, Items.DIAMOND_HELMET);
                    if (waited < 220) {
                        if (waited > 160 && helmet == 1) {
                            rig.fail("the issued Mending helmet still has Mending after " + waited + " ticks");
                        }
                        return;
                    }
                    int chest = mendingOf(rig, Items.NETHERITE_CHESTPLATE);
                    int boots = mendingOf(rig, Items.DIAMOND_BOOTS);
                    Rig.LOG.info("[issued] after {} ticks (-1 = gone, 0 = no Mending, 1 = Mending): the player's chestplate {}, pearls {}, "
                            + "issued helmet {}, the player's boots {}", waited, chest, countOf(rig.bot, Items.ENDER_PEARL), helmet, boots);
                    if (chest != 1) {
                        rig.fail("the player's Mending chestplate lost its Mending or was deleted after " + waited + " ticks: " + chest);
                    }
                    if (countOf(rig.bot, Items.ENDER_PEARL) != 8) {
                        rig.fail("the player's ender pearls were touched: " + countOf(rig.bot, Items.ENDER_PEARL) + " of 8 left");
                    }
                    if (helmet != 0) {
                        rig.fail("an issued Mending helmet was not stripped of Mending (it must exist without it): " + helmet);
                    }
                    if (boots != 1) {
                        rig.fail("Mending boots that were not issued lost their Mending or were deleted: " + boots);
                    }
                    rig.succeed();
                }
                default -> rig.fail("unknown phase " + phase[0]);
            }
        });
    }

    // ------------------------------------------------------------------ dormancy

    /** Everything that can be used up, as text: every non-empty slot with its count and damage, plus health and hunger. */
    private static String stateOf(ServerPlayer bot) {
        StringBuilder text = new StringBuilder();
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                text.append(slot).append('=').append(stack.getItem()).append(" x").append(stack.getCount())
                        .append(" damage ").append(stack.getDamageValue()).append("; ");
            }
        }
        return text + "health " + bot.getHealth() + " food " + bot.getFoodData().getFoodLevel();
    }

    private static int arrows(ServerPlayer bot) {
        int n = 0;
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(Items.ARROW)) {
                n += inventory.getItem(slot).getCount();
            }
        }
        return n;
    }

    private static Optional<BotRecord> record(Rig rig) {
        Optional<StructureRecord> record = services(rig).population().find(rig.key());
        return record.isEmpty() || record.get().bots.isEmpty() ? Optional.empty() : Optional.of(record.get().bots.get(0));
    }

    /**
     * Dormancy removes a bot far from every player (PvP BOT empties its inventory when it removes one) and used to wake it
     * dressed again from its profile: a full quiver, pristine armor, full health. Now it wakes with exactly what it had. The
     * archer fires real arrows first (so the arrows it used are really gone), its bow is then taken away so nothing changes
     * while dormancy is switched on for the scene, and the woken bot must match the state it had, slot by slot.
     */
    @GameTest(environment = ENV + "state_dormancy", maxTicks = 2400)
    public void dormancyKeepsTheLiveInventoryAndHealthArrowsFiredStayFired(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        rig.createTarget(10.0);
        int[] phase = {0};
        long[] mark = {0};
        String[] expected = {null};
        ServerPlayer[] before = {null};
        context.onEachTick(() -> {
            CommandServices services = services(rig);
            if (!rig.requestInhabitant()) {
                return;
            }
            long tick = context.getTick();
            switch (phase[0]) {
                case 0 -> {
                    if (!rig.inhabitantReady()) {
                        if (tick > 300) {
                            rig.fail("the inhabitant never appeared");
                        }
                        return;
                    }
                    rig.dressWornArcher();
                    before[0] = rig.bot;
                    mark[0] = tick;
                    phase[0] = 1;
                }
                case 1 -> {
                    rig.traceEvery(100, tick - mark[0], "dormancy");
                    // PvP BOT's own auto-target is managed off and the aggro controller only acquires what it sees (a bot spawns facing
                    // a random way): the archer is handed its target, exactly as the aggro controller would hand it.
                    if (Upstream.target(rig.botName).equals("none")) {
                        rig.forceTarget();
                    }
                    int shots = HarnessMod.shotsBy(rig.bot.getUUID()).size();
                    if (shots >= 2) {
                        int left = arrows(rig.bot);
                        Rig.LOG.info("[dormancy] the archer fired {} arrows; {} of 64 are left", shots, left);
                        if (left >= 64) {
                            rig.fail("the archer fired " + shots + " arrows but its quiver is still full");
                        }
                        // Nothing may change from here on: no bow, no more arrows used. Health and hunger stay as dressed.
                        rig.bot.getInventory().setItem(0, ItemStack.EMPTY);
                        services.adapter().targetControl().clearTarget(rig.botName);
                        rig.placeTarget(48.0); // out of sight and out of reach: the archer has nothing left to do
                        rig.bot.setHealth(13.0F);
                        rig.bot.getFoodData().setFoodLevel(14);
                        mark[0] = tick;
                        phase[0] = 2;
                    } else if (tick - mark[0] > 1000) {
                        rig.fail("the archer did not fire two arrows in 1000 ticks; " + rig.trace());
                    }
                }
                case 2 -> {
                    if (tick - mark[0] < 15) {
                        return;
                    }
                    expected[0] = stateOf(rig.bot);
                    Rig.LOG.info("[dormancy] state before dormancy: {}", expected[0]);
                    InhabitantsConfig cfg = services.config().get();
                    InhabitantsConfig.Dormancy d = cfg.dormancy;
                    InhabitantsConfig.Allocation alloc = cfg.allocation;
                    boolean aggro = cfg.aggro.enabled;
                    boolean enabled = d.enabled;
                    double distance = d.distanceBlocks;
                    int grace = alloc.graceTicks;
                    int dwell = alloc.dwellTicks;
                    int interval = alloc.intervalTicks;
                    var players = rig.server.getPlayerList();
                    int simulation = players.getSimulationDistance();
                    int view = players.getViewDistance();
                    rig.onCleanup(() -> {
                        cfg.aggro.enabled = aggro;
                        d.enabled = enabled;
                        d.distanceBlocks = distance;
                        alloc.graceTicks = grace;
                        alloc.dwellTicks = dwell;
                        alloc.intervalTicks = interval;
                        players.setSimulationDistance(simulation);
                        players.setViewDistance(view);
                        cfg.include.remove(rig.structureId());
                    });
                    // The nearest-first allocation replaced the distance rule: the mock player stands 48 blocks away, and with a simulation
                    // distance of 2 chunks (32 blocks) the structure is out of the relevance area and the bot's chunk is not loaded by
                    // the player. Only a bot a player has SEEN is put to sleep with its state (an unseen one is deleted, its slot is vacant):
                    // the sighting itself is tested by the population tests, here the record just says it was seen.
                    players.setSimulationDistance(2);
                    players.setViewDistance(2);
                    cfg.aggro.enabled = false; // a bot that hunts the player is engaged and never removed; it is not the subject here
                    d.distanceBlocks = 2.0;
                    d.enabled = true;
                    alloc.graceTicks = 0;
                    alloc.dwellTicks = 0;
                    alloc.intervalTicks = 5;
                    record(rig).ifPresent(b -> {
                        b.seen = true;
                        b.firstSeenMillis = System.currentTimeMillis();
                        b.lastSeenMillis = b.firstSeenMillis;
                    });
                    rig.placeTarget(56.0); // a player that moves lets the allocation look again at once
                    mark[0] = tick;
                    phase[0] = 3;
                }
                case 3 -> {
                    Optional<BotRecord> bot = record(rig);
                    if (bot.isPresent() && bot.get().state == BotState.DORMANT) {
                        services.config().get().dormancy.enabled = false;
                        if (rig.server.getPlayerList().getPlayerByName(bot.get().name) != null) {
                            rig.fail("the bot is recorded dormant but its entity is still online");
                        }
                        Rig.LOG.info("[dormancy] went dormant {} ticks after dormancy was switched on", tick - mark[0]);
                        // The player comes back and the structure's chunk loads again: the same call the structure detector makes; the
                        // allocation wakes the sleeper first.
                        rig.placeTarget(24.0);
                        services.config().get().include.add(rig.structureId());
                        ((PopulationEngine) services.engine()).submit(rig.arena());
                        mark[0] = tick;
                        phase[0] = 4;
                    } else if (tick - mark[0] > 400) {
                        rig.fail("the inhabitant did not go dormant within 400 ticks; " + rig.trace());
                    }
                }
                case 4 -> {
                    Optional<BotRecord> bot = record(rig);
                    ServerPlayer woken = bot.isEmpty() ? null : rig.server.getPlayerList().getPlayerByName(bot.get().name);
                    if (bot.isPresent() && bot.get().state == BotState.SPAWNED && woken != null) {
                        if (woken == before[0]) {
                            rig.fail("the bot that woke is the very same entity that was online before (it never went away)");
                        }
                        rig.bot = woken;
                        mark[0] = tick;
                        phase[0] = 5;
                    } else if (tick - mark[0] > 500) {
                        rig.fail("the dormant inhabitant did not wake within 500 ticks; state "
                                + bot.map(b -> b.state.toString()).orElse("no record"));
                    }
                }
                case 5 -> {
                    if (tick - mark[0] < 3) {
                        return;
                    }
                    String now = stateOf(rig.bot);
                    Rig.LOG.info("[dormancy] state after waking: {}", now);
                    if (!now.equals(expected[0])) {
                        rig.fail("the woken inhabitant is not what it was.\n  before: " + expected[0] + "\n  after:  " + now);
                    }
                    if (arrows(rig.bot) >= 64) {
                        rig.fail("the woken inhabitant has a full quiver again");
                    }
                    if (!rig.bot.getTags().contains("pvpbot_inhabitants")) {
                        rig.fail("the woken inhabitant is not marked as dressed");
                    }
                    rig.succeed();
                }
                default -> rig.fail("unknown phase " + phase[0]);
            }
        });
    }

    /**
     * The issued marker never leaves the wrapper's own bookkeeping: a shot arrow's pickup stack and what a dying mob drops
     * arrive in the world unmarked (the entity-load hook), as a dropped sword does in {@code state_issued_items}.
     */
    @GameTest(environment = ENV + "state_issued_shots", maxTicks = 40)
    public void aShotArrowAndADeathDropArriveInTheWorldUnmarked(GameTestHelper context) {
        net.minecraft.server.level.ServerLevel level = context.getLevel();
        net.minecraft.world.phys.Vec3 at = context.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 2.0, 2.5));
        ItemStack marked = new ItemStack(Items.ARROW);
        IssuedItems.mark(marked);
        if (!IssuedItems.isIssued(marked)) {
            context.fail(net.minecraft.network.chat.Component.literal("fixture: the arrow stack was not marked"));
            return;
        }
        net.minecraft.world.entity.projectile.arrow.Arrow arrow =
                new net.minecraft.world.entity.projectile.arrow.Arrow(level, at.x, at.y, at.z, marked, null);
        level.addFreshEntity(arrow);
        if (IssuedItems.isIssued(arrow.getPickupItemStackOrigin())) {
            context.fail(net.minecraft.network.chat.Component.literal("a shot arrow's pickup stack is still marked as issued"));
            return;
        }
        arrow.discard();

        net.minecraft.world.entity.monster.zombie.Zombie zombie =
                net.minecraft.world.entity.EntityType.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if (zombie == null) {
            context.fail(net.minecraft.network.chat.Component.literal("fixture: no zombie"));
            return;
        }
        zombie.setPos(at.x + 2, at.y, at.z);
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        IssuedItems.mark(sword);
        zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, sword);
        zombie.setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 2.0F); // above 1: always dropped, kept or not
        level.addFreshEntity(zombie);
        zombie.kill(level);
        List<net.minecraft.world.entity.item.ItemEntity> drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(zombie.blockPosition()).inflate(4.0));
        boolean sawSword = false;
        for (net.minecraft.world.entity.item.ItemEntity drop : drops) {
            if (drop.getItem().is(Items.IRON_SWORD)) {
                sawSword = true;
                if (IssuedItems.isIssued(drop.getItem())) {
                    context.fail(net.minecraft.network.chat.Component.literal("an issued stack dropped on death is still marked"));
                    return;
                }
            }
            drop.discard();
        }
        if (!sawSword) {
            context.fail(net.minecraft.network.chat.Component.literal("fixture: the zombie dropped no sword, so nothing was checked"));
            return;
        }
        context.succeed();
    }
}
