package dev.spawnbotswrapper.inhabitants.command;

import org.junit.jupiter.api.Test;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The thin bridge from marked-up lines to Minecraft text, feedback and the sender position. */
class ChatOutputTest {
    private static final String P = "§";

    @Test
    void chatTextKeepsTheVisibleTextAndAppliesTheColours() {
        Component t = ChatText.of(P + "6Title" + P + "r plain " + P + "aok" + P + "r");
        assertEquals("Title plain ok", t.getString());

        List<Component> parts = t.getSiblings();
        assertEquals(3, parts.size());
        assertEquals(ChatFormatting.GOLD.getColor(), parts.get(0).getStyle().getColor().getValue());
        assertNull(parts.get(1).getStyle().getColor());
        assertEquals(ChatFormatting.GREEN.getColor(), parts.get(2).getStyle().getColor().getValue());
    }

    @Test
    void chatTextOfAnEmptyLineIsEmptyText() {
        assertEquals("", ChatText.of("").getString());
        assertEquals("", ChatText.of(null).getString());
    }

    @Test
    void everyPaletteColourIsAValidMinecraftColour() {
        for (String coloured : List.of(Markup.title("x"), Markup.label("x"), Markup.id("x"), Markup.good("x"),
                Markup.warn("x"), Markup.bad("x"))) {
            Component t = ChatText.of(coloured);
            assertEquals("x", t.getString());
            assertNotNull(t.getSiblings().get(0).getStyle().getColor(), coloured);
        }
    }

    @Test
    void replyLinesGoOutAsSeparateMessagesWithoutBroadcastingToOps() {
        TestSources.Capture out = new TestSources.Capture();
        CommandSourceStack source = TestSources.level(2, out);

        // the capture wants op broadcasts; had the reply asked for one, the missing world would make this throw
        Reply.to(source).lines(List.of(Markup.title("Header"), "second line"));

        assertEquals(List.of("Header", "second line"), out.strings());
    }

    @Test
    void replyErrorsAreRedPlainTextWithoutMarkup() {
        TestSources.Capture out = new TestSources.Capture();
        Reply.to(TestSources.level(2, out)).error("bad " + Markup.bad("thing"));

        assertEquals(List.of("bad thing"), out.strings());
        assertEquals(ChatFormatting.RED.getColor(), out.messages.get(0).getStyle().getColor().getValue());
    }

    @Test
    void aSilencedSourceReceivesNothing() {
        TestSources.Capture out = new TestSources.Capture();
        CommandSourceStack silent = TestSources.level(2, out).withSuppressedOutput();
        Reply.to(silent).lines(List.of("hidden"));
        Reply.to(silent).error("hidden");
        assertTrue(out.messages.isEmpty());
    }

    @Test
    void senderChunkAndBlockPositionFloorForNegativeCoordinates() {
        Sender s = new Sender(null, Fixtures.OVERWORLD, -0.5, 64.9, 100.2);
        assertEquals(-1, s.chunkX());
        assertEquals(6, s.chunkZ());
        assertEquals(-1, s.blockPos().getX());
        assertEquals(64, s.blockPos().getY());
        assertEquals(100, s.blockPos().getZ());
    }

    @Test
    void aSourceWithoutAWorldIsRefusedClearly() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Sender.of(TestSources.level(2)));
        assertTrue(e.getMessage().contains("no world"), e.getMessage());
    }
}
