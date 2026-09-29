package dev.spawnbotswrapper.inhabitants.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.Category;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.Mechanism;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.SettingSpec;
import dev.spawnbotswrapper.inhabitants.catalog.SettingCatalog.ValueType;
import dev.spawnbotswrapper.inhabitants.command.FakeServices.FakeEngine.ResetCall;
import dev.spawnbotswrapper.inhabitants.engine.ForceMode;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.IdentifierArgument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Brigadier layer: tree shape, argument types, the permission requirement on every node, execution
 * through a real dispatcher (with fake services and no world) and tab completion.
 */
class InhabitantsCommandTest {
    private final FakeServices fs = new FakeServices();
    private final FakeServices.FakeCatalog catalog = new FakeServices.FakeCatalog();
    private final AtomicReference<CommandServices> services = new AtomicReference<>();
    private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
    private final TestSources.Capture out = new TestSources.Capture();

    @BeforeEach
    void register() {
        catalog.specs = List.of(
                new SettingSpec("moveSpeed", "movespeed", ValueType.DOUBLE, 0.1, 2.0, "1.0",
                        Category.PER_BOT_RANDOMIZABLE, Mechanism.ATTRIBUTE, "vitals", ""),
                new SettingSpec("combat", "combat", ValueType.BOOLEAN, Double.NaN, Double.NaN, "true",
                        Category.GLOBAL_ONLY, Mechanism.NONE, "", ""));
        services.set(fs.services());
        InhabitantsCommand.register(dispatcher, services::get, backends(catalog));
    }

    private static Backends backends(Backends.CatalogSource catalog) {
        return new Backends(catalog, (profile, caps) -> List.of("rendered " + profile.archetype()),
                FakeServices.senderAt(100.5, 64.0, -20.5));
    }

    private int run(String command) throws CommandSyntaxException {
        return run(command, 2);
    }

    private int run(String command, int level) throws CommandSyntaxException {
        return dispatcher.execute(command, TestSources.level(level, out));
    }

    private List<String> complete(String input) throws Exception {
        ParseResults<CommandSourceStack> parse = dispatcher.parse(input, TestSources.level(2, out));
        return dispatcher.getCompletionSuggestions(parse).get().getList().stream().map(Suggestion::getText).toList();
    }

    // ---------------------------------------------------------------- shape

