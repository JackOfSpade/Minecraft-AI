package io.github.zoyluo.minecraftai.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins omitted-count collection commands to their time-boxed forms. */
class MinecraftAiTaskSubcommandCollectionModeSourceContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/command/MinecraftAiTaskSubcommand.java");

    @Test
    void commandGrammarPreservesWhetherThePlayerSuppliedACount() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("assignForage(context, false, 0)"));
        assertTrue(source.contains("assignForage(context, true, IntegerArgumentType.getInteger(context, \"count\"))"));
        assertTrue(source.contains("assignMine(context, false, 0)"));
        assertTrue(source.contains("assignMine(context, true, IntegerArgumentType.getInteger(context, \"count\"))"));
        assertTrue(source.contains("assignGather(context, false, 0)"));
        assertTrue(source.contains("assignGather(context, true, IntegerArgumentType.getInteger(context, \"count\"))"));
    }

    @Test
    void omittedAndExplicitCommandsChooseDifferentCollectionModes() throws IOException {
        String source = Files.readString(SOURCE);
        String forage = methodBody(source, "private static int assignForage(");
        String mine = methodBody(source, "private static int assignMine(");
        String gather = methodBody(source, "private static int assignGather(");

        assertTrue(forage.contains("countSpecified")
                        && forage.contains("GatherQuotaTask.collectAdditional(Items.SWEET_BERRIES, count)")
                        && forage.contains("GatherQuotaTask.collectForDuration(Items.SWEET_BERRIES)"),
                "forage count must mean newly collected berries; omission must open the time box");
        assertTrue(mine.contains("countSpecified ? new OreDigTask(OreScan.oreFamily(block), count)")
                        && mine.contains("OreDigTask.collectForDuration(OreScan.oreFamily(block))"),
                "ore mining must distinguish an explicit new-drop quota from an omitted-count window");
        assertTrue(mine.contains("countSpecified ? new MineTask(block, count) : timedResourceMineTask(block)"),
                "non-ore mining must preserve its physical quota when supplied and collect drops by duration when omitted");
        assertTrue(gather.contains("countSpecified ? GatherQuotaTask.collectAdditional(item, count)")
                        && gather.contains("GatherQuotaTask.collectForDuration(item)"),
                "gather count must be additional material; omission must be time-boxed");
    }

    private static String methodBody(String source, String signature) {
        int signatureAt = source.indexOf(signature);
        assertTrue(signatureAt >= 0, () -> "missing method signature: " + signature);
        int open = source.indexOf('{', signatureAt);
        assertTrue(open >= 0, () -> "missing method body: " + signature);
        int depth = 0;
        for (int at = open; at < source.length(); at++) {
            char current = source.charAt(at);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(open, at + 1);
            }
        }
        throw new AssertionError("unterminated method body: " + signature);
    }
}
