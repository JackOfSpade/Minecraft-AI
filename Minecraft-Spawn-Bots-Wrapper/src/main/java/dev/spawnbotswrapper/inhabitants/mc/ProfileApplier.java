package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.config.DisabledEnchantments;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Applies a {@link BotProfile} to a live bot using the vanilla API only: inventory contents, vanilla
 * attribute modifiers, health and hunger. PvP BOT is never touched; it reads what the bot carries and its
 * attributes on its own.
 * <ul>
 *   <li><b>Only bots.</b> The applier is constructed with a predicate that says whether an entity is a bot
 *       (the PvP BOT adapter's check) and refuses to modify anything else, so a real player who happens to be
 *       passed in is never wiped or dressed.</li>
 *   <li><b>Silent.</b> Items are written straight into the inventory rather than through
 *       {@code equipStack}, which would play equip sounds and emit vibration game events; a bot spawning in
 *       an ancient city must not set off the sculk sensors around it.</li>
 *   <li><b>Idempotent.</b> Attribute modifiers use fixed ids ({@link ModifierIds}) and overwrite, so applying
 *       twice gives the same result as applying once. Items overwrite their planned slots.</li>
 *   <li><b>Marked.</b> {@link #mark} adds a scoreboard command tag, which vanilla saves in the player's data;
 *       after a restart {@link #isMarked} tells whether the profile is already on the entity.</li>
 * </ul>
 */
public final class ProfileApplier implements ProfileApplication {
    /** Scoreboard tag set on every bot the addon has dressed. */
    public static final String MARKER_TAG = "pvpbot_inhabitants";

    private final Predicate<ServerPlayer> isBot;
    private final Supplier<Set<String>> disabledEnchantments;

    /** @param isBot true for entities that are bots (never for real players); no enchantment is disabled */
    public ProfileApplier(Predicate<ServerPlayer> isBot) {
        this(isBot, Set::of);
    }

    /**
     * @param isBot true for entities that are bots (never for real players)
     * @param disabledEnchantments canonical ids (see {@link DisabledEnchantments}) no bot may carry; read on every
     *                             call so a config reload takes effect at once
     */
    public ProfileApplier(Predicate<ServerPlayer> isBot, Supplier<Set<String>> disabledEnchantments) {
        this.isBot = isBot;
        this.disabledEnchantments = disabledEnchantments;
    }

    @Override
    public Result apply(ServerPlayer bot, BotProfile profile, boolean clearInventoryFirst) {
        List<String> warnings = new ArrayList<>();
        try {
            if (!isBot.test(bot)) {
                warnings.add("refused to apply a profile to " + bot.getGameProfile().name() + ": it is not a bot");
                return new Result(false, false, warnings);
            }
            Sanitized sanitized = applyLoadout(bot, profile.loadout(), clearInventoryFirst, warnings,
                    disabledEnchantments.get());
            boolean vitals = applyVitals(bot, profile.vitals(), warnings);
            return new Result(sanitized != null, vitals, warnings,
                    sanitized == null ? 0 : sanitized.pearls(),
                    sanitized == null ? List.of() : sanitized.enchantments());
        } catch (RuntimeException e) {
            // Nothing here may propagate: the caller is the population engine in the middle of a tick.
            warnings.add("profile could not be applied: " + e);
            return new Result(false, false, warnings);
        }
    }

    @Override
    public boolean isMarked(ServerPlayer bot) {
        return bot.getTags().contains(MARKER_TAG);
    }

    @Override
    public void mark(ServerPlayer bot) {
        bot.addTag(MARKER_TAG);
        hideNametag(bot);
    }

    /**
     * Vanilla nametags render through terrain up to a distance (a long-standing vanilla behaviour, not
     * something this addon or PvP BOT introduces), which reads as a wallhack for a structure meant to feel
     * inhabited rather than radar-tagged. There is no vanilla per-entity, line-of-sight-gated nametag: the
     * only real lever is the scoreboard team {@code nametagVisibility} rule, which is binary (on the whole
     * team or off), so inhabitants get it turned fully off. Team membership is keyed by name and saved with
     * the scoreboard, so this needs doing only once per bot -- it is not lost across a restart or a new
     * entity instance for the same name, unlike the command tag it rides alongside.
     * <p>
     * Best-effort like the rest of {@code mark}: an entity not yet fully in a world (its {@code Level} or
     * {@code MinecraftServer} reference still null) is left for a later call rather than throwing.
     */
    private static void hideNametag(ServerPlayer bot) {
        ServerLevel world = bot.level();
        MinecraftServer server = world == null ? null : world.getServer();
        if (server == null) {
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(MARKER_TAG);
        if (team == null) {
            team = scoreboard.addPlayerTeam(MARKER_TAG);
            team.setNameTagVisibility(Team.Visibility.NEVER);
        }
        scoreboard.addPlayerToTeam(bot.getGameProfile().name(), team);
    }

    /** The sanitize step's findings, or null when the loadout could not be applied. */
    private static Sanitized applyLoadout(ServerPlayer bot, BotProfile.Loadout loadout, boolean clear,
                                          List<String> warnings, Set<String> disabled) {
        try {
            return fill(bot.getInventory(), bot.registryAccess(), loadout, clear, warnings, disabled);
        } catch (RuntimeException e) {
            warnings.add("loadout could not be applied: " + e);
            return null;
        }
    }

    /** What the sanitize step of a dressing took out: ender pearls (items) and disabled enchantments (one line each). */
    record Sanitized(int pearls, List<String> enchantments) {
    }

    /**
     * Writes a loadout into an inventory: plans the slots, builds every stack, and selects hotbar slot 0 so
     * the first hotbar item is what the bot holds. Separated from the entity so it can be tested against a
     * bare {@link Inventory}.
     */
    static Sanitized fill(Inventory inventory, HolderLookup.Provider registries, BotProfile.Loadout loadout,
                          boolean clear, List<String> warnings) {
        return fill(inventory, registries, loadout, clear, warnings, Set.of());
    }

    /** As above, and afterwards no item carries an enchantment in {@code disabled} (canonical ids). */
    static Sanitized fill(Inventory inventory, HolderLookup.Provider registries, BotProfile.Loadout loadout,
                          boolean clear, List<String> warnings, Set<String> disabled) {
        SlotPlanner.Plan plan = SlotPlanner.plan(loadout.items());
        warnings.addAll(plan.warnings());
        ItemStackFactory factory = new ItemStackFactory(registries);
        if (clear) {
            inventory.clearContent();
        }
        for (SlotPlanner.Placement placement : plan.placements()) {
            Optional<ItemStack> stack = factory.build(placement.spec(), warnings);
            stack.ifPresent(s -> inventory.setItem(placement.slot(), s));
        }
        // Stored profiles from before pearls were dropped from loadouts (and pearls already on a bot that is not
        // wiped) never survive a dressing; see removeEnderPearls for why.
        int pearls = removeEnderPearls(inventory);
        if (pearls > 0) {
            warnings.add("removed " + pearls + " ender pearl" + (pearls == 1 ? "" : "s")
                    + " (they make PvP BOT's cobweb escape loop cancel attacks)");
        }
        // Same for a disabled enchantment (Piercing by default): a stored profile from before it was disabled, or an
        // item already on a bot that is not wiped, never survives a dressing. Only that enchantment goes.
        List<String> enchantments = removeDisabledEnchantments(inventory, disabled);
        if (!enchantments.isEmpty()) {
            warnings.add("removed disabled enchantments: " + String.join(", ", enchantments));
        }
        inventory.setSelectedSlot(0);
        return new Sanitized(pearls, enchantments);
    }

    /**
     * Removes every ender pearl from every slot of the inventory (hotbar, main, armor, offhand) and returns how
     * many pearls were removed (items, not stacks). Nothing else is touched. PvP BOT's cobweb escape uses a
     * pearl when it stands in a cobweb without a water bucket and re-selects the pearl slot on every tick it stays
     * webbed, which cancels a crossbow charge, a bow draw and attacks; see {@code LoadoutRoller}.
     */
    static int removeEnderPearls(Inventory inventory) {
        int removed = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(Items.ENDER_PEARL)) {
                removed += stack.getCount();
                inventory.setItem(slot, ItemStack.EMPTY);
            }
        }
        return removed;
    }

    @Override
    public int stripEnderPearls(ServerPlayer bot) {
        try {
            return isBot.test(bot) ? removeEnderPearls(bot.getInventory()) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Removes the enchantments in {@code disabled} (canonical ids; see {@link DisabledEnchantments}) from every stack
     * in every slot of the inventory (hotbar, main, armor, offhand), on the enchantments component and on the
     * stored-enchantments component of enchanted books. The item, its count and all its other enchantments stay. One
     * description per removal, {@code <id> (<item>)}, so a caller can log what went from where.
     */
    static List<String> removeDisabledEnchantments(Inventory inventory, Set<String> disabled) {
        List<String> removed = new ArrayList<>();
        if (disabled == null || disabled.isEmpty()) {
            return removed;
        }
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            strip(stack, DataComponents.ENCHANTMENTS, item, disabled, removed);
            strip(stack, DataComponents.STORED_ENCHANTMENTS, item, disabled, removed);
        }
        return removed;
    }

    private static void strip(ItemStack stack, DataComponentType<ItemEnchantments> type, String item,
                              Set<String> disabled, List<String> removed) {
        ItemEnchantments present = stack.getOrDefault(type, ItemEnchantments.EMPTY);
        if (present.isEmpty()) {
            return;
        }
        ItemEnchantments.Mutable kept = new ItemEnchantments.Mutable(present);
        List<String> found = new ArrayList<>();
        kept.removeIf(holder -> {
            String id = holder.unwrapKey().map(key -> key.identifier().toString()).orElse(null);
            if (id != null && disabled.contains(id)) {
                found.add(id + " (" + item + ")");
                return true;
            }
            return false;
        });
        if (found.isEmpty()) {
            return;
        }
        ItemEnchantments left = kept.toImmutable();
        if (left.isEmpty()) {
            stack.remove(type);
        } else {
            stack.set(type, left);
        }
        removed.addAll(found);
    }

    @Override
    public List<String> stripDisabledEnchantments(ServerPlayer bot) {
        try {
            return isBot.test(bot) ? removeDisabledEnchantments(bot.getInventory(), disabledEnchantments.get()) : List.of();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static boolean applyVitals(ServerPlayer bot, BotProfile.Vitals vitals, List<String> warnings) {
        try {
            // Attributes first: max health is one of them, and health is a fraction of it.
            List<Map.Entry<String, BotProfile.AttributeMod>> mods = new ArrayList<>(vitals.attributes().entrySet());
            mods.sort(Map.Entry.comparingByKey());
            for (Map.Entry<String, BotProfile.AttributeMod> mod : mods) {
                applyAttribute(bot, mod.getKey(), mod.getValue(), warnings);
            }
            bot.setHealth(initialHealth(bot.getMaxHealth(), vitals.healthFraction()));
            bot.getFoodData().setFoodLevel(vitals.foodLevel());
            return true;
        } catch (RuntimeException e) {
            warnings.add("vitals could not be applied: " + e);
            return false;
        }
    }

    /** Starting health: the fraction of max health, but never below half a heart so a bot is not born dying. */
    static float initialHealth(float maxHealth, double fraction) {
        return Math.min(maxHealth, Math.max(1.0f, (float) (maxHealth * fraction)));
    }

    private static void applyAttribute(ServerPlayer bot, String attributeId, BotProfile.AttributeMod mod, List<String> warnings) {
        try {
            Identifier id = ItemStackFactory.parseId(attributeId);
            Optional<Holder.Reference<Attribute>> attribute = id == null
                    ? Optional.empty()
                    : BuiltInRegistries.ATTRIBUTE.get(id);
            if (attribute.isEmpty()) {
                warnings.add("unknown attribute '" + attributeId + "'; skipped");
                return;
            }
            AttributeInstance instance = bot.getAttribute(attribute.get());
            if (instance == null) {
                warnings.add("players have no " + id + " attribute; skipped");
                return;
            }
            install(instance, id, mod, warnings);
        } catch (RuntimeException e) {
            warnings.add("attribute " + attributeId + " could not be applied: " + e);
        }
    }

    /**
     * Puts the profile's modifier on one attribute under the fixed addon id, replacing any earlier one.
     * Persistent, so it is saved with the player and survives a restart. Returns false (with a warning) for
     * an unknown operation or a non-finite value.
     */
    static boolean install(AttributeInstance instance, Identifier attributeId, BotProfile.AttributeMod mod, List<String> warnings) {
        AttributeModifier.Operation operation = operationOf(mod.operation());
        if (operation == null) {
            warnings.add("unknown modifier operation '" + mod.operation() + "' for " + attributeId + "; skipped");
            return false;
        }
        if (!Double.isFinite(mod.value())) {
            warnings.add("non-finite modifier value for " + attributeId + "; skipped");
            return false;
        }
        Identifier modifierId = Identifier.fromNamespaceAndPath(ModifierIds.NAMESPACE,
                ModifierIds.pathFor(attributeId.getNamespace(), attributeId.getPath()));
        instance.addOrReplacePermanentModifier(new AttributeModifier(modifierId, mod.value(), operation));
        return true;
    }

    /** Maps the profile's operation name to vanilla's; null when unknown. */
    static AttributeModifier.Operation operationOf(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case BotProfile.Op.ADD_VALUE -> AttributeModifier.Operation.ADD_VALUE;
            case BotProfile.Op.ADD_MULTIPLIED_BASE -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case BotProfile.Op.ADD_MULTIPLIED_TOTAL -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            default -> null;
        };
    }
}
