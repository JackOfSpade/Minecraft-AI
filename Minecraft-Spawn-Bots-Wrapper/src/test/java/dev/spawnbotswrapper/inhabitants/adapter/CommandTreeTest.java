package dev.spawnbotswrapper.inhabitants.adapter;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandTreeTest {

    private static LiteralArgumentBuilder<Object> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static RequiredArgumentBuilder<Object, String> word(String name) {
        return RequiredArgumentBuilder.argument(name, StringArgumentType.word());
    }

    private static CommandDispatcher<Object> fullTree() {
        CommandDispatcher<Object> d = new CommandDispatcher<>();
        d.register(literal("pvpbot")
                .then(literal("spawn").then(word("name").executes(c -> 1)))
                .then(literal("remove").then(word("name").executes(c -> 1))));
        d.register(literal("playerspawn").then(word("player").executes(c -> 1)));
        d.register(literal("player").then(word("targets").executes(c -> 1)));
        d.register(literal("herobot").executes(c -> 1));
        return d;
    }

    @Test
    void findsEveryLiteralTheIntegrationDependsOn() {
        CommandTree t = CommandTree.scan(fullTree().getRoot());
        assertTrue(t.known());
        assertTrue(t.pvpbot() && t.pvpbotSpawn() && t.pvpbotRemove());
        assertTrue(t.playerspawn() && t.player() && t.herobot());
    }

    @Test
    void reportsExactlyWhichLiteralsAreMissing() {
        CommandDispatcher<Object> d = new CommandDispatcher<>();
        d.register(literal("pvpbot").then(literal("spawn").then(word("name").executes(c -> 1))));
        d.register(literal("player").then(word("targets").executes(c -> 1)));
        CommandTree t = CommandTree.scan(d.getRoot());
        assertTrue(t.known());
        assertTrue(t.pvpbot() && t.pvpbotSpawn());
        assertFalse(t.pvpbotRemove());
        assertFalse(t.playerspawn());
        assertTrue(t.player());
        assertFalse(t.herobot());
    }

    @Test
    void pvpbotWithoutSubcommandsHasNeitherSpawnNorRemove() {
        CommandDispatcher<Object> d = new CommandDispatcher<>();
        d.register(literal("pvpbot").executes(c -> 1));
        CommandTree t = CommandTree.scan(d.getRoot());
        assertTrue(t.pvpbot());
        assertFalse(t.pvpbotSpawn());
        assertFalse(t.pvpbotRemove());
    }

    @Test
    void anEmptyDispatcherIsKnownAndEmpty() {
        CommandTree t = CommandTree.scan(new CommandDispatcher<Object>().getRoot());
        assertTrue(t.known());
        assertFalse(t.pvpbot() || t.playerspawn() || t.player() || t.herobot());
    }

    @Test
    void aCommandThatRefusesEverySourceIsStillRegistered() {
        CommandDispatcher<Object> d = new CommandDispatcher<>();
        d.register(literal("playerspawn").requires(s -> false).then(word("player").executes(c -> 1)));
        assertTrue(CommandTree.scan(d.getRoot()).playerspawn(),
                "we execute as the console; the requires predicate is checked at execution, not here");
    }

    @Test
    void aNullRootIsUnknown() {
        assertEquals(CommandTree.UNKNOWN, CommandTree.scan(null));
        assertFalse(CommandTree.UNKNOWN.known());
    }
}
