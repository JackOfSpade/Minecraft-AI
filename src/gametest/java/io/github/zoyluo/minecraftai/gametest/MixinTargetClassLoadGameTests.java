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
 *   <li>{@code MerchantEntityInvokerMixin} (an {@code @Invoker} on {@code AbstractVillager}, used by TradeTask).</li>
 * </ul>
 *
 * <p>Everything is by class NAME (never {@code X.class} of a mixin: Mixin forbids referencing a mixin class directly), and the
 * test also checks that the mixin really is merged into the loaded class.</p>
 */
public final class MixinTargetClassLoadGameTests {
    private static final String MIXIN_PACKAGE = "io.github.zoyluo.minecraftai.mixin.";
    private static final String LOGIN_LISTENER = "net.minecraft.server.network.ServerLoginPacketListenerImpl";
    private static final String ABSTRACT_VILLAGER = "net.minecraft.world.entity.npc.villager.AbstractVillager";

    @GameTest(maxTicks = 20)
    public void loginTimeoutMixinTargetLoadsWithTheMixinApplied(GameTestHelper context) {
        Class<?> target = load(context, LOGIN_LISTENER);
        require(context, Arrays.stream(target.getDeclaredMethods()).anyMatch(m -> m.getName().contains("extendLoginTimeout")),
                "LoginTimeoutMixin's @ModifyConstant handler was not merged into " + LOGIN_LISTENER);
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void merchantInvokerMixinTargetLoadsWithTheInvokerInterfaceApplied(GameTestHelper context) {
        Class<?> target = load(context, ABSTRACT_VILLAGER);
        require(context, Arrays.stream(target.getInterfaces()).anyMatch(i -> i.getName().equals(MIXIN_PACKAGE + "MerchantEntityInvokerMixin")),
                "MerchantEntityInvokerMixin is not an interface of " + ABSTRACT_VILLAGER + ": the @Invoker was not applied");
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
