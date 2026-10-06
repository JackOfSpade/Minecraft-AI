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
         * New raw resources plus a crafted result: gather_then_give would hand over the raw resource, not the
         * crafted item. fulfill_items stays (the raw quota as an allocation without a recipient), and so do the
         * plain collectors for collecting first.
         */
        CRAFT_RESULT(Set.of("give_item", "achieve_goal", "gather_then_give")),
        /**
         * A handoff of only part of the collection, or a crafted result the bot keeps: gather_then_give would hand over
         * everything it collected, and fulfill_items cannot keep the rest beside the part (nor does it make a second
         * tool for the bot), so collect first and craft or hand the part over afterwards.
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

    private static Phrase craftResult(String text) {
        return new Phrase(text, Route.CRAFT_RESULT);
    }

    private static Phrase partialHandoff(String text) {
        return new Phrase(text, Route.COLLECT_FIRST);
    }

    private static Phrase keptResult(String text) {
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
            keptResult("lets go mine some coal , need more torches"),
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
            craftResult("gather 32 logs then craft a table and give it to me"),
            craftResult("gather 32 logs, craft an iron pickaxe, then give it to me"),
            craftResult("chop 32 logs, craft a table, and hand it to me"),
            craftResult("gather 32 logs and make me a chest"),
            keptResult("mine 10 coal and craft 4 torches"),
            craftResult("get 20 logs and make me a crafting table"),
            keptResult("make an iron pickaxe and mine 10 diamonds"),
            craftResult("gather 32 logs and give me a table"),
            keptResult("gather 32 logs, then collect an iron pickaxe"),
            // --- handing over only part of the collection: gather_then_give would hand over all of it
            partialHandoff("gather 32 logs and give 16 to me"),
            partialHandoff("gather 32 logs and give me half"),
            partialHandoff("gather 32 logs, then give half to me"),
            partialHandoff("mine 10 coal and give Steve half"),
            partialHandoff("chop 20 logs and hand me 5"),
            partialHandoff("gather a stack of logs and give me a dozen"),
            partialHandoff("gather 32 logs and give me some"),
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
            free("get 3 levels"),
            // === second round: lines that are only a quantity and a resource
            collect("32 logs pls"),
            collect("32 logs"),
            collect("64 cobblestone please"),
            collect("10 coal pls"),
            collect("a stack of logs pls"),
            collect("x32 logs"),
            collect("logs x32"),
            collect("some wood pls"),
            collect("wood pls"),
            collect("logs plz"),
            collect("more logs"),
            collect("32 iron"),
            collect("10 diamonds"),
            collect("64 dirt"),
            collect("3 wheat"),
            collect("32 logs, thanks"),
            collect("moss 32 logs pls"),
            collect("Moss, 32 logs please"),
            collect("32 oak logs plz"),
            collect("16 coal asap"),
            collect("64 cobble pls"),
            collect("32 logs and 10 coal pls"),
            collect("hey moss 20 sand"),
            collect("a few logs pls"),
            collect("another stack of cobblestone"),
            collect("a couple of diamonds pls"),
            free("32 logs?"),
            free("logs"),
            free("wood"),
            free("iron"),
            free("thanks for the 32 logs"),
            free("i have 32 logs"),
            free("there are 32 logs here"),
            free("32 logs is a lot"),
            free("32"),
            free("pls"),
            free("64 iron pickaxes pls"),
            free("3 blocks"),
            free("5 min"),
            free("2 hours"),
            free("diamond"),
            free("the logs pls"),
            free("my 32 logs"),
            free("32 logs would be nice"),
            free("wow 64 diamonds"),
            free("thx for the wood"),
            // === a negation that governs several verbs ("or", "and", "nor")
            free("dont gather or mine anything"),
            free("stop mining and chopping trees"),
            free("don't mine or chop trees"),
            free("no mining or chopping"),
            free("never gather or mine"),
            free("stop mining and chopping"),
            free("dont mine coal and iron"),
            free("do not gather logs or mine coal"),
            free("stop gathering and mining, give me the logs"),
            free("stop mining and give me your coal"),
            free("dont mine and dont chop"),
            free("stop mining or chopping please"),
            free("please dont gather or chop anything"),
            free("dont collect or harvest any wheat"),
            free("do not mine nor chop"),
            free("no more gathering or mining"),
            free("stop all mining and gathering"),
            free("forget mining and chopping"),
            free("dont get wood or stone"),
            free("dont gather logs or coal"),
            free("stop collecting logs and coal"),
            free("i dont want you to gather or mine"),
            free("stop hunting and mining"),
            free("dont kill or mine anything"),
            // ...but a contrast, a comma or a command in another form is a new request
            collect("dont gather logs, mine coal"),
            collect("don't gather logs but mine coal"),
            collect("stop chopping, start mining coal"),
            collect("stop mining coal and chop some trees"),
            collect("stop gathering logs and mine some coal"),
            collect("dont mine, gather 32 logs"),
            collect("stop mining. get 32 logs"),
            // === hunting and what creatures drop
            free("hunt 5 cows"),
            free("kill 10 zombies"),
            free("kill the sheep"),
            free("go hunting"),
            free("start hunting"),
            free("hunt"),
            free("kill 5 cows and cook the beef"),
            free("hunt 5 cows and cook them"),
            free("bring me 8 gunpowder"),
            free("give me the beef you have"),
            free("give me your leather"),
            free("kill the zombie that is chasing you"),
            free("kill it"),
            free("kill all the mobs"),
            free("hunt down the creeper"),
            free("how many cows did you kill"),
            free("did you kill the zombie"),
            free("stop killing the cows"),
            free("dont hunt the pigs"),
            collect("hunt for meat"),
            collect("hunt for food"),
            collect("kill skeletons for bones"),
            collect("kill sheep for wool"),
            collect("kill 4 spiders for string"),
            collect("kill a cow and give me the beef"),
            collect("hunt some pigs and give me the pork"),
            collect("slaughter 3 pigs and bring me the meat"),
            collect("butcher some cows and give me the leather"),
            collect("kill 5 sheep and bring me the wool"),
            collect("hunt cows for leather"),
            collect("farm 20 rotten flesh"),
            collect("farm bones"),
            collect("farm drops"),
            collect("get me 10 feathers"),
            collect("get 5 beef"),
            collect("kill a creeper and get me the gunpowder"),
            // === several resources in one request
            collect("get 32 logs and 10 coal"),
            collect("gather logs and coal"),
            collect("gather logs, coal and iron"),
            collect("mine 10 iron and some coal"),
            collect("get me logs, stone and dirt"),
            collect("collect 64 cobblestone and 32 dirt"),
            collect("chop 10 trees and mine 5 coal"),
            collect("gather logs, then mine coal"),
            collect("gather some logs and also some coal"),
            collect("mine coal, iron and gold"),
            collect("get 10 wheat and 10 carrots"),
            collect("i need logs and coal"),
            free("give me logs and coal"),
            free("give me your logs and coal"),
            // === collect, craft, hand over: the raw quota goes through fulfill_items
            keptResult("get 32 logs and a pickaxe"),
            keptResult("gather 32 logs and craft a table"),
            craftResult("gather logs, craft a chest and give me it"),
            keptResult("mine 10 iron and make an iron pickaxe"),
            keptResult("gather 20 logs and craft sticks"),
            keptResult("get 10 coal and 4 torches"),
            keptResult("mine 5 iron and smelt it"),
            keptResult("gather 20 logs and make a chest"),
            keptResult("chop 10 logs and craft 40 planks"),
            keptResult("farm 10 wheat and bake bread"),
            craftResult("mine 5 iron, smelt it and give it to me"),
            // === natural resources a recipe also makes: collected, like any other resource
            collect("collect 10 white wool"),
            collect("gather 20 white wool"),
            collect("get 5 white wool"),
            collect("mine 5 melons"),
            collect("gather 4 melons"),
            collect("harvest 5 melon"),
            collect("get 5 melons"),
            collect("collect 8 sea lanterns"),
            collect("get 8 sea lanterns"),
            collect("collect 16 prismarine"),
            collect("get 16 prismarine"),
            collect("gather 10 terracotta"),
            collect("get 10 terracotta"),
            collect("collect 5 coarse dirt"),
            collect("get 10 leather"),
            collect("farm leather"),
            collect("get 5 magma cream"),
            collect("collect 20 snow"),
            collect("get 10 mossy cobblestone"),
            collect("get 8 honeycomb"),
            collect("gather 32 stone"),
            collect("gather 64 stone"),
            collect("get 20 red wool"),
            // === ...and what is made from them is not
            free("get 3 hay blocks"),
            free("gather 3 hay blocks"),
            free("get 10 slime blocks"),
            free("gather 16 prismarine bricks"),
            free("gather 20 stone bricks"),
            free("get a bed"),
            free("get 5 shears"),
            free("get me a chest"),
            free("get 3 buckets"),
            free("gather a diamond sword"),
            free("gather 10 planks"),
            free("collect 64 sticks"),
            free("collect 4 torches"),
            free("gather 16 iron ingots"),
            free("collect 9 iron nuggets"),
            free("get a crafting table"),
            free("gather 8 furnaces"),
            free("collect 3 iron helmets"),
            // === more wordings of a request to collect
            collect("please start mining coal"),
            collect("can you go chop some trees"),
            collect("i'd like 32 logs"),
            collect("we need 64 cobblestone"),
            collect("moss go get me 10 coal"),
            collect("go gather 32 logs"),
            collect("gonna need 10 iron"),
            collect("need 32 logs asap"),
            collect("get to chopping trees"),
            collect("time to mine some iron"),
            collect("can you gather?"),
            collect("harvest the wheat"),
            collect("go mining"),
            collect("keep chopping"),
            collect("collect leaves"),
            collect("get me some sand"),
            collect("i want 5 emeralds"),
            // an absolute target: what is already carried counts towards it
            free("mine until you have 20 coal"),
            // === more handoffs of carried stock
            free("give Steve 32 logs"),
            free("send me 10 coal"),
            free("hand over the logs"),
            free("drop the logs here"),
            free("can you share your coal"),
            free("donate your iron to Steve"),
            free("toss me the pickaxe"),
            free("pass me 5 logs"),
            free("give me the 32 logs you gathered"),
            free("hand me what you mined"),
            free("give me all the coal"),
            free("could you give me some sand"),
            // === questions and talk about collecting
            free("can you mine?"),
            free("what can you mine?"),
            free("do you have coal?"),
            free("is this coal?"),
            free("where is the nearest forest?"),
            free("are you mining?"),
            free("what are you collecting?"),
            free("how long to gather 32 logs?"),
            free("i will mine some coal myself"),
            free("ill gather the logs myself"),
            free("im going to chop some trees"),
            free("i can mine that"),
            free("thanks"),
            free("good job"),
            free("nice"),
            free("lol"),
            free("wait"),
            free("come back"),
            free("whats the plan"),
            // === collect and hand over, number or not
            collectAndGive("mine 10 coal and hand it to me"),
            collectAndGive("collect 20 sand and give it to me"),
            collectAndGive("fetch 16 logs and bring them here"),
            collectAndGive("chop 8 logs, then give them to me"),
            collectAndGive("gather 5 logs and drop them here"),
            collectAndGive("dig 10 dirt and give it to me"),
            collect("mine some coal and give it to me"),
            collect("gather some logs and hand them to me"),
            collect("chop logs and bring them to me"),
            // === handing over part of the collection
            partialHandoff("collect 20 sand and give me 5"),
            partialHandoff("mine 10 coal and give me 4"),
            partialHandoff("gather 64 logs and give me a stack"),
            partialHandoff("chop 20 logs and give me half"),
            // === the remaining wordings of the routing audit (chat routing review)
            collect("gather 32 logs and bring them to base"),
            collectAndGive("gather 32 logs and drop them here"),
            collectAndGive("gather 32 logs and give me 32 of them"),
            collectAndGive("chop 32 logs and give me 'em"),
            collectAndGive("gather 32 logs and give me those"),
            collectAndGive("get 64 cobblestone and give it to me"),
            collect("chop some wood and give it to me"),
            collect("get 32 logs, then give me your 10 coal"),
            partialHandoff("gather 32 logs, give 16 to me"),
            free("get 3 iron ingots"),
            free("get me some sticks"),
            free("get me the logs you collected"),
            free("give 16 to Steve and 16 to me"),
            // === everyday wordings, slang, thanks and talk around collecting
            free("gimme 32 logs"),
            free("gib me wood"),
            collect("get me wood pls"),
            collect("chop wood"),
            collect("chop trees"),
            collect("mine stone"),
            collect("mine some stone"),
            free("break some stone"),
            free("break 10 blocks of stone"),
            collect("wood please"),
            free("can i have 32 logs"),
            collect("can i get 32 logs"),
            collect("i need wood"),
            collect("need wood"),
            collect("we need wood"),
            free("wood needed"),
            collect("collect all the wood"),
            collect("gather all wood"),
            collect("chop down that tree"),
            free("mine that"),
            free("mine this"),
            collect("mine that iron"),
            collect("mine the coal over there"),
            free("dig down"),
            free("dig a hole"),
            collect("dig for diamonds"),
            free("build a farm"),
            free("start a farm"),
            collect("farm wheat"),
            collect("harvest crops"),
            collect("collect the eggs"),
            free("milk the cow"),
            free("shear the sheep"),
            collect("shear sheep for wool"),
            collect("shear 3 sheep and give me the wool"),
            free("lets go"),
            free("stay"),
            free("come"),
            free("what do you need"),
            free("do you need wood"),
            free("you need to eat"),
            free("you have 32 logs"),
            free("you have logs"),
            free("you got any logs"),
            free("got any wood"),
            free("got logs?"),
            free("do u have wood"),
            collect("mine 3 gold"),
            collect("mine 3 gold please"),
            collect("please get me 3 gold"),
            free("please give me 3 gold"),
            collect("plz gather 20 sand"),
            collect("gather 20 sand plz"),
            free("thanks for gathering the logs"),
            free("thank you for mining"),
            free("good job mining"),
            free("nice mining"),
            free("you are mining too slow"),
            free("why are you not mining"),
            free("why arent you chopping trees"),
            free("you gathered enough"),
            free("enough logs"),
            free("enough wood"),
            free("that is enough"),
            free("stop"),
            free("stop it"),
            free("hold on"),
            collect("harvest all the wheat you can find"),
            collect("collect as much wood as you can"),
            collect("gather as many logs as possible"),
            collect("chop every tree here"),
            collect("mine all the coal"),
            collect("mine everything"),
            free("mine nothing"),
            free("dont mine"),
            free("dont chop"),
            free("dont gather anything"),
            free("do not collect anything"),
            collect("collect everything you can find"),
            collect("gather some stuff"),
            free("give me some stuff"),
            free("get me some stuff"),
            free("get me your stuff"),
            free("show me your inventory"),
            free("what do you have"),
            free("what do you have in your inventory"),
            collect("go get wood"),
            collect("go get me wood"),
            collect("go chop some wood"),
            collect("go mine some iron"),
            collect("go and mine iron"),
            collect("go gather some sand and come back"),
            collectAndGive("go gather 10 sand and bring it to me"),
            collectAndGive("go chop 10 logs and come give them to me"),
            collect("hey moss could you please gather 32 logs for me"),
            collect("moss i would like 32 logs"),
            collect("moss i want 32 oak logs"),
            collect("moss pls collect 20 coal"),
            free("moss pls give me 20 coal"),
            free("moss pls give me the coal you have"),
            free("moss pls hand over the coal"),
            free("moss get ready to mine"),
            free("moss get ready"),
            free("moss get over here"),
            collect("moss come get the logs"),
            free("moss come here and give me the logs"),
            collect("get 32 logs then come here"),
            collect("get 32 logs and then come here"),
            free("get here"),
            free("get back here"),
            free("get inside"),
            free("get out of the way"),
            free("get lost"),
            free("get in"),
            collect("get to work chopping trees"),
            collect("get to mining"),
            collect("start mining"),
            collect("start chopping"),
            collect("keep gathering"),
            collect("continue mining"),
            collect("continue gathering logs"),
            collect("resume mining"),
            free("resume"),
            free("pause mining"),
            free("pause gathering"),
            free("stop mining please"),
            free("stop gathering and follow me"),
            free("stop chopping, follow me"),
            free("stop and give me the logs"),
            free("hey stop gathering and hand me the logs"),
            collect("stop following and mine coal"),
            collect("stop following me and gather 32 logs"),
            free("follow me and dont mine"),
            free("follow me then"),
            collect("follow me and gather logs"),
            collect("follow me and mine coal on the way"),
            free("eat then follow me"),
            collect("eat and then gather 32 logs"),
            free("craft a table"),
            free("craft planks"),
            free("make a pickaxe"),
            free("make me a sword"),
            free("make 4 chests"),
            free("smelt the iron"),
            free("cook the porkchop"),
            free("cook 5 beef"),
            craftResult("gather 20 logs and make me a chest"),
            collect("shear 5 sheep for wool"),
            free("shear the sheep"),
            free("thank you for gathering"),
            free("pause the mining"),
            collect("harvest the wheat you can find"),
            free("give me the wheat you harvested"),
            free("give me the wheat you have"),
            collect("get me the wheat you can find"));

    @BeforeAll
    static void bootstrap() {
        RegistryBootstrap.ensure();
    }

    private static Set<String> withheldFor(String text) {
        ToolRouting routing = new ToolRouting();
        routing.beginInstruction(RequestIntent.parse(text, PLAYERS));
        return routing.withheldTools();
    }

    private static List<String> mismatches() {
        List<String> mismatches = new ArrayList<>();
        for (Phrase phrase : PHRASES) {
            Set<String> actual = withheldFor(phrase.text());
            if (!actual.equals(phrase.route().withheld)) {
                mismatches.add("'" + phrase.text() + "' expected " + phrase.route() + " "
                        + new LinkedHashSet<>(phrase.route().withheld) + " but withheld " + actual);
            }
        }
        return mismatches;
    }

    @Test
    void everyPhrasingWithholdsExactlyTheToolsACarriedStackCouldSatisfy() {
        List<String> mismatches = mismatches();
        assertTrue(mismatches.isEmpty(), mismatches.size() + " of " + PHRASES.size() + " phrasings:\n"
                + String.join("\n", mismatches));
    }

    @Test
    void everyPhrasingReadsTheSameWithTheGamesWholeRecipeIndexInstalled() {
        // A running server's recipe index holds every vanilla crafting recipe; a bare test JVM's holds none, so a
        // noun could read as raw here and as crafted in play. Run the table against the real recipe data too.
        RuntimeRecipeFixture.installVanilla();
        try {
            List<String> mismatches = mismatches();
            assertTrue(mismatches.isEmpty(), mismatches.size() + " of " + PHRASES.size()
                    + " phrasings differ in the running game:\n" + String.join("\n", mismatches));
        } finally {
            RuntimeRecipeFixture.clear();
        }
    }

    @Test
    void theTableIsLargeEnoughToCoverEachCategory() {
        assertTrue(PHRASES.size() >= 400, "phrase table size " + PHRASES.size());
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
        assertTrue(!withheldFor("gather 32 logs then craft a table and give it to me").contains("fulfill_items"),
                "or in one call with the raw quota as an allocation without a recipient");
        assertEquals(Set.of(), withheldFor("give me 32 logs"));
    }

    @Test
    void severalResourcesAreEachAskedFor() {
        assertEquals(2, RequestIntent.parse("get 32 logs and 10 coal").resources().size());
        assertEquals(3, RequestIntent.parse("gather logs, coal and iron").resources().size());
        assertEquals(1, RequestIntent.parse("gather logs and wood").resources().size(),
                "two words for the same thing are one resource");
        assertEquals(List.of(Set.of()), RequestIntent.parse("start gathering").resources(),
                "a request that names nothing is answered by any collection");
        assertEquals(List.of(), RequestIntent.parse("give me 32 logs").resources());
        assertTrue(RequestIntent.parse("get 32 logs and 10 coal").quantityStated());
    }

    @Test
    void aLineThatIsOnlyAQuantityAndAResourceNeedsANumberOrAnImperative() {
        assertTrue(RequestIntent.parse("32 logs").acquiresRaw());
        assertTrue(RequestIntent.parse("wood pls").acquiresRaw());
        assertFalse(RequestIntent.parse("wood").acquiresRaw());
        assertFalse(RequestIntent.parse("32 logs?").acquiresRaw());
        assertFalse(RequestIntent.parse("i have 32 logs").acquiresRaw());
        assertTrue(RequestIntent.parse("32 logs pls").quantityStated());
        assertFalse(RequestIntent.parse("wood pls").quantityStated());
    }

    @Test
    void whatACreatureDropsIsOnlyAskedForWhenTheRequestSaysSo() {
        assertFalse(RequestIntent.parse("kill 5 cows").acquiresRaw());
        assertTrue(RequestIntent.parse("kill 5 cows and give me the beef").acquiresRaw());
        assertTrue(RequestIntent.parse("kill 5 cows and give me the beef").handsOverAcquired());
        assertFalse(RequestIntent.parse("kill 5 cows and give me the beef").quantityStated(),
                "a hunt is no gather: the number is not a quota for gather_then_give");
        assertTrue(RequestIntent.parse("kill skeletons for bones").acquiresRaw());
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
                    "gather 20 sandstone", "get 10 sandstone", "get 10 sea lanterns", "mine 10 sea lanterns")) {
                assertEquals(Route.COLLECT.withheld, withheldFor(text), text);
            }
            // Crafted stays crafted for the verbs that can mean either, so achieve_goal remains usable.
            assertEquals(Set.of(), withheldFor("get 4 pistons"));
            assertEquals(Set.of(), withheldFor("collect an iron pickaxe"));
        } finally {
            RuntimeRecipeFixture.clear();
        }
    }

    @Test
    void aCollectingVerbDoesNotLetARecipeOfTheRunningGameMakeAResourceCrafted() {
        // "gather"/"mine" name what the world yields: an item whose only recipe is in the game's index (a piston)
        // counts as a resource for them in the running game exactly as in a bare test JVM, while "get" reads it
        // as the crafted item it is there.
        RuntimeRecipeFixture.install(Items.PISTON, Items.DISPENSER);
        try {
            assertEquals(Route.COLLECT.withheld, withheldFor("gather 4 pistons"));
            assertEquals(Route.COLLECT.withheld, withheldFor("collect 4 dispensers"));
            assertEquals(Set.of(), withheldFor("get 4 pistons"));
        } finally {
            RuntimeRecipeFixture.clear();
        }
        assertEquals(Route.COLLECT.withheld, withheldFor("gather 4 pistons"));
        assertEquals(Route.COLLECT.withheld, withheldFor("get 4 pistons"), "no recipe index: nothing says crafted");
    }
}
