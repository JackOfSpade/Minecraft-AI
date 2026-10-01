package io.github.zoyluo.minecraftai.gametest;

import java.util.Arrays;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Class-load probe for the mixins that no other GameTest exercises. {@code minecraftai.mixins.json} sets
 * {@code injectors.defaultRequire = 1}, so a target that does not exist (a renamed method or class, for example after a mapping
 * change) fails hard the moment Mixin applies the config to the target class. Forcing the target class through the game class
 * loader here turns that into an explicit test failure instead of a crash that only shows up when the feature is used:
 *
 * <ul>
 *   <li>{@code LoginTimeoutMixin} (a {@code @ModifyConstant} in {@code ServerLoginPacketListenerImpl.tick}), and</li>
 *   <li>{@code PhantomSpawnerHumansOnlyMixin} (a MixinExtras {@code @ModifyExpressionValue} on the {@code players()} call in {@code PhantomSpawner.tick}).</li>
 * </ul>
 *
 * <p>Everything is by class NAME (never {@code X.class} of a mixin: Mixin forbids referencing a mixin class directly), and the
 * test also checks that the mixin really is merged into the loaded class.</p>
 */
public final class MixinTargetClassLoadGameTests {
    private static final String LOGIN_LISTENER = "net.minecraft.server.network.ServerLoginPacketListenerImpl";
    private static final String PHANTOM_SPAWNER = "net.minecraft.world.level.levelgen.PhantomSpawner";

    @GameTest(maxTicks = 20)
    public void loginTimeoutMixinTargetLoadsWithTheMixinApplied(GameTestHelper context) {
        Class<?> target = load(context, LOGIN_LISTENER);
        require(context, Arrays.stream(target.getDeclaredMethods()).anyMatch(m -> m.getName().contains("extendLoginTimeout")),
                "LoginTimeoutMixin's @ModifyConstant handler was not merged into " + LOGIN_LISTENER);
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void phantomSpawnerMixinTargetLoadsWithTheMixinApplied(GameTestHelper context) {
        Class<?> target = load(context, PHANTOM_SPAWNER);
        require(context, Arrays.stream(target.getDeclaredMethods()).anyMatch(m -> m.getName().contains("minecraftai$skipBots")),
                "PhantomSpawnerHumansOnlyMixin's @ModifyExpressionValue handler was not merged into " + PHANTOM_SPAWNER);
        context.succeed();
    }

    /**
     * BotMeleeKnockbackMixin: a MixinExtras @WrapOperation on the {@code hurtMarked} read in {@code Player.causeExtraKnockback}, the place where
     * vanilla throws a ServerPlayer target's server-side knockback away (a bot has no client to apply it).
     */
    @GameTest(maxTicks = 20)
    public void botMeleeKnockbackMixinTargetLoadsWithTheMixinApplied(GameTestHelper context) {
        Class<?> target = load(context, "net.minecraft.world.entity.player.Player");
        require(context, Arrays.stream(target.getDeclaredMethods()).anyMatch(m -> m.getName().contains("minecraftai$botKeepsItsKnockback")),
                "BotMeleeKnockbackMixin's @WrapOperation handler was not merged into " + target.getName());
        context.succeed();
    }

    /**
     * The Baritone mixins are applied to vanilla classes at start-up whatever the navigation engine is: the palette accessor (no
     * static initialiser that can crash start-up: the reflective scan is a lazy holder), the loot-context wrapper (a non-exclusive
     * WrapOperation) and the item-stack hash (a field write on damage, computed on read).
     */
    @GameTest(maxTicks = 20)
    public void baritonePalettedContainerMixinIsAppliedAndItsLazyScanSucceeds(GameTestHelper context) {
        Class<?> target = load(context, "net.minecraft.world.level.chunk.PalettedContainer");
        require(context, Arrays.stream(target.getInterfaces()).anyMatch(i -> i.getName().equals("baritone.utils.accessor.IPalettedContainer")),
                "BaritonePalettedContainerMixin did not add IPalettedContainer to PalettedContainer");
        try {
            io.github.zoyluo.minecraftai.baritone.PaletteAccess.verify();
        } catch (LinkageError e) {
            context.fail(Component.nullToEmpty("the palette scan failed on this game version / mod set: " + e));
            throw new IllegalStateException(e);
        }
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void baritoneLootContextMixinIsAppliedAsAWrapOperation(GameTestHelper context) {
        Class<?> target = load(context, "net.minecraft.world.level.storage.loot.LootContext$Builder");
        require(context, Arrays.stream(target.getDeclaredMethods()).anyMatch(m -> m.getName().contains("minecraftai$registriesForBaritoneStub")),
                "BaritoneLootContextBuilderMixin's @WrapOperation handler was not merged into " + target.getName());
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void baritoneItemStackMixinIsAppliedAndKeepsTheHashContract(GameTestHelper context) {
        Class<?> target = load(context, "net.minecraft.world.item.ItemStack");
        require(context, Arrays.stream(target.getDeclaredMethods()).anyMatch(m -> m.getName().contains("minecraftai$onItemDamageSet")),
                "BaritoneItemStackMixin's damage hook was not merged into ItemStack");
        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE);
        baritone.api.utils.accessor.IItemStack hashed = (baritone.api.utils.accessor.IItemStack) (Object) stack;
        int item = net.minecraft.world.item.Items.IRON_PICKAXE.hashCode();
        require(context, hashed.getBaritoneHash() == item, "an undamaged stack must hash as item.hashCode() + 0");
        stack.setDamageValue(7);
        require(context, hashed.getBaritoneHash() == item + 7, "the hash must follow the damage: " + hashed.getBaritoneHash());
        stack.setDamageValue(3);
        require(context, hashed.getBaritoneHash() == item + 3, "the hash must follow a second damage change");
        context.succeed();
    }

    private static Class<?> load(GameTestHelper context, String name) {
        Class<?> type;
        try {
            type = Class.forName(name, true, MinecraftServer.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            // Mixin failures surface as MixinApplyError / MixinTransformerError (Error) or InvalidInjectionException
            context.fail(Component.nullToEmpty("loading " + name + " failed, a mixin target no longer resolves: " + e));
            throw new IllegalStateException(e);
        }
        require(context, type.getClassLoader() == MinecraftServer.class.getClassLoader(),
                name + " was not loaded by the game class loader");
        return type;
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
            throw new IllegalStateException(message);
        }
    }
}
