package io.github.zoyluo.minecraftai.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Real player phrasings against the real tool list: which tools the model is not offered for each.
 * Everything a carried stack could satisfy is withheld exactly when the player asks for new raw
 * resources, and nothing else is.
 */
final class ChatRoutingPhraseTableTest {
    /** What a phrasing withholds. */
    private enum Route {
        /** Nothing: a handoff of carried stock, a crafted output, a command or a question. */
        FREE(Set.of()),
        /** New raw resources: no carried stack may satisfy it. */
        COLLECT(Set.of("give_item", "achieve_goal")),
        /** New raw resources handed over: only gather_then_give / fulfill_items start the collection. */
        COLLECT_AND_GIVE(Set.of("give_item", "achieve_goal", "gather", "assign_task", "forage", "mine_ore",
                "mine_valuables_in_radius", "harvest_crop", "mine_block")),
        /**
         * New raw resources plus a crafted result, or a handoff of only part of them: gather_then_give would
         * hand over everything it collected, so collect first and craft / hand over afterwards.
         */
        COLLECT_FIRST(Set.of("give_item", "achieve_goal", "gather_then_give", "fulfill_items"));

        private final Set<String> withheld;

        Route(Set<String> withheld) {
            this.withheld = withheld;
        }
    }

    private record Phrase(String text, Route route) {
    }

    private static Phrase free(String text) {
        return new Phrase(text, Route.FREE);
    }

    private static Phrase collect(String text) {
        return new Phrase(text, Route.COLLECT);
    }

    private static Phrase collectAndGive(String text) {
        return new Phrase(text, Route.COLLECT_AND_GIVE);
    }

    private static Phrase collectFirst(String text) {
        return new Phrase(text, Route.COLLECT_FIRST);
    }

    private static final Set<String> PLAYERS = Set.of("Steve", "JackNotInTheBox", "Moss");

