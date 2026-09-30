package dev.spawnbotswrapper.inhabitants.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The offhand rule of the inhabitants on a real server with PvP BOT running: the best shield, else a totem of undying; when the
 * offhand item breaks or pops, the next of the same kind, else the next rung. PvP BOT's own auto-totem and totem priority are
 * managed off, so PvP BOT does not undo the placement (it forces a totem into the offhand every tick otherwise).
 */
public final class OffhandPolicyGameTests {
    private static final String ENV = "pvpbot-inhabitants-gametest:";

    private static boolean offhandIs(Rig rig, net.minecraft.world.item.Item item) {
        return rig.bot.getOffhandItem().is(item);
    }

    private static int count(Rig rig, net.minecraft.world.item.Item item) {
        int n = 0;
        Inventory inv = rig.bot.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).is(item)) {
                n += inv.getItem(i).getCount();
            }
        }
        return n + (rig.bot.getOffhandItem().is(item) ? rig.bot.getOffhandItem().getCount() : 0);
    }

    /** Uses the offhand item up the way play does: a shield takes hits until it breaks (its stack becomes empty). */
    private static void breakOffhand(Rig rig) {
        for (int i = 0; i < 2000 && !rig.bot.getOffhandItem().isEmpty(); i++) {
            rig.bot.getOffhandItem().hurtAndBreak(1, rig.bot, EquipmentSlot.OFFHAND);
        }
    }

    /** A lethal hit on a bot holding a totem: vanilla pops it (the stack is used up, health and effects are restored). */
    private static void pop(Rig rig) {
        if (!rig.bot.connection.hasClientLoaded()) {
            rig.bot.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        }
        int before = count(rig, Items.TOTEM_OF_UNDYING);
        rig.bot.invulnerableTime = 0; // a fresh hit every time, not one swallowed by the invulnerability frames of the last one
        boolean applied = rig.bot.hurtServer(rig.level, rig.level.damageSources().generic(), 1000.0F);
        Rig.LOG.info("[offhand] pop: totems {} -> {}, applied={} alive={} health={} offhand={}", before, count(rig, Items.TOTEM_OF_UNDYING),
                applied, rig.bot.isAlive(), rig.bot.getHealth(), rig.bot.getOffhandItem());
        rig.bot.setHealth(rig.bot.getMaxHealth());
    }

    private static void require(Rig rig, boolean condition, String message) {
        if (!condition) {
            rig.fail(message);
        }
    }

    /**
     * A bot with two shields and two totems, the old loadout shape (a totem in the offhand, a shield in the hotbar): the policy puts
     * a shield in the offhand, PvP BOT leaves it there, breaking it gives the second shield, breaking that a totem, and a popped
     * totem the next totem; after the last totem the offhand stays empty.
     */
    @GameTest(environment = ENV + "offhand_ladder", maxTicks = 600)
    public void shieldsThenTotemsInTheOffhand(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        int[] phase = {0};
        long[] since = {0};
        context.onEachTick(() -> {
            if (!rig.requestInhabitant()) {
                return;
            }
            if (phase[0] == 0) {
                if (!rig.inhabitantReady()) {
                    if (context.getTick() > 200) {
                        rig.fail("the inhabitant never appeared");
                    }
                    return;
                }
                require(rig, Boolean.FALSE.equals(Upstream.setting("isAutoTotemEnabled"))
                                && Boolean.FALSE.equals(Upstream.setting("isTotemPriority")),
                        "PvP BOT's auto-totem / totem priority are not managed off: autoTotem=" + Upstream.setting("isAutoTotemEnabled")
                                + " totemPriority=" + Upstream.setting("isTotemPriority"));
                Inventory inv = rig.bot.getInventory();
                inv.clearContent();
                inv.setItem(0, new ItemStack(Items.IRON_SWORD));
                inv.setItem(1, new ItemStack(Items.SHIELD));
                inv.setItem(2, new ItemStack(Items.SHIELD));
                inv.setItem(3, new ItemStack(Items.TOTEM_OF_UNDYING));
                inv.setItem(4, new ItemStack(Items.TOTEM_OF_UNDYING));
                inv.setSelectedSlot(0);
                rig.bot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING)); // a third totem, the legacy placement
                rig.bot.setHealth(rig.bot.getMaxHealth());
                rig.bot.getFoodData().setFoodLevel(20);
                phase[0] = 1;
                since[0] = context.getTick();
                return;
            }
            long waited = context.getTick() - since[0];
            switch (phase[0]) {
                case 1 -> {
                    // The legacy placement (a totem in the offhand, shields in the hotbar) is fixed by the policy and stays fixed.
                    if (offhandIs(rig, Items.SHIELD)) {
                        require(rig, count(rig, Items.SHIELD) == 2 && count(rig, Items.TOTEM_OF_UNDYING) == 3,
                                "an item was lost or duplicated by the offhand policy: shields=" + count(rig, Items.SHIELD)
                                        + " totems=" + count(rig, Items.TOTEM_OF_UNDYING));
                        phase[0] = 2;
                        since[0] = context.getTick();
                    } else if (waited > 20) {
                        rig.fail("the offhand is " + rig.bot.getOffhandItem() + ", not the shield, " + waited + " ticks after dressing");
                    }
                }
                case 2 -> {
                    // PvP BOT's auto-totem would push the totem back into the offhand within a tick: it must not.
                    require(rig, offhandIs(rig, Items.SHIELD), "PvP BOT or something else took the shield out of the offhand: "
                            + rig.bot.getOffhandItem());
                    if (waited >= 40) {
                        breakOffhand(rig);
                        phase[0] = 3;
                        since[0] = context.getTick();
                    }
                }
                case 3 -> {
                    if (offhandIs(rig, Items.SHIELD)) {
                        require(rig, count(rig, Items.SHIELD) == 1, "the second shield was duplicated: " + count(rig, Items.SHIELD));
                        breakOffhand(rig);
                        phase[0] = 4;
                        since[0] = context.getTick();
                    } else if (waited > 20) {
                        rig.fail("the first shield broke but the offhand is " + rig.bot.getOffhandItem() + ", not the second shield");
                    }
                }
                case 4 -> {
                    if (offhandIs(rig, Items.TOTEM_OF_UNDYING)) {
                        require(rig, count(rig, Items.SHIELD) == 0 && count(rig, Items.TOTEM_OF_UNDYING) == 3,
                                "after the last shield the totems are not all there: " + count(rig, Items.TOTEM_OF_UNDYING));
                        pop(rig);
                        phase[0] = 5;
                        since[0] = context.getTick();
                    } else if (waited > 20) {
                        rig.fail("both shields broke but the offhand is " + rig.bot.getOffhandItem() + ", not a totem");
                    }
                }
                case 5 -> {
                    if (offhandIs(rig, Items.TOTEM_OF_UNDYING) && count(rig, Items.TOTEM_OF_UNDYING) == 2) {
                        pop(rig);
                        phase[0] = 6;
                        since[0] = context.getTick();
                    } else if (waited > 20) {
                        rig.fail("a totem popped (" + count(rig, Items.TOTEM_OF_UNDYING) + " left) but the offhand is "
                                + rig.bot.getOffhandItem() + ", not the next totem");
                    }
                }
                case 6 -> {
                    if (offhandIs(rig, Items.TOTEM_OF_UNDYING) && count(rig, Items.TOTEM_OF_UNDYING) == 1) {
                        pop(rig);
                        phase[0] = 7;
                        since[0] = context.getTick();
                    } else if (waited > 20) {
                        rig.fail("the second totem popped (" + count(rig, Items.TOTEM_OF_UNDYING) + " left) but the offhand is "
                                + rig.bot.getOffhandItem() + ", not the last totem");
                    }
                }
                case 7 -> {
                    if (count(rig, Items.TOTEM_OF_UNDYING) == 0) {
                        require(rig, rig.bot.getOffhandItem().isEmpty(), "an offhand item appeared from nowhere: " + rig.bot.getOffhandItem());
                        if (waited > 10) {
                            rig.succeed();
                        }
                    } else if (waited > 20) {
                        rig.fail("the last totem never popped: " + count(rig, Items.TOTEM_OF_UNDYING) + " left, offhand "
                                + rig.bot.getOffhandItem());
                    }
                }
                default -> rig.fail("unexpected phase " + phase[0]);
            }
        });
    }

    /** Anything that is neither a shield nor a totem in the offhand (a torch here) is left alone, whatever else the bot carries. */
    @GameTest(environment = ENV + "offhand_other_item_stays", maxTicks = 200)
    public void anotherOffhandItemIsLeftAlone(GameTestHelper context) {
        Rig rig = new Rig(context);
        rig.buildPlatform();
        boolean[] dressed = {false};
        long[] since = {0};
        context.onEachTick(() -> {
            if (!rig.requestInhabitant()) {
                return;
            }
            if (!dressed[0]) {
                if (!rig.inhabitantReady()) {
                    if (context.getTick() > 200) {
                        rig.fail("the inhabitant never appeared");
                    }
                    return;
                }
                Inventory inv = rig.bot.getInventory();
                inv.clearContent();
                inv.setItem(0, new ItemStack(Items.IRON_SWORD));
                inv.setItem(1, new ItemStack(Items.SHIELD));
                inv.setItem(2, new ItemStack(Items.TOTEM_OF_UNDYING));
                inv.setSelectedSlot(0);
                rig.bot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TORCH, 16));
                dressed[0] = true;
                since[0] = context.getTick();
                return;
            }
            require(rig, offhandIs(rig, Items.TORCH), "the torch in the offhand was replaced by " + rig.bot.getOffhandItem());
            if (context.getTick() - since[0] > 40) {
                rig.succeed();
            }
        });
    }
}
