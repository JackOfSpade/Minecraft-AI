package dev.spawnbotswrapper.inhabitants.command;

import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FmtTest {

    @Test
    void percentShowsADecimalOnlyWhenItMatters() {
        assertEquals("65%", Fmt.percent(0.65));
        assertEquals("0.5%", Fmt.percent(0.005));
        assertEquals("85.5%", Fmt.percent(0.855));
        assertEquals("100%", Fmt.percent(1.0));
        assertEquals("0%", Fmt.percent(0.0));
        assertEquals("?", Fmt.percent(Double.NaN));
    }

    @Test
    void numbersUseThousandsSeparatorsRegardlessOfLocale() {
        assertEquals("1,234,567", Fmt.num(1_234_567));
        assertEquals("0", Fmt.num(0));
        assertEquals("1 bot", Fmt.plural(1, "bot"));
        assertEquals("2 bots", Fmt.plural(2, "bot"));
        assertEquals("0 bots", Fmt.plural(0, "bot"));
        assertEquals("1,200 structures", Fmt.plural(1200, "structure"));
    }

    @Test
    void geometryFormatting() {
        assertEquals("(0,60,0) to (63,90,63)  64x31x64", Fmt.box(new IntBox(0, 60, 0, 63, 90, 63)));
        assertEquals("-4,7", Fmt.chunk(-4, 7));
        assertEquals("1.5, 64.0, -3.3", Fmt.xyz(1.5, 64, -3.25));
        assertEquals(5, Fmt.blocks(0, 0, 3, 4));
    }

    @Test
    void permissionLevelIsNamedAndClamped() {
        assertEquals("0 (everyone)", Fmt.permissionLevel(0));
        assertEquals("2 (gamemasters)", Fmt.permissionLevel(2));
        assertEquals("4 (owners)", Fmt.permissionLevel(4));
        assertEquals("4 (owners)", Fmt.permissionLevel(99));
        assertEquals("0 (everyone)", Fmt.permissionLevel(-3));
    }

    @Test
    void orDashReplacesNullAndBlank() {
        assertEquals("-", Fmt.orDash(null));
        assertEquals("-", Fmt.orDash("  "));
        assertEquals("x", Fmt.orDash("x"));
    }

    @Test
    void linesFlattensMultilineTextAndDropsBlanks() {
        assertEquals(List.of("a", "b", " c"), Fmt.lines(Arrays.asList("a\nb", null, "", " c\r\n\r\n")));
        assertEquals(List.of(), Fmt.lines(null));
    }

    @Test
    void addCappedNeverExceedsTheLimitAndSaysHowManyWereLeftOut() {
        List<String> items = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            items.add("item" + i);
        }
        List<String> out = new ArrayList<>();
        Fmt.addCapped(out, items, 3, "narrow it");
        assertEquals(List.of("item1", "item2", "item3"), out.subList(0, 3));
        assertEquals(4, out.size());
        assertEquals("... and 2 more (narrow it)", Markup.strip(out.get(3)));

        List<String> exact = new ArrayList<>();
        Fmt.addCapped(exact, items, 5, "narrow it");
        assertEquals(items, exact);

        List<String> noHint = new ArrayList<>();
        Fmt.addCapped(noHint, items, 4, "");
        assertEquals("... and 1 more", Markup.strip(noHint.get(4)));
    }
}