    private static final List<Phrase> PHRASES = List.of(
            // --- new raw resources, plain wording
            collect("get 32 logs"),
            collect("gather 32 logs"),
            collect("collect 64 cobblestone"),
            collect("mine some coal"),
            collect("chop 10 trees"),
            collect("get me 64 cobblestone"),
            collect("grab 32 logs"),
            collect("fetch me 32 logs"),
            collect("cut 32 logs"),
            collect("cut down 10 trees"),
            collect("fell 32 trees"),
            collect("pick up 32 logs"),
            collect("dig 20 dirt"),
            collect("I need 32 logs"),
            collect("i want 16 coal"),
            collect("mine me 32 stone"),
            collect("start gathering logs"),
            collect("keep collecting coal"),
            collect("stock up on logs"),
            collect("get thirty-two logs"),
            collect("gather a stack of oak logs"),
            collect("go mining for diamonds"),
            collect("harvest some wheat"),
            collect("get some wheat"),
            collect("get some oak wood"),
            collect("get me a stack of cobble"),
            collect("gather 4 blaze rods"),
            collect("get 10 blaze rods"),
            collect("mine 10 iron"),
            collect("get 10 raw iron"),
            collect("obtain 20 sand"),
            collect("acquire some gravel"),
            collect("forage 16 sweet berries"),
            collect("get 64 stone"),
            collect("get 5 obsidian"),
            collect("mine 32 diamonds"),
            collect("mine the whole vein"),
            collect("gather logs"),
            collect("Moss, please gather 32 logs"),
            collect("hey moss could you chop 10 logs please"),
            collect("can you gather 32 logs?"),
            collect("could you mine some coal"),
            collect("would you get me 10 iron"),
            collect("do you mind getting me 32 logs"),
            collect("are you able to gather 32 logs?"),
            collect("you need to gather 32 logs"),
            collect("get 32 logs, then get over here"),
            collect("get 32 logs, then get over here and wait"),
            // --- a handoff of what the bot carries
            free("give me 32 logs"),
            free("give me your logs"),
            free("give me some logs"),
            free("can you hand me your logs?"),
            free("hand me 10 coal"),
            free("bring me the logs you collected"),
            free("get me the logs you are holding"),
            free("get me the logs you're holding"),
            free("get me your logs"),
            free("can you get me the iron you mined"),
            free("give me what you have"),
            free("give me everything"),
            free("give me all your stuff"),
            free("bring me the logs you have"),
            free("give me the iron you mined"),
            free("give me mine back"),
            free("give me mine now"),
            free("give 32 logs to me"),
            free("give 16 logs to Steve"),
            free("drop your logs here"),
            free("toss me 10 coal"),
            free("pass me the sword"),
            free("throw me some bread"),
            free("give me 32 logs and 16 coal"),
            free("give me your iron pickaxe and sword"),
            free("give me a pork chop"),
            free("give me the harvest"),
            // ambiguous: "bring me N X" can mean "fetch" or "hand over"; nothing is withheld so a carried
            // stack can be handed over and the model can still collect when it carries none
            free("bring me 10 oak logs"),
            free("bring me 32 logs"),
            // --- movement words around a handoff
            free("get over here and give me 32 logs"),
            free("get to me, then hand me your logs"),
            free("get out and give me your logs"),
            free("get in the boat and give me your logs"),
            free("get ready and give me the logs"),
            free("get going then give me your logs"),
            free("get off the boat and give me 10 logs"),
            free("get near me and hand me your logs"),
            // --- a place or thing that merely shares a verb's spelling
            free("go to the farm and give me 32 wheat"),
            free("go to my farm and bring me wheat"),
            free("meet me at the farm and give me your logs"),
            free("walk to the mine and give me your coal"),
            // --- negation
            free("don't collect any more, just give me the logs"),
            free("dont gather logs, give me the ones you have"),
            free("stop gathering and give me the logs"),
            free("no more mining, give me your coal"),
            free("do not chop any trees"),
            free("never mine diamonds"),
            free("instead of gathering, give me your logs"),
            free("without mining give me the coal you have"),
            // --- questions about what happened or what is carried
            free("how many logs did you gather?"),
            free("did you get 32 logs?"),
            free("what did you mine?"),
            free("have you collected the logs?"),
            free("how many logs do you have"),
            free("do you have 32 logs?"),
            free("is there any wood nearby?"),
            free("where can i mine iron"),
            free("hey moss, how many logs did you gather"),
            // --- crafted outputs are not raw resources
            free("collect an iron pickaxe"),
            free("craft 4 sticks"),
            free("get 4 crafting tables"),
            free("obtain iron ingots"),
            free("get iron ingots"),
            free("obtain 10 iron ingots"),
            free("get 8 torches"),
            free("get 16 sticks"),
            free("get 4 oak planks"),
            free("get iron armor"),
            free("get stone tools"),
            free("get stone bricks"),
            free("get 5 iron blocks"),
            free("get 9 iron nuggets"),
            free("get me 3 beds"),
            free("make an iron pickaxe"),
            free("make me an iron pickaxe"),
            free("get a diamond sword"),
            free("acquire a diamond sword"),
            free("gather a diamond sword"),
            free("get 32 iron pickaxes"),
            free("make yourself a pickaxe and make me one"),
            free("get me a full set of iron armor"),
            free("smelt 10 iron"),
            free("cook the beef"),
            free("get me a bucket"),
            free("get a furnace"),
            free("get me bread"),
            free("craft a chest and give it to me"),
            // --- lines the player really typed to the bot (session logs)
            collect("moss get 32 logs"),
            collect("Moss chop some woof"),
            collect("gather 64 dirt"),
            collect("gather leaves"),
            collect("lets go get 32 wood"),
            collect("moss, mine some coal"),
            collect("moss lets mine some coal"),
            collect("mine this iron ore"),
            collect("mine the entire iron ore vein"),
            collectFirst("lets go mine some coal , need more torches"),
            free("get us both a full set of stone tools"),
            free("moss, get full stone tool set for both u and me"),
            free("moss make full cobblestone tool set for both of us, one set for you that you keep and give one set for me"),
            free("get in boat"),
            free("get out of boat"),
            free("in minecraft is hay best gather with shovel?"),
            free("u need to swim in water to follow me, we crossing water"),
            free("break 32 leaves"),
            free("what does sculk block give"),
            free("theres coal here, you missed some?"),
            free("eat till full"),
            free("moss find the bonus chest"),
            // --- other commands and chatter
            free("follow me"),
            free("come here"),
            free("stay here"),
            free("hello moss"),
            free("build a house"),
            free("find iron"),
            free("show me the ore"),
            free("what are you doing"),
            free("eat something"),
            free("go to the farm"),
            // --- collect and hand over, with a stated number
            collectAndGive("gather 32 logs and give them to me"),
            collectAndGive("gather 32 logs, then give them to me"),
            collectAndGive("get 32 logs and bring them to me"),
            collectAndGive("gather and hand me 32 logs"),
            collectAndGive("chop 10 logs and hand them over"),
            collectAndGive("mine 10 coal and give it to me"),
            collectAndGive("mine 10 iron and give them to me"),
            collectAndGive("collect 64 cobblestone and drop it here"),
            collectAndGive("gather 20 logs and bring them back"),
            collectAndGive("gather 32 logs and give me 32"),
            collectAndGive("gather 32 logs and give me all of them"),
            collectAndGive("gather 32 logs, give me them"),
            collectAndGive("gather 32 logs and send them to me"),
            collectAndGive("gather 32 logs and toss them to me"),
            collectAndGive("I need 32 logs, give them to me"),
            collectAndGive("fetch 32 logs and deliver them to Steve"),
            collectAndGive("get a log and give it to me"),
            collectAndGive("gather 32 logs and give them to Steve"),
            collectAndGive("get 20 logs and hand them to JackNotInTheBox"),
            collectAndGive("gather 32 logs with an iron pickaxe, then hand them to me"),
            // --- collect and hand over, but no way for gather_then_give to know the number
            collect("get wood and give it to me"),
            collect("gather some wood and give it to me"),
            collect("harvest the wheat and give it to me"),
            // --- "give" that is not a handoff of the collected stack
            collect("gather 32 logs and bring them to the chest"),
            collect("gather 32 logs and drop them in the chest"),
            collect("gather 32 logs and give them to the chest"),
            collect("gather 32 logs and give them to Bob"),
            collect("gather 32 logs and give me a status update"),
            collect("gather 32 logs and give me your coal"),
            collect("gather 32 logs, then give me the logs you have"),
            // --- collect, then craft
            collectFirst("gather 32 logs then craft a table and give it to me"),
            collectFirst("gather 32 logs, craft an iron pickaxe, then give it to me"),
            collectFirst("chop 32 logs, craft a table, and hand it to me"),
            collectFirst("gather 32 logs and make me a chest"),
            collectFirst("mine 10 coal and craft 4 torches"),
            collectFirst("get 20 logs and make me a crafting table"),
            collectFirst("make an iron pickaxe and mine 10 diamonds"),
            collectFirst("gather 32 logs and give me a table"),
            collectFirst("gather 32 logs, then collect an iron pickaxe"),
            // --- handing over only part of the collection: gather_then_give would hand over all of it
            collectFirst("gather 32 logs and give 16 to me"),
            collectFirst("gather 32 logs and give me half"),
            collectFirst("gather 32 logs, then give half to me"),
            collectFirst("mine 10 coal and give Steve half"),
            collectFirst("chop 20 logs and hand me 5"),
            collectFirst("gather a stack of logs and give me a dozen"),
            collectFirst("gather 32 logs and give me some"),
            // --- suggestions and conditions in question shape still ask for work
            collect("how about gathering 32 logs"),
            collect("how about you gather 32 logs"),
            collect("what about getting 32 logs"),
            collect("why dont you gather some logs"),
            collect("why don't you mine 10 coal"),
            collect("why not chop 10 logs"),
            collect("do you think you could gather 32 logs"),
            collect("do you think you can mine some coal?"),
            collect("is it possible to gather 32 logs"),
            collect("is it possible for you to chop 10 logs?"),
            collect("when you get a chance, gather 32 logs"),
            collect("when you can gather 32 logs"),
            // a question word only opens the question's own part: a later request after a comma still counts
            collect("how many logs do you have, gather 32 more"),
            collect("what are you doing, mine some coal"),
            // --- but these remain questions
            free("what about the logs you have?"),
            free("how about the logs you gathered"),
            free("when did you gather the logs"),
            free("when will you mine the coal?"),
            free("why didnt you gather the logs"),
            free("do you think this is the best spot to mine?"),
            free("do you think you gathered enough logs"),
            free("is it possible that you mined the coal"),
            free("how much coal did you mine, give it to me"),
            // --- negation reaches the verb it governs, not the next command
            collect("dont get logs get stone"),
            collect("don't mine coal mine iron"),
            collect("dont get logs, get stone"),
            collect("no problem gather 32 logs"),
            collect("no worries mine some coal"),
            free("i dont need to gather logs"),
            free("dont want you to mine coal"),
            free("no need to gather logs, give me the ones you have"),
            // --- natural blocks a recipe also makes are collected like any other resource
            collect("mine 64 andesite"),
            collect("dig 20 clay"),
            collect("mine some glowstone"),
            collect("gather 20 sandstone"),
            collect("get 10 granite"),
            collect("collect 16 diorite"),
            collect("get 10 snow blocks"),
            collect("get 16 red sandstone"),
            // --- words no tool can fetch are not a collection request after a soft verb
            free("get me 3 steaks"),
            free("get 5 steaks"),
            free("i need 20 xp"),
            free("get 3 levels"));