    private static void collectExecutable(CommandNode<CommandSourceStack> node, String prefix, List<String> out) {
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            String token = child instanceof LiteralCommandNode ? child.getName() : "<" + child.getName() + ">";
            String path = prefix.isEmpty() ? token : prefix + " " + token;
            if (child.getCommand() != null) {
                out.add(path);
            }
            collectExecutable(child, path, out);
        }
    }

    private static void collectAll(CommandNode<CommandSourceStack> node, List<CommandNode<CommandSourceStack>> out) {
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            out.add(child);
            collectAll(child, out);
        }
    }

    private CommandNode<CommandSourceStack> node(String... path) {
        CommandNode<CommandSourceStack> n = dispatcher.getRoot();
        for (String p : path) {
            n = n.getChild(p);
            assertNotNull(n, "missing node " + p + " in " + String.join(" ", path));
        }
        return n;
    }

    @Test
    void rootAndAliasAreRegisteredAndTheAliasRedirectsToTheRoot() {
        Set<String> top = new TreeSet<>();
        dispatcher.getRoot().getChildren().forEach(c -> top.add(c.getName()));
        assertEquals(Set.of("inhabitants", "pvpbot_inhabitants"), top);

        assertSame(node("inhabitants"), node("pvpbot_inhabitants").getRedirect());
        assertTrue(node("pvpbot_inhabitants").getChildren().isEmpty(), "the alias reuses the root's children");
    }

    @Test
    void theExecutableCommandsAreExactlyTheDocumentedOnes() {
        List<String> paths = new ArrayList<>();
        collectExecutable(node("inhabitants"), "", paths);

        assertEquals(new TreeSet<>(List.of(
                "info",
                "adapter",
                "structure here",
                "nearby",
                "nearby <radiusChunks>",
                "process nearest",
                "process nearest roll",
                "process nearest occupied",
                "process nearest abandoned",
                "reset here",
                "reset here removeBots",
                "reset nearest",
                "reset nearest removeBots",
                "reset structure <structureId> <chunkX> <chunkZ>",
                "reset structure <structureId> <chunkX> <chunkZ> removeBots",
                "profile <botName>",
                "catalog",
                "catalog <category>",
                "reload")), new TreeSet<>(paths));
        assertEquals(paths.size(), new TreeSet<>(paths).size(), "no path is registered twice");
        assertNotNull(node("inhabitants").getCommand(), "the bare command prints the usage");
        assertNotNull(node("pvpbot_inhabitants").getCommand(), "so does the alias");
    }

    @Test
    void theForceModeLiteralsComeFromTheEnum() {
        Set<String> literals = new TreeSet<>();
        node("inhabitants", "process", "nearest").getChildren().forEach(c -> literals.add(c.getName()));
        Set<String> expected = new TreeSet<>();
        for (ForceMode m : ForceMode.values()) {
            expected.add(CommandArgs.modeLiteral(m));
        }
        assertEquals(expected, literals);
    }

    @SuppressWarnings("unchecked")
    private <T> ArgumentType<T> argumentType(String... path) {
        CommandNode<CommandSourceStack> n = node(path);
        assertInstanceOf(ArgumentCommandNode.class, n);
        return ((ArgumentCommandNode<CommandSourceStack, T>) n).getType();
    }

    @Test
    void argumentsHaveTheDocumentedTypesAndBounds() {
        IntegerArgumentType radius = assertInstanceOf(IntegerArgumentType.class,
                argumentType("inhabitants", "nearby", "radiusChunks"));
        assertEquals(1, radius.getMinimum());
        assertEquals(64, radius.getMaximum());

        assertInstanceOf(IdentifierArgument.class,
                argumentType("inhabitants", "reset", "structure", "structureId"));

        for (String coord : List.of("chunkX", "chunkZ")) {
            CommandNode<CommandSourceStack> n = coord.equals("chunkX")
                    ? node("inhabitants", "reset", "structure", "structureId", "chunkX")
                    : node("inhabitants", "reset", "structure", "structureId", "chunkX", "chunkZ");
            IntegerArgumentType t = assertInstanceOf(IntegerArgumentType.class,
                    ((ArgumentCommandNode<?, ?>) n).getType());
            assertEquals(-1_875_000, t.getMinimum());
            assertEquals(1_875_000, t.getMaximum());
        }

        StringArgumentType bot = assertInstanceOf(StringArgumentType.class,
                argumentType("inhabitants", "profile", "botName"));
        assertEquals(StringArgumentType.StringType.SINGLE_WORD, bot.getType());
        StringArgumentType category = assertInstanceOf(StringArgumentType.class,
                argumentType("inhabitants", "catalog", "category"));
        assertEquals(StringArgumentType.StringType.SINGLE_WORD, category.getType());
    }

    @Test
    void everyFreeTextOrIdentifierArgumentOffersCompletion() {
        assertNotNull(((ArgumentCommandNode<?, ?>) node("inhabitants", "profile", "botName")).getCustomSuggestions());
        assertNotNull(((ArgumentCommandNode<?, ?>) node("inhabitants", "catalog", "category")).getCustomSuggestions());
        assertNotNull(((ArgumentCommandNode<?, ?>) node("inhabitants", "reset", "structure", "structureId"))
                .getCustomSuggestions());
        assertNotNull(((ArgumentCommandNode<?, ?>) node("inhabitants", "reset", "structure", "structureId", "chunkX"))
                .getCustomSuggestions());
        assertNotNull(((ArgumentCommandNode<?, ?>) node("inhabitants", "reset", "structure", "structureId", "chunkX",
                "chunkZ")).getCustomSuggestions());
    }

    @Test
    void noNodeMixesLiteralAndArgumentChildrenAndBrigadierFindsNoAmbiguity() {
        List<CommandNode<CommandSourceStack>> all = new ArrayList<>();
        collectAll(node("inhabitants"), all);
        all.add(node("inhabitants"));
        for (CommandNode<CommandSourceStack> n : all) {
            boolean literals = n.getChildren().stream().anyMatch(c -> c instanceof LiteralCommandNode);
            boolean arguments = n.getChildren().stream().anyMatch(c -> c instanceof ArgumentCommandNode);
            assertFalse(literals && arguments, "node '" + n.getName() + "' mixes literal and argument children");
        }

        List<String> ambiguities = new ArrayList<>();
        dispatcher.findAmbiguities((parent, child, sibling, inputs) ->
                ambiguities.add(parent.getName() + ": " + child.getName() + " vs " + sibling.getName() + " on " + inputs));
        assertTrue(ambiguities.isEmpty(), ambiguities.toString());
    }

    // ---------------------------------------------------------------- permission requirement

    private List<CommandNode<CommandSourceStack>> everyNode() {
        List<CommandNode<CommandSourceStack>> all = new ArrayList<>();
        all.add(node("inhabitants"));
        all.add(node("pvpbot_inhabitants"));
        collectAll(node("inhabitants"), all);
        return all;
    }

    @Test
    void everyNodeIsGuardedByTheConfiguredLevel() {
        List<CommandNode<CommandSourceStack>> nodes = everyNode();
        assertTrue(nodes.size() >= 28, "expected the whole tree, got " + nodes.size());

        for (int configLevel = 0; configLevel <= 4; configLevel++) {
            fs.config.commandPermissionLevel = configLevel;
            for (int sourceLevel = 0; sourceLevel <= 4; sourceLevel++) {
                CommandSourceStack source = TestSources.level(sourceLevel);
                for (CommandNode<CommandSourceStack> n : nodes) {
                    assertEquals(sourceLevel >= configLevel, n.canUse(source),
                            "node '" + n.getName() + "' config " + configLevel + " source " + sourceLevel);
                }
            }
        }
    }

    @Test
    void anUnauthorisedSourceCannotEvenParseTheCommandOrItsAlias() {
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants info", 1));
        assertThrows(CommandSyntaxException.class, () -> run("pvpbot_inhabitants info", 0));
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants", 1));
        assertThrows(CommandSyntaxException.class, () -> run("pvpbot_inhabitants", 1),
                "the bare alias would print the usage if it did not carry the requirement itself");
        assertTrue(out.messages.isEmpty(), "an unauthorised source learns nothing");
    }

    @Test
    void changingTheLevelInTheConfigTakesEffectWithoutReRegistering() throws Exception {
        fs.config.commandPermissionLevel = 3;
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants info", 2));
        assertEquals(1, run("inhabitants info", 3));

        fs.config.commandPermissionLevel = 0;
        assertEquals(1, run("inhabitants info", 0));
    }

    @Test
    void disablingTheCommandsInTheConfigHidesThemFromEverybodyExceptReload() {
        fs.config.debugCommands = false;
        for (int level = 0; level <= 4; level++) {
            int l = level;
            assertThrows(CommandSyntaxException.class, () -> run("inhabitants info", l));
        }
        for (CommandNode<CommandSourceStack> n : everyNode()) {
            // "reload" is exempt on purpose (see reloadStaysReachableEvenWhenDisabled). The root and the
            // alias carry the wider of the two requirements purely so Brigadier lets traversal continue
            // towards "reload" (every ancestor on a path must pass its own requires()) - they are not
            // themselves supposed to do anything when used bare, so accepting a level-4 source here is the
            // documented, harmless side effect, not a second exemption.
            if (n.getName().equals("reload") || n.getName().equals("inhabitants") || n.getName().equals("pvpbot_inhabitants")) {
                continue;
            }
            assertFalse(n.canUse(TestSources.level(4)), n.getName());
        }
        // a source that does not even qualify for reload gets nothing at all, including the root/alias
        assertFalse(node("inhabitants").canUse(TestSources.level(0)));
        assertFalse(node("pvpbot_inhabitants").canUse(TestSources.level(0)));
    }

    @Test
    void reloadStaysReachableEvenWhenDisabled() throws Exception {
        // debugCommands=false must never be a one-way trap: an operator has to be able to reload the config
        // (and so turn debugCommands back on) without a server restart.
        fs.config.debugCommands = false;
        fs.config.commandPermissionLevel = 3;
        CommandNode<CommandSourceStack> reload = node("inhabitants", "reload");
        assertFalse(reload.canUse(TestSources.level(2)), "still below the configured level");
        assertTrue(reload.canUse(TestSources.level(3)), "the configured level must still work");
        assertEquals(1, run("inhabitants reload", 3));

        // every other node stays refused throughout
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants info", 4));
    }

    // ---------------------------------------------------------------- not ready / failures

    @Test
    void beforeTheServerIsReadyEveryCommandSaysSoInsteadOfFailing() throws Exception {
        services.set(null);
        for (String cmd : List.of("inhabitants info", "inhabitants adapter", "inhabitants structure here",
                "inhabitants nearby", "inhabitants nearby 5", "inhabitants process nearest",
                "inhabitants process nearest occupied", "inhabitants reset here", "inhabitants reset nearest removeBots",
                "inhabitants reset structure minecraft:village_plains 1 2", "inhabitants profile Bob",
                "inhabitants catalog", "inhabitants catalog global", "inhabitants reload",
                "pvpbot_inhabitants info")) {
            TestSources.Capture capture = new TestSources.Capture();
            int result = dispatcher.execute(cmd, TestSources.level(2, capture));
            assertEquals(0, result, cmd);
            assertTrue(capture.joined().contains("not ready yet"), cmd + " -> " + capture.joined());
        }
        assertEquals(0, fs.reloads);
        assertTrue(fs.engine.resetCalls.isEmpty());
    }

    @Test
    void beforeTheServerIsReadyTheUsageIsStillAvailableAndTheDefaultLevelApplies() throws Exception {
        services.set(null);
        assertEquals(1, run("inhabitants"));
        assertTrue(out.joined().contains("/inhabitants info"), out.joined());
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants", 1));
    }

    @Test
    void aFailingServiceIsReportedAsAnErrorNotThrown() throws Exception {
        fs.population.failure = new IllegalStateException("store closed");
        assertEquals(0, run("inhabitants info"));
        assertTrue(out.joined().contains("The command failed: IllegalStateException: store closed"), out.joined());
        assertEquals(0, run("inhabitants nearby 5"));
        assertEquals(0, run("inhabitants profile Bob"));
    }

    @Test
    void aMissingWorldIsReportedAsAnError() throws Exception {
        CommandDispatcher<CommandSourceStack> real = new CommandDispatcher<>();
        InhabitantsCommand.register(real, services::get,
                new Backends(catalog, (p, c) -> List.of(), Sender::of));
        TestSources.Capture capture = new TestSources.Capture();
        assertEquals(0, real.execute("inhabitants structure here", TestSources.level(2, capture)));
        assertTrue(capture.joined().contains("no world"), capture.joined());
    }

    @Test
    void aLinkageErrorFromAnUpstreamHelperIsContainedToo() throws Exception {
        FakeServices.FakeCatalog broken = new FakeServices.FakeCatalog() {
            @Override
            public List<SettingSpec> all() {
                throw new NoClassDefFoundError("upstream/Gone");
            }
        };
        CommandDispatcher<CommandSourceStack> d = new CommandDispatcher<>();
        InhabitantsCommand.register(d, services::get, backends(broken));
        TestSources.Capture capture = new TestSources.Capture();
        assertEquals(0, d.execute("inhabitants catalog", TestSources.level(2, capture)));
        assertTrue(capture.joined().contains("The command failed: NoClassDefFoundError"), capture.joined());
    }

    @Test
    void registrationRejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> InhabitantsCommand.register(null, services::get));
        assertThrows(NullPointerException.class, () -> InhabitantsCommand.register(new CommandDispatcher<>(), null));
    }

    // ---------------------------------------------------------------- execution

    @Test
    void infoAndTheAliasRunAndDeliverFeedbackToTheSource() throws Exception {
        assertEquals(1, run("inhabitants info"));
        assertTrue(out.joined().startsWith("PvP BOT Inhabitants 1.2.3"), out.joined());

        TestSources.Capture viaAlias = new TestSources.Capture();
        assertEquals(1, dispatcher.execute("pvpbot_inhabitants info", TestSources.level(2, viaAlias)));
        assertEquals(out.joined(), viaAlias.joined());
    }

    @Test
    void theBareCommandListsTheSubcommands() throws Exception {
        assertEquals(1, run("inhabitants"));
        String t = out.joined();
        for (String sub : List.of("info", "adapter", "structure here", "nearby", "process nearest", "reset here",
                "reset structure", "profile", "catalog", "reload")) {
            assertTrue(t.contains("/inhabitants " + sub), sub + " missing from:\n" + t);
        }
        TestSources.Capture alias = new TestSources.Capture();
        assertEquals(1, dispatcher.execute("pvpbot_inhabitants", TestSources.level(2, alias)));
        assertTrue(alias.joined().contains("/pvpbot_inhabitants info"), alias.joined());
    }

    @Test
    void adapterCommandPrintsTheAdapterReport() throws Exception {
        assertEquals(1, run("inhabitants adapter"));
        assertTrue(out.joined().startsWith("PvP BOT adapter"), out.joined());
    }

    @Test
    void nearbyUsesTheDefaultRadiusOrTheGivenOne() throws Exception {
        fs.population.nearbyResult = List.of();
        assertEquals(0, run("inhabitants nearby"));
        assertEquals(CommandArgs.DEFAULT_NEARBY_RADIUS, fs.population.lastRadius);
        assertEquals(6, fs.population.lastChunkX);
        assertEquals(-2, fs.population.lastChunkZ);
        assertEquals(0, run("inhabitants nearby 20"));
        assertEquals(20, fs.population.lastRadius);
    }

    @Test
    void nearbyRejectsOutOfRangeAndNonNumericRadii() {
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants nearby 0"));
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants nearby 65"));
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants nearby many"));
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants nearby 5 extra"));
    }

    @Test
    void structureHereQueriesTheLocatorAtTheSenderBlock() throws Exception {
        assertEquals(0, run("inhabitants structure here"));
        assertEquals(new net.minecraft.core.BlockPos(100, 64, -21), fs.locator.lastAtPos);

        fs.locator.at = List.of(Fixtures.snapshot("minecraft:mansion", 6, -2, true));
        assertEquals(1, run("inhabitants structure here"));
        assertTrue(out.joined().contains("minecraft:mansion"), out.joined());
    }

    @Test
    void processNearestDefaultsToRollAndAcceptsEachMode() throws Exception {
        fs.locator.near = List.of(Fixtures.snapshot("minecraft:x", 0, 0, true));
        assertEquals(1, run("inhabitants process nearest"));
        assertEquals(1, run("inhabitants process nearest roll"));
        assertEquals(1, run("inhabitants process nearest occupied"));
        assertEquals(1, run("inhabitants process nearest abandoned"));
        assertEquals(List.of(ForceMode.ROLL, ForceMode.ROLL, ForceMode.OCCUPIED, ForceMode.ABANDONED),
                fs.engine.processCalls.stream().map(c -> c.mode()).toList());
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants process nearest sometimes"));
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants process"));
    }

    @Test
    void resetHereAndNearestPassTheRemoveBotsFlag() throws Exception {
        StructureKey key = Fixtures.key("minecraft:village_plains", 5, 5);
        fs.locator.at = List.of(Fixtures.snapshot("minecraft:village_plains", 5, 5, true));
        fs.population.records.put(key, Fixtures.synthesizedAbandoned());

        assertEquals(1, run("inhabitants reset here"));
        assertEquals(1, run("inhabitants reset here removeBots"));
        assertEquals(1, run("inhabitants reset nearest"));
        assertEquals(1, run("inhabitants reset nearest removeBots"));

        assertEquals(List.of(new ResetCall(key, false), new ResetCall(key, true), new ResetCall(key, false),
                new ResetCall(key, true)), fs.engine.resetCalls);
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants reset here everything"));
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants reset"));
    }

    @Test
    void resetStructureParsesIdentifierChunksAndFlag() throws Exception {
        assertEquals(1, run("inhabitants reset structure minecraft:village_plains 12 -5"));
        assertEquals(1, run("inhabitants reset structure somemod:deep/tower -1 0 removeBots"));
        assertEquals(1, run("inhabitants reset structure village_desert 0 0"));

        assertEquals(List.of(
                new ResetCall(new StructureKey(Fixtures.OVERWORLD, "minecraft:village_plains", 12, -5), false),
                new ResetCall(new StructureKey(Fixtures.OVERWORLD, "somemod:deep/tower", -1, 0), true),
                new ResetCall(new StructureKey(Fixtures.OVERWORLD, "minecraft:village_desert", 0, 0), false)),
                fs.engine.resetCalls);
    }

    @Test
    void resetStructureRejectsIncompleteOrOutOfRangeArguments() {
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants reset structure"));
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants reset structure minecraft:x"));
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants reset structure minecraft:x 1"));
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants reset structure minecraft:x 1 two"));
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants reset structure minecraft:x 99999999 0"));
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants reset structure minecraft:x -99999999 0"));
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants reset structure Bad!Id 1 2"));
        assertTrue(fs.engine.resetCalls.isEmpty());
    }

    @Test
    void resetOfAnUnknownStructureReturnsZeroWithAnExplanation() throws Exception {
        fs.engine.resetResult = false;
        assertEquals(0, run("inhabitants reset structure minecraft:village_plains 1 2"));
        assertTrue(out.joined().contains("Nothing to reset"), out.joined());
    }

    @Test
    void profileFindsAnInhabitantAndReportsUnknownOnes() throws Exception {
        BotRecord bot = Fixtures.bot(0, "Inh_Steve", BotState.SPAWNED);
        bot.profile = Fixtures.profile();
        fs.population.records.put(Fixtures.key("minecraft:pillager_outpost", 4, 7), Fixtures.populated(bot));

        assertEquals(1, run("inhabitants profile inh_steve"));
        assertTrue(out.joined().startsWith("Inhabitant Inh_Steve"), out.joined());
        assertTrue(out.joined().contains("rendered guard"), out.joined());

        TestSources.Capture unknown = new TestSources.Capture();
        assertEquals(0, dispatcher.execute("inhabitants profile Nobody", TestSources.level(2, unknown)));
        assertTrue(unknown.joined().contains("No inhabitant named 'Nobody'"), unknown.joined());

        assertThrows(CommandSyntaxException.class, () -> run("inhabitants profile"));
    }

    @Test
    void catalogSummaryCategoryAndUnknownCategory() throws Exception {
        assertEquals(1, run("inhabitants catalog"));
        assertTrue(out.joined().contains("Setting catalog: 2 PvP BOT settings"), out.joined());

        TestSources.Capture category = new TestSources.Capture();
        assertEquals(1, dispatcher.execute("inhabitants catalog global_only", TestSources.level(2, category)));
        assertTrue(category.joined().startsWith("GLOBAL_ONLY: 1 setting"), category.joined());

        TestSources.Capture bogus = new TestSources.Capture();
        assertEquals(0, dispatcher.execute("inhabitants catalog bogus", TestSources.level(2, bogus)));
        assertTrue(bogus.joined().contains("Unknown category 'bogus'"), bogus.joined());
    }

    @Test
    void reloadRunsTheReloaderAndPrintsItsMessages() throws Exception {
        fs.reloadMessages = List.of("commandPermissionLevel 9 -> 4");
        assertEquals(1, run("inhabitants reload"));
        assertEquals(1, fs.reloads);
        assertTrue(out.joined().contains("Config reloaded with 1 message"), out.joined());
        assertTrue(out.joined().contains("commandPermissionLevel 9 -> 4"), out.joined());
    }

    @Test
    void unknownSubcommandsAndTrailingJunkAreSyntaxErrors() {
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants bogus"));
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants info extra"));
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants structure"));
        assertThrows(CommandSyntaxException.class, () -> run("inhabitants reload now"));
    }

    // ---------------------------------------------------------------- completion

    @Test
    void theRootCompletesEverySubcommand() throws Exception {
        assertEquals(Set.of("info", "adapter", "structure", "nearby", "process", "reset", "profile", "catalog", "reload"),
                Set.copyOf(complete("inhabitants ")));
        assertEquals(Set.of("info"), Set.copyOf(complete("inhabitants inf")));
    }

    @Test
    void optionalWordsCompleteToo() throws Exception {
        assertEquals(Set.of("roll", "occupied", "abandoned"), Set.copyOf(complete("inhabitants process nearest ")));
        assertEquals(Set.of("here", "nearest", "structure"), Set.copyOf(complete("inhabitants reset ")));
        assertEquals(Set.of("removeBots"), Set.copyOf(complete("inhabitants reset here ")));
        assertEquals(Set.of("removeBots"), Set.copyOf(complete("inhabitants reset nearest ")));
        assertEquals(Set.of("here"), Set.copyOf(complete("inhabitants structure ")));
        assertEquals(Set.of("per_bot_randomizable", "global_only", "admin_operational", "unsupported"),
                Set.copyOf(complete("inhabitants catalog ")));
        assertEquals(List.of("global_only"), complete("inhabitants catalog gl"));
    }

    @Test
    void botNamesCompleteFromTheStructuresNearTheSender() throws Exception {
        fs.population.records.put(Fixtures.key("minecraft:a", 6, -2), Fixtures.populated(
                Fixtures.bot(0, "Inh_Alice", BotState.SPAWNED), Fixtures.bot(1, "Inh_Bob", BotState.SPAWNED)));
        fs.population.records.put(Fixtures.key("minecraft:b", 7, -2), Fixtures.populated(
                Fixtures.bot(0, "Inh_Carol", BotState.SPAWNED)));

        assertEquals(Set.of("Inh_Alice", "Inh_Bob", "Inh_Carol"), Set.copyOf(complete("inhabitants profile ")));
        assertEquals(List.of("Inh_Bob"), complete("inhabitants profile inh_b"));
        assertEquals(List.of("Inh_Carol"), complete("inhabitants profile car"));
        assertEquals(CommandArgs.SUGGEST_RADIUS, fs.population.lastRadius);
    }

    @Test
    void botNameCompletionIsEmptyNotAnErrorWhenThePopulationFails() throws Exception {
        fs.population.failure = new IllegalStateException("store closed");
        assertEquals(List.of(), complete("inhabitants profile "));
        services.set(null);
        assertEquals(List.of(), complete("inhabitants profile "));
    }

    @Test
    void chunkCoordinatesCompleteFromKnownStructuresOfTheTypedId() throws Exception {
        fs.population.records.put(Fixtures.key("minecraft:village_plains", 12, -5), Fixtures.synthesizedAbandoned());
        fs.population.records.put(Fixtures.key("minecraft:village_plains", 12, 9), Fixtures.synthesizedAbandoned());
        fs.population.records.put(Fixtures.key("minecraft:village_plains", 40, 7), Fixtures.synthesizedAbandoned());
        fs.population.records.put(Fixtures.key("minecraft:mansion", 99, 99), Fixtures.synthesizedAbandoned());

        assertEquals(Set.of("12", "40"),
                Set.copyOf(complete("inhabitants reset structure minecraft:village_plains ")));
        assertEquals(Set.of("-5", "9"),
                Set.copyOf(complete("inhabitants reset structure minecraft:village_plains 12 ")));
        assertEquals(List.of("6"), complete("inhabitants reset structure minecraft:unknown_thing "),
                "an id without records offers the sender's own chunk");
    }

    @Test
    void structureIdCompletionWithoutAServerYieldsNothingInsteadOfThrowing() throws Exception {
        assertEquals(List.of(), complete("inhabitants reset structure "));
    }

    @Test
    void theRequirementAlsoGuardsCompletion() throws Exception {
        ParseResults<CommandSourceStack> parse = dispatcher.parse("inhabitants profile ", TestSources.level(1, out));
        List<String> suggestions = dispatcher.getCompletionSuggestions(parse).get().getList().stream()
                .map(Suggestion::getText).toList();
        assertEquals(List.of(), suggestions);
    }

    @Test
    void theStandardBackendsExist() {
        Backends standard = Backends.standard();
        assertNotNull(standard.catalog());
        assertNotNull(standard.profiles());
        assertNotNull(standard.senders());
    }
}
