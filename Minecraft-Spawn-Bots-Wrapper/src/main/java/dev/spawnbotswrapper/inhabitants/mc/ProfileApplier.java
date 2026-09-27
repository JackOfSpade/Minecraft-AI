package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

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

    private final Predicate<ServerPlayerEntity> isBot;

    /** @param isBot true for entities that are bots (never for real players) */
    public ProfileApplier(Predicate<ServerPlayerEntity> isBot) {
        this.isBot = isBot;
    }

    @Override
    public Result apply(ServerPlayerEntity bot, BotProfile profile, boolean clearInventoryFirst) {
        List<String> warnings = new ArrayList<>();
        try {
            if (!isBot.test(bot)) {
                warnings.add("refused to apply a profile to " + bot.getGameProfile().name() + ": it is not a bot");
                return new Result(false, false, warnings);
            }
            boolean loadout = applyLoadout(bot, profile.loadout(), clearInventoryFirst, warnings);
            boolean vitals = applyVitals(bot, profile.vitals(), warnings);
            return new Result(loadout, vitals, warnings);
        } catch (RuntimeException e) {
            // Nothing here may propagate: the caller is the population engine in the middle of a tick.
            warnings.add("profile could not be applied: " + e);
            return new Result(false, false, warnings);
        }
    }

    @Override
    public boolean isMarked(ServerPlayerEntity bot) {
        return bot.getCommandTags().contains(MARKER_TAG);
    }

    @Override
    public void mark(ServerPlayerEntity bot) {
        bot.addCommandTag(MARKER_TAG);
    }

    private static boolean applyLoadout(ServerPlayerEntity bot, BotProfile.Loadout loadout, boolean clear, List<String> warnings) {
        try {
            fill(bot.getInventory(), bot.getRegistryManager(), loadout, clear, warnings);
            return true;
        } catch (RuntimeException e) {
            warnings.add("loadout could not be applied: " + e);
            return false;
        }
    }

    /**
     * Writes a loadout into an inventory: plans the slots, builds every stack, and selects hotbar slot 0 so
     * the first hotbar item is what the bot holds. Separated from the entity so it can be tested against a
     * bare {@link PlayerInventory}.
     */
    static void fill(PlayerInventory inventory, RegistryWrapper.WrapperLookup registries, BotProfile.Loadout loadout,
                     boolean clear, List<String> warnings) {
        SlotPlanner.Plan plan = SlotPlanner.plan(loadout.items());
        warnings.addAll(plan.warnings());
        ItemStackFactory factory = new ItemStackFactory(registries);
        if (clear) {
            inventory.clear();
        }
        for (SlotPlanner.Placement placement : plan.placements()) {
            Optional<ItemStack> stack = factory.build(placement.spec(), warnings);
            stack.ifPresent(s -> inventory.setStack(placement.slot(), s));
        }
        inventory.setSelectedSlot(0);
    }

    private static boolean applyVitals(ServerPlayerEntity bot, BotProfile.Vitals vitals, List<String> warnings) {
        try {
            // Attributes first: max health is one of them, and health is a fraction of it.
            List<Map.Entry<String, BotProfile.AttributeMod>> mods = new ArrayList<>(vitals.attributes().entrySet());
            mods.sort(Map.Entry.comparingByKey());
            for (Map.Entry<String, BotProfile.AttributeMod> mod : mods) {
                applyAttribute(bot, mod.getKey(), mod.getValue(), warnings);
            }
            bot.setHealth(initialHealth(bot.getMaxHealth(), vitals.healthFraction()));
            bot.getHungerManager().setFoodLevel(vitals.foodLevel());
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

    private static void applyAttribute(ServerPlayerEntity bot, String attributeId, BotProfile.AttributeMod mod, List<String> warnings) {
        try {
            Identifier id = ItemStackFactory.parseId(attributeId);
            Optional<RegistryEntry.Reference<EntityAttribute>> attribute = id == null
                    ? Optional.empty()
                    : Registries.ATTRIBUTE.getEntry(id);
            if (attribute.isEmpty()) {
                warnings.add("unknown attribute '" + attributeId + "'; skipped");
                return;
            }
            EntityAttributeInstance instance = bot.getAttributeInstance(attribute.get());
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
    static boolean install(EntityAttributeInstance instance, Identifier attributeId, BotProfile.AttributeMod mod, List<String> warnings) {
        EntityAttributeModifier.Operation operation = operationOf(mod.operation());
        if (operation == null) {
            warnings.add("unknown modifier operation '" + mod.operation() + "' for " + attributeId + "; skipped");
            return false;
        }
        if (!Double.isFinite(mod.value())) {
            warnings.add("non-finite modifier value for " + attributeId + "; skipped");
            return false;
        }
        Identifier modifierId = Identifier.of(ModifierIds.NAMESPACE,
                ModifierIds.pathFor(attributeId.getNamespace(), attributeId.getPath()));
        instance.overwritePersistentModifier(new EntityAttributeModifier(modifierId, mod.value(), operation));
        return true;
    }

    /** Maps the profile's operation name to vanilla's; null when unknown. */
    static EntityAttributeModifier.Operation operationOf(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case BotProfile.Op.ADD_VALUE -> EntityAttributeModifier.Operation.ADD_VALUE;
            case BotProfile.Op.ADD_MULTIPLIED_BASE -> EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case BotProfile.Op.ADD_MULTIPLIED_TOTAL -> EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            default -> null;
        };
    }
}
