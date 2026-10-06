package io.github.zoyluo.minecraftai.brain;

import io.github.zoyluo.minecraftai.craft.RecipeRegistry;
import io.github.zoyluo.minecraftai.craft.RuntimeRecipeIndex;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.Item;

/**
 * Stands in for the runtime recipe index of a running server, which unit tests never build (it needs a
 * recipe manager). The game's index holds every vanilla crafting recipe, so a noun that is raw in a bare
 * test JVM can be "crafted" in play; this puts chosen recipes in the index the way the server would.
 */
final class RuntimeRecipeFixture {
    private RuntimeRecipeFixture() {
    }

    /** Makes each item look like the output of a crafting recipe in the runtime index. */
    static void install(Item... outputs) {
        install(List.of(outputs));
    }

    /** The index of a running server: every vanilla crafting recipe's result (see {@link VanillaRecipeOutputs}). */
    static void installVanilla() {
        install(VanillaRecipeOutputs.indexed());
    }

    @SuppressWarnings("unchecked")
    private static void install(Collection<Item> outputs) {
        try {
            Field index = RuntimeRecipeIndex.class.getDeclaredField("INDEX");
            index.setAccessible(true);
            Map<Item, RecipeRegistry.Recipe> recipes = (Map<Item, RecipeRegistry.Recipe>) index.get(null);
            for (Item output : outputs) {
                // The ingredient is irrelevant to classification: only whether a recipe exists matters.
                recipes.put(output, new RecipeRegistry.Recipe(output, 1,
                        List.of(new RecipeRegistry.Ingredient(List.of(output), 4)), false));
            }
            Field ready = RuntimeRecipeIndex.class.getDeclaredField("ready");
            ready.setAccessible(true);
            ready.setBoolean(null, true);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("the runtime recipe index changed shape; update this fixture", exception);
        }
    }

    static void clear() {
        RuntimeRecipeIndex.clear();
    }
}