    @BeforeAll
    static void bootstrap() {
        RegistryBootstrap.ensure();
    }

    private static Set<String> withheldFor(String text) {
        ToolRouting routing = new ToolRouting();
        routing.beginInstruction(RequestIntent.parse(text, PLAYERS));
        return routing.withheldTools();
    }

    @Test
    void everyPhrasingWithholdsExactlyTheToolsACarriedStackCouldSatisfy() {
        List<String> mismatches = new ArrayList<>();
        for (Phrase phrase : PHRASES) {
            Set<String> actual = withheldFor(phrase.text());
            if (!actual.equals(phrase.route().withheld)) {
                mismatches.add("'" + phrase.text() + "' expected " + phrase.route() + " "
                        + new LinkedHashSet<>(phrase.route().withheld) + " but withheld " + actual);
            }
        }
        assertTrue(mismatches.isEmpty(), mismatches.size() + " of " + PHRASES.size() + " phrasings:\n"
                + String.join("\n", mismatches));
    }

    @Test
    void theTableIsLargeEnoughToCoverEachCategory() {
        assertTrue(PHRASES.size() >= 80, "phrase table size " + PHRASES.size());
        for (Route route : Route.values()) {
            assertTrue(PHRASES.stream().filter(phrase -> phrase.route() == route).count() >= 8, route.name());
        }
    }

