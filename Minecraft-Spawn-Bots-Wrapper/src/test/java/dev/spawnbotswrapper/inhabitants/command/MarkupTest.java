package dev.spawnbotswrapper.inhabitants.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkupTest {
    private static final String P = "§";

    @Test
    void helpersWrapWithAColourAndAReset() {
        assertEquals(P + "6Hi" + P + "r", Markup.title("Hi"));
        assertEquals(P + "7Hi" + P + "r", Markup.label("Hi"));
        assertEquals(P + "bHi" + P + "r", Markup.id("Hi"));
        assertEquals(P + "aHi" + P + "r", Markup.good("Hi"));
        assertEquals(P + "eHi" + P + "r", Markup.warn("Hi"));
        assertEquals(P + "cHi" + P + "r", Markup.bad("Hi"));
        assertEquals("Hi", Markup.plain("Hi"));
    }

    @Test
    void emptyOrNullTextProducesNoMarkupAtAll() {
        assertEquals("", Markup.title(""));
        assertEquals("", Markup.label(null));
        assertEquals("", Markup.plain(null));
        assertEquals("", Markup.strip((String) null));
    }

    @Test
    void untrustedTextCannotInjectAColour() {
        String evil = "Bob" + P + "4 is here";
        assertEquals("Bob4 is here", Markup.plain(evil));
        List<Markup.Span> spans = Markup.spans(Markup.plain(evil));
        assertEquals(List.of(new Markup.Span("Bob4 is here", (char) 0)), spans);

        String wrapped = Markup.id(evil);
        assertEquals(List.of(new Markup.Span("Bob4 is here", 'b')), Markup.spans(wrapped));
    }

    @Test
    void stripKeepsOnlyTheVisibleText() {
        assertEquals("Title rest green", Markup.strip(P + "6Title" + P + "r rest " + P + "agreen"));
        assertEquals(List.of("a", "b"), Markup.strip(List.of(Markup.good("a"), Markup.bad("b"))));
    }

    @Test
    void spansSplitAtEveryColourChangeAndResetReturnsToDefault() {
        List<Markup.Span> spans = Markup.spans(P + "6Title" + P + "r rest " + P + "agreen");
        assertEquals(List.of(
                new Markup.Span("Title", '6'),
                new Markup.Span(" rest ", (char) 0),
                new Markup.Span("green", 'a')), spans);
    }

    @Test
    void spansMergeRunsOfTheSameColourAndIgnoreUnknownCodesAndDanglingPrefix() {
        assertEquals(List.of(new Markup.Span("XY", 'a')), Markup.spans(P + "aX" + P + "aY"));
        // a modifier code (bold) is not part of the palette: dropped, the colour stays
        assertEquals(List.of(new Markup.Span("bold", 'a')), Markup.spans(P + "a" + P + "lbold"));
        assertEquals(List.of(new Markup.Span("abc", (char) 0)), Markup.spans("abc" + P));
        assertEquals(List.of(new Markup.Span("up", 'a')), Markup.spans(P + "Aup"));
        assertTrue(Markup.spans("").isEmpty());
        assertTrue(Markup.spans(null).isEmpty());
    }
}
