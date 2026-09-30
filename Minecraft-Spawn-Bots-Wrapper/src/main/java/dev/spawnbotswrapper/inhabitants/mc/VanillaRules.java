package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.engine.BotGateway;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The rules that make an inhabitant an ordinary survival player again, applied with vanilla's own paths:
 * <ul>
 *   <li>{@link #enforceSurvival}: game mode SURVIVAL and no creative-style ability. HeroBot's spawn without a mode
 *       makes a CREATIVE fake player, and PvP BOT's fallbacks use that form.</li>
 *   <li>{@link #stripWrapperModifiers}: no attribute modifier of this addon. Older versions gave every bot permanent
 *       modifiers with no item or effect behind them (max health, reach, knockback resistance, attack speed), which a
 *       normal player cannot have.</li>
 *   <li>{@link #mustStopEating}: the vanilla "can this player eat now" gate. PvP BOT starts eating with
 *       {@code startUsingItem}, which skips it.</li>
 * </ul>
 * Static and stateless; server thread only.
 */
final class VanillaRules {
    private VanillaRules() {
    }

    /**
     * Forces the bot into SURVIVAL and clears any ability a survival player does not have. Returns what was wrong (an
     * empty result when the bot already was an ordinary survival player). Vanilla's {@code setGameMode} does nothing
     * when the mode already is SURVIVAL, so a leftover ability (mayfly from a flying spawn) is reset through the
     * game mode's own ability update.
     */
    static BotGateway.StateFixes enforceSurvival(ServerPlayer bot) {
        GameType mode = bot.gameMode.getGameModeForPlayer();
        Abilities abilities = bot.getAbilities();
        List<String> wrong = new ArrayList<>();
        if (abilities.instabuild) {
            wrong.add("instabuild");
        }
        if (abilities.mayfly) {
            wrong.add("mayfly");
        }
        if (abilities.invulnerable) {
            wrong.add("invulnerable");
        }
        if (abilities.flying) {
            wrong.add("flying");
        }
        String previous = null;
        if (mode != GameType.SURVIVAL) {
            bot.setGameMode(GameType.SURVIVAL);
            previous = mode.getName();
        }
        Abilities now = bot.getAbilities();
        if (now.instabuild || now.mayfly || now.invulnerable || now.flying) {
            GameType.SURVIVAL.updatePlayerAbilities(now);
            bot.onUpdateAbilities();
        }
        return new BotGateway.StateFixes(previous, wrong, List.of());
    }

    /**
     * Removes every attribute modifier whose id belongs to this addon (see {@link ModifierIds}) from every attribute
     * of the bot, then keeps its health within the new maximum. Returns one description per removed modifier
     * ({@code minecraft:max_health +12.0}), empty when there was none.
     */
    static List<String> stripWrapperModifiers(ServerPlayer bot) {
        List<String> removed = new ArrayList<>();
        BuiltInRegistries.ATTRIBUTE.listElements().forEach(holder -> {
            AttributeInstance instance = bot.getAttribute(holder);
            if (instance != null) {
                removed.addAll(stripFrom(holder.key().identifier().toString(), instance));
            }
        });
        if (!removed.isEmpty() && bot.getHealth() > bot.getMaxHealth()) {
            bot.setHealth(bot.getMaxHealth());
        }
        return removed;
    }

    /** One attribute's share of {@link #stripWrapperModifiers}: removes the modifiers of this addon, keeps every other one. */
    static List<String> stripFrom(String attributeId, AttributeInstance instance) {
        List<String> removed = new ArrayList<>();
        for (AttributeModifier modifier : new ArrayList<>(instance.getModifiers())) {
            if (ModifierIds.NAMESPACE.equals(modifier.id().getNamespace()) && instance.removeModifier(modifier.id())) {
                removed.add(attributeId + " " + String.format(Locale.ROOT, "%+.2f", modifier.amount())
                        + " (" + modifier.operation().getSerializedName() + ")");
            }
        }
        return removed;
    }

    /**
     * Vanilla's {@code Player.canEat}: food can be eaten when the food level is below 20, or when the item is always
     * edible (golden apples, chorus fruit, ...). True when the bot is in the middle of eating something it could not
     * have started. Only food is ever affected: a potion or a bucket of milk has no food component.
     */
    static boolean mustStopEating(ServerPlayer bot) {
        if (!bot.isUsingItem()) {
            return false;
        }
        ItemStack using = bot.getUseItem();
        FoodProperties food = using.get(DataComponents.FOOD);
        return mustStopEating(food != null, food != null && food.canAlwaysEat(), bot.getFoodData().getFoodLevel());
    }

    /** The gate itself, without a game object: eating food that is not always edible at a full food bar is refused. */
    static boolean mustStopEating(boolean usingFood, boolean alwaysEdible, int foodLevel) {
        return usingFood && !alwaysEdible && foodLevel >= 20;
    }
}
