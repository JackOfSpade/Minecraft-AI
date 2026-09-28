package io.github.zoyluo.minecraftai.goal;

import net.minecraft.block.Block;
import net.minecraft.item.Item;

import java.util.Set;

public sealed interface Goal permits Goal.HaveItem, Goal.HavePickaxeTier, Goal.MineOre, Goal.HarvestCrop, Goal.Armor, Goal.Workstation, Goal.Stockpile, Goal.Food, Goal.Build {
    record HaveItem(Item item, int count) implements Goal {
        public HaveItem {
            count = Math.max(1, count);
        }
    }

    record HavePickaxeTier(int tier) implements Goal {
        public HavePickaxeTier {
            tier = Math.max(0, tier);
        }
    }

    record MineOre(Set<Block> ores, int count) implements Goal {
        public MineOre {
            ores = ores == null ? Set.of() : Set.copyOf(ores);
            count = Math.max(1, count);
        }
    }

    /** P3: harvest N crops (wheat/carrot/potato). Backward-chained: have a hoe (+seeds) -> till/plant/wait to mature/harvest. */
    record HarvestCrop(Block crop, Item seed, Item produce, int count) implements Goal {
        public HarvestCrop {
            count = Math.max(1, count);
        }
    }

    /** Phase1: gear up -- full armor set + sword (currently iron tier, reuses GoalPlanner.ensureArmor's backward chaining). */
    record Armor() implements Goal {
    }

    /** Phase2: infrastructure -- prepare and place the crafting table/furnace/chest trio (a production + storage base). */
    record Workstation() implements Goal {
    }

    /** Phase3: stockpiling -- acquire count of item, and (best-effort) store it in a nearby chest. */
    record Stockpile(Item item, int count) implements Goal {
        public Stockpile {
            count = Math.max(1, count);
        }
    }

    /** Layer 4 Provisioning: hunt for meat and cook it into cookedCount servings of cooked food (follows GoalPlanner's hunt -> cook loop).
     *  Serves colloquial entry points like "go hunting/go get some food/get some meat" (the provision_food tool). */
    record Food(int cookedCount) implements Goal {
        public Food {
            cookedCount = Math.max(1, cookedCount);
        }
    }

    /** Build goal: construct according to a blueprint (the "build a house" one-line full chain: auto-gather materials -> build), blueprint names like small_hut/hut_5x5. */
    record Build(String blueprint) implements Goal {
    }
}
