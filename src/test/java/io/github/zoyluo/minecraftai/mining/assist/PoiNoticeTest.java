package io.github.zoyluo.minecraftai.mining.assist;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Design 6.1/6.8/6.9: the four notice templates render exact literal text for a fixed fixture, stay within
 * {@link PoiNotice#MAX_LENGTH} (truncating the note first, then the label) even at maximal inputs, a blank
 * note is omitted cleanly, and {@code compassDirection} covers all 8 sectors. */
class PoiNoticeTest {
    private static final BlockPos BOT_POS = new BlockPos(0, 64, 0);
    private static final BlockPos SITE_EAST = new BlockPos(10, 70, 0);

    // ---- exact literal text for a fixed fixture ------------------------------------------------------

    @Test
    void renderStandardWithoutANoteProducesTheExactTemplate() {
        assertEquals("Stopped: possible mineshaft at 10 70 0 (~10 blocks E, y=70). "
                        + "Say \"continue\" to keep mining or \"cancel\" to redirect me.",
                PoiNotice.renderStandard("mineshaft", SITE_EAST, BOT_POS, null));
    }

    @Test
    void renderStandardWithANoteAppendsItBetweenTheCoordinatesAndTheResumeInstruction() {
        assertEquals("Stopped: possible mineshaft at 10 70 0 (~10 blocks E, y=70). (auto-detected). "
                        + "Say \"continue\" to keep mining or \"cancel\" to redirect me.",
                PoiNotice.renderStandard("mineshaft", SITE_EAST, BOT_POS, "(auto-detected)"));
    }

    @Test
    void renderDigDownDescendProducesTheExactTemplateWithNoNoteSlotAndNoYCoordinate() {
        assertEquals("Stopped descent: possible mineshaft at 10 70 0 (~10 blocks E). "
                        + "Saying \"continue\" makes me climb back to the surface; say \"cancel\" to stay here.",
                PoiNotice.renderDigDownDescend("mineshaft", SITE_EAST, BOT_POS));
    }

    @Test
    void renderMandatoryProducesTheExactFixedTemplateWithNoLabelSubstituted() {
        assertEquals("Stopped: warden risk (ancient city / deep dark) at 10 70 0 (~10 blocks E). "
                        + "I will not go further on my own. Say \"continue\" to override or \"cancel\" to redirect me.",
                PoiNotice.renderMandatory(SITE_EAST, BOT_POS));
    }

    @Test
    void renderFyiProducesTheExactTemplate() {
        assertEquals("FYI: possible mineshaft at 10 70 0 (auto-detected, not confirmed). "
                        + "I'm continuing; say \"stop\" if you want to look.",
                PoiNotice.renderFyi("mineshaft", SITE_EAST, BOT_POS));
    }

    // ---- a blank note is omitted cleanly, never a dangling ". ." --------------------------------------

    @Test
    void aNullOrBlankNoteIsOmittedEntirelyWithNoDanglingPunctuation() {
        for (String note : new String[] {null, "", "   "}) {
            String rendered = PoiNotice.renderStandard("mineshaft", SITE_EAST, BOT_POS, note);
            assertFalse(rendered.contains(".."), "no dangling double period for note=" + note);
            assertTrue(rendered.contains("y=70). Say \"continue\""),
                    "the note clause vanishes entirely, so the tail follows the coordinates directly: " + rendered);
        }
    }

    // ---- MAX_LENGTH truncation, note first then label, always ending in the resume instruction --------

    @Test
    void renderStandardTruncatesTheNoteFirstThenTheLabelAndAlwaysEndsWithTheResumeInstruction() {
        String longLabel = "x".repeat(400);
        String longNote = "y".repeat(400);
        BlockPos farSite = new BlockPos(-1_234_567, -2033, 9_876_543);
        BlockPos farBot = new BlockPos(1_234_567, 320, -9_876_543);

        String withLongNote = PoiNotice.renderStandard(longLabel, farSite, farBot, longNote);
        assertTrue(withLongNote.length() <= PoiNotice.MAX_LENGTH);
        assertTrue(withLongNote.endsWith("Say \"continue\" to keep mining or \"cancel\" to redirect me."));

        String withoutNote = PoiNotice.renderStandard(longLabel, farSite, farBot, null);
        assertTrue(withoutNote.length() <= PoiNotice.MAX_LENGTH,
                "an abnormally long label alone must still be shortened to fit");
        assertTrue(withoutNote.endsWith("Say \"continue\" to keep mining or \"cancel\" to redirect me."));
        assertTrue(withoutNote.startsWith("Stopped: possible "));
    }

    @Test
    void renderDigDownDescendAndRenderFyiAlsoStayWithinMaxLengthWithAnAbnormallyLongLabel() {
        String longLabel = "z".repeat(400);
        BlockPos farSite = new BlockPos(-1_234_567, -2033, 9_876_543);
        BlockPos farBot = new BlockPos(1_234_567, 320, -9_876_543);

        String descend = PoiNotice.renderDigDownDescend(longLabel, farSite, farBot);
        assertTrue(descend.length() <= PoiNotice.MAX_LENGTH);
        assertTrue(descend.endsWith("Saying \"continue\" makes me climb back to the surface; say \"cancel\" to stay here."));

        String fyi = PoiNotice.renderFyi(longLabel, farSite, farBot);
        assertTrue(fyi.length() <= PoiNotice.MAX_LENGTH);
        assertTrue(fyi.endsWith("I'm continuing; say \"stop\" if you want to look."));
    }

    @Test
    void renderMandatoryHasNoLabelToTruncateAndStaysWellWithinMaxLength() {
        BlockPos farSite = new BlockPos(-1_234_567, -2033, 9_876_543);
        BlockPos farBot = new BlockPos(1_234_567, 320, -9_876_543);
        String rendered = PoiNotice.renderMandatory(farSite, farBot);
        assertTrue(rendered.length() <= PoiNotice.MAX_LENGTH);
    }

    // ---- compassDirection: all 8 sectors, Minecraft axes (north = -z, east = +x) -----------------------

    @Test
    void compassDirectionCoversAllEightSectorsOnMinecraftAxes() {
        BlockPos origin = new BlockPos(0, 64, 0);
        assertEquals("N", PoiNotice.compassDirection(origin, new BlockPos(0, 64, -10)));
        assertEquals("NE", PoiNotice.compassDirection(origin, new BlockPos(10, 64, -10)));
        assertEquals("E", PoiNotice.compassDirection(origin, new BlockPos(10, 64, 0)));
        assertEquals("SE", PoiNotice.compassDirection(origin, new BlockPos(10, 64, 10)));
        assertEquals("S", PoiNotice.compassDirection(origin, new BlockPos(0, 64, 10)));
        assertEquals("SW", PoiNotice.compassDirection(origin, new BlockPos(-10, 64, 10)));
        assertEquals("W", PoiNotice.compassDirection(origin, new BlockPos(-10, 64, 0)));
        assertEquals("NW", PoiNotice.compassDirection(origin, new BlockPos(-10, 64, -10)));
    }

    @Test
    void compassDirectionWithNoHorizontalOffsetAtAllResolvesToNorth() {
        BlockPos origin = new BlockPos(5, 64, 5);
        assertEquals("N", PoiNotice.compassDirection(origin, new BlockPos(5, 100, 5)),
                "directly above/below has no defined bearing and resolves to N");
    }
}