    @Test
    void theListOfferedToTheModelLosesExactlyTheWithheldTools() {
        List<ToolDefinition> all = new ToolRegistry().tools(null, true, true, true);
        Set<String> allNames = all.stream().map(ToolDefinition::name).collect(Collectors.toCollection(LinkedHashSet::new));
        for (String text : List.of("get 32 logs", "gather 32 logs and give them to me",
                "gather 32 logs then craft a table and give it to me", "give me 32 logs")) {
            Set<String> withheld = withheldFor(text);
            List<ToolDefinition> offered = BrainCoordinator.toolsForCall(all, false, withheld);
            Set<String> offeredNames = offered.stream().map(ToolDefinition::name).collect(Collectors.toSet());
            Set<String> missing = new LinkedHashSet<>(allNames);
            missing.removeAll(offeredNames);
            assertEquals(withheld, missing, text);
        }
    }

    @Test
    void everyWithheldToolNameIsARegisteredTool() {
        Set<String> registered = new ToolRegistry().tools(null, true, true, true).stream()
                .map(ToolDefinition::name).collect(Collectors.toSet());
        for (Route route : Route.values()) {
            Set<String> unknown = new LinkedHashSet<>(route.withheld);
            unknown.removeAll(registered);
            assertTrue(unknown.isEmpty(), route + " names tools that do not exist: " + unknown);
        }
    }

    @Test
    void theCanonicalPhrasingsKeepTheToolsThatCanServeThem() {
        assertEquals(Route.COLLECT.withheld, withheldFor("get 32 logs"),
                "gather with a count stays: it is the new quota");
        assertTrue(!withheldFor("gather 32 logs and give them to me").contains("gather_then_give"));
        assertTrue(!withheldFor("gather 32 logs and give them to me").contains("fulfill_items"));
        assertTrue(!withheldFor("mine some coal").contains("mine_ore"));
        assertTrue(withheldFor("mine some coal").contains("give_item"));
        assertTrue(!withheldFor("gather 32 logs then craft a table and give it to me").contains("gather"),
                "the raw collection of a compound request is started with gather");
        assertEquals(Set.of(), withheldFor("give me 32 logs"));
    }

    @Test
    void quantityIsTakenFromTheCollectionClauseOrItsHandoff() {
        assertTrue(RequestIntent.parse("gather a stack of oak logs").quantityStated());
        assertTrue(RequestIntent.parse("gather and hand me 32 logs").quantityStated());
        assertTrue(!RequestIntent.parse("mine some coal").quantityStated());
        assertTrue(!RequestIntent.parse("get wood and give it to me").quantityStated());
        assertTrue(RequestIntent.parse("get 32 logs and give them to me").handsOverAcquired());
        assertTrue(RequestIntent.parse("gather 32 logs then craft a table and give it to me").craftsOutcome());
        assertEquals(RequestIntent.NONE, RequestIntent.parse(""));
        assertEquals(RequestIntent.NONE, RequestIntent.parse(null));
    }

    @Test
    void aHandoffIsPartialOnlyWhenItNamesAnotherQuantityThanTheCollection() {
        assertTrue(RequestIntent.parse("gather 32 logs and give me 16").handsOverPart());
        assertTrue(RequestIntent.parse("gather 32 logs and give me half").handsOverPart());
        assertFalse(RequestIntent.parse("gather 32 logs and give me 32").handsOverPart());
        assertFalse(RequestIntent.parse("gather 32 logs and give them to me").handsOverPart());
        assertFalse(RequestIntent.parse("gather and hand me 32 logs").handsOverPart(),
                "the handoff supplies the collection's number");
        assertFalse(RequestIntent.parse("give me 16 logs").handsOverPart());
    }

    @Test
    void naturalBlocksAreStillCollectedWhenTheGamesRecipeIndexListsTheirRecipe() {
        // A running server's recipe index holds the compaction recipes (sand to sandstone, clay balls to clay)
        // that a bare test JVM does not; without the natural-block list these would read as crafted there.
        RuntimeRecipeFixture.install(Items.ANDESITE, Items.CLAY, Items.GLOWSTONE, Items.SANDSTONE,
                Items.SEA_LANTERN, Items.PISTON, Items.IRON_PICKAXE);
        try {
            for (String text : List.of("mine 64 andesite", "dig 20 clay", "mine some glowstone",
                    "gather 20 sandstone", "get 10 sandstone")) {
                assertEquals(Route.COLLECT.withheld, withheldFor(text), text);
            }
            // Not a listed natural block: "mine" cannot mean crafting, so the verb alone keeps it physical.
            assertEquals(Route.COLLECT.withheld, withheldFor("mine 10 sea lanterns"));
            // Crafted stays crafted for the verbs that can mean either, so achieve_goal remains usable.
            assertEquals(Set.of(), withheldFor("get 10 sea lanterns"));
            assertEquals(Set.of(), withheldFor("get 4 pistons"));
            assertEquals(Set.of(), withheldFor("collect an iron pickaxe"));
        } finally {
            RuntimeRecipeFixture.clear();
        }
    }
}
